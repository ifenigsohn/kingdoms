package name.kingdoms.blueprint;

import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

import java.util.*;

/**
 * RoadBuilder V2 (REPLACEMENT)
 *
 * Goals (hard requirements):
 * - Roads connect all blueprint anchors (RoadNetworkPlanner edges)
 * - Roads prefer straight lines + smoothing (LOS simplification + re-route)
 * - Stairs generated deterministically when dy != 0 (correct direction + placement)
 * - Bridges over water/air: spruce planks + spruce fences
 * - Terrain grading is simple + predictable (no deep trenches)
 *
 * Key simplifications:
 * - NO DP height planner
 * - Use RoadAStar's returned y directly (single source of truth)
 * - Stairs are only placed for dy = +/-1 steps
 * - Bridge decision is local: water below OR air gap below
 */
public final class RoadBuilder {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Deque<Job> JOBS = new ArrayDeque<>();

    // Throughput knobs
    public static int OPS_PER_TICK = 1800;
    public static long MAX_NANOS_PER_TICK = 7_000_000; // ~7ms

    // Road geometry
    private static final int HALF_WIDTH = 1;   // 3-wide
    private static final int FENCE_OFFSET = 2; // fences outside road

    // Clear + grading
    private static final int CLEAR_HEIGHT = 4;
    private static final int CUT_MAX = 3;       // keep grading shallow
    private static final int FILL_MAX = 6;

    // Landing pad (turn-on-grade)
    private static final int PAD_HALF = 2;     // 5x5 square
    private static final int PAD_CUT_MAX = 2;  // keep pad grading shallow
    private static final int PAD_FILL_MAX = 6;
    private static final int MAX_DIRT_SUPPORT_DEPTH = 6;


    // Bridge detection
    private static final int GAP_BRIDGE_DEPTH = 5; // air below this many blocks => bridge

    // Smoothing
    private static final int MAX_WAYPOINTS_PER_EDGE = 64; // safety
    private static final int LOS_MARGIN = RoadAStar.FOOTPRINT_MARGIN;

    private RoadBuilder() {}

    public static void init() {
        LOGGER.info("[Kingdoms] RoadBuilder.init() registered tick");
        ServerTickEvents.END_SERVER_TICK.register(RoadBuilder::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> JOBS.clear());
    }

    public static void enqueue(ServerLevel level, long regionKey, List<RoadEdge> edges) {
        if (edges == null || edges.isEmpty()) return;
        JOBS.add(new Job(level, regionKey, edges));
        LOGGER.info("[Kingdoms] Enqueued {} road edges for region {}", edges.size(), regionKey);
    }

   

    private static void tick(MinecraftServer server) {
        if (JOBS.isEmpty()) return;

        Job job = JOBS.peek();
        if (job == null) return;

        if ((server.getTickCount() % 200) == 0) {
            LOGGER.info("[RoadBuilder] ALIVE region={} edgeIndex={}/{} blocksPlaced={}",
                    job.regionKey, job.edgeIndex, job.edges.size(), job.blocksPlacedTotal);
        }


        long deadline = System.nanoTime() + MAX_NANOS_PER_TICK;

        boolean done = job.step(OPS_PER_TICK, deadline);
        if (done) {
            JOBS.poll();

            if (job.blocksPlacedTotal > 0 || job.edges.isEmpty()) {
                KingdomGenGate.endRegion(job.regionKey);
            } else {
                LOGGER.warn("[Kingdoms] Roads job finished with 0 blocks for region {} — retrying", job.regionKey);
                RoadBuilder.enqueue(job.level, job.regionKey, job.edges);
                // don’t unlock
            }

            LOGGER.info("[Kingdoms] Finished roads for region {} (blocksPlaced={})",
                    job.regionKey, job.blocksPlacedTotal);
        }

    }

    // ============================================================
    // Job
    // ============================================================
    private static final class Job {
        final ServerLevel level;
        final long regionKey;
        final List<RoadEdge> edges;
        int edgeRetryTicks = 0;
        static final int EDGE_RETRY_TICKS_BEFORE_SKIP = 20 * 10; // 10s

        int edgeIndex = 0;

        // current edge path
        List<BlockPos> path = null;
        int pathIndex = 0;

        long blocksPlacedTotal = 0;

        private boolean isStairAt(int x, int y, int z) {
            scratch.set(x, y, z);
            return level.getBlockState(scratch).getBlock() instanceof StairBlock;
        }

        private boolean isNearStairTop(int x, int y, int z) {
            // Check same level and one above (top of stair)
            for (int yy = y; yy <= y + 1; yy++) {
                if (isStairAt(x, yy, z)) return true;

                // 4-neighbors (no diagonals)
                if (isStairAt(x + 1, yy, z)) return true;
                if (isStairAt(x - 1, yy, z)) return true;
                if (isStairAt(x, yy, z + 1)) return true;
                if (isStairAt(x, yy, z - 1)) return true;
            }
            return false;
        }


        private BlockPos nudgeOutOfFootprint(BlockPos p, int margin, int maxRadius) {
            int px = p.getX();
            int pz = p.getZ();

            if (!BlueprintFootprintState.get(level).isBlocked(regionKey, px, pz, margin)) {
                return p;
            }

            for (int r = 1; r <= maxRadius; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    int x1 = px + dx, z1 = pz - r;
                    if (!BlueprintFootprintState.get(level).isBlocked(regionKey, x1, z1, margin)) {
                        return new BlockPos(x1, surfaceY(x1, z1), z1);
                    }
                    int x2 = px + dx, z2 = pz + r;
                    if (!BlueprintFootprintState.get(level).isBlocked(regionKey, x2, z2, margin)) {
                        return new BlockPos(x2, surfaceY(x2, z2), z2);
                    }
                }
                for (int dz = -r + 1; dz <= r - 1; dz++) {
                    int x1 = px - r, z1 = pz + dz;
                    if (!BlueprintFootprintState.get(level).isBlocked(regionKey, x1, z1, margin)) {
                        return new BlockPos(x1, surfaceY(x1, z1), z1);
                    }
                    int x2 = px + r, z2 = pz + dz;
                    if (!BlueprintFootprintState.get(level).isBlocked(regionKey, x2, z2, margin)) {
                        return new BlockPos(x2, surfaceY(x2, z2), z2);
                    }
                }
            }

            return p;
        }


        final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

        // local “done columns” to avoid re-cutting the same XZ a million times
        final HashSet<Long> touchedXZ = new HashSet<>(65536);

        Job(ServerLevel level, long regionKey, List<RoadEdge> edges) {
            this.level = level;
            this.regionKey = regionKey;
            this.edges = edges;
        }

        boolean step(int budget, long deadline) {
            int ops = 0;

            while (ops < budget) {
                if (System.nanoTime() >= deadline) return false;

                if (path == null) {
                    if (edgeIndex >= edges.size()) return true;

                    RoadEdge e = edges.get(edgeIndex);

                    BlockPos a = nudgeOutOfFootprint(surfaceAlign(e.a()), 2, 96);
                    BlockPos b = nudgeOutOfFootprint(surfaceAlign(e.b()), 2, 96);


                    // A* (already has turn penalty + slope limit)
                    List<BlockPos> raw = RoadAStar.findPath(level, regionKey, a, b);

                    if (raw == null || raw.size() < 2) {
                    // Don't skip edges. Most failures are just unloaded chunks.
                    edgeRetryTicks++;
                    if ((edgeRetryTicks % 40) == 0) {
                        LOGGER.warn("[RoadBuilder] Edge {} still failing after {} ticks (a={} b={})",
                                edgeIndex, edgeRetryTicks, a, b);
                    }

                    // Try a different edge next tick so we don't stall the whole region on one bad edge.
                    edgeIndex = (edgeIndex + 1) % edges.size();
                    return false;
                }
                edgeRetryTicks = 0;


                    // Smoothing: LOS waypoints + re-route between waypoints (keeps A* heights)
                    path = smoothAndReroute(raw);
                    pathIndex = 0;

                    edgeIndex++;
                }

                if (pathIndex >= path.size()) {
                    path = null;
                    continue;
                }

                BlockPos cur  = path.get(pathIndex);
                BlockPos prev = (pathIndex > 0) ? path.get(pathIndex - 1) : null;
                BlockPos next = (pathIndex + 1 < path.size()) ? path.get(pathIndex + 1) : null;

                int placed = placeAt(prev, cur, next, pathIndex);
                ops += placed;
                blocksPlacedTotal += placed;

                pathIndex++;
            }

            return false;
        }

        // ============================================================
        // Smoothing
        // ============================================================

        private List<BlockPos> smoothAndReroute(List<BlockPos> raw) {
            // Waypoints are a subset of raw points; keeps stable endpoints.
            List<BlockPos> wp = simplifyLOS(raw);
            if (wp.size() > MAX_WAYPOINTS_PER_EDGE) {
                // safety: if something goes wild, just keep raw
                return raw;
            }

            // Re-route between waypoints using A* again (shorter, straighter problem),
            // then concatenate, removing duplicates.
            ArrayList<BlockPos> out = new ArrayList<>();
            out.add(wp.get(0));

            for (int i = 0; i < wp.size() - 1; i++) {
                BlockPos s = wp.get(i);
                BlockPos g = wp.get(i + 1);

                List<BlockPos> seg = RoadAStar.findPath(level, regionKey, s, g);
                if (seg == null || seg.size() < 2) {
                    // fallback: a simple stepping line with clamped y
                    seg = fallbackLine(s, g);
                }

                // append without duplicating first point
                for (int k = 1; k < seg.size(); k++) out.add(seg.get(k));
            }

            return out;
        }

        private List<BlockPos> simplifyLOS(List<BlockPos> raw) {
            ArrayList<BlockPos> out = new ArrayList<>();
            int i = 0;
            out.add(raw.get(0));

            while (i < raw.size() - 1) {
                int best = i + 1;
                // find farthest reachable j by LOS
                for (int j = raw.size() - 1; j > best; j--) {
                    if (hasLOS(raw.get(i), raw.get(j))) {
                        best = j;
                        break;
                    }
                }
                out.add(raw.get(best));
                i = best;
            }
            return out;
        }

        private boolean hasLOS(BlockPos a, BlockPos b) {
            int x0 = a.getX(), z0 = a.getZ();
            int x1 = b.getX(), z1 = b.getZ();

            int dx = Math.abs(x1 - x0);
            int dz = Math.abs(z1 - z0);
            int sx = Integer.compare(x1, x0);
            int sz = Integer.compare(z1, z0);

            int err = dx - dz;
            int x = x0, z = z0;

            while (!(x == x1 && z == z1)) {
                // allow endpoints, block interior cells if footprint-blocked
                if (!(x == x0 && z == z0) && !(x == x1 && z == z1)) {
                    if (BlueprintFootprintState.get(level).isBlocked(regionKey, x, z, LOS_MARGIN)) return false;
                }
                int e2 = err << 1;
                if (e2 > -dz) { err -= dz; x += sx; }
                if (e2 < dx)  { err += dx; z += sz; }
            }
            return true;
        }

        private List<BlockPos> fallbackLine(BlockPos a, BlockPos b) {
            ArrayList<BlockPos> out = new ArrayList<>();
            int x = a.getX(), z = a.getZ();
            int tx = b.getX(), tz = b.getZ();

            int y = a.getY();
            out.add(new BlockPos(x, y, z));

            int guard = 0;
            while ((x != tx || z != tz) && guard++ < 20000) {
                int dx = Integer.compare(tx, x);
                int dz = Integer.compare(tz, z);

                // dominant axis stepping
                if (Math.abs(tx - x) >= Math.abs(tz - z)) x += dx;
                else z += dz;

                int surf = surfaceY(x, z);
                int dy = surf - y;
                if (dy > 1) surf = y + 1;
                else if (dy < -1) surf = y - 1;
                y = surf;

                out.add(new BlockPos(x, y, z));
            }
            return out;
        }

        // ============================================================
        // Build logic
        // ============================================================

        private int placeAt(BlockPos prev, BlockPos cur, BlockPos next, int stepIndex) {

            int ops = 0;

            int y = cur.getY();
            Direction fwd = (next != null) ? dirTo(cur, next) : (prev != null ? dirTo(prev, cur) : Direction.EAST);
            int[] perp = perpXZ(fwd);
            
            int px = perp[0], pz = perp[1];

            boolean isBridge = isBridgeAt(cur, fwd);

            Direction prevDir = (prev != null) ? dirTo(prev, cur) : null;
            Direction nextDir = (next != null) ? dirTo(cur, next) : null;


            // ---- landing-pad detection ----
            int dyPrev = (prev != null) ? (cur.getY() - prev.getY()) : 0;
            int dyNext = (next != null) ? (next.getY() - cur.getY()) : 0;

            // 90-degree turn (not just "changed direction", but orthogonal turn)
            boolean turned90 = (prevDir != null && nextDir != null
                    && prevDir != nextDir
                    && prevDir != nextDir.getOpposite());

            // "turn on grade" = turning while entering or exiting a slope
            boolean turnOnGrade = turned90 && ((dyPrev != 0) || (dyNext != 0));

            // Place the pad only on non-bridges
            boolean placedPad = false;
            if (!isBridge && turnOnGrade) {
                ops += placeLandingPadSquare(cur, y);
                placedPad = true;
            }

            // ----- stair reservation for this step (prevents surface overwriting stair tiles) -----
            BlockPos stairLower = null;
            Direction stairTravel = null;
            int stairDY = 0;

            if (next != null) {
                stairDY = next.getY() - cur.getY();
                if (stairDY == 1 || stairDY == -1) {
                    stairTravel = dirTo(cur, next);
                    stairLower = (stairDY > 0) ? cur : next; // lower cell contains the stair
                }
            }

            BlockState surface = isBridge
                    ? Blocks.SPRUCE_PLANKS.defaultBlockState()
                    : Blocks.DIRT_PATH.defaultBlockState();

             // ---- Stairs (deterministic) ----
            if (next != null) {
                int dy = next.getY() - cur.getY();
                if (dy == 1 || dy == -1) {
                    ops += placeStairStep(cur, next, fwd, dy, placedPad);
                }
            }


            // ---- Surface / bridge deck (3-wide) ----
            for (int w = -HALF_WIDTH; w <= HALF_WIDTH; w++) {
                int rx = cur.getX() + px * w;
                int rz = cur.getZ() + pz * w;

                if (isStairAt(rx, y, rz) || isStairAt(rx, y + 1, rz)) {
                    continue;
                }

                // NEW: also prevent placing path blocks right next to stair tops
                if (isNearStairTop(rx, y, rz)) {
                    continue;
                }


                // If this step includes stairs, don't paint the lower stair row as flat surface.
                // (prevents another segment from overwriting stairs)
                if (stairLower != null) {
                    int[] sp = perpXZ(stairTravel);
                    int spx = sp[0], spz = sp[1];

                    int sx = stairLower.getX() + spx * w;
                    int sz = stairLower.getZ() + spz * w;

                    if (rx == sx && rz == sz && y == stairLower.getY() + 1) {
                        continue;
                    }
                }


                if (isFootprintBlocked(rx, rz)) continue;

                long k = keyXZ(rx, rz);
                if (!touchedXZ.add(k)) {
                    // already processed this column, but still ensure surface at this y
                    // (idempotent; cheap)
                } else {
                    // grade gently: cut small overhangs and fill small gaps
                    if (!isBridge) {
                        cutDownTo(rx, rz, y);
                        fillUpTo(rx, rz, y - 1);
                    }

                     clearRoadVolume(rx, y, rz);
                }

                // support / pillars
                if (isBridge) {
                    ensurePillarToSolid(rx, y - 1, rz, 96);
                } else {
                    ensureSupport(rx, y - 1, rz, 16);
                }

                if (isStairAt(rx, y, rz)) continue; // never overwrite stairs
                set(rx, y, rz, surface);
                ops++;
            }

            // ---- Lamp posts (every 10 blocks, one side) ----
            if (!isBridge) {
                ops += tryPlaceLampPost(cur, fwd, y, stepIndex);
            }


           

            // ---- Bridge fences ----
            if (isBridge) {
                BlockState fence = Blocks.SPRUCE_FENCE.defaultBlockState();

                // Consider this segment "straight" only if prevDir == nextDir.
                // On turns, fences often end up on top/inside corners.
                boolean straight = (prevDir != null && nextDir != null && prevDir == nextDir);

                for (int side : new int[]{-FENCE_OFFSET, FENCE_OFFSET}) {
                    int fx = cur.getX() + px * side;
                    int fz = cur.getZ() + pz * side;
                    int fy = y + 1;

                    if (straight) {
                        // only place fence if air at fence spot
                        if (isAir(fx, fy, fz)) {
                            set(fx, fy, fz, fence);
                            ops++;
                        }
                    } else {
                        // ✅ cleanup: if a fence exists here from earlier placement, remove it
                        scratch.set(fx, fy, fz);
                        if (level.getBlockState(scratch).is(Blocks.SPRUCE_FENCE)) {
                            level.setBlock(scratch, Blocks.AIR.defaultBlockState(), 2);
                            ops++;
                        }
                    }
                }
            }


            return ops;
        }

        private int placeLandingPadSquare(BlockPos center, int y) {
            int ops = 0;

            for (int dx = -PAD_HALF; dx <= PAD_HALF; dx++) {
                for (int dz = -PAD_HALF; dz <= PAD_HALF; dz++) {
                    int x = center.getX() + dx;
                    int z = center.getZ() + dz;

                    if (isFootprintBlocked(x, z)) continue;

                    // Don't bulldoze stairs that already exist
                    if (isStairAt(x, y, z)) continue;

                    // Shallow grading to y (pad wants to be flat)
                    cutDownToPad(x, z, y);
                    fillUpToPad(x, z, y - 1);

                    clearRoadVolume(x, y, z);
                    ensureSupport(x, y - 1, z, 16);

                    set(x, y, z, Blocks.DIRT_PATH.defaultBlockState());
                    ops++;
                }
            }

            return ops;
        }

        private int tryPlaceLampPost(BlockPos cur, Direction fwd, int roadY, int stepIndex) {
            // every 10 blocks
            if (stepIndex <= 0 || (stepIndex % 10) != 0) return 0;

            // alternate sides every 10 so it doesn't look like a picket fence
            int side = (((stepIndex / 10) & 1) == 0) ? (HALF_WIDTH + 1) : -(HALF_WIDTH + 1);

            int[] perp = perpXZ(fwd);
            int px = perp[0], pz = perp[1];

            int x = cur.getX() + px * side;
            int z = cur.getZ() + pz * side;
            int y = roadY;

            // don't place inside blueprint footprint margins
            if (isFootprintBlocked(x, z)) return 0;

            // must NOT be "over path blocks" / road blocks
            scratch.set(x, y, z);
            BlockState ground = level.getBlockState(scratch);
            boolean groundIsRoad =
                    ground.is(Blocks.DIRT_PATH) ||
                    ground.is(Blocks.SPRUCE_PLANKS) ||
                    (ground.getBlock() instanceof StairBlock);

            if (groundIsRoad) return 0;

            // must not be waterlogged / fluid tile
            if (!ground.getFluidState().isEmpty()) return 0;

            // no floating posts: require solid support directly below
            scratch.set(x, y - 1, z);
            BlockState below = level.getBlockState(scratch);
            if (below.isAir() || !below.getFluidState().isEmpty()) return 0;

            // clear vertical space for the post + lantern
            // (only clears "soft" blocks; won't bulldoze buildings)
            clearSoft(x, y + 1, z);
            clearSoft(x, y + 2, z);
            clearSoft(x, y + 3, z);

            // only place if the spaces are now air (so we don't replace e.g. stone walls)
            if (!isAir(x, y + 1, z)) return 0;
            if (!isAir(x, y + 2, z)) return 0;
            if (!isAir(x, y + 3, z)) return 0;

            // place: 2 fences + lantern
            set(x, y + 1, z, Blocks.SPRUCE_FENCE.defaultBlockState());
            set(x, y + 2, z, Blocks.SPRUCE_FENCE.defaultBlockState());
            set(x, y + 3, z, Blocks.LANTERN.defaultBlockState());

            return 3;
        }


        private void cutDownToPad(int x, int z, int padY) {
            int surf = surfaceY(x, z);
            if (surf <= padY) return;

            int top = Math.min(surf, padY + PAD_CUT_MAX);
            for (int yy = padY + 1; yy <= top; yy++) {
                clearSoft(x, yy, z);
            }
        }

        private void fillUpToPad(int x, int z, int y) {
            int yy = y;
            for (int d = 0; d < PAD_FILL_MAX; d++) {
                if (yy < level.getMinY()) return;

                scratch.set(x, yy, z);
                BlockState bs = level.getBlockState(scratch);
                if (!bs.isAir() && bs.getFluidState().isEmpty()) return;

                level.setBlock(scratch, Blocks.DIRT.defaultBlockState(), 2);
                yy--;
            }
        }


        private int placeStairStep(BlockPos cur, BlockPos next, Direction travel, int dy, boolean isBridge) {
            int ops = 0;

            // stairs go on the lower cell:
            // dy=+1 -> lower is cur
            // dy=-1 -> lower is next
            BlockPos lower = (dy > 0) ? cur : next;
            int stairY = lower.getY() + 1;


            // stairs face uphill
            Direction facing = (dy > 0) ? travel : travel.getOpposite();

            int[] stairPerp = perpXZ(travel);
            int spx = stairPerp[0], spz = stairPerp[1];

            BlockState stair = (isBridge ? Blocks.SPRUCE_STAIRS : Blocks.COBBLESTONE_STAIRS).defaultBlockState()
                    .setValue(StairBlock.FACING, facing)
                    .setValue(StairBlock.HALF, Half.BOTTOM)
                    .setValue(StairBlock.SHAPE, StairsShape.STRAIGHT);

            for (int w = -HALF_WIDTH; w <= HALF_WIDTH; w++) {
                int sx = lower.getX() + spx * w;
                int sz = lower.getZ() + spz * w;

                if (isFootprintBlocked(sx, sz)) continue;

                // carve + support
                clearRoadVolume(sx, stairY, sz);
                if (isBridge) ensurePillarToSolid(sx, stairY - 1, sz, 96);
                else          ensureSupport(sx, stairY - 1, sz, 16);


                // overwrite surface at this tile with stair (intended)
                set(sx, stairY, sz, stair);

                ops++;
            }

            // ---- Top landing strip (guarantees no jump at the top of the stair) ----
            BlockPos upper = (dy > 0) ? next : cur;
            int upperY = upper.getY();

            int[] sp2 = perpXZ(travel);
            int spx2 = sp2[0], spz2 = sp2[1];

            for (int w = -HALF_WIDTH; w <= HALF_WIDTH; w++) {
                int ux = upper.getX() + spx2 * w;
                int uz = upper.getZ() + spz2 * w;

                if (isFootprintBlocked(ux, uz)) continue;
                if (isStairAt(ux, upperY, uz) || isStairAt(ux, upperY + 1, uz)) continue;

                clearRoadVolume(ux, upperY, uz);
                if (isBridge) ensurePillarToSolid(ux, upperY - 1, uz, 96);
                else          ensureSupport(ux, upperY - 1, uz, 16);

                BlockState landing = isBridge ? Blocks.SPRUCE_PLANKS.defaultBlockState()
                              : Blocks.DIRT_PATH.defaultBlockState();
                        set(ux, upperY, uz, landing);

            }


            
                // debug verify: center stair must exist
                int cx = lower.getX();
                int cz = lower.getZ();
               if (!isFootprintBlocked(cx, cz) && !isStairAt(cx, stairY, cz)) {
                    LOGGER.warn("[RoadBuilder] Expected stair missing at {} dy={} travel={}",
                            new BlockPos(cx, stairY, cz), dy, travel);
                }
            return ops;
        }

        // ============================================================
        // Bridge detection (simple + reliable)
        // ============================================================

        private boolean isBridgeAt(BlockPos cur, Direction fwd) {
            int y = cur.getY();

            // perpendicular for 3-wide
            int[] perp = perpXZ(fwd);
            int px = perp[0], pz = perp[1];

            boolean water = false;
            boolean airGap = false;

            for (int w = -HALF_WIDTH; w <= HALF_WIDTH; w++) {
                int x = cur.getX() + px * w;
                int z = cur.getZ() + pz * w;

                if (isWaterAtOrBelow(x, y, z)) water = true;
                if (isDeepGapBelow(x, y - 1, z, GAP_BRIDGE_DEPTH)) airGap = true;
            }

            return water || airGap;
        }

        // ============================================================
        // Terrain + world helpers
        // ============================================================

        private int surfaceY(int x, int z) {
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            y = Math.max(level.getMinY() + 1, y);

            scratch.set(x, y, z);

            boolean steppedDownThroughRoad = false;

            while (scratch.getY() > level.getMinY() + 1) {
                BlockState bs = level.getBlockState(scratch);

                boolean isRoad =
                        bs.is(Blocks.DIRT_PATH) ||
                        bs.is(Blocks.COBBLESTONE) ||
                        bs.is(Blocks.SPRUCE_PLANKS) ||
                        bs.is(Blocks.SPRUCE_FENCE) ||
                        bs.is(Blocks.SPRUCE_LOG) ||
                        bs.is(Blocks.LANTERN) ||
                        (bs.getBlock() instanceof StairBlock);

                if (!isRoad) break;

                steppedDownThroughRoad = true;
                scratch.move(0, -1, 0);
            }

            int out = steppedDownThroughRoad ? (scratch.getY() + 1) : scratch.getY();
            return Math.min(level.getMaxY() - 1, out);
        }


        private BlockPos surfaceAlign(BlockPos p) {
            int surf = surfaceY(p.getX(), p.getZ());
            if (Math.abs(p.getY() - surf) <= 3) return p;
            return new BlockPos(p.getX(), surf, p.getZ());
        }

        private void cutDownTo(int x, int z, int roadY) {
            int surf = surfaceY(x, z);
            if (surf <= roadY) return;

            int top = Math.min(surf, roadY + CUT_MAX);
            for (int yy = roadY + 1; yy <= top; yy++) {
                clearSoft(x, yy, z);
            }
        }

        private void fillUpTo(int x, int z, int y) {
            int yy = y;
            for (int d = 0; d < FILL_MAX; d++) {
                if (yy < level.getMinY()) return;

                scratch.set(x, yy, z);
                BlockState bs = level.getBlockState(scratch);

                if (!bs.isAir() && bs.getFluidState().isEmpty()) return; // already solid

                level.setBlock(scratch, Blocks.DIRT.defaultBlockState(), 2);
                yy--;
            }
        }

        private boolean isAir(int x, int y, int z) {
            if (y < level.getMinY() || y >= level.getMaxY()) return false;
            scratch.set(x, y, z);
            return level.getBlockState(scratch).isAir();
        }

        private void clearRoadVolume(int x, int y, int z) {
            for (int dy = 0; dy <= CLEAR_HEIGHT; dy++) clearSoft(x, y + dy, z);
        }

        private void clearSoft(int x, int y, int z) {
            if (y < level.getMinY() || y >= level.getMaxY()) return;
            scratch.set(x, y, z);
            BlockState bs = level.getBlockState(scratch);

            if (bs.isAir()) return;
            if (!bs.getFluidState().isEmpty()) return;

            boolean isRoad =
                    bs.is(Blocks.DIRT_PATH) ||
                    bs.is(Blocks.SPRUCE_PLANKS) ||
                    bs.is(Blocks.SPRUCE_FENCE) ||
                    bs.is(Blocks.SPRUCE_LOG) ||
                    (bs.getBlock() instanceof StairBlock);

            if (isRoad) return;

            // clear natural-ish blocks only
            level.setBlock(scratch, Blocks.AIR.defaultBlockState(), 2);
        }


        private boolean isDeepGapBelow(int x, int y, int z, int depth) {
            int yy = y;
            for (int i = 0; i < depth; i++) {
                if (yy < level.getMinY()) return true;
                scratch.set(x, yy, z);
                BlockState bs = level.getBlockState(scratch);
                if (!bs.isAir() && bs.getFluidState().isEmpty()) return false;
                yy--;
            }
            return true;
        }

        private boolean isWaterAtOrBelow(int x, int y, int z) {
            scratch.set(x, y, z);
            if (!level.getBlockState(scratch).getFluidState().isEmpty()) return true;
            scratch.set(x, y - 1, z);
            return !level.getBlockState(scratch).getFluidState().isEmpty();
        }

        private void ensureSupport(int x, int y, int z, int maxDepth) {
            int yy = y;
            int placed = 0;

            for (int d = 0; d < maxDepth; d++) {
                if (yy < level.getMinY()) return;

                scratch.set(x, yy, z);
                BlockState bs = level.getBlockState(scratch);
                if (!bs.isAir() && bs.getFluidState().isEmpty()) return;

                // ✅ stop making giant dirt towers; after a few blocks, switch to logs
                if (placed < MAX_DIRT_SUPPORT_DEPTH) {
                    level.setBlock(scratch, Blocks.DIRT.defaultBlockState(), 2);
                } else {
                    level.setBlock(scratch, Blocks.SPRUCE_LOG.defaultBlockState(), 2);
                }

                placed++;
                yy--;
            }
        }


        private void ensurePillarToSolid(int x, int y, int z, int maxDepth) {
            int yy = y;
            for (int d = 0; d < maxDepth; d++) {
                if (yy < level.getMinY()) return;

                scratch.set(x, yy, z);
                BlockState bs = level.getBlockState(scratch);
                if (!bs.isAir() && bs.getFluidState().isEmpty()) return;

                // spruce log pillar (matches spruce bridges)
                level.setBlock(scratch, Blocks.SPRUCE_LOG.defaultBlockState(), 2);
                yy--;
            }
        }

        private void set(int x, int y, int z, BlockState state) {
            if (y < level.getMinY() || y >= level.getMaxY()) return;
            scratch.set(x, y, z);
            level.setBlock(scratch, state, 2);
        }

        private boolean isFootprintBlocked(int x, int z) {
            return BlueprintFootprintState.get(level).isBlocked(regionKey, x, z, FOOTPRINT_MARGIN);
        }

        private long keyXZ(int x, int z) {
            return (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
        }

        private int[] perpXZ(Direction d) {
            return switch (d) {
                case EAST  -> new int[]{ 0,  1};
                case SOUTH -> new int[]{-1,  0};
                case WEST  -> new int[]{ 0, -1};
                case NORTH -> new int[]{ 1,  0};
                default    -> new int[]{ 0,  1};
            };
        }

        private Direction dirTo(BlockPos a, BlockPos b) {
            int dx = b.getX() - a.getX();
            int dz = b.getZ() - a.getZ();
            if (dx == 0 && dz == 0) return Direction.EAST;

            if (Math.abs(dx) >= Math.abs(dz)) return (dx > 0) ? Direction.EAST : Direction.WEST;
            return (dz > 0) ? Direction.SOUTH : Direction.NORTH;
        }
    }

    

    // must match RoadAStar
    private static final int FOOTPRINT_MARGIN = RoadAStar.FOOTPRINT_MARGIN;
}
