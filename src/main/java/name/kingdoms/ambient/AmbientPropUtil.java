package name.kingdoms.ambient;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

import java.util.HashMap;
import java.util.Map;

public final class AmbientPropUtil {
    private AmbientPropUtil() {}

    /**
     * Builds a temporary grading patch that:
     *  - flattens the footprint to a chosen flatY (median of sampled surface)
     *  - enforces air clearance by carving to air from flatY+1..flatY+airHeight
     *
     * Rejects placement if:
     *  - total surface variation across footprint > maxVariation
     *  - any surface column is fluid/air
     *  - any clearance block is fluid
     *  - any clearance block has a block entity (chests, furnaces, etc.) (safety)
     */
    public static Map<BlockPos, BlockState> buildTerrainGradePatch(ServerLevel level,
                                                                   BlockPos centerGround,
                                                                   int halfX, int halfZ,
                                                                   int maxVariation,
                                                                   int airHeight) {

        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        int sizeX = halfX * 2 + 1;
        int sizeZ = halfZ * 2 + 1;

        int[][] surfaceY = new int[sizeX][sizeZ];
        BlockState[][] surfaceState = new BlockState[sizeX][sizeZ];

        // ---- Pass 1: sample surface + measure variation + basic rejects
        for (int dx = -halfX; dx <= halfX; dx++) {
            for (int dz = -halfZ; dz <= halfZ; dz++) {
                int x = centerGround.getX() + dx;
                int z = centerGround.getZ() + dz;

                // Heightmap gives first non-air ABOVE surface; subtract 1 for surface block
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;

                surfaceY[dx + halfX][dz + halfZ] = y;

                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);

                BlockPos surfPos = new BlockPos(x, y, z);
                BlockState surf = level.getBlockState(surfPos);
                surfaceState[dx + halfX][dz + halfZ] = surf;

                if (surf.isAir()) return null;
                if (surf.getFluidState() != Fluids.EMPTY.defaultFluidState()) return null;
            }
        }

        if (maxY - minY > maxVariation) return null;

        // Choose a stable flatten plane: median of sampled surface Ys
        java.util.ArrayList<Integer> ys = new java.util.ArrayList<>(sizeX * sizeZ);
        for (int ix = 0; ix < sizeX; ix++) {
            for (int iz = 0; iz < sizeZ; iz++) {
                ys.add(surfaceY[ix][iz]);
            }
        }
        java.util.Collections.sort(ys);
        int flatY = ys.get(ys.size() / 2);

        Map<BlockPos, BlockState> edits = new HashMap<>();

        // ---- Pass 2: flatten terrain + carve air clearance
        for (int dx = -halfX; dx <= halfX; dx++) {
            for (int dz = -halfZ; dz <= halfZ; dz++) {
                int x = centerGround.getX() + dx;
                int z = centerGround.getZ() + dz;

                int ySurf = surfaceY[dx + halfX][dz + halfZ];
                BlockState surfState = surfaceState[dx + halfX][dz + halfZ];

                // Preserve top look where possible (grass stays grass), fill becomes dirt when grassy
                boolean grassy = surfState.is(Blocks.GRASS_BLOCK) || surfState.is(Blocks.MYCELIUM);
                BlockState topState = surfState;
                BlockState fillState = grassy ? Blocks.DIRT.defaultBlockState() : surfState;

                if (ySurf > flatY) {
                    // cut down: clear flatY+1..ySurf to air
                    for (int y = flatY + 1; y <= ySurf; y++) {
                        edits.put(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                    edits.put(new BlockPos(x, flatY, z), topState);

                } else if (ySurf < flatY) {
                    // fill up: fill ySurf+1..flatY-1, then set top at flatY
                    for (int y = ySurf + 1; y <= flatY - 1; y++) {
                        edits.put(new BlockPos(x, y, z), fillState);
                    }
                    edits.put(new BlockPos(x, flatY, z), topState);
                }

                // Air clearance: flatY+1..flatY+airHeight becomes air
                for (int ay = 1; ay <= airHeight; ay++) {
                    BlockPos p = new BlockPos(x, flatY + ay, z);
                    BlockState bs = level.getBlockState(p);

                    if (bs.getFluidState() != Fluids.EMPTY.defaultFluidState()) {
                        return null; // don't carve fluids
                    }
                    if (!bs.isAir()) {
                        if (bs.hasBlockEntity()) return null; // don't temporarily delete inventories
                        edits.put(p, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }

        return edits;
    }
}
