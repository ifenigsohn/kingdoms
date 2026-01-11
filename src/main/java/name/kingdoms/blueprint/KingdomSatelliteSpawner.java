package name.kingdoms.blueprint;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

import java.util.*;

/**
 * Plans + enqueues 5-25 "satellite" blueprints around a castle anchor.
 *
 * Uses BlueprintPlacerEngine to do per-building site check + grading + placement.
 * That means we only need to plan good candidate locations here.
 */
public final class KingdomSatelliteSpawner {
    private static final Logger LOGGER = LogUtils.getLogger();

    private KingdomSatelliteSpawner() {}

    public enum KingdomSize { SMALL, MEDIUM, LARGE }


    // NOTE: enforced relative to the CASTLE FOOTPRINT RECTANGLE, not just origin
    public static final int MIN_DIST_FROM_CASTLE = 20;
    public static final int MIN_CENTER_SPACING  = 50;

    private static final int CANDIDATES_PER_PASS = 600;

    private static final int MOUNTAIN_Y_ABOVE_SEA = 40;
    private static final int WATER_CHECK_RADIUS = 8;

    private enum Pass { STRICT, RELAXED, LAST_RESORT }

    public static void enqueueSatellitesAfterCastle(
            ServerLevel level,
            BlockPos castleOrigin,
            Blueprint castleBp,
            String modId,
            long regionKey,
            KingdomSize size,
            List<String> blueprintIds,
            RandomSource rng
    ) {
        int target = pickBuildingCount(size, rng);
        if (target <= 0 || blueprintIds.isEmpty()) return;

        // ---- FILTER SATELLITE POOL (prevents castle spawning as satellite) ----
        String castleId = (castleBp != null ? castleBp.id : null);

        ArrayList<String> pool = new ArrayList<>(blueprintIds.size());
        for (String id : blueprintIds) {
            if (id == null || id.isBlank()) continue;

            // never pick the same blueprint as the castle itself
            if (castleId != null && id.equals(castleId)) continue;

            // never pick anything that looks like a castle (optional but recommended)
            // If you name castles differently, adjust this rule.
            if (id.startsWith("castle")) continue;

            pool.add(id);
        }

        if (pool.isEmpty()) {
            LOGGER.warn("[Kingdoms] Satellite pool is empty after filtering (castleId={}). Input={}",
                    castleId, blueprintIds);
            return;
        }
        // ---------------------------------------------------------------------

        int fx = footprintX(castleBp);
        int fz = footprintZ(castleBp);

        int castleMinX = castleOrigin.getX();
        int castleMinZ = castleOrigin.getZ();
        int castleMaxX = castleOrigin.getX() + fx - 1;
        int castleMaxZ = castleOrigin.getZ() + fz - 1;

        ArrayList<BlockPos> chosenCenters = new ArrayList<>(target);
        int placed = 0;

        for (Pass pass : Pass.values()) {
            if (placed >= target) break;

            ArrayList<Candidate> candidates = generateCandidates(level, castleOrigin, size, rng, pass, CANDIDATES_PER_PASS);
            candidates.sort(Comparator.comparingInt(c -> c.score));

            for (Candidate c : candidates) {
                if (placed >= target) break;

                int dToCastleRect = distToRectXZ(
                        c.pos.getX(), c.pos.getZ(),
                        castleMinX, castleMinZ,
                        castleMaxX, castleMaxZ
                );
                if (dToCastleRect < MIN_DIST_FROM_CASTLE) continue;

                if (tooCloseToOthers(c.pos, chosenCenters, MIN_CENTER_SPACING)) continue;

                String bpId = pool.get(rng.nextInt(pool.size()));

                try {
                    Blueprint bp = Blueprint.load(level.getServer(), modId, bpId);

                    int sx = footprintX(bp);
                    int sz = footprintZ(bp);

                    // candidate is treated as "center"
                    int ox = c.pos.getX() - (sx / 2);
                    int oz = c.pos.getZ() - (sz / 2);

                    int y = surfaceY(level, ox, oz);
                    BlockPos origin = new BlockPos(ox, y, oz);

                    // IMPORTANT: unique key per building job
                    long buildingKey = mixKey(regionKey, placed, origin);

                    // Gate for async-ish placement pipeline
                    KingdomGenGate.beginOne();

                    // capture per-satellite for callbacks
                    final BlockPos satOrigin = origin;
                    final String satBpId = bpId;

                    Runnable onSatSuccess = () -> {
                        try {
                            RoadAnchorState st = RoadAnchorState.get(level);

                            // If satellites also use your SOLID -> BARRIER -> BARRIER markers:
                            List<BlockPos> anchors = RoadAnchors.consumeBarrierAnchors(level, satOrigin);

                            // Fallback: if no barriers found, use the old "origin+1,+1" heightmap anchor
                            if (anchors.isEmpty()) {
                                anchors = List.of(RoadAnchors.fallbackFromBlueprintOrigin(level, satOrigin));
                            }

                            for (BlockPos anchorPos : anchors) {
                                st.add(regionKey, anchorPos);
                            }

                            LOGGER.info("[Kingdoms] Satellite anchors added region={} origin={} anchors={} bp={}",
                                    regionKey, satOrigin, anchors, satBpId);

                        } catch (Exception e) {
                            LOGGER.warn("[Kingdoms] Failed to add satellite anchors region={} origin={} bp={}",
                                    regionKey, satOrigin, satBpId, e);
                        } finally {
                            KingdomGenGate.oneSatelliteFinished();
                        }
                    };

                    Runnable onSatFail = () -> KingdomGenGate.oneSatelliteFinished();

                    BlueprintPlacerEngine.enqueueWorldgen(
                            level, bp, origin, modId, false,
                            regionKey, buildingKey,
                            onSatSuccess,
                            onSatFail
                    );

                    chosenCenters.add(origin);
                    placed++;

                } catch (Exception ex) {
                    KingdomGenGate.oneSatelliteFinished();
                    LOGGER.warn("[Kingdoms] Failed to enqueue satellite blueprint '{}' near {}",
                            bpId, c.pos, ex);
                }
            }
        }

        LOGGER.info("[Kingdoms] Enqueued {} / {} satellites around castle at {} (size={})",
                placed, target, castleOrigin, size);
    }

    private static int pickBuildingCount(KingdomSize size, RandomSource rng) {
        int min, max;
        switch (size) {
            case SMALL -> { min = 5;  max = 10; }
            case MEDIUM -> { min = 10; max = 18; }
            default -> { min = 18; max = 25; }
        }
        return min + rng.nextInt(max - min + 1);
    }

    public static int maxRadiusForSize(KingdomSize size) {
        return switch (size) {
            case SMALL -> 100;
            case MEDIUM -> 200;
            default -> 300; // LARGE
        };
    }

    private static int footprintX(Blueprint bp) {
        if (bp != null && bp.sizeX > 0) return bp.sizeX;
        if (bp == null) return 1;
        return bp.sectionsX * bp.sectionSize;
    }

    private static int footprintZ(Blueprint bp) {
        if (bp != null && bp.sizeZ > 0) return bp.sizeZ;
        if (bp == null) return 1;
        return bp.sectionsZ * bp.sectionSize;
    }

    private static int distToRectXZ(int x, int z, int minX, int minZ, int maxX, int maxZ) {
        int dx = 0;
        if (x < minX) dx = minX - x;
        else if (x > maxX) dx = x - maxX;

        int dz = 0;
        if (z < minZ) dz = minZ - z;
        else if (z > maxZ) dz = z - maxZ;

        return Math.max(dx, dz);
    }

    private static int surfaceY(ServerLevel level, int x, int z) {
        int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int y = h - 1;
        return Math.max(level.getMinY() + 1, y);
    }

    private static boolean tooCloseToOthers(BlockPos p, List<BlockPos> others, int minDist) {
        int minDistSq = minDist * minDist;
        for (BlockPos o : others) {
            long dx = (long)p.getX() - o.getX();
            long dz = (long)p.getZ() - o.getZ();
            long d2 = dx*dx + dz*dz;
            if (d2 < minDistSq) return true;
        }
        return false;
    }

    private record Candidate(BlockPos pos, int score) {}

    private static ArrayList<Candidate> generateCandidates(
        ServerLevel level,
        BlockPos castle,
        KingdomSize size,
        RandomSource rng,
        Pass pass,
        int count

    ) {
        ArrayList<Candidate> out = new ArrayList<>(count);
        int sea = level.getSeaLevel();

        for (int i = 0; i < count; i++) {
            double t = rng.nextDouble();
            double minR = Math.max(20, MIN_DIST_FROM_CASTLE);
            int maxR = Math.max(maxRadiusForSize(size), (int)minR + 1);
            double r = minR + t * (maxR - minR);
            double ang = rng.nextDouble() * Math.PI * 2.0;

            int x = castle.getX() + (int)Math.round(Math.cos(ang) * r);
            int z = castle.getZ() + (int)Math.round(Math.sin(ang) * r);

            int y = surfaceY(level, x, z);

            int score = 0;

            int dx = x - castle.getX();
            int dz = z - castle.getZ();
            int dist = (int)Math.round(Math.sqrt((double)dx * dx + (double)dz * dz));
            if (dist < 25) score += (25 - dist) * 200;

            int slope = approxSlope(level, x, z);
            score += slope * 500;

            boolean watery = isWatery(level, x, y, z);
            boolean mountainy = y > sea + MOUNTAIN_Y_ABOVE_SEA;

            if (pass == Pass.STRICT) {
                if (watery) score += 10_000;
                if (mountainy) score += 6_000;
            } else if (pass == Pass.RELAXED) {
                if (watery) score += 4_000;
                if (mountainy) score += 2_000;
            } else {
                if (watery) score += 800;
                if (mountainy) score += 400;
            }

            out.add(new Candidate(new BlockPos(x, y, z), score));
        }

        return out;
    }

    private static int approxSlope(ServerLevel level, int x, int z) {
        int y0 = surfaceY(level, x, z);
        int y1 = surfaceY(level, x + 6, z);
        int y2 = surfaceY(level, x - 6, z);
        int y3 = surfaceY(level, x, z + 6);
        int y4 = surfaceY(level, x, z - 6);
        int min = Math.min(y0, Math.min(y1, Math.min(y2, Math.min(y3, y4))));
        int max = Math.max(y0, Math.max(y1, Math.max(y2, Math.max(y3, y4))));
        return max - min;
    }

    private static boolean isWatery(ServerLevel level, int x, int surfaceY, int z) {
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int dx = -WATER_CHECK_RADIUS; dx <= WATER_CHECK_RADIUS; dx++) {
            for (int dz = -WATER_CHECK_RADIUS; dz <= WATER_CHECK_RADIUS; dz++) {
                int xx = x + dx;
                int zz = z + dz;
                int yy = surfaceY(level, xx, zz);
                for (int y = yy; y <= yy + 2; y++) {
                    mp.set(xx, y, zz);
                    if (!level.getBlockState(mp).getFluidState().isEmpty()) return true;
                }
            }
        }
        return false;
    }

    private static long mixKey(long regionKey, int index, BlockPos pos) {
        long h = regionKey;
        h ^= (long)index * 0x9E3779B97F4A7C15L;
        h ^= ((long)pos.getX() * 0xBF58476D1CE4E5B9L);
        h ^= ((long)pos.getZ() * 0x94D049BB133111EBL);
        return h;
    }
}
