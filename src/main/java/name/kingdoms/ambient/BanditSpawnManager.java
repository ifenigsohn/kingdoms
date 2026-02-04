package name.kingdoms.ambient;

import name.kingdoms.kingdomState;
import name.kingdoms.kingdomState.Kingdom;
import name.kingdoms.entity.ai.aiKingdomNPCEntity;
import name.kingdoms.entity.modEntities; // <-- change to your actual registry holder
import name.kingdoms.war.WarState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class BanditSpawnManager {
    private BanditSpawnManager() {}

    private static final Map<UUID, Long> NEXT_DUE = new HashMap<>();

    // Base cadence: “normal security” bandits are rare
    private static final int BASE_INTERVAL_TICKS = 20 * 60 * 4; // 4 minutes
    // Minimum cadence when security is awful
    private static final int MIN_INTERVAL_TICKS  = 20 * 35;     // 35 seconds

    // Keep spawns from popping right on top of the player
    private static final int MIN_DIST = 26;
    private static final int MAX_DIST = 90;

    public static void tick(MinecraftServer server) {
        long now = server.getTickCount();

        var ks = kingdomState.get(server);
        var war = WarState.get(server);

        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (!(sp.level() instanceof ServerLevel level)) continue;

            long due = NEXT_DUE.getOrDefault(sp.getUUID(), -1L);
            if (due < 0) {
                // stagger the very first run
                NEXT_DUE.put(sp.getUUID(), now + level.random.nextInt(BASE_INTERVAL_TICKS));
                continue;
            }
            if (now < due) continue;

            Kingdom pk = ks.getPlayerKingdom(sp.getUUID());

            // Compute “deficit” only when the kingdom actually cares about security
            double deficit01 = 0.0;
            boolean hasKingdom = (pk != null);

            if (hasKingdom) {
                int pop = pk.populationJobs();
                if (pop > 5) {
                    double sEff = pk.securityEff(server); // 0..1
                    double req = Kingdom.REQUIRED_SECURITY; // 0.30 in your code
                    deficit01 = clamp01((req - sEff) / req); // 0 normal, 1 very bad
                }
            }

            // Schedule next run: lower security => shorter interval
            int interval = (int) Math.round(lerp(BASE_INTERVAL_TICKS, MIN_INTERVAL_TICKS, deficit01));
            int jitter = (int) (interval * 0.25);
            long next = now + interval + (jitter <= 0 ? 0 : (level.random.nextInt(jitter * 2 + 1) - jitter));
            NEXT_DUE.put(sp.getUUID(), next);

            // Small base chance even at good security, ramps up fast with deficit
            double chance = 0.06 + 0.34 * deficit01; // 6%..40% each pulse
            if (level.random.nextDouble() > chance) continue;

            // Decide whether to try inside the kingdom border
            boolean tryInsideKingdom = false;
            if (hasKingdom && pk.hasBorder && pk.isInsideBorder(sp.blockPosition())) {
                // With low security, bias heavily towards “inside the kingdom”
                double insideBias = 0.35 + 0.55 * deficit01; // 35%..90%
                tryInsideKingdom = level.random.nextDouble() < insideBias;
            }

            BlockPos center = sp.blockPosition();
            BlockPos spawn = tryInsideKingdom
                    ? findSpawnInsideBorder(level, pk, center)
                    : findSpawnNearPlayer(level, center);

            if (spawn == null) continue;

            // Don’t spawn in war zones (you said this is fine)
            if (WarZoneUtil.isInsideAnyWarZone(server, spawn)) continue;


            // Spawn a squad size scaled by deficit (1..6)
            int count = 1 + level.random.nextInt(1 + (int)Math.round(lerp(1, 5, deficit01)));
            spawnBanditSquad(level, sp, spawn, count, (int)Math.round(lerp(20*60, 20*180, deficit01)));
        }
    }

    private static void spawnBanditSquad(ServerLevel level, ServerPlayer targetPlayer, BlockPos origin, int count, int ttlTicks) {
        int r = 6 + level.random.nextInt(7);

        for (int i = 0; i < count; i++) {
            int dx = level.random.nextInt(r * 2 + 1) - r;
            int dz = level.random.nextInt(r * 2 + 1) - r;

            BlockPos p = origin.offset(dx, 0, dz);
            p = topSolid(level, p);
            if (p == null) continue;

            // Create your existing NPC entity type
            aiKingdomNPCEntity bandit = modEntities.AI_KINGDOM_NPC.create(level, EntitySpawnReason.EVENT);
            if (bandit == null) continue;

            bandit.teleportTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
            bandit.setYRot(level.random.nextFloat() * 360f);
            bandit.setXRot(0f);
            bandit.yBodyRot = bandit.getYRot();
            bandit.yHeadRot = bandit.getYRot();


            bandit.setAmbient(true);
            bandit.setAmbientScene("bandit_raid", null, ttlTicks);

            // Important: mark them as bandit type + random skin
            bandit.initFromSpawner("bandit", -1);

            // Encourage them to hang around this area (so it feels like “inside the kingdom”)
            bandit.setAmbientAnchor(origin, 18);
            bandit.setAmbientLoiter(origin, 16, ttlTicks);

            // If you want them to “key” onto the player immediately, your goals already do that:
            // targetSelector has NearestAttackableTargetGoal(Player) gated by isBandit()

            level.addFreshEntity(bandit);
        }

        // Optional: tiny “warning bark” from your ambient system if you want:
        // AmbientManager.queueSpeech(level.getServer(), ???speakerId???, targetPlayer.getUUID(), "Guard", "Bandits in the fields!", 20*6, 20);
    }

    private static BlockPos findSpawnNearPlayer(ServerLevel level, BlockPos center) {
        for (int tries = 0; tries < 18; tries++) {
            double ang = level.random.nextDouble() * (Math.PI * 2.0);
            int dist = MIN_DIST + level.random.nextInt(MAX_DIST - MIN_DIST + 1);

            int x = center.getX() + (int)Math.round(Math.cos(ang) * dist);
            int z = center.getZ() + (int)Math.round(Math.sin(ang) * dist);

            BlockPos p = topSolid(level, new BlockPos(x, center.getY(), z));
            if (p == null) continue;

            // keep it from being right beside the player
            if (p.distSqr(center) < (MIN_DIST * MIN_DIST)) continue;

            return p;
        }
        return null;
    }

    private static BlockPos findSpawnInsideBorder(ServerLevel level, Kingdom k, BlockPos playerPos) {
        if (k == null || !k.hasBorder) return null;

        int minX = k.borderMinX + 6;
        int maxX = k.borderMaxX - 6;
        int minZ = k.borderMinZ + 6;
        int maxZ = k.borderMaxZ - 6;
        if (minX >= maxX || minZ >= maxZ) return null;

        for (int tries = 0; tries < 24; tries++) {
            int x = Mth.nextInt(level.random, minX, maxX);
            int z = Mth.nextInt(level.random, minZ, maxZ);

            BlockPos p = topSolid(level, new BlockPos(x, playerPos.getY(), z));
            if (p == null) continue;

            // don’t pop right next to the player even if “inside”
            if (p.distSqr(playerPos) < (MIN_DIST * MIN_DIST)) continue;

            return p;
        }
        return null;
    }

    private static BlockPos topSolid(ServerLevel level, BlockPos p) {
        // Fast-ish surface pick
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
        if (y <= level.getHeight() + 2) return null;

        BlockPos top = new BlockPos(p.getX(), y, p.getZ());

        // Ensure 2 blocks of air (basic “can stand” check)
        if (!level.getBlockState(top).getCollisionShape(level, top).isEmpty()) return null;
        if (!level.getBlockState(top.above()).getCollisionShape(level, top.above()).isEmpty()) return null;

        // Must have solid ground under
        if (level.getBlockState(top.below()).getCollisionShape(level, top.below()).isEmpty()) return null;

        return top;
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * clamp01(t);
    }
}
