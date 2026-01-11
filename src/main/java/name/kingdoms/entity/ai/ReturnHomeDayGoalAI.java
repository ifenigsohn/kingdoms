package name.kingdoms.entity.ai;

import name.kingdoms.entity.aiKingdomEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;

import java.util.EnumSet;

public class ReturnHomeDayGoalAI extends Goal {

    private final aiKingdomEntity mob;
    private final double speed;

    public ReturnHomeDayGoalAI(aiKingdomEntity mob, double speed) {
        this.mob = mob;
        this.speed = speed;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        Level level = mob.level();
        if (level.isClientSide()) return false;
        if (!isDaytime(level)) return false;

        BlockPos home = mob.getHomePos();
        if (home == null) return false;

        return mob.blockPosition().distSqr(home) > (double) (16 * 16);
    }

    @Override
    public void start() {
        BlockPos home = mob.getHomePos();
        if (home == null) return;

        mob.getNavigation().moveTo(
                home.getX() + 0.5,
                home.getY(),
                home.getZ() + 0.5,
                speed
        );
    }

    @Override
    public boolean canContinueToUse() {
        Level level = mob.level();
        if (level.isClientSide()) return false;
        if (!isDaytime(level)) return false;

        BlockPos home = mob.getHomePos();
        if (home == null) return false;

        return !mob.getNavigation().isDone()
                && mob.blockPosition().distSqr(home) > (double) (8 * 8);
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }

    private static boolean isDaytime(Level level) {
        long t = level.getDayTime() % 24000L;
        return t < 12000L;
    }
}
