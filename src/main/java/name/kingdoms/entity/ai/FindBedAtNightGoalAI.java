package name.kingdoms.entity.ai;

import name.kingdoms.entity.aiKingdomEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.EnumSet;

public class FindBedAtNightGoalAI extends Goal {
    private final aiKingdomEntity mob;
    private final double speed;
    private final int searchRange;

    private BlockPos targetBedHead;

    public FindBedAtNightGoalAI(aiKingdomEntity mob, double speed, int searchRange) {
        this.mob = mob;
        this.speed = speed;
        this.searchRange = searchRange;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Level level = mob.level();
        if (level.isClientSide()) return false;
        if (!level.dimensionType().bedWorks()) return false;
        if (isDaytime(level)) return false;
        if (mob.isSleeping()) return false;
        if (!(level instanceof ServerLevel sl)) return false;

        // 1) Prefer assigned bed if still valid and not claimed by someone else
        BlockPos assigned = mob.getAssignedBedPos();
        if (assigned != null) {
            BlockPos head = normalizeToBedHead(level, assigned);
            if (head != null && !mob.isBedClaimedByOther(head, searchRange)) {
                targetBedHead = head;
                return true;
            } else {
                mob.setAssignedBedPos(null);
            }
        }

        // 2) Find a new unclaimed bed nearby
        targetBedHead = findNearbyUnclaimedBedHead(sl, mob.blockPosition(), searchRange, mob);
        if (targetBedHead != null) {
            mob.setAssignedBedPos(targetBedHead);
            return true;
        }

        return false;
    }

    @Override
    public void start() {
        if (targetBedHead == null) return;

        PathNavigation nav = mob.getNavigation();
        nav.moveTo(
                targetBedHead.getX() + 0.5,
                targetBedHead.getY(),
                targetBedHead.getZ() + 0.5,
                speed
        );
    }

    @Override
    public boolean canContinueToUse() {
        Level level = mob.level();
        if (level.isClientSide()) return false;
        if (isDaytime(level)) return false;

        if (mob.isSleeping()) return true;
        if (targetBedHead == null) return false;

        BlockPos head = normalizeToBedHead(level, targetBedHead);
        if (head == null) return false;

        return !mob.getNavigation().isDone();
    }

    @Override
    public void tick() {
        Level level = mob.level();
        if (level.isClientSide()) return;

        if (!mob.isSleeping()
                && targetBedHead != null
                && mob.blockPosition().distSqr(targetBedHead) <= 2.5D) {

            BlockPos head = normalizeToBedHead(level, targetBedHead);
            if (head == null) {
                mob.setAssignedBedPos(null);
                targetBedHead = null;
                return;
            }

            if (mob.isBedClaimedByOther(head, searchRange)) {
                mob.setAssignedBedPos(null);
                targetBedHead = null;
                mob.getNavigation().stop();
                return;
            }

            mob.startSleeping(head);
        }
    }

    @Override
    public void stop() {
        targetBedHead = null;
    }

    private static BlockPos normalizeToBedHead(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof BedBlock)) return null;

        if (state.hasProperty(BedBlock.PART) && state.getValue(BedBlock.PART) == BedPart.FOOT) {
            pos = pos.relative(state.getValue(BedBlock.FACING));
            state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof BedBlock)) return null;
        }
        return pos;
    }

    private static BlockPos findNearbyUnclaimedBedHead(ServerLevel level, BlockPos origin, int r, aiKingdomEntity mob) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    BlockState s = level.getBlockState(p);

                    if (!(s.getBlock() instanceof BedBlock)) continue;

                    BlockPos head = normalizeToBedHead(level, p);
                    if (head == null) continue;

                    BlockPos above = head.above();
                    if (!level.getBlockState(above).getCollisionShape(level, above).isEmpty()) continue;

                    if (mob.isBedClaimedByOther(head, r)) continue;

                    double d = head.distSqr(origin);
                    if (d < bestDist) {
                        bestDist = d;
                        best = head;
                    }
                }
            }
        }

        return best;
    }

    private static boolean isDaytime(Level level) {
        long t = level.getDayTime() % 24000L;
        return t < 12000L;
    }
}
