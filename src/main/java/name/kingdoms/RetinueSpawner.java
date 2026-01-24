package name.kingdoms;

import name.kingdoms.entity.kingdomWorkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.levelgen.Heightmap;

import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public final class RetinueSpawner {
    private RetinueSpawner() {}

    public static void ensureRetinue(ServerLevel level, ServerPlayer owner, kingdomState.Kingdom k) {
        // Debug log
        System.out.println("[Kingdoms] ensureRetinue owner=" + owner.getName().getString()
                + " kingdom=" + (k == null ? "null" : k.id)
                + " scribe=" + (k == null ? null : k.retinueScribe)
                + " treasurer=" + (k == null ? null : k.retinueTreasurer)
                + " general=" + (k == null ? null : k.retinueGeneral));

        if (k == null) return;

        // Ensure ONLY one of each per owner
        k.retinueScribe = ensureOne(level, owner, k.retinueScribe, "scribe");
        k.retinueTreasurer = ensureOne(level, owner, k.retinueTreasurer, "treasurer");
        k.retinueGeneral = ensureOne(level, owner, k.retinueGeneral, "general");

        // If your kingdom is SavedData-backed, make sure whoever owns this data calls markDirty().
        // (Often you do ks.markDirty() in the block that calls ensureRetinue.)
    }

    /**
     * Ensures exactly ONE retinue entity exists for (owner, jobId).
     * Order:
     *  1) If stored UUID is loaded+alive -> keep it.
     *  2) Else search nearby for any already-existing matching entity -> adopt it.
     *  3) Else spawn a new one.
     */
    private static UUID ensureOne(ServerLevel level, ServerPlayer owner, UUID existing, String jobId) {
        // 1) Fast path: stored UUID is currently loaded
        if (existing != null) {
            var ent = level.getEntity(existing);
            if (ent instanceof kingdomWorkerEntity w && w.isAlive()) {
                rebind(w, owner, jobId);
                return existing;
            }
        }

        // 2) Search nearby for a matching (owner+job) retinue and adopt it
        kingdomWorkerEntity found = findExisting(level, owner.getUUID(), jobId, 256);
        if (found != null) {
            rebind(found, owner, jobId);
            return found.getUUID();
        }

        // 3) None exists -> spawn new
        kingdomWorkerEntity spawned = spawn(level, owner, jobId);
        return spawned == null ? null : spawned.getUUID();
    }

    // ----------------------------
    // Find/adopt helpers
    // ----------------------------

    private static kingdomWorkerEntity findExisting(ServerLevel level, UUID ownerId, String jobId, double radius) {
        AABB box = ownerSearchBox(level, ownerId, radius);

        List<kingdomWorkerEntity> list = level.getEntitiesOfClass(
                kingdomWorkerEntity.class,
                box,
                w -> w.isAlive()
                        && w.isRetinue()
                        && ownerId.equals(w.getOwnerUUID())
                        && jobId.equals(w.getJobId())
        );

        return list.isEmpty() ? null : list.get(0);
    }

    public static void setEnabled(ServerPlayer owner, boolean enabled) {
        if (!enabled) {
            // whatever you currently do to despawn/disband retinue
            despawnAll(owner);
        } else {
            // whatever you currently do to spawn/restore retinue
            spawnOrRestore(owner);
        }
    }

    

    private static AABB ownerSearchBox(ServerLevel level, UUID ownerId, double r) {
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(ownerId);
        double x = (p != null) ? p.getX() : 0;
        double z = (p != null) ? p.getZ() : 0;
        return new AABB(x - r, -64, z - r, x + r, 320, z + r);
    }

    private static void rebind(kingdomWorkerEntity w, ServerPlayer owner, String jobId) {
        // Re-assert (helps after reload, fixes “adopted” retinue)
        w.setRetinue(true);
        w.setOwnerUUID(owner.getUUID());
        w.setJobId(jobId);
        w.setHomePos(null);
    }

    // ----------------------------
    // Spawn
    // ----------------------------

    private static kingdomWorkerEntity spawn(ServerLevel level, ServerPlayer owner, String jobId) {
        var type = Kingdoms.KINGDOM_WORKER_ENTITY_TYPE;
        if (type == null) {
            System.out.println("[Kingdoms] RetinueSpawner: KINGDOM_WORKER_ENTITY_TYPE is null");
            return null;
        }

        BlockPos spawnPos = level.getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                owner.blockPosition()
        );

        kingdomWorkerEntity mob = type.create(
                level,
                (Consumer<kingdomWorkerEntity>) e -> {},
                spawnPos,
                EntitySpawnReason.EVENT,
                false,
                false
        );

        if (mob == null) {
            System.out.println("[Kingdoms] RetinueSpawner: failed to create entity for jobId=" + jobId);
            return null;
        }

        mob.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);

        // Retinue binding
        mob.setRetinue(true);
        mob.setOwnerUUID(owner.getUUID());
        mob.setHomePos(null);

        // Job id drives your renderer (textures/entity/worker/<jobId>.png)
        mob.setJobId(jobId);

        // Name from medieval list
        String baseName = namePool.randomMedieval(level.getServer(), level.random);

        // Pretty job label (no translation key dependency)
        String prettyJob = switch (jobId) {
            case "general" -> "General";
            case "scribe" -> "Scribe";
            case "treasurer" -> "Treasurer";
            default -> (jobId == null || jobId.isBlank())
                    ? ""
                    : jobId.substring(0, 1).toUpperCase() + jobId.substring(1);
        };

        mob.setCustomName(
                Component.literal(baseName + " (" + prettyJob + ")")
        );
        mob.setCustomNameVisible(true);

        level.addFreshEntity(mob);

        System.out.println("[Kingdoms] RetinueSpawner: spawned " + jobId + " uuid=" + mob.getUUID()
                + " at " + spawnPos.getX() + "," + spawnPos.getY() + "," + spawnPos.getZ());

        return mob;
    }

    public static void recallNow(ServerPlayer owner) {
        // "Recall" should be safe even if disabled; just respawn/teleport when enabled.
        // We'll implement it as "despawn then restore" so you never get duplicates.
        despawnAll(owner);
        spawnOrRestore(owner);
    }

    /** Removes retinue entities and clears UUIDs from the owner's Kingdom record. */
    private static void despawnAll(ServerPlayer owner) {
        if (!(owner.level() instanceof ServerLevel sl)) return;

        var server = sl.getServer();
        var ks = kingdomState.get(server);
        var k = ks.getPlayerKingdom(owner.getUUID());
        if (k == null) return;

        // discard in all levels (retinue might be in another dimension)
        discardIfPresent(server, k.retinueScribe);
        discardIfPresent(server, k.retinueTreasurer);
        discardIfPresent(server, k.retinueGeneral);

        k.retinueScribe = null;
        k.retinueTreasurer = null;
        k.retinueGeneral = null;

        ks.markDirty();
    }

    /** Ensures the retinue exists again (spawns if missing). */
    private static void spawnOrRestore(ServerPlayer owner) {
        if (!(owner.level() instanceof ServerLevel sl)) return;

        var server = sl.getServer();
        var ks = kingdomState.get(server);
        var k = ks.getPlayerKingdom(owner.getUUID());
        if (k == null) return;

        ensureRetinue(sl, owner, k);
        ks.markDirty();
    }

    /** Finds an entity by UUID across all dimensions and discards it if it's a retinue worker. */
    private static void discardIfPresent(net.minecraft.server.MinecraftServer server, UUID id) {
        if (id == null) return;

        for (ServerLevel level : server.getAllLevels()) {
            var e = level.getEntity(id);
            if (e instanceof kingdomWorkerEntity w) {
                // Extra safety: only discard if it's actually a retinue mob
                if (w.isAlive() && w.isRetinue()) {
                    w.discard();
                }
            }
        }
    }

    
}
