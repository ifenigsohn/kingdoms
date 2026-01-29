package name.kingdoms.entity.ambient;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public final class RoadUtil {
    private RoadUtil() {}

    /** Spawn ONLY on these blocks (under feet). Keep this strict. */
    public static boolean isSpawnRoadBlock(BlockState bs) {
        return bs.is(Blocks.DIRT_PATH);
    }

    /** Walkable “road surface” blocks. This can be broader. */
    public static boolean isPathableRoadBlock(BlockState bs) {
        if (isSpawnRoadBlock(bs)) return true;

        // --- Add your “connectors” here ---
        if (bs.is(Blocks.COBBLESTONE_STAIRS)) return true;
        if (bs.is(Blocks.COBBLESTONE_SLAB)) return true;

        if (bs.is(Blocks.SPRUCE_PLANKS)) return true;
        if (bs.is(Blocks.SPRUCE_STAIRS)) return true;
        if (bs.is(Blocks.SPRUCE_SLAB)) return true;

        // Optional: common “road-ish” fillers
        // if (bs.is(Blocks.GRAVEL)) return true;
        // if (bs.is(Blocks.COBBLESTONE)) return true;

        return false;
    }

    /** Feet position is "on spawn-road" if block under feet is spawn-road. */
    public static boolean isStandingOnSpawnRoad(Level level, BlockPos feetPos) {
        return isSpawnRoadBlock(level.getBlockState(feetPos.below()));
    }

    /** Feet position is "on pathable road" if block under feet is pathable-road. */
    public static boolean isStandingOnPathableRoad(Level level, BlockPos feetPos) {
        return isPathableRoadBlock(level.getBlockState(feetPos.below()));
    }

    /** Find nearest SPAWN-ROAD feet position (strict). */
    public static BlockPos findNearestSpawnRoad(Level level, BlockPos origin, int r) {
        return findNearest(level, origin, r, true);
    }

    /** Find nearest PATHABLE-ROAD feet position (broad). */
    public static BlockPos findNearestPathableRoad(Level level, BlockPos origin, int r) {
        return findNearest(level, origin, r, false);
    }

    private static BlockPos findNearest(Level level, BlockPos origin, int r, boolean spawnOnly) {
        BlockPos best = null;
        int bestD2 = Integer.MAX_VALUE;

        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                BlockPos p = new BlockPos(ox + dx, oy, oz + dz);

                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos q = p.offset(0, dy, 0);

                    boolean ok = spawnOnly
                            ? isStandingOnSpawnRoad(level, q)
                            : isStandingOnPathableRoad(level, q);

                    if (!ok) continue;

                    int d2 = dx * dx + dz * dz + dy * dy;
                    if (d2 < bestD2) {
                        bestD2 = d2;
                        best = q;
                    }
                }
            }
        }
        return best;
    }

    /** Direction choices should consider PATHABLE continuity. */
    public static Direction pickPathableDirection(Level level, BlockPos roadFeet, RandomSource r, Direction preferred) {
        List<Direction> dirs = new ArrayList<>(4);
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            if (hasPathableNeighbor(level, roadFeet, d)) dirs.add(d);
        }
        if (dirs.isEmpty()) return null;

        // Prefer continuing forward if possible
        if (preferred != null) {
            for (Direction d : dirs) {
                if (d == preferred) return preferred;
            }
        }

        return dirs.get(r.nextInt(dirs.size()));
    }

    public static boolean hasPathableNeighbor(Level level, BlockPos roadFeet, Direction d) {
        BlockPos next = roadFeet.relative(d);
        for (int dy = -1; dy <= 1; dy++) {
            BlockPos cand = next.offset(0, dy, 0);
            if (isStandingOnPathableRoad(level, cand)) return true;
        }
        return false;
    }

    /** Walk forward along PATHABLE road surface. */
    public static BlockPos walkPathable(Level level, BlockPos startFeet, Direction dir, int steps) {
        BlockPos cur = startFeet;

        for (int i = 0; i < steps; i++) {
            BlockPos next = cur.relative(dir);

            BlockPos best = null;
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos cand = next.offset(0, dy, 0);
                if (isStandingOnPathableRoad(level, cand)) { best = cand; break; }
            }

            if (best == null) return cur; // stop at last valid
            cur = best;
        }

        return cur;
    }

    public static boolean hasSpawnRoadPatch(Level level, BlockPos feet, int w, int l) {
        // We check tiles UNDER feet (same logic as isStandingOnSpawnRoad)
        // w x l axis-aligned rectangles (X/Z). Accept either orientation: w-by-l or l-by-w.

        return hasSpawnRoadPatchAxis(level, feet, w, l) || hasSpawnRoadPatchAxis(level, feet, l, w);
    }

    private static boolean hasSpawnRoadPatchAxis(Level level, BlockPos feet, int wX, int lZ) {
        // feet is the candidate entity feet position.
        // We require a rectangle of spawn-road tiles on the surface under these feet positions.

        int fx = feet.getX();
        int fy = feet.getY();
        int fz = feet.getZ();

        // We want the patch to be able to include the feet tile anywhere inside the rectangle.
        // Try all rectangle placements where the rectangle covers (fx,fz).
        for (int offX = -(wX - 1); offX <= 0; offX++) {
            for (int offZ = -(lZ - 1); offZ <= 0; offZ++) {

                boolean ok = true;

                for (int dx = 0; dx < wX && ok; dx++) {
                    for (int dz = 0; dz < lZ; dz++) {
                        int x = fx + offX + dx;
                        int z = fz + offZ + dz;

                        BlockPos tileFeet = new BlockPos(x, fy, z);

                        // Require spawn-road under feet at same Y plane
                        if (!isStandingOnSpawnRoad(level, tileFeet)) {
                            ok = false;
                            break;
                        }
                    }
                }

                if (ok) return true;
            }
        }

        return false;
    }

}
