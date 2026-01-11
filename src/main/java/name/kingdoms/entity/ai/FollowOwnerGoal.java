package name.kingdoms.entity.ai;

import name.kingdoms.entity.kingdomWorkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumSet;
import java.util.UUID;

public class FollowOwnerGoal extends Goal {
    private final kingdomWorkerEntity mob;
    private final double speed;
    private final float startDist;
    private final float stopDist;

    private ServerPlayer owner;

    // --------------------
    // Teleport tuning
    // --------------------
    private static final int TELEPORT_RADIUS = 40;
    private static final int HARD_TELEPORT_RADIUS = 70;
    private static final double MAX_OWNER_HSPEED = 0.22;    // blocks/tick (horizontal)
    private static final int TELEPORT_COOLDOWN_TICKS = 40;  // 2s
    private int teleportCooldown = 0;

    public FollowOwnerGoal(kingdomWorkerEntity mob, double speed, float startDist, float stopDist) {
        this.mob = mob;
        this.speed = speed;
        this.startDist = startDist;
        this.stopDist = stopDist;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!mob.isRetinue()) return false;
        if (!(mob.level() instanceof ServerLevel sl)) return false;

        UUID id = mob.getOwnerUUID();
        if (id == null) return false;

        owner = sl.getServer().getPlayerList().getPlayer(id);
        if (owner == null) return false;
        if (mob.isSleeping()) return false;

        return mob.distanceToSqr(owner) > (startDist * startDist);
    }

    @Override
    public boolean canContinueToUse() {
        if (!mob.isRetinue()) return false;
        if (owner == null || !owner.isAlive()) return false;
        if (mob.isSleeping()) return false;

        return mob.distanceToSqr(owner) > (stopDist * stopDist);
    }

    @Override
    public void tick() {
        if (owner == null) return;
        if (!(mob.level() instanceof ServerLevel sl)) return;

        if (teleportCooldown > 0) teleportCooldown--;

        double distSqr = mob.distanceToSqr(owner);

        // Teleport if far enough, but ONLY when owner is slow and grounded
        if (teleportCooldown == 0) {
            boolean hard = distSqr > (HARD_TELEPORT_RADIUS * HARD_TELEPORT_RADIUS);
            boolean soft = distSqr > (TELEPORT_RADIUS * TELEPORT_RADIUS);

            if ((hard || soft) && ownerIsSafeToTeleportTo(owner) && mobIsSafeToTeleport(mob)) {
                if (tryTeleportNearOwner(sl, mob, owner)) {
                    teleportCooldown = TELEPORT_COOLDOWN_TICKS;
                    return;
                }
            }
        }

        // Normal follow pathing
        mob.getNavigation().moveTo(owner, speed);
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        owner = null;
    }

    // --------------------
    // Teleport gating
    // --------------------

    private static double horizontalSpeedSqr(ServerPlayer p) {
        var v = p.getDeltaMovement();
        return (v.x * v.x) + (v.z * v.z);
    }

    private static boolean ownerIsSafeToTeleportTo(ServerPlayer owner) {
        if (!owner.onGround()) return false;

        if (owner.isFallFlying()) return false;
        if (owner.getAbilities().flying) return false;
        if (owner.isSwimming()) return false;
        if (owner.isPassenger()) return false;

        // Water check (older mappings)
        if (owner.isInWater()) return false;

        return horizontalSpeedSqr(owner) <= (MAX_OWNER_HSPEED * MAX_OWNER_HSPEED);
    }

    private static boolean mobIsSafeToTeleport(kingdomWorkerEntity mob) {
        if (!mob.onGround()) return false;
        if (mob.isPassenger()) return false;

        // Water check (older mappings)
        return !mob.isInWater();
    }

    // --------------------
    // Teleport destination
    // --------------------

    private static boolean tryTeleportNearOwner(ServerLevel level, kingdomWorkerEntity mob, ServerPlayer owner) {
        BlockPos base = owner.blockPosition();

        for (int i = 0; i < 12; i++) {
            int dx = mob.getRandom().nextIntBetweenInclusive(-3, 3);
            int dz = mob.getRandom().nextIntBetweenInclusive(-3, 3);
            if (dx == 0 && dz == 0) continue;

            BlockPos p = base.offset(dx, 0, dz);

            BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p);

            mob.getNavigation().stop();
            mob.teleportTo(top.getX() + 0.5, top.getY(), top.getZ() + 0.5);
            return true;
        }

        return false;
    }
}
