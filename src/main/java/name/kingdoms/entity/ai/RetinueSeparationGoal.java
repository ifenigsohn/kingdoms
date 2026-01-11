package name.kingdoms.entity.ai;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import name.kingdoms.entity.kingdomWorkerEntity;

public class RetinueSeparationGoal extends Goal {
    private final kingdomWorkerEntity mob;
    private final double minDist;
    private final double speed;

    public RetinueSeparationGoal(kingdomWorkerEntity mob, double minDist, double speed) {
        this.mob = mob;
        this.minDist = minDist;
        this.speed = speed;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!mob.isRetinue()) return false;

        double minSqr = minDist * minDist;

        List<kingdomWorkerEntity> nearby =
                mob.level().getEntitiesOfClass(
                        kingdomWorkerEntity.class,
                        mob.getBoundingBox().inflate(minDist, 1.5, minDist),
                        w -> w != mob && isSameRetinue(w)
                );

        if (nearby.isEmpty()) return false;

        // anyone too close?
        for (kingdomWorkerEntity w : nearby) {
            if (mob.distanceToSqr(w) < minSqr) return true;
        }

        return false;
    }

    @Override
    public void start() {
        Vec3 push = Vec3.ZERO;

        for (kingdomWorkerEntity w : mob.level().getEntitiesOfClass(
                kingdomWorkerEntity.class,
                mob.getBoundingBox().inflate(minDist, 1.5, minDist),
                this::isSameRetinue
        )) {
            Vec3 diff = mob.position().subtract(w.position());
            if (diff.lengthSqr() > 0.001) {
                push = push.add(diff.normalize());
            }
        }

        if (push.lengthSqr() > 0.001) {
            Vec3 target = mob.position().add(push.normalize().scale(minDist));
            mob.getNavigation().moveTo(target.x, target.y, target.z, speed);
        }
    }

    private boolean isSameRetinue(kingdomWorkerEntity other) {
        UUID a = mob.getOwnerUUID();
        UUID b = other.getOwnerUUID();
        return a != null && a.equals(b);
    }
}

