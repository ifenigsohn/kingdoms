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

    // Owner-side retinue maintenance
    private static final int RECALL_CHECK_EVERY_TICKS = 20; // once per second
    private static final int RECALL_DISTANCE = 70;          // same as HARD_TELEPORT_RADIUS in entity
    private static final int MISSING_GRACE_TICKS = 20 * 10; // 10s missing -> respawn near owner

    // If a slot (owner+job) is missing (not found/loaded), track since when
    private static final Map<Key, Long> MISSING_SINCE = new HashMap<>();

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
        UUID owner = player.getUUID();

        // We need a ServerLevel to access the server safely in your mappings
        if (!(player.level() instanceof ServerLevel sl)) return;

        // Remember specs from nearby loaded retinue (optional)
        var found = sl.getEntitiesOfClass(
                kingdomWorkerEntity.class,
                player.getBoundingBox().inflate(256),
                w -> w.isRetinue() && owner.equals(w.getOwnerUUID())
        );
        for (kingdomWorkerEntity w : found) {
            rememberOwned(owner, w.getJobId(), w.getSkinId(), prettyNameFromJob(w.getJobId()));
        }

        // Despawn ALL loaded retinue across all levels/dimensions
        despawnAllLoaded(sl.getServer(), owner);

        LOGGER.info("[Kingdoms] Retinue despawned on disconnect owner={}", owner);
    }


    /** On join: spawn anything not waiting on a timer; apply respawning effect if needed. */
   public static void onJoin(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel sl)) return;

        UUID owner = player.getUUID();

        // --- GATE: if disabled, don't spawn or apply respawn effect ---
        if (!isEnabled(owner)) {
            try { player.removeEffect(ModEffects.RESPAWNING); } catch (Throwable ignored) {}
            return;
        }

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
            if (hasAliveRetinue(sl, player, spec.jobId())) continue;
            spawnOne(sl, player, spec);
        }
    }

    // ------------------------------------------------------------
    // Called when a retinue member dies
    // ------------------------------------------------------------
    public static void onRetinueDied(ServerLevel level, UUID owner, String jobId, int skinId, String displayName) {
        if (jobId == null || jobId.isBlank()) return;

        rememberOwned(owner, jobId, skinId, displayName);

        // --- GATE: if disabled, do NOT queue respawns/effects ---
        if (!isEnabled(owner)) return;

        long runAt = level.getGameTime() + RESPAWN_TICKS;
        String keyJob = jobId.trim().toLowerCase(Locale.ROOT);
        RESPAWN_AT.put(new Key(owner, keyJob), runAt);


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
        if ((server.getTickCount() % RECALL_CHECK_EVERY_TICKS) != 0) return;

        // We need to process per-online-owner, per-dimension timebase (use owner’s current level time)
        for (ServerPlayer ownerPlayer : server.getPlayerList().getPlayers()) {
            UUID owner = ownerPlayer.getUUID();
            if (!(ownerPlayer.level() instanceof ServerLevel sl)) continue;

             // --- GATE: if disabled, clean state + skip all spawning/recall logic ---
            if (!isEnabled(owner)) {
                RESPAWN_AT.entrySet().removeIf(e -> e.getKey().owner().equals(owner));
                MISSING_SINCE.entrySet().removeIf(e -> e.getKey().owner().equals(owner));
                try { ownerPlayer.removeEffect(ModEffects.RESPAWNING); } catch (Throwable ignored) {}
                continue;
            }

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
                    if (!hasAliveRetinue(sl, ownerPlayer, spec.jobId())) {
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

           ensureRetinueNearOwner(sl, ownerPlayer);


        }
    }

    private static void ensureRetinueNearOwner(ServerLevel sl, ServerPlayer ownerPlayer) {
        UUID owner = ownerPlayer.getUUID();
        Map<String, Spec> specs = OWNED.get(owner);
        if (specs == null || specs.isEmpty()) return;

        long now = sl.getGameTime();

        for (Spec spec : specs.values()) {
            String jobId = spec.jobId();

            // If this slot is currently blocked by a death timer, don't "replace" it early,
            // but we *can* still teleport if it's found loaded.
            boolean blocked = isBlocked(owner, jobId, now);

            // Try to find an ALIVE retinue entity for this slot (LOADED ones only)
            kingdomWorkerEntity ent = findLoadedRetinue(sl, owner, jobId, ownerPlayer);

            if (ent != null) {
                // Found it loaded -> clear missing timer
                MISSING_SINCE.remove(new Key(owner, jobId));

                // If far away -> teleport near owner (NO speed gating)
                double d2 = ent.distanceToSqr(ownerPlayer);
                if (d2 > (RECALL_DISTANCE * RECALL_DISTANCE)) {
                    ent.getNavigation().stop();
                    ent.teleportTo(ownerPlayer.getX() + 1.5, ownerPlayer.getY(), ownerPlayer.getZ() + 1.5);
                }
                continue;
            }

            // Not found (probably unloaded / different dimension / stuck) -> start missing timer
            Key key = new Key(owner, jobId);
            long since = MISSING_SINCE.getOrDefault(key, -1L);
            if (since < 0) {
                MISSING_SINCE.put(key, now);
                continue;
            }

            // If missing too long, and not blocked, spawn replacement near owner
            if (!blocked && (now - since) >= MISSING_GRACE_TICKS) {
                spawnOne(sl, ownerPlayer, spec);
                MISSING_SINCE.remove(key);
            }
        }
    }

    private static kingdomWorkerEntity findLoadedRetinue(ServerLevel sl, UUID owner, String jobId, ServerPlayer ownerPlayer) {
        // Only searches LOADED entities near the player.
        // If the retinue is outside sim distance, it won’t be found (that’s why we respawn after grace).
        var list = sl.getEntitiesOfClass(
                kingdomWorkerEntity.class,
                ownerPlayer.getBoundingBox().inflate(256), // keep this small-ish for performance
                w -> w.isRetinue()
                        && owner.equals(w.getOwnerUUID())
                        && jobId.equals(w.getJobId())
                        && w.isAlive()
        );

        return list.isEmpty() ? null : list.get(0);
    }



    // ------------------------------------------------------------
    // Spawn helpers
    // ------------------------------------------------------------
    private static void spawnOne(ServerLevel level, ServerPlayer owner, Spec spec) {
        // Create first (not positioned yet)
        kingdomWorkerEntity ent = WORKER_TYPE.create(level, EntitySpawnReason.EVENT);
        if (ent == null) {
            LOGGER.warn("[Kingdoms] Failed to create retinue entity owner={} job={}", owner.getUUID(), spec.jobId());
            return;
        }

        // Configure retinue fields
        ent.setRetinue(true);
        ent.setOwnerUUID(owner.getUUID());
        ent.setHomePos(null);

        ent.setJobId(spec.jobId());
        ent.setSkinId(spec.skinId());

        // Naming (your existing logic)
        String baseName = namePool.randomMedieval(level.getServer(), level.random);
        String prettyJob = switch (spec.jobId()) {
            case "general" -> "General";
            case "scribe" -> "Scribe";
            case "treasurer" -> "Treasurer";
            case "royal_guard" -> "Royal Guard";
            default -> (spec.jobId() == null || spec.jobId().isBlank())
                    ? ""
                    : spec.jobId().substring(0, 1).toUpperCase() + spec.jobId().substring(1);
        };
        ent.setCustomName(Component.literal(baseName + " (" + prettyJob + ")"));
        ent.setCustomNameVisible(true);

        // Pick a safe spawn position (AIR + headroom + noCollision)
        BlockPos pos = pickSafeRetinueSpawnPos(level, owner, ent);

        // Place + face roughly same direction as player
        ent.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);

        ent.setYRot(owner.getYRot());
        ent.setYHeadRot(owner.getYRot());
        try { ent.setXRot(0.0f); } catch (Throwable ignored) {}


        level.addFreshEntity(ent);
        LOGGER.info("[Kingdoms] Spawned retinue owner={} job={} at={}", owner.getUUID(), spec.jobId(), pos);
    }


    private static boolean hasAliveRetinue(ServerLevel level, ServerPlayer ownerPlayer, String jobId) {
        UUID owner = ownerPlayer.getUUID();
        return !level.getEntitiesOfClass(
                kingdomWorkerEntity.class,
                ownerPlayer.getBoundingBox().inflate(256),
                w -> w.isRetinue()
                        && owner.equals(w.getOwnerUUID())
                        && jobId.equals(w.getJobId())
                        && w.isAlive()
        ).isEmpty();
    }

    private static BlockPos pickSafeRetinueSpawnPos(ServerLevel level, ServerPlayer owner, kingdomWorkerEntity ent) {
        BlockPos base = owner.blockPosition();

        // Try a bunch of random spots around the player
        for (int attempt = 0; attempt < 48; attempt++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0;
            double r = 1.5 + level.random.nextDouble() * 4.5; // 1.5..6.0 blocks

            int dx = (int) Math.round(Math.cos(angle) * r);
            int dz = (int) Math.round(Math.sin(angle) * r);

            BlockPos p = base.offset(dx, 0, dz);

            // Try small Y adjustments near player's Y
            for (int dy = 2; dy >= -2; dy--) {
                BlockPos feet = p.offset(0, dy, 0);
                BlockPos head = feet.above();
                BlockPos below = feet.below();

                // Must not be inside fluids
                if (!level.getFluidState(feet).isEmpty()) continue;
                if (!level.getFluidState(head).isEmpty()) continue;

                // Feet+head must have empty collision shapes
                if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()) continue;
                if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()) continue;

                // Need something to stand on
                if (level.getBlockState(below).getCollisionShape(level, below).isEmpty()) continue;

                // FULL entity bounding-box collision check
                ent.setPos(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                if (!level.noCollision(ent)) continue;

                return feet;
            }
        }

        // Fallback: player's position +1,+1 if everything fails
        BlockPos fallback = base.offset(1, 0, 1);
        // Try to not suffocate on fallback
        ent.setPos(fallback.getX() + 0.5, fallback.getY(), fallback.getZ() + 0.5);
        return fallback;
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

    public static void despawnAllLoaded(MinecraftServer server, UUID owner) {
        for (ServerLevel sl : server.getAllLevels()) {

            var list = sl.getEntitiesOfClass(
                    kingdomWorkerEntity.class,
                    new net.minecraft.world.phys.AABB(-3.0E7, -2048, -3.0E7, 3.0E7, 4096, 3.0E7),
                    w -> w.isRetinue() && owner.equals(w.getOwnerUUID())
            );

            for (kingdomWorkerEntity w : list) {
                // IMPORTANT: remember slot before removing
                rememberOwned(owner, w.getJobId(), w.getSkinId(), prettyNameFromJob(w.getJobId()));

                try { w.forceDespawnRetinueHorse(sl); } catch (Throwable ignored) {}
                w.discard();
            }

            // mounts cleanup (optional)
            var horses = sl.getEntitiesOfClass(
                    net.minecraft.world.entity.animal.horse.Horse.class,
                    new net.minecraft.world.phys.AABB(-3.0E7, -2048, -3.0E7, 3.0E7, 4096, 3.0E7),
                    h -> h.getTags().contains("kingdoms_retinue_mount")
                    && h.getTags().contains("kingdoms_owner:" + owner)
            );
            for (var h : horses) h.discard();
        }
    }

    public static void spawnOwnedNow(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel sl)) return;

        UUID owner = player.getUUID();
        if (!isEnabled(owner)) return;

        Map<String, Spec> specs = OWNED.get(owner);
        if (specs == null || specs.isEmpty()) return;

        long now = sl.getGameTime();

        for (Spec spec : specs.values()) {
            if (isBlocked(owner, spec.jobId(), now)) continue;

            // Use your existing "near owner" check to avoid duplicates
            if (hasAliveRetinue(sl, player, spec.jobId())) continue;

            spawnOne(sl, player, spec);
        }
    }



    private static final Map<UUID, Boolean> ENABLED = new HashMap<>();

    public static boolean isEnabled(UUID owner) {
        return ENABLED.getOrDefault(owner, true);
    }

    public static void setEnabled(MinecraftServer server, UUID owner, boolean enabled) {
        ENABLED.put(owner, enabled);

        if (!enabled) {
            // cancel pending respawns + missing timers
            RESPAWN_AT.entrySet().removeIf(e -> e.getKey().owner().equals(owner));
            MISSING_SINCE.entrySet().removeIf(e -> e.getKey().owner().equals(owner));

            // remove effect if present
            ServerPlayer sp = server.getPlayerList().getPlayer(owner);
            if (sp != null) {
                try { sp.removeEffect(ModEffects.RESPAWNING); } catch (Throwable ignored) {}
            }

            // and despawn everything loaded
            despawnAllLoaded(server, owner);
        }
    }

    private static BlockPos pickRetinueSpawnPos(ServerLevel level, ServerPlayer owner) {
    BlockPos base = owner.blockPosition();

    // Try a ring around the player; fall back to next to them.
    for (int attempt = 0; attempt < 16; attempt++) {
        double angle = level.random.nextDouble() * Math.PI * 2.0;
        double r = 1.5 + level.random.nextDouble() * 3.0; // 1.5..4.5 blocks away

        int dx = (int) Math.round(Math.cos(angle) * r);
        int dz = (int) Math.round(Math.sin(angle) * r);

        BlockPos p = base.offset(dx, 0, dz);

        // Adjust Y slightly to find a 2-block-tall empty space near the player's Y
        for (int dy = 2; dy >= -2; dy--) {
            BlockPos tryPos = p.offset(0, dy, 0);

            // Needs two air blocks to fit a standing mob
            boolean airFeet = level.getBlockState(tryPos).getCollisionShape(level, tryPos).isEmpty();
            boolean airHead = level.getBlockState(tryPos.above()).getCollisionShape(level, tryPos.above()).isEmpty();

            // Needs something solid-ish under feet
            BlockPos below = tryPos.below();
            boolean solidBelow = !level.getBlockState(below).getCollisionShape(level, below).isEmpty();

            if (airFeet && airHead && solidBelow) {
                return tryPos;
            }
        }
    }

    return base.offset(1, 0, 1);
}



}
