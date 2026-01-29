package name.kingdoms.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

public class AmbientLoiterGoal extends Goal {
    private final PathfinderMob mob;
    private final double speed;
    private int cooldown = 0;

    public interface LoiterAccess {
        BlockPos getAmbientLoiterCenter();
        int getAmbientLoiterRadius();
        boolean isAmbientLoiterActive();
    }

    private final LoiterAccess access;

    public AmbientLoiterGoal(PathfinderMob mob, LoiterAccess access, double speed) {
        this.mob = mob;
        this.access = access;
        this.speed = speed;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return access.isAmbientLoiterActive() && access.getAmbientLoiterCenter() != null;
    }

    @Override
    public void tick() {
        if (cooldown-- > 0) return;
        cooldown = 40 + mob.getRandom().nextInt(40);

        BlockPos c = access.getAmbientLoiterCenter();
        int r = access.getAmbientLoiterRadius();

        int dx = mob.getRandom().nextInt(r * 2 + 1) - r;
        int dz = mob.getRandom().nextInt(r * 2 + 1) - r;

        BlockPos target = c.offset(dx, 0, dz);
        mob.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, speed);
    }
}
