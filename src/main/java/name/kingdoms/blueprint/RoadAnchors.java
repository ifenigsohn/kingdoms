package name.kingdoms.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

/**
 * Detects door/road connection markers using:
 *   SOLID -> BARRIER(B1) -> BARRIER(B2)
 *
 * B1 = where road hooks up
 * B2 = under the 2nd road block (direction hint)
 *
 * We create anchors UNDER B1 (B1.below()) and delete both barriers.
 */
public final class RoadAnchors {
    private RoadAnchors() {}

    public static void cleanupBarrierAnchors(ServerLevel level, long regionKey) {
        List<BlockPos> anchors = RoadAnchorState.get(level).getAnchors(regionKey);
        if (anchors.isEmpty()) return;

        for (BlockPos p : anchors) {
            if (level.getBlockState(p).is(Blocks.BARRIER)) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }


    /** Find all barrier-pair anchors near origin, add anchors under B1, and delete markers. */
    public static List<BlockPos> consumeBarrierAnchors(ServerLevel level, BlockPos origin) {
        // Tune: how far from blueprint origin to scan for markers
        final int R = 96;

        // Limit Y scan (keeps this cheap even for big worlds)
        final int yMin = Math.max(level.getMinY() + 1, origin.getY() - 48);
        final int yMax = Math.min(level.getMaxY() - 2, origin.getY() + 64);

        BlockPos min = new BlockPos(origin.getX() - R, yMin, origin.getZ() - R);
        BlockPos max = new BlockPos(origin.getX() + R, yMax, origin.getZ() + R);

        // Collect all barrier blocks in scan box
        ArrayList<BlockPos> barriers = new ArrayList<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    p.set(x, y, z);
                    if (level.getBlockState(p).is(Blocks.BARRIER)) {
                        barriers.add(p.immutable());
                    }
                }
            }
        }

        if (barriers.isEmpty()) return Collections.emptyList();

        // Pairing + dedup
        HashSet<Long> usedPairs = new HashSet<>();
        HashSet<Long> usedB1 = new HashSet<>();
        ArrayList<Pair> pairs = new ArrayList<>();

        // To speed adjacency lookups
        HashSet<Long> barrierSet = new HashSet<>(barriers.size() * 2);
        for (BlockPos b : barriers) barrierSet.add(b.asLong());

        for (BlockPos a : barriers) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos b = a.relative(dir);
                if (!barrierSet.contains(b.asLong())) continue;

                long key = canonicalPairKey(a, b);
                if (!usedPairs.add(key)) continue;

                // Decide ordering: we want B1 -> B2 outward
                // Heuristic: behind B1 (opposite facing) should be solid-ish,
                // behind B2 should be NOT solid-ish (usually outside/air).
                Direction facingAtoB = dir;
                boolean aLooksLikeB1 = isSolidish(level, a.relative(facingAtoB.getOpposite()));
                boolean bLooksLikeB1 = isSolidish(level, b.relative(facingAtoB.getOpposite())); // behind B2 relative to same facing

                BlockPos b1, b2;
                Direction facing;

                if (aLooksLikeB1 && !bLooksLikeB1) {
                    b1 = a;
                    b2 = b;
                    facing = facingAtoB;
                } else if (!aLooksLikeB1 && bLooksLikeB1) {
                    // flip
                    b1 = b;
                    b2 = a;
                    facing = facingAtoB.getOpposite();
                } else {
                    // Ambiguous; still accept but pick a as B1 to avoid missing anchors.
                    b1 = a;
                    b2 = b;
                    facing = facingAtoB;
                }

                // One B1 should only contribute one anchor
                if (!usedB1.add(b1.asLong())) continue;

                pairs.add(new Pair(b1, b2, facing));
            }
        }

        if (pairs.isEmpty()) return Collections.emptyList();

        // Build anchors under B1 and delete markers
        ArrayList<BlockPos> anchors = new ArrayList<>(pairs.size());
        for (Pair pair : pairs) {
            BlockPos roadStart = pair.b1.below(); // <--- your requirement

            // Clamp Y to world bounds just in case
            int y = Math.max(level.getMinY() + 1, Math.min(level.getMaxY() - 2, roadStart.getY()));
            roadStart = new BlockPos(roadStart.getX(), y, roadStart.getZ());

            anchors.add(roadStart);

            // delete barriers
            level.setBlock(pair.b1, Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(pair.b2, Blocks.AIR.defaultBlockState(), 2);
        }

        return anchors;
    }

    // -------- helpers --------

    private record Pair(BlockPos b1, BlockPos b2, Direction facing) {}

    private static long canonicalPairKey(BlockPos a, BlockPos b) {
        long la = a.asLong();
        long lb = b.asLong();
        long lo = Math.min(la, lb);
        long hi = Math.max(la, lb);
        return lo * 31L + hi;
    }

    private static boolean isSolidish(ServerLevel level, BlockPos pos) {
        if (pos.getY() < level.getMinY() || pos.getY() >= level.getMaxY()) return false;
        BlockState s = level.getBlockState(pos);
        if (s.isAir()) return false;
        if (!s.getFluidState().isEmpty()) return false;
        if (s.is(Blocks.BARRIER)) return false;
        // treat foliage as NOT solid-ish so forests don't confuse "inside" detection
        if (s.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) return false;
        return true;
    }

    /** Fallback: old behavior if no markers found. */
    public static BlockPos fallbackFromBlueprintOrigin(ServerLevel level, BlockPos origin) {
        int x = origin.getX() + 1;
        int z = origin.getZ() + 1;

        int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int y = Math.max(level.getMinY() + 1, h - 1);

        return new BlockPos(x, y, z);
    }
}
