package name.kingdoms;

import com.mojang.logging.LogUtils;
import name.kingdoms.entity.kingdomWorkerEntity;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import org.slf4j.Logger;

import java.util.*;

import net.minecraft.network.chat.Component;

public final class RetinueRespawnManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int RESPAWN_TICKS = 20 * 120; // 2 minutes

    private static EntityType<kingdomWorkerEntity> WORKER_TYPE;
    private static boolean HOOKED = false;

    // Canonical: what retinue slots the owner SHOULD have (keyed by jobId)
    private record Spec(String jobId, int skinId, String displayName) {}

    // Owner -> (jobId -> Spec)
    private static final Map<UUID, Map<String, Spec>> OWNED = new HashMap<>();

    // Pending respawns (owner+jobId) -> runAt
    private record Key(UUID owner, String jobId) {}
    private static final Map<Key, Long> RESPAWN_AT = new HashMap<>();

    private RetinueRespawnManager() {}

    /** Call once AFTER entity types registered. */
    public static void init(EntityType<kingdomWorkerEntity> workerType) {
        WORKER_TYPE = workerType;

        if (HOOKED) return;
        HOOKED = true;

        ServerTickEvents.END_SERVER_TICK.register(RetinueRespawnManager::tick);
    }

    // ------------------------------------------------------------
    // Hook points: call these from connection events
    // ------------------------------------------------------------

    /** Despawn retinue when player disconnects (and remember their specs). */
    public static void onDisconnect(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel sl)) return;

        UUID owner = player.getUUID();

        // Scan & despawn owned retinue entities; remember their specs
        List<kingdomWorkerEntity> found = sl.getEntitiesOfClass(
                kingdomWorkerEntity.class,
                player.getBoundingBox().inflate(256),
                w -> w.isRetinue() && owner.equals(w.getOwnerUUID())
        );

        for (kingdomWorkerEntity w : found) {
            rememberOwned(owner, w.getJobId(), w.getSkinId(), prettyNameFromJob(w.getJobId()));
            w.discard(); // remove cleanly
        }

        LOGGER.info("[Kingdoms] Retinue despawned on disconnect owner={} count={}", owner, found.size());
    }

    /** On join: spawn anything not waiting on a timer; apply respawning effect if needed. */
    public static void onJoin(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel sl)) return;

        UUID owner = player.getUUID();
        long now = sl.getGameTime();

        // Apply/update effect for remaining time (if any)
        int remaining = getMaxRemainingTicks(owner, now);
        if (remaining > 0 && ModEffects.RESPAWNING != null) {
            MobEffectInstance cur = player.getEffect(ModEffects.RESPAWNING);

            // Only add or update if missing or duration changed significantly
            if (cur == null || cur.getDuration() != remaining) {
                player.addEffect(new MobEffectInstance(
                        ModEffects.RESPAWNING,
                        remaining,
                        0,
                        false,  // ambient
                        false,  // showParticles
                        true    // showIcon
                ));
            }
        } else {
            if (player.hasEffect(ModEffects.RESPAWNING)) {
                player.removeEffect(ModEffects.RESPAWNING);
            }
        }

        // Spawn owned specs that are NOT currently blocked by a respawn timer
        Map<String, Spec> specs = OWNED.get(owner);
        if (specs == null || specs.isEmpty()) return;

        for (Spec spec : specs.values()) {
            if (isBlocked(owner, spec.jobId(), now)) continue;
            if (hasAliveRetinue(sl, owner, spec.jobId())) continue;
            spawnOne(sl, player, spec);
        }
    }

    // ------------------------------------------------------------
    // Called when a retinue member dies
    // ------------------------------------------------------------
    public static void onRetinueDied(ServerLevel level, UUID owner, String jobId, int skinId, String displayName) {
        if (jobId == null || jobId.isBlank()) return;

        rememberOwned(owner, jobId, skinId, displayName);

        long runAt = level.getGameTime() + RESPAWN_TICKS;
        RESPAWN_AT.put(new Key(owner, jobId), runAt);

        ServerPlayer sp = level.getServer().getPlayerList().getPlayer(owner);
        if (sp != null && ModEffects.RESPAWNING != null) {
            int remaining = (int)Math.max(0, runAt - level.getGameTime());
            sp.addEffect(new MobEffectInstance(ModEffects.RESPAWNING, remaining, 0, false, true, true));
        }

        LOGGER.info("[Kingdoms] Retinue death queued owner={} job={} respawnAt={}", owner, jobId, runAt);
    }

    // ------------------------------------------------------------
    // Tick: spawn when timers expire
    // ------------------------------------------------------------
    private static void tick(MinecraftServer server) {
        if (WORKER_TYPE == null) return;
        if (RESPAWN_AT.isEmpty()) return;

        // We need to process per-online-owner, per-dimension timebase (use owner’s current level time)
        for (ServerPlayer ownerPlayer : server.getPlayerList().getPlayers()) {
            UUID owner = ownerPlayer.getUUID();
            if (!(ownerPlayer.level() instanceof ServerLevel sl)) continue;

            long now = sl.getGameTime();

            // Spawn any expired timers for this owner
            Iterator<Map.Entry<Key, Long>> it = RESPAWN_AT.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                Key k = e.getKey();
                long runAt = e.getValue();

                if (!k.owner().equals(owner)) continue;
                if (now < runAt) continue;

                Spec spec = getSpec(owner, k.jobId());
                if (spec != null) {
                    if (!hasAliveRetinue(sl, owner, spec.jobId())) {
                        spawnOne(sl, ownerPlayer, spec);
                    }
                }

                it.remove();
            }

            // Update/clear effect
            int remaining = getMaxRemainingTicks(owner, now);
            if (remaining > 0 && ModEffects.RESPAWNING != null) {
                ownerPlayer.addEffect(new MobEffectInstance(ModEffects.RESPAWNING, remaining, 0, false, true, true));
            } else {
                try { ownerPlayer.removeEffect(ModEffects.RESPAWNING); } catch (Throwable ignored) {}
            }
        }
    }

    // ------------------------------------------------------------
    // Spawn helpers
    // ------------------------------------------------------------
    private static void spawnOne(ServerLevel level, ServerPlayer owner, Spec spec) {
        BlockPos pos = BlockPos.containing(owner.getX() + 1.5, owner.getY(), owner.getZ() + 1.5);

        kingdomWorkerEntity ent = WORKER_TYPE.create(
                level,
                (kingdomWorkerEntity w) -> {
                    w.setRetinue(true);
                    w.setOwnerUUID(owner.getUUID());
                    w.setHomePos(null);

                    w.setJobId(spec.jobId());
                    w.setSkinId(spec.skinId());

                    // --- Option A naming: random base name + pretty job ---
                    String baseName = namePool.randomMedieval(level.getServer(), level.random);

                    String prettyJob = switch (spec.jobId()) {
                        case "general" -> "General";
                        case "scribe" -> "Scribe";
                        case "treasurer" -> "Treasurer";
                        default -> (spec.jobId() == null || spec.jobId().isBlank())
                                ? ""
                                : spec.jobId().substring(0, 1).toUpperCase() + spec.jobId().substring(1);
                    };

                    w.setCustomName(Component.literal(baseName + " (" + prettyJob + ")"));
                    w.setCustomNameVisible(true);
                },
                pos,
                EntitySpawnReason.EVENT,
                true,
                true
        );

        if (ent != null) {
            level.addFreshEntity(ent);
            LOGGER.info("[Kingdoms] Spawned retinue owner={} job={}", owner.getUUID(), spec.jobId());
        } else {
            LOGGER.warn("[Kingdoms] Failed to create retinue entity owner={} job={}", owner.getUUID(), spec.jobId());
        }
    }

    private static boolean hasAliveRetinue(ServerLevel level, UUID owner, String jobId) {
        return !level.getEntitiesOfClass(
                kingdomWorkerEntity.class,
                new net.minecraft.world.phys.AABB(
                        ownerPosX(level, owner) - 256, -64, ownerPosZ(level, owner) - 256,
                        ownerPosX(level, owner) + 256, 320, ownerPosZ(level, owner) + 256
                ),
                w -> w.isRetinue()
                        && owner.equals(w.getOwnerUUID())
                        && jobId.equals(w.getJobId())
                        && w.isAlive()
        ).isEmpty();
    }

    // Cheap fallback; we only use this AABB builder above for “near owner”.
    private static double ownerPosX(ServerLevel level, UUID owner) {
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(owner);
        return (p != null) ? p.getX() : 0;
    }
    private static double ownerPosZ(ServerLevel level, UUID owner) {
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(owner);
        return (p != null) ? p.getZ() : 0;
    }

    // ------------------------------------------------------------
    // State helpers
    // ------------------------------------------------------------
    private static void rememberOwned(UUID owner, String jobId, int skinId, String displayName) {
        if (jobId == null) jobId = "";
        String key = jobId.trim().toLowerCase(Locale.ROOT);
        if (key.isBlank()) return;

        OWNED.computeIfAbsent(owner, k -> new HashMap<>())
                .put(key, new Spec(key, skinId, displayName == null ? "" : displayName));
    }

    private static Spec getSpec(UUID owner, String jobId) {
        Map<String, Spec> m = OWNED.get(owner);
        if (m == null) return null;
        return m.get(jobId.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isBlocked(UUID owner, String jobId, long now) {
        Long runAt = RESPAWN_AT.get(new Key(owner, jobId.trim().toLowerCase(Locale.ROOT)));
        return runAt != null && runAt > now;
    }

    private static int getMaxRemainingTicks(UUID owner, long now) {
        long max = 0;
        for (var e : RESPAWN_AT.entrySet()) {
            if (!e.getKey().owner().equals(owner)) continue;
            max = Math.max(max, e.getValue());
        }
        if (max <= now) return 0;
        long rem = max - now;
        return (int)Math.min(Integer.MAX_VALUE, rem);
    }

    private static String prettyNameFromJob(String job) {
        if (job == null) return "";
        return switch (job) {
            case "general" -> "General";
            case "scribe" -> "Scribe";
            case "treasurer" -> "Treasurer";
            default -> job.substring(0, 1).toUpperCase() + job.substring(1);
        };
    }
}
