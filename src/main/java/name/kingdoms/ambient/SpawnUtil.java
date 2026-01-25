package name.kingdoms.ambient;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;

public final class SpawnUtil {
    private SpawnUtil() {}

    public static BlockPos findRingSpawn(ServerLevel level, BlockPos center, int minR, int maxR, int tries) {
        for (int i = 0; i < tries; i++) {
            double ang = level.random.nextDouble() * Math.PI * 2.0;
            int r = minR + level.random.nextInt(Math.max(1, maxR - minR + 1));

            int x = center.getX() + (int) Math.round(Math.cos(ang) * r);
            int z = center.getZ() + (int) Math.round(Math.sin(ang) * r);

            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos p = new BlockPos(x, y, z);

            // basic sanity: don’t spawn in/over liquid, and require solid ground + headroom
            BlockPos below = p.below();
            BlockPos above = p.above();

            if (!level.getFluidState(p).isEmpty()) continue;
            if (!level.getFluidState(below).isEmpty()) continue;

            // must be able to stand
            if (!level.getBlockState(below).isSolid()) continue;

            // feet/head must be empty collision
            if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) continue;
            if (!level.getBlockState(above).getCollisionShape(level, above).isEmpty()) continue;

            // don’t spawn inside war zones
            if (WarZoneUtil.isInsideAnyWarZone(level.getServer(), p)) continue;


            return p;
        }
        return null;
    }

    public static void walkTowardPlayer(net.minecraft.world.entity.Mob mob,
                                        net.minecraft.server.level.ServerPlayer player) {
        // stop a bit short so they don’t crowd the player
        double px = player.getX();
        double pz = player.getZ();

        double dx = px - mob.getX();
        double dz = pz - mob.getZ();
        double dist = Math.sqrt(dx*dx + dz*dz);
        if (dist < 6.0) return;

        // target point 4 blocks away from player along the line
        double nx = mob.getX() + dx / dist * (dist - 4.0);
        double nz = mob.getZ() + dz / dist * (dist - 4.0);

        mob.getNavigation().moveTo(nx, player.getY(), nz, 1.0);
    }
}
