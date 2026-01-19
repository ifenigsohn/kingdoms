package name.kingdoms.entity;

import name.kingdoms.Kingdoms;
import name.kingdoms.kingdomState;
import name.kingdoms.namePool;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Comparator;

public final class RoyalGuardManager {

    private RoyalGuardManager() {}


    private static final int COUNT = 3;

    // Choose ids that match your renderer expectations
    private static final int SKIN_GUARD = 8;
    private static final String JOB_GUARD = "royal_guard";
    private static final String JOB_BANNER = "royal_bannerman";
    private static final int SKIN_BANNER = 8;

    public static void setEnabled(ServerPlayer owner, boolean enabled) {
        ServerLevel level = (ServerLevel) owner.level();
        if (enabled) spawnIfMissing(owner, level);
        else despawn(owner, level);
    }

    private static ItemStack getPlayerHeraldryOrDefault(ServerPlayer owner) {
        MinecraftServer server = ((ServerLevel) owner.level()).getServer();
        if (server == null) return new ItemStack(Items.BLUE_BANNER);

        kingdomState ks = kingdomState.get(server);
        kingdomState.Kingdom k = ks.getPlayerKingdom(owner.getUUID());

        if (k != null && k.heraldry != null && !k.heraldry.isEmpty()) {
            return k.heraldry.copyWithCount(1);
        }
        return new ItemStack(Items.BLUE_BANNER);
    }




    private static void spawnIfMissing(ServerPlayer owner, ServerLevel level) {
        var existing = level.getEntitiesOfClass(
                kingdomWorkerEntity.class,
                owner.getBoundingBox().inflate(512),
                w -> w.isRetinue()
                        && owner.getUUID().equals(w.getOwnerUUID())
                        && (JOB_GUARD.equals(w.getJobId()) || JOB_BANNER.equals(w.getJobId()))
        );

        // If we already have 3, ensure exactly one bannerman and exit
        if (existing.size() >= COUNT) {
            ensureExactlyOneBannerman(existing);
            return;
        }

        // Decide which “slots” are missing (0=bannerman, 1/2=guards)
        boolean hasBanner = existing.stream().anyMatch(RoyalGuardManager::isBannerman);
        int guards = (int) existing.stream().filter(w -> !isBannerman(w)).count();

        // Spawn bannerman if missing
        if (!hasBanner) spawnOne(owner, level, 0);

        // Spawn remaining guards up to COUNT-1
        while (guards < COUNT - 1) {
            spawnOne(owner, level, 1 + guards);
            guards++;
        }

        applyHeraldryNow(owner);
    }

    private static boolean isBannerman(kingdomWorkerEntity w) {
        return JOB_BANNER.equals(w.getJobId());
    }


    private static void ensureExactlyOneBannerman(java.util.List<kingdomWorkerEntity> list) {
        var banners = list.stream().filter(RoyalGuardManager::isBannerman).toList();
        if (banners.size() <= 1) return;

        // Keep the oldest (lowest id) as the bannerman, remove banner from the rest
        var keep = banners.stream().min(Comparator.comparingInt(kingdomWorkerEntity::getId)).orElse(null);
        for (var w : banners) {
            if (w == keep) continue;
            w.setJobId(JOB_GUARD);
            w.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            w.setSkinId(SKIN_GUARD);


        }
    }

    /**
     * slot:
     * 0 = bannerman
     * 1.. = regular guards
     */
    private static void spawnOne(ServerPlayer owner, ServerLevel level, int slot) {
        var e = Kingdoms.KINGDOM_WORKER_ENTITY_TYPE.create(level, EntitySpawnReason.EVENT);
        if (e == null) return;

        boolean banner = (slot == 0);
        e.setJobId(banner ? "royal_bannerman" : "royal_guard");

        // Position near owner
        double ox = owner.getX();
        double oy = owner.getY();
        double oz = owner.getZ();

        // Slight spread so they don't stack
        double dx = (banner ? 0.0 : (slot == 1 ? -1.2 : 1.2));
        double dz = 1.5;

        e.teleportTo(ox + dx, oy, oz + dz);
        e.setYRot(owner.getYRot());
        e.setYHeadRot(owner.getYRot());
        e.setXRot(owner.getXRot());

        e.setPersistenceRequired();
        e.setRetinue(true);
        e.setOwnerUUID(owner.getUUID());
      

        String nm = namePool.randomMedieval(level.getServer(), level.random);
        if (banner) nm = nm + " the Standard Bearer";
        e.setRetinueBaseName(nm);

        // Skin
        e.setSkinId(banner ? SKIN_BANNER : SKIN_GUARD);

        ItemStack heraldry = getPlayerHeraldryOrDefault(owner);

        // Equipment
        if (banner) {
            // bannerman holds banner (or you can keep sword if you prefer)
            e.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            e.setItemSlot(EquipmentSlot.HEAD, heraldry.copyWithCount(1));
        } else {
            e.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            e.setItemSlot(EquipmentSlot.OFFHAND, SoldierEntity.makeShieldFromBanner(heraldry));
        }


    
        // Prevent drops (optional but usually desired for retinue)
        e.setDropChance(EquipmentSlot.MAINHAND, 0.0f);
        e.setDropChance(EquipmentSlot.HEAD, 0.0f);
        e.setDropChance(EquipmentSlot.OFFHAND, 0.0f);


        level.addFreshEntity(e);
    }

    private static void despawn(ServerPlayer owner, ServerLevel level) {
        var list = level.getEntitiesOfClass(
                kingdomWorkerEntity.class,
                owner.getBoundingBox().inflate(512),
                w -> w.isRetinue()
                        && owner.getUUID().equals(w.getOwnerUUID())
                        && (JOB_GUARD.equals(w.getJobId()) || JOB_BANNER.equals(w.getJobId()))
        );
        for (var w : list) w.discard();
    }

    public static void applyHeraldryNow(ServerPlayer owner) {
        if (!(owner.level() instanceof ServerLevel level)) return;

        ItemStack heraldry = getPlayerHeraldryOrDefault(owner);

        // Update *all* retinue combatants + bannermen owned by this player
        var list = level.getEntitiesOfClass(
                kingdomWorkerEntity.class,
                owner.getBoundingBox().inflate(512),
                w -> w.isRetinue()
                    && owner.getUUID().equals(w.getOwnerUUID())
                    && (JOB_GUARD.equals(w.getJobId()) || JOB_BANNER.equals(w.getJobId()))

                    );

        for (var w : list) {
            String job = w.getJobId();

            // Bannermen show the banner on head
            if (JOB_BANNER.equals(job)) {
                w.setItemSlot(EquipmentSlot.HEAD, heraldry.copyWithCount(1));
                w.setDropChance(EquipmentSlot.HEAD, 0.0f);
                // optional: make sure bannerman doesn't carry shield
                w.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                w.setDropChance(EquipmentSlot.OFFHAND, 0.0f);
            }

            if (JOB_GUARD.equals(job)) {
                w.setItemSlot(EquipmentSlot.OFFHAND, SoldierEntity.makeShieldFromBanner(heraldry));
                w.setDropChance(EquipmentSlot.OFFHAND, 0.0f);
                // optional: guards don't wear banner
                w.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            }

        }
    }

}
