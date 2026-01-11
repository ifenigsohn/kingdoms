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
 * RoadBuilder (rewritten)
 *
 * Goals:
 * - Roads always stay connected (profile enforces |dy|<=1 between consecutive nodes)
 * - Way fewer stairs (planner prefers flat; uses cut/fill)
 * - Fix "stairs too low" by placing stairs on the LOWER cell (downhill uses NEXT)
 * - No weird gaps from stair-row skipping (we do NOT reserve/skip rows; we just don't overwrite StairBlocks)
 * - Landing pads at turns on grades (forces flat)
 */
public final class RoadBuilder {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Deque<Job> JOBS = new ArrayDeque<>();

    // tune
    public static int OPS_PER_TICK = 1500;
    public static long MAX_NANOS_PER_TICK = 6_000_000; // ~6ms

    // road width settings
    private static final int HALF_WIDTH = 1;     // 3 wide = -1..+1
    private static final int FENCE_OFFSET = 2;   // fences outside road edge

    // pathfinding failure handling
    private static final int MAX_EDGE_FAILS_BEFORE_SKIP = 5; // set 0 to "never skip"

    // IMPORTANT: must match RoadAStar.FOOTPRINT_MARGIN
    private static final int FOOTPRINT_MARGIN = 2;

    // How far we’ll search to escape a footprint
    private static final int NUDGE_MAX_RADIUS = 96;

    // Road shaping
    private static final int CLEAR_HEIGHT = 3;        // headroom above road surface
    private static final int SUPPORT_DEPTH = 20;      // normal dirt fill depth (a bit more aggressive)
    private static final int GAP_BRIDGE_DEPTH = 6;    // if air below road >= this, treat as bridge
    private static final int BRIDGE_PILLAR_MAX = 96;  // more aggressive pillars
    private static final int LANDING_HALF = 2;        // 5x5 platform at turns

    // Terrain grading (no shoulder blocks)
    private static final int GRADE_SIDE = 1;
    private static final int GRADE_CUT_MAX = 10;
    private static final int GRADE_FILL_MAX = 10;

    // ----------------------------
    // HEIGHT PLANNER (DP)
    // ----------------------------
    private static final int PLAN_Y_RADIUS = 6;           // try y in [terrain-6 .. terrain+6]
    private static final double COST_TERRAIN = 1.0;       // stay near terrain surface
    private static final double COST_STEP = 6.0;         // penalize dy!=0 (reduces stair spam)
    private static final double COST_DY_CHANGE = 10.0;    // penalize changing dy
    private static final double COST_SIGN_FLIP = 40.0;    // penalize up-then-down / down-then-up
    private static final int TURN_LANDING_RADIUS = 2;     // force flat around turns

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

        long deadline = System.nanoTime() + MAX_NANOS_PER_TICK;

        boolean done = job.step(OPS_PER_TICK, deadline);
        if (done) {
            JOBS.poll();
            LOGGER.info("[Kingdoms] Finished roads for region {} (blocksPlaced={})",
                    job.regionKey, job.blocksPlacedTotal);
        }
    }

    private static final class Job {
        final ServerLevel level;
        final long regionKey;
        final List<RoadEdge> edges;
        
        private boolean hasStairAt(int x, int y, int z) {
            scratch.set(x, y, z);
            return level.getBlockState(scratch).getBlock() instanceof StairBlock;
        }

        private boolean anyStairNearY(int x, int y, int z) {
            return hasStairAt(x, y, z) || hasStairAt(x, y - 1, z) || hasStairAt(x, y + 1, z);
        }




        int edgeIndex = 0;

        // current path work
        List<BlockPos> path = null;
        int pathIndex = 0;

        // diagnostics / safety
        long blocksPlacedTotal = 0;
        int edgeFailStreak = 0;

        final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

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

                    BlockPos a0 = surfaceAlign(e.a());
                    BlockPos b0 = surfaceAlign(e.b());

                    BlockPos a = nudgeOutOfFootprint(a0, FOOTPRINT_MARGIN, NUDGE_MAX_RADIUS);
                    BlockPos b = nudgeOutOfFootprint(b0, FOOTPRINT_MARGIN, NUDGE_MAX_RADIUS);

                    if (!level.hasChunkAt(a) || !level.hasChunkAt(b)) return false;

                    // A* gives an XZ path (Y doesn't matter; we will re-plan Y)
                    List<BlockPos> raw = RoadAStar.findPath(level, regionKey, a, b);

                    if (raw == null || raw.size() < 2) {
                        path = null;
                        edgeFailStreak++;

                        if (MAX_EDGE_FAILS_BEFORE_SKIP > 0 && edgeFailStreak >= MAX_EDGE_FAILS_BEFORE_SKIP) {
                            LOGGER.warn("[Kingdoms] Road edge {} failed {}x; skipping edge for region {}",
                                    edgeIndex + 1, edgeFailStreak, regionKey);
                            edgeIndex++;
                            edgeFailStreak = 0;
                        }
                        return false;
                    }

                    // ✅ PLAN HEIGHTS (major fix): stable profile -> fewer stairs, always connected
                    path = followTerrainProfile(raw);
                    pathIndex = 0;

                    edgeIndex++;
                    edgeFailStreak = 0;
                }

                if (pathIndex >= path.size()) {
                    path = null;
                    continue;
                }

                BlockPos cur  = path.get(pathIndex);
                BlockPos prev = (pathIndex > 0) ? path.get(pathIndex - 1) : null;
                BlockPos next = (pathIndex + 1 < path.size()) ? path.get(pathIndex + 1) : null;

                int placed = placeSegment(prev, cur, next);
                ops += placed;
                blocksPlacedTotal += placed;

                pathIndex++;
            }

            return false;
        }

        // ----------------------------
        // Height / terrain helpers
        // ----------------------------
        private int surfaceY(int x, int z) {
            int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            return Math.max(level.getMinY() + 1, h - 1);
        }

        private BlockPos surfaceAlign(BlockPos p) {
            int surf = surfaceY(p.getX(), p.getZ());
            int dy = Math.abs(p.getY() - surf);
            if (dy <= 3) return p;
            return new BlockPos(p.getX(), surf, p.getZ());
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

        // ----------------------------
        // HEIGHT PLANNER (DP)
        // ----------------------------
        private List<BlockPos> applyPlannedHeights(List<BlockPos> xzPath) {
            final int n = xzPath.size();
            if (n <= 1) return xzPath;

            int[] xs = new int[n];
            int[] zs = new int[n];
            int[] terr = new int[n];

            for (int i = 0; i < n; i++) {
                BlockPos p = xzPath.get(i);
                xs[i] = p.getX();
                zs[i] = p.getZ();
                terr[i] = surfaceY(xs[i], zs[i]);
            }

            // Mark turns and force flat landing windows around them
            boolean[] forceFlat = new boolean[n];
            for (int i = 1; i + 1 < n; i++) {
                int dx1 = Integer.signum(xs[i] - xs[i - 1]);
                int dz1 = Integer.signum(zs[i] - zs[i - 1]);
                int dx2 = Integer.signum(xs[i + 1] - xs[i]);
                int dz2 = Integer.signum(zs[i + 1] - zs[i]);
                boolean turned = (dx1 != dx2 || dz1 != dz2);

                if (turned) {
                    int lo = Math.max(0, i - TURN_LANDING_RADIUS);
                    int hi = Math.min(n - 1, i + TURN_LANDING_RADIUS);
                    for (int k = lo; k <= hi; k++) forceFlat[k] = true;
                }
            }

            // DP over states: (y, lastDy)
            class Rec { double cost; int prevKey; }

            @SuppressWarnings("unchecked")
            Map<Integer, Rec>[] dp = new Map[n];
            for (int i = 0; i < n; i++) dp[i] = new HashMap<>(2048);

            // init: try around terrain at start
            for (int y = terr[0] - PLAN_Y_RADIUS; y <= terr[0] + PLAN_Y_RADIUS; y++) {
                int k = packKey(y, 0);
                Rec r = new Rec();
                r.cost = Math.abs(y - terr[0]) * COST_TERRAIN;
                r.prevKey = Integer.MIN_VALUE;
                dp[0].put(k, r);
            }

            // transitions
            for (int i = 1; i < n; i++) {

                for (int y = terr[i] - PLAN_Y_RADIUS; y <= terr[i] + PLAN_Y_RADIUS; y++) {

                    double bestCostForYDy;
                    int bestPrevKeyForYDy;
                    int bestDyForYDy;

                    // We’ll choose the best predecessor for each (y,dy) state
                    for (int dyState = -1; dyState <= 1; dyState++) {

                        bestCostForYDy = Double.POSITIVE_INFINITY;
                        bestPrevKeyForYDy = -1;
                        bestDyForYDy = dyState;

                        for (var e : dp[i - 1].entrySet()) {
                            int pk = e.getKey();
                            int prevY = unpackY(pk);
                            int prevLastDy = unpackDy(pk);
                            Rec prev = e.getValue();

                            int dy = y - prevY;
                            if (dy < -1 || dy > 1) continue;

                            // this state is specifically for dyState; skip others
                            if (dy != dyState) continue;

                            // force flat in landing windows
                            if (forceFlat[i] && dy != 0) continue;

                            double c = prev.cost;

                            // stay near terrain
                            c += Math.abs(y - terr[i]) * COST_TERRAIN;

                            // reduce stair spam
                            if (dy != 0) c += COST_STEP;

                            // reduce jitter in slope
                            if (dy != prevLastDy) c += COST_DY_CHANGE;

                            // strongly discourage up-then-down or down-then-up
                            if (prevLastDy != 0 && dy != 0 && Integer.signum(prevLastDy) != Integer.signum(dy)) {
                                c += COST_SIGN_FLIP;
                            }

                            if (c < bestCostForYDy) {
                                bestCostForYDy = c;
                                bestPrevKeyForYDy = pk;
                            }
                        }

                        if (bestPrevKeyForYDy != -1) {
                            Rec r = new Rec();
                            r.cost = bestCostForYDy;
                            r.prevKey = bestPrevKeyForYDy;

                            // ✅ CRITICAL: store the dy we actually used, not the predecessor’s dy
                            dp[i].put(packKey(y, bestDyForYDy), r);
                        }
                    }
                }

                if (dp[i].isEmpty()) {
                    return hardFallbackProfile(xs, zs, terr);
                }
            }

            // choose best end
            int bestEndKey = 0;
            double bestEndCost = Double.POSITIVE_INFINITY;
            for (var e : dp[n - 1].entrySet()) {
                if (e.getValue().cost < bestEndCost) {
                    bestEndCost = e.getValue().cost;
                    bestEndKey = e.getKey();
                }
            }

            // backtrack ys
            int[] ys = new int[n];
            int curKey = bestEndKey;
            for (int i = n - 1; i >= 0; i--) {
                ys[i] = unpackY(curKey);
                Rec r = dp[i].get(curKey);
                if (r == null || r.prevKey == Integer.MIN_VALUE) break;
                curKey = r.prevKey;
            }

            // final safety pass: enforce |dy|<=1 no matter what
            for (int i = 1; i < n; i++) {
                int dy = ys[i] - ys[i - 1];
                if (dy > 1) ys[i] = ys[i - 1] + 1;
                else if (dy < -1) ys[i] = ys[i - 1] - 1;
            }

            ArrayList<BlockPos> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(new BlockPos(xs[i], ys[i], zs[i]));
            return out;
        }

        private List<BlockPos> hardFallbackProfile(int[] xs, int[] zs, int[] terr) {
            int n = xs.length;
            int[] ys = new int[n];
            ys[0] = terr[0];
            for (int i = 1; i < n; i++) {
                int target = terr[i];
                int prev = ys[i - 1];
                int dy = target - prev;
                if (dy > 1) target = prev + 1;
                else if (dy < -1) target = prev - 1;
                ys[i] = target;
            }
            ArrayList<BlockPos> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(new BlockPos(xs[i], ys[i], zs[i]));
            return out;
        }

        private List<BlockPos> followTerrainProfile(List<BlockPos> xzPath) {
            final int n = xzPath.size();
            if (n <= 1) return xzPath;

            int[] xs = new int[n];
            int[] zs = new int[n];
            int[] terr = new int[n];

            for (int i = 0; i < n; i++) {
                BlockPos p = xzPath.get(i);
                xs[i] = p.getX();
                zs[i] = p.getZ();
                terr[i] = surfaceY(xs[i], zs[i]);
            }

            int[] ys = new int[n];
            ys[0] = terr[0];

            for (int i = 1; i < n; i++) {
                int target = terr[i];
                int prev = ys[i - 1];

                int dy = target - prev;
                if (dy > 1) target = prev + 1;
                else if (dy < -1) target = prev - 1;

                ys[i] = target;
            }

            ArrayList<BlockPos> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(new BlockPos(xs[i], ys[i], zs[i]));
            return out;
        }


        // pack (y,dy) into key. dy in {-1,0,1}
        private static int packKey(int y, int dy) {
            int ddy = dy + 1; // 0..2
            return (y << 2) | (ddy & 3);
        }
        private static int unpackY(int k) { return k >> 2; }
        private static int unpackDy(int k) { return (k & 3) - 1; }

        // ----------------------------
        // Segment building
        // ----------------------------
        private int placeSegment(BlockPos prev, BlockPos cur, BlockPos next) {
            final int baseY = cur.getY();
            int ops = 0;

            Direction prevDir = (prev != null) ? dirTo(prev, cur) : null;
            Direction nextDir = (next != null) ? dirTo(cur, next) : null;

            boolean turned = (prevDir != null && nextDir != null && prevDir != nextDir);

            boolean gradeHere = false;
            if (prev != null && Math.abs(cur.getY() - prev.getY()) == 1) gradeHere = true;
            if (next != null && Math.abs(next.getY() - cur.getY()) == 1) gradeHere = true;

            Direction fwd = nextDir;
            if (fwd == null && prevDir != null) fwd = prevDir;
            if (fwd == null) fwd = Direction.EAST;

            int[] perp = perpXZ(fwd);
            int px = perp[0], pz = perp[1];

            // Bridge classification
            boolean waterBridge = isWaterAtOrBelow(cur.getX(), baseY, cur.getZ());
            boolean gapBridge   = isDeepGapBelow(cur.getX(), baseY - 1, cur.getZ(), GAP_BRIDGE_DEPTH);
            boolean isBridge    = waterBridge || gapBridge;

            // Landing pad at turning-on-grade (helps a lot)
            if (!isBridge && turned && gradeHere) {
                ops += placeLandingPad(cur, baseY);
            }

            BlockState surface = isBridge
                    ? Blocks.SPRUCE_PLANKS.defaultBlockState()
                    : Blocks.DIRT_PATH.defaultBlockState();

            // ---------------------------
            // STAIRS (final)
            // Always place on CUR cell; face toward the UPHILL direction.
            // ---------------------------
            if (!isBridge && next != null) {
                int dy = next.getY() - cur.getY();
                if (Math.abs(dy) == 1) {
                    Direction travel = dirTo(cur, next);
                    if (travel != null) {

                        // ✅ stairs go on the LOWER cell
                        BlockPos stairPos = (dy > 0) ? cur : next;
                        int stairY = stairPos.getY();

                        // ✅ stairs face UPHILL
                        Direction facing = (dy > 0) ? travel : travel.getOpposite();

                        int[] stairPerp = perpXZ(travel);
                        int spx = stairPerp[0], spz = stairPerp[1];

                        BlockState stair = Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                                .setValue(StairBlock.FACING, facing)
                                .setValue(StairBlock.HALF, Half.BOTTOM)
                                .setValue(StairBlock.SHAPE, StairsShape.STRAIGHT);

                        for (int w = -HALF_WIDTH; w <= HALF_WIDTH; w++) {
                            int sx = stairPos.getX() + spx * w;
                            int sz = stairPos.getZ() + spz * w;

                            if (isFootprintBlocked(sx, sz)) continue;

                            clearRoadVolume(sx, stairY, sz);
                            ensureSupportOrBridgePillar(sx, stairY - 1, sz, false);

                            set(sx, stairY, sz, stair);
                            ops++;
                        }
                    }
                }
            }



            // ---------------------------
            // SURFACE 3-wide at baseY
            // ---------------------------
           for (int w = -HALF_WIDTH; w <= HALF_WIDTH; w++) {
                int x = cur.getX() + px * w;
                int z = cur.getZ() + pz * w;

                if (isFootprintBlocked(x, z)) continue;

                // ✅ stronger: never overwrite stairs at y, y-1, or y+1
                if (anyStairNearY(x, baseY, z)) continue;

                clearRoadVolume(x, baseY, z);
                ensureSupportOrBridgePillar(x, baseY - 1, z, isBridge);

                set(x, baseY, z, surface);
                ops++;

                if (!isBridge) {
                    ops += gradeSideNoShoulder(x, baseY, z, px, pz, w);
                }
            }


            // BRIDGE FENCES
            if (isBridge) {
                BlockState fence = Blocks.SPRUCE_FENCE.defaultBlockState();
                for (int side : new int[]{-FENCE_OFFSET, FENCE_OFFSET}) {
                    int fx = cur.getX() + px * side;
                    int fz = cur.getZ() + pz * side;
                    int fy = baseY + 1;

                    if (isAir(fx, fy, fz)) {
                        set(fx, fy, fz, fence);
                        ops++;
                    }
                }
            }

            return ops;
        }

        private int placeLandingPad(BlockPos cur, int baseY) {
            int ops = 0;

            for (int dx = -LANDING_HALF; dx <= LANDING_HALF; dx++) {
                for (int dz = -LANDING_HALF; dz <= LANDING_HALF; dz++) {
                    int x = cur.getX() + dx;
                    int z = cur.getZ() + dz;

                    if (isFootprintBlocked(x, z)) continue;

                    // ✅ stronger: don't overwrite stairs near this Y
                    if (anyStairNearY(x, baseY, z)) continue;

                    clearRoadVolume(x, baseY, z);
                    ensureSupportOrBridgePillar(x, baseY - 1, z, false);

                    set(x, baseY, z, Blocks.DIRT_PATH.defaultBlockState());
                    ops++;
                }
            }

            return ops;
        }


        // ----------------------------
        // Side grading
        // ----------------------------
        private int gradeSideNoShoulder(int roadX, int baseY, int roadZ, int px, int pz, int w) {
            if (w != -HALF_WIDTH && w != HALF_WIDTH) return 0;

            int ops = 0;
            int dir = (w < 0) ? -1 : 1;

            for (int s = 1; s <= GRADE_SIDE; s++) {
                int gx = roadX + px * dir * s;
                int gz = roadZ + pz * dir * s;

                if (isFootprintBlocked(gx, gz)) continue;

                int surf = surfaceY(gx, gz);

                // CUT
                if (surf > baseY) {
                    int cut = Math.min(GRADE_CUT_MAX, surf - baseY);
                    for (int yy = baseY + 1; yy <= baseY + cut; yy++) {
                        clearSoft(gx, yy, gz);
                        ops++;
                    }
                    for (int yy = baseY + 1; yy <= baseY + CLEAR_HEIGHT; yy++) {
                        clearSoft(gx, yy, gz);
                    }

                    if (isAir(gx, baseY, gz)) {
                        set(gx, baseY, gz, Blocks.DIRT.defaultBlockState());
                        ops++;
                    }
                }

                // FILL up to baseY-1 (no shoulder at baseY)
                if (surf < baseY - 1) {
                    int need = (baseY - 1) - surf;
                    int fill = Math.min(GRADE_FILL_MAX, need);

                    for (int i = 0; i < fill; i++) {
                        int yy = (baseY - 1) - i;
                        if (yy < level.getMinY()) break;

                        scratch.set(gx, yy, gz);
                        BlockState bs = level.getBlockState(scratch);

                        if (bs.isAir() || !bs.getFluidState().isEmpty()) {
                            level.setBlock(scratch, Blocks.DIRT.defaultBlockState(), 2);
                            ops++;
                        } else {
                            break;
                        }
                    }
                }

                // keep headroom
                for (int yy = baseY + 1; yy <= baseY + CLEAR_HEIGHT; yy++) {
                    clearSoft(gx, yy, gz);
                }
            }

            return ops;
        }

        // ----------------------------
        // Geometry helpers
        // ----------------------------
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
            if (dx == 0 && dz == 0) return null;

            if (Math.abs(dx) >= Math.abs(dz)) {
                return (dx > 0) ? Direction.EAST : Direction.WEST;
            } else {
                return (dz > 0) ? Direction.SOUTH : Direction.NORTH;
            }
        }

        // ----------------------------
        // World helpers
        // ----------------------------
        private boolean isAir(int x, int y, int z) {
            if (y < level.getMinY() || y >= level.getMaxY()) return false;
            scratch.set(x, y, z);
            return level.getBlockState(scratch).isAir();
        }

        private void clearRoadVolume(int x, int y, int z) {
            for (int dy = 0; dy <= CLEAR_HEIGHT; dy++) {
                clearSoft(x, y + dy, z);
            }
        }

        private void clearSoft(int x, int y, int z) {
            if (y < level.getMinY() || y >= level.getMaxY()) return;

            scratch.set(x, y, z);
            BlockState bs = level.getBlockState(scratch);

            if (bs.isAir()) return;
            if (!bs.getFluidState().isEmpty()) return;

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

        private void ensureSupportOrBridgePillar(int x, int y, int z, boolean isBridge) {
            if (isBridge) {
                ensurePillarToSolid(x, y, z, BRIDGE_PILLAR_MAX);
            } else {
                ensureSupport(x, y, z, SUPPORT_DEPTH);
            }
        }

        private void ensureSupport(int x, int y, int z, int maxDepth) {
            int yy = y;
            for (int d = 0; d < maxDepth; d++) {
                if (yy < level.getMinY()) return;
                scratch.set(x, yy, z);
                BlockState bs = level.getBlockState(scratch);
                if (!bs.isAir() && bs.getFluidState().isEmpty()) return;

                level.setBlock(scratch, Blocks.DIRT.defaultBlockState(), 2);
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

                level.setBlock(scratch, Blocks.COBBLESTONE.defaultBlockState(), 2);
                yy--;
            }
        }

        private boolean isWaterAtOrBelow(int x, int y, int z) {
            scratch.set(x, y, z);
            if (!level.getBlockState(scratch).getFluidState().isEmpty()) return true;
            scratch.set(x, y - 1, z);
            return !level.getBlockState(scratch).getFluidState().isEmpty();
        }

        private void set(int x, int y, int z, BlockState state) {
            if (y < level.getMinY() || y >= level.getMaxY()) return;
            scratch.set(x, y, z);
            level.setBlock(scratch, state, 2);
        }

        private boolean isFootprintBlocked(int x, int z) {
            return BlueprintFootprintState.get(level).isBlocked(regionKey, x, z, FOOTPRINT_MARGIN);
        }
    }
}
