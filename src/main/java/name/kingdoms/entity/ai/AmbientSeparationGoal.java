package name.kingdoms.entity.ai;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

public class AmbientSeparationGoal extends Goal {
    private final aiKingdomNPCEntity mob;
    private final double minDist;
    private final double speed;

    // small cooldown so we don't spam path recalcs every tick
    private int cooldown = 0;

    public AmbientSeparationGoal(aiKingdomNPCEntity mob, double minDist, double speed) {
        this.mob = mob;
        this.minDist = minDist;
        this.speed = speed;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!mob.isAmbient()) return false;
        if (mob.isSleeping()) return false;
        if (cooldown > 0) { cooldown--; return false; }

        double minSqr = minDist * minDist;

        List<aiKingdomNPCEntity> nearby =
                mob.level().getEntitiesOfClass(
                        aiKingdomNPCEntity.class,
                        mob.getBoundingBox().inflate(minDist, 1.5, minDist),
                        other -> other != mob && other.isAmbient() && sameAmbientGroup(other)
                );

        if (nearby.isEmpty()) return false;

        for (aiKingdomNPCEntity other : nearby) {
            if (mob.distanceToSqr(other) < minSqr) return true;
        }
        return false;
    }

    @Override
    public void start() {
        Vec3 push = Vec3.ZERO;

        for (aiKingdomNPCEntity other : mob.level().getEntitiesOfClass(
                aiKingdomNPCEntity.class,
                mob.getBoundingBox().inflate(minDist, 1.5, minDist),
                o -> o != mob && o.isAmbient() && sameAmbientGroup(o)
        )) {
            Vec3 diff = mob.position().subtract(other.position());
            double d2 = diff.lengthSqr();
            if (d2 > 0.0001) {
                // Stronger repulsion when very close
                push = push.add(diff.normalize().scale(1.0 / Math.max(0.25, Math.sqrt(d2))));
            }
        }

        if (push.lengthSqr() > 0.0001) {
            Vec3 target = mob.position().add(push.normalize().scale(minDist));

            // Try to keep same Y pathing (nav will resolve height)
            mob.getNavigation().moveTo(target.x, target.y, target.z, speed);
        }

        // tune: 5–10 ticks feels good; prevents jitter
        cooldown = 8;
    }

    private boolean sameAmbientGroup(aiKingdomNPCEntity other) {
        // Prefer grouping by ambient event id so different ambient scenes don't repel each other forever.
        String a = mob.getAmbientEventId();
        String b = other.getAmbientEventId();

        if (a != null && !a.isBlank() && b != null && !b.isBlank()) {
            return a.equals(b);
        }

        // If either is blank, fall back to "all ambient"
        return true;
    }
}
