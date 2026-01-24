package name.kingdoms;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.*;

public final class ThroneSeatManager {
    private ThroneSeatManager() {}

    private static final String SEAT_TAG = "kingdoms_throne_seat";

    // Track "just pressed shift"
    private static final Map<UUID, Boolean> LAST_SNEAK = new HashMap<>();
    private static final Map<UUID, Float> FIX_YAW_NEXT_TICK = new HashMap<>();

    // Seat cleanup: seatUUID -> dimension
    private static final Map<UUID, ResourceKey<net.minecraft.world.level.Level>> SEAT_DIM = new HashMap<>();

    // Pending sit requests (to avoid mounting while sneaking)
    private record PendingSit(ResourceKey<net.minecraft.world.level.Level> dim, BlockPos thronePos, int ticksLeft) {}
    private static final Map<UUID, PendingSit> PENDING = new HashMap<>();

    private static boolean HOOKED = false;
    private static int scanTicker = 0;

    public static void hook() {
        if (HOOKED) return;
        HOOKED = true;
        ServerTickEvents.END_SERVER_TICK.register(ThroneSeatManager::tick);
    }

    private static void tick(MinecraftServer server) {
        // Detect shift edge -> queue sit request
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            boolean sneaking = sp.isShiftKeyDown();
            boolean last = LAST_SNEAK.getOrDefault(sp.getUUID(), false);
            LAST_SNEAK.put(sp.getUUID(), sneaking);

            if (sneaking && !last) {
                queueSitIfOnThrone(sp);
            }
            applyRotationFixes(server);

        }

        // Process pending sit requests: mount only when NOT sneaking (avoids instant dismount)
        processPending(server);

        // Cleanup seats we spawned when empty
        cleanupKnownSeats(server);

        // Safety sweep for tagged seats (rarely needed but cheap enough)
        scanTicker++;
        if (scanTicker >= 20 * 5) {
            scanTicker = 0;
            sweepTaggedSeats(server);
        }
    }

    private static void queueSitIfOnThrone(ServerPlayer sp) {
        if (!(sp.level() instanceof ServerLevel sl)) return;
        if (sp.isSpectator()) return;
        if (sp.getVehicle() != null) return;

        BlockPos below = sp.blockPosition().below();
        if (!sl.getBlockState(below).is(modBlock.kingdom_block)) return;

        // Give a short window (e.g. 10 ticks = 0.5s) to release shift and mount
        PENDING.put(sp.getUUID(), new PendingSit(sl.dimension(), below, 10));
    }

    private static void processPending(MinecraftServer server) {
        Iterator<Map.Entry<UUID, PendingSit>> it = PENDING.entrySet().iterator();

        while (it.hasNext()) {
            var e = it.next();
            UUID playerId = e.getKey();
            PendingSit ps = e.getValue();

            ServerPlayer sp = server.getPlayerList().getPlayer(playerId);
            if (sp == null) { it.remove(); continue; }

            // expire
            if (ps.ticksLeft <= 0) { it.remove(); continue; }

            // still holding shift? wait (this is the key fix)
            if (sp.isShiftKeyDown()) {
                PENDING.put(playerId, new PendingSit(ps.dim, ps.thronePos, ps.ticksLeft - 1));
                continue;
            }

            // must still be eligible
            if (sp.getVehicle() != null || sp.isSpectator()) { it.remove(); continue; }
            if (!(sp.level() instanceof ServerLevel sl) || !sl.dimension().equals(ps.dim)) { it.remove(); continue; }

            // must still be standing on the throne block
            BlockPos below = sp.blockPosition().below();
            if (!below.equals(ps.thronePos)) { it.remove(); continue; }
            if (!sl.getBlockState(below).is(modBlock.kingdom_block)) { it.remove(); continue; }

            // spawn an invisible marker armor stand as the "seat"
            spawnSeatAndMount(sp, sl, below);

            it.remove();
        }
    }

    private static void spawnSeatAndMount(ServerPlayer sp, ServerLevel sl, BlockPos thronePos) {
        double x = thronePos.getX() + 0.5;
        double y = thronePos.getY() - 1.45; // further down
        double z = thronePos.getZ() + 0.5;

        ArmorStand seat = new ArmorStand(EntityType.ARMOR_STAND, sl);
        seat.setPos(x, y, z);

        seat.setNoGravity(true);
        seat.setInvisible(true);
        seat.setInvulnerable(true);
        seat.setSilent(true);
        seat.setCustomNameVisible(false);
        seat.addTag(SEAT_TAG);

        float yaw = Mth.wrapDegrees(sp.getYRot() + 180.0f); // rotate 180, wrapped
        seat.setYRot(yaw);
        seat.setYHeadRot(yaw);
        seat.setXRot(0.0f);

        sl.addFreshEntity(seat);

        sp.startRiding(seat, true, true);

        // force yaw next tick so it doesn't snap at +/-180
        FIX_YAW_NEXT_TICK.put(sp.getUUID(), yaw);

        SEAT_DIM.put(seat.getUUID(), sl.dimension());
    }


    private static void applyRotationFixes(MinecraftServer server) {
        if (FIX_YAW_NEXT_TICK.isEmpty()) return;

        Iterator<Map.Entry<UUID, Float>> it = FIX_YAW_NEXT_TICK.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            ServerPlayer sp = server.getPlayerList().getPlayer(e.getKey());
            if (sp != null) {
                float yaw = e.getValue();
                sp.setYRot(yaw);
                sp.setYHeadRot(yaw);
                sp.setXRot(0.0f);
            }
            it.remove();
        }
    }



    private static void cleanupKnownSeats(MinecraftServer server) {
        Iterator<Map.Entry<UUID, ResourceKey<net.minecraft.world.level.Level>>> it = SEAT_DIM.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            UUID seatId = e.getKey();
            ResourceKey<net.minecraft.world.level.Level> dim = e.getValue();

            ServerLevel level = server.getLevel(dim);
            if (level == null) { it.remove(); continue; }

            Entity seat = level.getEntity(seatId);
            if (seat == null || !seat.isAlive()) { it.remove(); continue; }

            if (seat.getPassengers().isEmpty()) {
                seat.discard();
                it.remove();
            }
        }
    }

    private static void sweepTaggedSeats(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity ent : level.getAllEntities()) {
                if (ent.getType() == EntityType.ARMOR_STAND && ent.getTags().contains(SEAT_TAG)) {
                    if (ent.getPassengers().isEmpty()) ent.discard();
                }
            }
        }
    }
}
