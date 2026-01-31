package name.kingdoms.ambient;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public final class AmbientPropManager {
    private AmbientPropManager() {}

    private record Replaced(ServerLevel level, BlockPos pos, BlockState prev) {}
    private record ActiveProp(long expireTick, ServerLevel level, List<Replaced> replaced, List<UUID> entityIds) {}


    private static final Map<UUID, ActiveProp> ACTIVE = new HashMap<>();

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(AmbientPropManager::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(AmbientPropManager::onServerStopping);
    }

    /** Places blocks, records replaced states, and schedules restore. Returns a handle id or null if failed. */
    public static UUID place(ServerLevel level, Map<BlockPos, BlockState> absBlocks, int ttlTicks) {
        if (absBlocks == null || absBlocks.isEmpty()) return null;

        List<Replaced> replaced = new ArrayList<>(absBlocks.size());

        // snapshot first
        for (var e : absBlocks.entrySet()) {
            BlockPos p = e.getKey();
            replaced.add(new Replaced(level, p, level.getBlockState(p)));
        }

        // place
        for (var e : absBlocks.entrySet()) {
            level.setBlockAndUpdate(e.getKey(), e.getValue());
        }

        UUID id = UUID.randomUUID();
        long expire = level.getServer().getTickCount() + Math.max(20, ttlTicks);
        ACTIVE.put(id, new ActiveProp(expire, level, replaced, new ArrayList<>()));
        return id;

    }

    /** Register an entity to be cleaned up when the prop scene expires. */
    public static void registerEntity(UUID sceneId, Entity e) {
        if (sceneId == null || e == null) return;
        ActiveProp ap = ACTIVE.get(sceneId);
        if (ap == null) return;
        ap.entityIds.add(e.getUUID());
    }


    private static void tick(MinecraftServer server) {
        long now = server.getTickCount();

        Iterator<Map.Entry<UUID, ActiveProp>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            ActiveProp ap = entry.getValue();

            // While active: stop any registered living entities from showing on fire
            for (UUID eid : ap.entityIds) {
                Entity ent = ap.level.getEntity(eid);
                if (ent instanceof LivingEntity le) {
                    le.setRemainingFireTicks(0);
                }
            }

            if (now < ap.expireTick) continue;


            // remove registered entities first
            for (UUID eid : ap.entityIds) {
                Entity ent = ap.level.getEntity(eid);
                if (ent != null) ent.discard();
            }

            // restore blocks
            for (Replaced r : ap.replaced) {
                r.level.setBlockAndUpdate(r.pos, r.prev);
            }

            it.remove();

        }
    }

    private static void onServerStopping(MinecraftServer server) {
        restoreAll();
        
    }

    private static void restoreAll() {
        for (ActiveProp ap : ACTIVE.values()) {

            // --- REMOVE ENTITIES ---
            for (UUID eid : ap.entityIds) {
                Entity ent = ap.level.getEntity(eid);
                if (ent != null) ent.discard();
            }

            // --- RESTORE BLOCKS ---
            for (Replaced r : ap.replaced) {
                r.level.setBlockAndUpdate(r.pos, r.prev);
            }
        }
        ACTIVE.clear();
    }


}
