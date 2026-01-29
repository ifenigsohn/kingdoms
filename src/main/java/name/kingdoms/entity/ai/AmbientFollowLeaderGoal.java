package name.kingdoms.entity.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;
import java.util.UUID;

public class AmbientFollowLeaderGoal extends Goal {
    private final aiKingdomNPCEntity mob;
    private final double speed;
    private final float startDist;
    private final float stopDist;

    public AmbientFollowLeaderGoal(aiKingdomNPCEntity mob, double speed, float startDist, float stopDist) {
        this.mob = mob;
        this.speed = speed;
        this.startDist = startDist;
        this.stopDist = stopDist;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    private Mob resolveLeader() {
        UUID id = mob.getAmbientLeaderId();
        if (id == null) return null;
        if (!(mob.level() instanceof ServerLevel sl)) return null;
        var e = sl.getEntity(id);
        return (e instanceof Mob m) ? m : null;
    }

    @Override
    public boolean canUse() {
        Mob leader = resolveLeader();
        if (leader == null) return false;
        if (!leader.isAlive()) return false;

        double d2 = mob.distanceToSqr(leader);
        return d2 > (startDist * startDist);
    }

    @Override
    public boolean canContinueToUse() {
        Mob leader = resolveLeader();
        if (leader == null) return false;
        if (!leader.isAlive()) return false;

        double d2 = mob.distanceToSqr(leader);
        return d2 > (stopDist * stopDist);
    }

    @Override
    public void tick() {
        Mob leader = resolveLeader();
        if (leader == null) return;

        // Follow leader directly; your spawn spacing keeps a "train" look
        mob.getNavigation().moveTo(leader, speed);
    }
}
