package name.kingdoms.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

public final class RoadAStar {
    private RoadAStar() {}

    private static final int MAX_ITERS = 250_000;

    // Hard constraint: minimum straight steps before a turn
    private static final int MIN_STRAIGHT_BEFORE_TURN = 3;

    // A* assumes builder will clear a corridor above the road
    private static final int CLEAR_HEIGHT = 4;

    // Penalty for tunneling through solid blocks in the corridor
    private static final double TUNNEL_BLOCK_PENALTY = 6.0;

    // Penalty for placing the road “inside” a solid block at the deck position
    private static final double DECK_SOLID_PENALTY = 18.0;


    // How far outside a footprint we treat as blocked (keeps roads off walls)
    public static final int FOOTPRINT_MARGIN = 2;

    // Limit search region so A* can't wander forever looking for a gentle slope.
    private static final int SEARCH_PADDING = 96; // tune (64..192)

    // Penalize turns so road doesn't "stair-step" in XZ.
    private static final double TURN_PENALTY = 8.0;

    // Strongly prefer flat over climbing/descending
    private static final double GRADE_PENALTY = 12.0;
    private static final double UPHILL_EXTRA = 2.0;

    // Smoothness penalties (reduce "jittery" elevation)
    private static final double DY_CHANGE_PENALTY = 25.0;     // dy differs from lastDy
    private static final double SIGN_FLIP_PENALTY  = 25.0;    // lastDy and dy opposite signs
    private static final double STEP_RUN_PENALTY   = 1.25;   // extended runs of dy!=0
    private static final double AXIS_ALTERNATION_PENALTY = 40.0; // tune


    

    private enum Dir {
        NONE, EAST, WEST, SOUTH, NORTH;

        static Dir fromDelta(int dx, int dz) {
            if (dx > 0) return EAST;
            if (dx < 0) return WEST;
            if (dz > 0) return SOUTH;
            if (dz < 0) return NORTH;
            return NONE;
        }
    }


    public static List<BlockPos> findPath(ServerLevel level, long regionKey, BlockPos start, BlockPos goal) {
        NodeKey startK = new NodeKey(start.getX(), start.getZ(), Dir.NONE, MIN_STRAIGHT_BEFORE_TURN);
        int goalX = goal.getX();
        int goalZ = goal.getZ();


        int minX = Math.min(start.getX(), goal.getX()) - SEARCH_PADDING;
        int maxX = Math.max(start.getX(), goal.getX()) + SEARCH_PADDING;
        int minZ = Math.min(start.getZ(), goal.getZ()) - SEARCH_PADDING;
        int maxZ = Math.max(start.getZ(), goal.getZ()) + SEARCH_PADDING;

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        HashMap<NodeKey, NodeRec> best = new HashMap<>(8192);

        // start lastDy=0, stepRun=0
        int startY = surfaceY(level, start.getX(), start.getZ());
        NodeRec sRec = new NodeRec(startK, startY, null, 0.0, 0, 0);

        sRec.f = heuristic(start.getX(), start.getZ(), goal.getX(), goal.getZ());
        best.put(startK, sRec);
        open.add(new Node(startK, sRec.f));

        int iters = 0;

        while (!open.isEmpty() && iters++ < MAX_ITERS) {
            Node curN = open.poll();
            NodeRec cur = best.get(curN.k);
            if (cur == null) continue;

            // stale queue entries check
            if (curN.f > cur.f + 1e-9) continue;

            if (cur.k.x == goalX && cur.k.z == goalZ) {
                return reconstruct(cur);
            }


            int cx = cur.k.x;
            int cz = cur.k.z;

            step(level, regionKey, cur, cx + 1, cz, goalX, goalZ, best, open, minX, maxX, minZ, maxZ);
            step(level, regionKey, cur, cx - 1, cz, goalX, goalZ, best, open, minX, maxX, minZ, maxZ);
            step(level, regionKey, cur, cx, cz + 1, goalX, goalZ, best, open, minX, maxX, minZ, maxZ);
            step(level, regionKey, cur, cx, cz - 1, goalX, goalZ, best, open, minX, maxX, minZ, maxZ);

        }

        return fallbackLine(level, regionKey, start, goal);
    }
    

    private static void step(ServerLevel level,
                         long regionKey,
                         NodeRec cur,
                         int nx, int nz,
                         int goalX, int goalZ,
                         HashMap<NodeKey, NodeRec> best,
                         PriorityQueue<Node> open,
                         int minX, int maxX, int minZ, int maxZ) {


        if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) return;
        
        try {
            level.getChunk(nx >> 4, nz >> 4);
        } catch (Exception ex) {
            return;
        }

        boolean isGoal = (nx == goalX && nz == goalZ);
        if (!isGoal && isFootprintBlocked(level, regionKey, nx, nz, FOOTPRINT_MARGIN)) return;

        // Determine move direction
        int mdx = nx - cur.k.x;
        int mdz = nz - cur.k.z;
        Dir ndir = Dir.fromDelta(mdx, mdz);

        // Turn spacing hard constraint
        Dir cdir = cur.k.dir;
        int since = cur.k.sinceTurn;

        boolean isTurn = (cdir != Dir.NONE && ndir != cdir);
        if (isTurn && since < MIN_STRAIGHT_BEFORE_TURN) {
            return; // hard reject
        }
        int nextSince = isTurn ? 0 : (since + 1);


        // Desired surface, but we only step by +/-1 max (tunneling allowed)
        int surf = surfaceY(level, nx, nz);

        int curY = cur.y;
        int ny = surf;
        if (ny > curY + 1) ny = curY + 1;
        else if (ny < curY - 1) ny = curY - 1;

        int dy = ny - curY; // guaranteed in [-1, +1]

        // base move cost + grade penalty
        double g2 = cur.g + 1.0 + Math.abs(dy) * GRADE_PENALTY;
        if (dy > 0) g2 += UPHILL_EXTRA;

        // Extra anti-diagonal: penalize alternating axis steps (E/N/E/N...)
        if (cur.prev != null) {
            int pdx = cur.k.x - cur.prev.k.x;
            int pdz = cur.k.z - cur.prev.k.z;

            boolean prevAxisX = (pdx != 0);
            boolean nextAxisX = (mdx != 0);

            if (prevAxisX != nextAxisX) {
                g2 += AXIS_ALTERNATION_PENALTY;
            }
        }


        // mild water penalty (still allowed)
        if (isWaterAtOrBelow(level, nx, ny, nz)) g2 += 2.0;

        // turn penalty (still useful, but the spacing rule is the real guard)
        if (isTurn) g2 += TURN_PENALTY;

        // ---- smoothness penalties ----
        int newStepRun = (dy != 0) ? (cur.stepRun + 1) : 0;
        if (dy != cur.lastDy) g2 += DY_CHANGE_PENALTY;
        if (cur.lastDy != 0 && dy != 0 && Integer.signum(cur.lastDy) != Integer.signum(dy)) g2 += SIGN_FLIP_PENALTY;
        if (newStepRun > 2) g2 += (newStepRun - 2) * STEP_RUN_PENALTY;

        // ---- tunneling penalty: count solids in corridor ABOVE the road deck ----
        g2 += tunnelPenalty(level, nx, ny, nz);

        // Create next key/state
        NodeKey nk = new NodeKey(nx, nz, ndir, nextSince);

        NodeRec prevBest = best.get(nk);
        if (prevBest == null || g2 < prevBest.g) {
            NodeRec nr = new NodeRec(nk, ny, cur, g2, dy, newStepRun);
            nr.f = g2 + heuristic(nx, nz, goalX, goalZ);
            best.put(nk, nr);
            open.add(new Node(nk, nr.f));
        }

    }

    private static int surfaceY(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        y = Math.max(level.getMinY() + 1, y);

        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, y, z);

        boolean steppedDownThroughRoad = false;

        // Walk DOWN if we landed on our own road materials (prevents A* feedback),
        // but ONLY add +1 if we actually stepped down.
        while (p.getY() > level.getMinY() + 1) {
            BlockState bs = level.getBlockState(p);

            boolean isRoad =
                    bs.is(Blocks.DIRT_PATH) ||
                    bs.is(Blocks.COBBLESTONE) ||
                    bs.is(Blocks.SPRUCE_PLANKS) ||
                    (bs.getBlock() instanceof StairBlock);

            if (!isRoad) break;

            steppedDownThroughRoad = true;
            p.move(0, -1, 0);
        }

        int out = steppedDownThroughRoad ? (p.getY() + 1) : p.getY();
        return Math.min(level.getMaxY() - 1, out);
    }


    private static boolean isWaterAtOrBelow(ServerLevel level, int x, int y, int z) {
        var p = new BlockPos.MutableBlockPos(x, y, z);
        if (!level.getBlockState(p).getFluidState().isEmpty()) return true;
        p.setY(y - 1);
        return !level.getBlockState(p).getFluidState().isEmpty();
    }

    private static double heuristic(int x, int z, int gx, int gz) {
       return 1.0 * (Math.abs(gx - x) + Math.abs(gz - z));
    }

    private static List<BlockPos> reconstruct(NodeRec end) {
        ArrayList<BlockPos> out = new ArrayList<>();
        NodeRec cur = end;
        while (cur != null) {
            out.add(new BlockPos(cur.k.x, cur.y, cur.k.z));
            cur = cur.prev;
        }
        Collections.reverse(out);
        return out;
    }

    private static double tunnelPenalty(ServerLevel level, int x, int roadY, int z) {
        double p = 0.0;

        // deck position itself
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, roadY, z);
        BlockState deck = level.getBlockState(m);
        if (!deck.isAir() && deck.getFluidState().isEmpty()) {
            p += DECK_SOLID_PENALTY;
        }

        // corridor above deck
        for (int i = 1; i <= CLEAR_HEIGHT; i++) {
            m.set(x, roadY + i, z);
            BlockState bs = level.getBlockState(m);
            if (!bs.isAir() && bs.getFluidState().isEmpty()) {
                p += TUNNEL_BLOCK_PENALTY;
            }
        }
        return p;
    }


    private static List<BlockPos> fallbackLine(ServerLevel level, long regionKey, BlockPos a, BlockPos b) {
        ArrayList<BlockPos> out = new ArrayList<>();
        int x = a.getX(), z = a.getZ();
        int tx = b.getX(), tz = b.getZ();
        int y = a.getY();

        out.add(new BlockPos(x, y, z));

        int guard = 0;
        while ((x != tx || z != tz) && guard++ < 20000) {
            int dx = Integer.compare(tx, x);
            int dz = Integer.compare(tz, z);

            int candX = x, candZ = z;
            if (Math.abs(tx - x) > Math.abs(tz - z)) candX = x + dx;
            else candZ = z + dz;

            boolean candIsGoal = (candX == tx && candZ == tz);

            if (!candIsGoal && isFootprintBlocked(level, regionKey, candX, candZ, FOOTPRINT_MARGIN)) {
                int altX = x + dx;
                int altZ = z + dz;

                boolean altOkX = (altX != x) && ((altX == tx && z == tz) || !isFootprintBlocked(level, regionKey, altX, z, FOOTPRINT_MARGIN));
                boolean altOkZ = (altZ != z) && ((x == tx && altZ == tz) || !isFootprintBlocked(level, regionKey, x, altZ, FOOTPRINT_MARGIN));

                if (altOkX && !altOkZ) { candX = altX; candZ = z; }
                else if (!altOkX && altOkZ) { candX = x; candZ = altZ; }
                else if (altOkX) { candX = altX; candZ = z; }
                else break;
            }

            x = candX;
            z = candZ;

            if (!level.hasChunk(x >> 4, z >> 4)) break;

            int ny = surfaceY(level, x, z);
            int step = ny - y;
            if (step < -1) ny = y - 1;
            if (step > 1)  ny = y + 1;
            y = ny;

            out.add(new BlockPos(x, y, z));
        }

        return out;
    }

    private static boolean isFootprintBlocked(ServerLevel level, long regionKey, int x, int z, int margin) {
        return BlueprintFootprintState.get(level).isBlocked(regionKey, x, z, margin);
    }

    private record Node(NodeKey k, double f) {}
    private record NodeKey(int x, int z, Dir dir, int sinceTurn) {}


    private static final class NodeRec {
        final NodeKey k;
        final int y;
        final NodeRec prev;
        final double g;
        final int lastDy;
        final int stepRun;
        double f;

        NodeRec(NodeKey k, int y, NodeRec prev, double g, int lastDy, int stepRun) {
            this.k = k;
            this.y = y;
            this.prev = prev;
            this.g = g;
            this.lastDy = lastDy;
            this.stepRun = stepRun;
        }
    }
}
