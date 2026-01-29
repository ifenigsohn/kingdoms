package name.kingdoms.ambient;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;


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

    public static final String AMBIENT_MOUNT_TAG = "kingdoms_ambient_mount";

    /** If mob is riding something navigable (like a horse), steer the vehicle instead. */
    private static Mob navMob(Mob mob) {
        if (mob.isPassenger()) {
            Entity v = mob.getVehicle();
            if (v instanceof Mob vm) return vm;
        }
        return mob;
    }

    /** Send mob *past* the player, continuing beyond them. */
    public static void walkPastPlayer(Mob mob, net.minecraft.server.level.ServerPlayer player) {
        Mob mover = navMob(mob);
        if (!(mover.level() instanceof ServerLevel level)) return;

        double mx = mover.getX();
        double mz = mover.getZ();
        double px = player.getX();
        double pz = player.getZ();

        double dx = px - mx;
        double dz = pz - mz;

        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 6.0) return;

        // Direction from mover -> player
        double ux = dx / dist;
        double uz = dz / dist;

        // Perpendicular for a little "lane offset" so they don't clip directly through you
        double pxn = -uz;
        double pzn =  ux;

        double beyond = 14.0; // how far beyond the player to aim
        double side = (level.random.nextBoolean() ? 1 : -1) * (1.5 + level.random.nextDouble() * 2.5);

        double tx = px + ux * beyond + pxn * side;
        double tz = pz + uz * beyond + pzn * side;

        int ty = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(tx), Mth.floor(tz));

        // Slightly faster if mounted (vehicle is a horse)
        double speed = (mover instanceof Horse) ? 1.15 : 1.0;

        mover.getNavigation().moveTo(tx, ty, tz, speed);
    }

    /** Vanilla horse mount for ambient scenes. */
    public static Horse spawnAmbientHorse(ServerLevel level, BlockPos p) {
        Horse horse = EntityType.HORSE.create(level, EntitySpawnReason.EVENT);
        if (horse == null) return null;

        horse.teleportTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
        horse.setYRot(level.random.nextFloat() * 360f);

        horse.setPersistenceRequired();
        horse.addTag(AMBIENT_MOUNT_TAG);

        // Optional: saddle so it looks correct (safe if slot exists; otherwise remove this)
        try {
            horse.setItemSlot(net.minecraft.world.entity.EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));
        } catch (Throwable ignored) {}

        return horse;
    }

    public static BlockPos findNearbyValidGround(ServerLevel level, BlockPos center, int radius, int tries) {
        for (int i = 0; i < tries; i++) {
            int dx = level.random.nextInt(radius * 2 + 1) - radius;
            int dz = level.random.nextInt(radius * 2 + 1) - radius;

            int x = center.getX() + dx;
            int z = center.getZ() + dz;
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);

            BlockPos p = new BlockPos(x, y, z);

            var state = level.getBlockState(p);
            if (!state.getFluidState().isEmpty()) continue;

            // require solid block below, and air at p (tweak if needed)
            if (!level.getBlockState(p.below()).isSolid()) continue;
            if (!level.getBlockState(p).isAir()) continue;

            return p;
        }
        return null;
    }

        public static boolean isSafeHumanoidSpawn(ServerLevel level, BlockPos feet) {
            BlockPos below = feet.below();
            BlockState bsBelow = level.getBlockState(below);

            // don’t spawn on water
            if (level.getFluidState(below).is(FluidTags.WATER)) return false;

            // needs solid ground
            if (!bsBelow.isSolid()) return false;

            // feet and head must be empty
            if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()) return false;
            if (!level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) return false;

            return true;
        }

        public static boolean isSafeMountSpawn(ServerLevel level, BlockPos feet) {
            // mounts need 2-high clearance as well; horse is taller but this is “good enough”
            return isSafeHumanoidSpawn(level, feet) &&
                    level.getBlockState(feet.above(2)).getCollisionShape(level, feet.above(2)).isEmpty();
        }

        // ring picker with a few attempts
        public static BlockPos pickRingNear(ServerLevel level, BlockPos center, int minR, int maxR, int tries) {
            for (int i = 0; i < tries; i++) {
                double ang = level.random.nextDouble() * Math.PI * 2.0;
                int r = minR + level.random.nextInt(Math.max(1, (maxR - minR + 1)));
                int x = center.getX() + (int) Math.round(Math.cos(ang) * r);
                int z = center.getZ() + (int) Math.round(Math.sin(ang) * r);
                return new BlockPos(x, center.getY(), z);
            }
            return null;
        }
    }


