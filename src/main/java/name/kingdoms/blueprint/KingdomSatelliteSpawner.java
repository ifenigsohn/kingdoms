package name.kingdoms.blueprint;

import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

import java.util.*;

/**
 * Plans + enqueues 5-25 "satellite" blueprints around a castle anchor.
 *
 * IMPORTANT CHANGE (server-safe):
 * - Satellite placement is now DRIP-FED over time instead of enqueued all at once.
 * - enqueueSatellitesAfterCastle() only plans/queues SatJobs.
 * - init() processes up to SATS_PER_TICK jobs per tick, and pauses if BP queue is busy.
 */
public final class KingdomSatelliteSpawner {
    private static final Logger LOGGER = LogUtils.getLogger();

    private KingdomSatelliteSpawner() {}

    public enum KingdomSize { SMALL, MEDIUM, LARGE }

    // NOTE: enforced relative to the CASTLE FOOTPRINT RECTANGLE, not just origin
    public static final int MIN_DIST_FROM_CASTLE = 20;
    public static final int MIN_CENTER_SPACING  = 50;

    // Reduced from 600 to reduce one-tick CPU spikes
    private static final int CANDIDATES_PER_PASS = 180;

    private static final int MOUNTAIN_Y_ABOVE_SEA = 40;
    private static final int WATER_CHECK_RADIUS = 4;

    private enum Pass { STRICT, RELAXED, LAST_RESORT }

    // --------------------
    // Satellite drip queue
    // --------------------
    private static final Deque<SatJob> SAT_QUEUE = new ArrayDeque<>();

    // --------------------
    // Satellite plan queue (NEW)
    // --------------------
    private static final Deque<PlanJob> PLAN_QUEUE = new ArrayDeque<>();

    private record PlanJob(
            ServerLevel level,
            BlockPos castleOrigin,
            Blueprint castleBp,
            String modId,
            long regionKey,
            KingdomSize size,
            List<String> blueprintIds,
            RandomSource rng,
            int delayTicks,
            ClampRect clamp
    ) {}


    /** How many satellites to enqueue per server tick */
    private static final int SATS_PER_TICK = 1;

    /** Pause satellite enqueue if blueprint placer is already busy */
    private static final int SAT_MAX_BP_QUEUE = 1;

    private record SatJob(
            ServerLevel level,
            String modId,
            long regionKey,
            BlockPos origin,
            String bpId,
            long buildingKey
    ) {}

    /**
     * Used by BlueprintPlacerEngine to delay road generation until this region
     * has no pending satellite work.
     */
    public static boolean hasPending(long regionKey) {
        // active (already-enqueued) satellite placements still running
        if (KingdomGenGate.isBusy(regionKey)) return true;

        // pending drip-feed placements
        for (SatJob sj : SAT_QUEUE) {
            if (sj.regionKey() == regionKey) return true;
        }

        // pending delayed planning
        for (PlanJob pj : PLAN_QUEUE) {
            if (pj.regionKey() == regionKey) return true;
        }

        return false;
    }


    public static void enqueuePlanAfterDelay(
            ServerLevel level,
            BlockPos castleOrigin,
            Blueprint castleBp,
            String modId,
            long regionKey,
            KingdomSize size,
            List<String> blueprintIds,
            RandomSource rng,
            int delayTicks,
            int borderMinX, int borderMaxX,
            int borderMinZ, int borderMaxZ
    ) {
        KingdomGenGate.beginOne(regionKey);
        PLAN_QUEUE.addLast(new PlanJob(
                level, castleOrigin, castleBp, modId, regionKey, size, blueprintIds, rng, delayTicks,
                new ClampRect(borderMinX, borderMaxX, borderMinZ, borderMaxZ)
        ));
    }

    private record ClampRect(int minX, int maxX, int minZ, int maxZ) {
        boolean containsXZ(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        boolean containsFootprint(int ox, int oz, int sx, int sz) {
            int x2 = ox + sx - 1;
            int z2 = oz + sz - 1;
            return ox >= minX && x2 <= maxX && oz >= minZ && z2 <= maxZ;
        }

        int maxChebyshevRadiusFrom(BlockPos p) {
            int dx = Math.min(p.getX() - minX, maxX - p.getX());
            int dz = Math.min(p.getZ() - minZ, maxZ - p.getZ());
            return Math.max(0, Math.min(dx, dz));
        }

    }


    /**
     * MUST be called once during mod init (similar to RoadBuilder.init()).
     * This processes queued satellite jobs gradually, smoothing TPS.
     */
    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int did = 0;
            
            // --------------------
            // delayed planning (run at most 1 plan job per tick)
            // --------------------
            if (!PLAN_QUEUE.isEmpty()) {
                PlanJob pj = PLAN_QUEUE.peekFirst();
                if (pj != null) {
                    if (pj.delayTicks() > 0) {
                        // decrement delay (keep it at front)
                        PLAN_QUEUE.pollFirst();
                        PLAN_QUEUE.addFirst(new PlanJob(
                                pj.level(),
                                pj.castleOrigin(),
                                pj.castleBp(),
                                pj.modId(),
                                pj.regionKey(),
                                pj.size(),
                                pj.blueprintIds(),
                                pj.rng(),
                                pj.delayTicks() - 1,
                                pj.clamp()
                        ));
                    } else {
                        // delay complete: run planning now
                        PLAN_QUEUE.pollFirst();
                        try {
                            enqueueSatellitesAfterCastle(
                                    pj.level(),
                                    pj.castleOrigin(),
                                    pj.castleBp(),
                                    pj.modId(),
                                    pj.regionKey(),
                                    pj.size(),
                                    pj.blueprintIds(),
                                    pj.rng(),
                                    pj.clamp()
                            );
                        } catch (Exception ex) {
                            LOGGER.warn("[Kingdoms] Satellite planning failed for region={} origin={}",
                                    pj.regionKey(), pj.castleOrigin(), ex);
                        } finally {
                            // IMPORTANT: release the plan token (satellite jobs will keep the gate busy)
                            KingdomGenGate.oneSatelliteFinished(pj.regionKey());
                            
                        }
                    }
                }
            }

            while (did < SATS_PER_TICK && !SAT_QUEUE.isEmpty()) {
                if (BlueprintPlacerEngine.getQueueSize() >= SAT_MAX_BP_QUEUE) break;

               SatJob job = SAT_QUEUE.pollFirst();
                if (job == null) break;

                try {
                    // Capture everything the lambdas need (must be effectively final)
                    final ServerLevel lvl = job.level();
                    final String modId = job.modId();
                    final long rrk = job.regionKey();
                    final BlockPos satOrigin = job.origin();
                    final String satBpId = job.bpId();
                    final long buildingKey = job.buildingKey();

                    Blueprint bp = Blueprint.load(lvl.getServer(), modId, satBpId);


                    Runnable onSatSuccess = () -> {
                        try {
                            RoadAnchorState st = RoadAnchorState.get(lvl);

                            List<BlockPos> anchors = RoadAnchors.consumeBarrierAnchors(lvl, satOrigin);
                            if (anchors.isEmpty()) {
                                anchors = List.of(RoadAnchors.fallbackFromBlueprintOrigin(lvl, satOrigin));
                            }

                            for (BlockPos anchorPos : anchors) {
                                st.add(rrk, anchorPos);
                            }

                            LOGGER.info("[Kingdoms] Satellite anchors added region={} origin={} anchors={} bp={}",
                                    rrk, satOrigin, anchors, satBpId);

                        } catch (Exception e) {
                            LOGGER.warn("[Kingdoms] Failed to add satellite anchors region={} origin={} bp={}",
                                    rrk, satOrigin, satBpId, e);
                        } finally {
                            // IMPORTANT: activity ends when the blueprint is finished/failed
                            RegionActivityState.get(lvl).end(rrk);
                            KingdomGenGate.oneSatelliteFinished(rrk);
                             BlueprintPlacerEngine.requestRoadStart(lvl, rrk);
                        }
                    };

                    Runnable onSatFail = () -> {
                        try {
                            // nothing
                        } finally {
                            RegionActivityState.get(lvl).end(rrk);
                            KingdomGenGate.oneSatelliteFinished(rrk);
                             BlueprintPlacerEngine.requestRoadStart(lvl, rrk);
                        }
                    };

                    // IMPORTANT: begin activity only when we actually enqueue
                    RegionActivityState.get(lvl).begin(rrk);

                    BlueprintPlacerEngine.enqueueWorldgen(
                            lvl, bp, satOrigin, modId, false,
                            rrk, buildingKey,
                            onSatSuccess,
                            onSatFail
                    );

                } catch (Exception ex) {
                    // If we fail before enqueue, we never began activity and never started the gate.
                    LOGGER.warn("[Kingdoms] Failed to enqueue satellite blueprint '{}' at {}",
                            job.bpId(), job.origin(), ex);
                }
                               
                did++;
            }
        });
    }

    /**
     * Plan satellites and queue them for drip-fed enqueue.
     * No longer directly enqueues all satellites in a single tick.
     */
    public static void enqueueSatellitesAfterCastle(
            ServerLevel level,
            BlockPos castleOrigin,
            Blueprint castleBp,
            String modId,
            long regionKey,
            KingdomSize size,
            List<String> blueprintIds,
            RandomSource rng,
            ClampRect clamp
    ) {

        int target = pickBuildingCount(size, rng);
        if (target <= 0 || blueprintIds.isEmpty()) return;

        // ---- FILTER SATELLITE POOL (prevents castle spawning as satellite) ----
        String castleId = (castleBp != null ? castleBp.id : null);

        ArrayList<String> pool = new ArrayList<>(blueprintIds.size());
        for (String id : blueprintIds) {
            if (id == null || id.isBlank()) continue;

            if (castleId != null && id.equals(castleId)) continue;
            if (id.startsWith("castle")) continue;

            pool.add(id);
        }

        if (pool.isEmpty()) {
            LOGGER.warn("[Kingdoms] Satellite pool is empty after filtering (castleId={}). Input={}",
                    castleId, blueprintIds);
            return;
        }
        // ---------------------------------------------------------------------

        int maxR = maxRadiusForSize(size);

        // Further clamp radius so we never even sample outside the border box
        if (clamp != null) {
            // How far can we go from castle while still being inside borders?
            // subtract a little margin so centers don't hug the edge too hard
            int borderR = Math.max(1, clamp.maxChebyshevRadiusFrom(castleOrigin) - 8);
            maxR = Math.min(maxR, borderR);
        }

        int fx = footprintX(castleBp);
        int fz = footprintZ(castleBp);

        int castleMinX = castleOrigin.getX();
        int castleMinZ = castleOrigin.getZ();
        int castleMaxX = castleOrigin.getX() + fx - 1;
        int castleMaxZ = castleOrigin.getZ() + fz - 1;

        ArrayList<BlockPos> chosenCenters = new ArrayList<>(target);
        int planned = 0;

        for (Pass pass : Pass.values()) {
            if (planned >= target) break;

            ArrayList<Candidate> candidates = generateCandidates(level, castleOrigin, maxR, rng, pass, CANDIDATES_PER_PASS, clamp);
            candidates.sort(Comparator.comparingInt(c -> c.score));

            for (Candidate c : candidates) {
                if (planned >= target) break;

                int dToCastleRect = distToRectXZ(
                        c.pos.getX(), c.pos.getZ(),
                        castleMinX, castleMinZ,
                        castleMaxX, castleMaxZ
                );
                if (dToCastleRect < MIN_DIST_FROM_CASTLE) continue;

                if (tooCloseToOthers(c.pos, chosenCenters, MIN_CENTER_SPACING)) continue;

                String bpId = pool.get(rng.nextInt(pool.size()));

                try {
                    // We still load the blueprint here only to compute footprint (size).
                    // This is far cheaper than enqueuing/placing everything in one tick,
                    // and candidate count has been reduced.
                    Blueprint bp = Blueprint.load(level.getServer(), modId, bpId);

                    int sx = footprintX(bp);
                    int sz = footprintZ(bp);

                    // candidate is treated as "center"
                    int ox = c.pos.getX() - (sx / 2);
                    int oz = c.pos.getZ() - (sz / 2);

                    // HARD CLAMP: footprint must fit inside kingdom border
                    if (clamp != null && !clamp.containsFootprint(ox, oz, sx, sz)) {
                        continue;
                    }

                    int y = surfaceY(level, ox, oz);
                    BlockPos origin = new BlockPos(ox, y, oz);

                    // Unique key per building job
                    long buildingKey = mixKey(regionKey, planned, origin);

                    // Queue for drip-feed tick

                    KingdomGenGate.beginOne(regionKey);
                    SAT_QUEUE.addLast(new SatJob(level, modId, regionKey, origin, bpId, buildingKey));
                   
                    chosenCenters.add(c.pos);
                    planned++;

                } catch (Exception ex) {
                    LOGGER.warn("[Kingdoms] Failed to plan satellite blueprint '{}' near {}",
                            bpId, c.pos, ex);
                }
            }
        }

        LOGGER.info("[Kingdoms] Planned {} / {} satellites around castle at {} (size={}) (satQueueTotal={})",
        planned, target, castleOrigin, size, SAT_QUEUE.size());

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
            int maxR,
            RandomSource rng,
            Pass pass,
            int count,
            ClampRect clamp
    ) {
        ArrayList<Candidate> out = new ArrayList<>(count);
        int sea = level.getSeaLevel();

        for (int i = 0; i < count; i++) {
            double t = rng.nextDouble();
            double minR = Math.max(20, MIN_DIST_FROM_CASTLE);
            int maxRLocal = Math.max(maxR, (int)minR + 1);
            double r = minR + t * (maxRLocal - minR);
            double ang = rng.nextDouble() * Math.PI * 2.0;

            int x = castle.getX() + (int)Math.round(Math.cos(ang) * r);
            int z = castle.getZ() + (int)Math.round(Math.sin(ang) * r);

            if (clamp != null && !clamp.containsXZ(x, z)) {
                continue; // center must be inside border
            }

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
