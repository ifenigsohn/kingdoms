package name.kingdoms.pressure;

import name.kingdoms.aiKingdomState;
import name.kingdoms.kingdomState;
import name.kingdoms.entity.kingdomWorkerEntity;
import name.kingdoms.payload.WorkerActionC2SPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.*;

public final class WorkerActionHandler {
    private WorkerActionHandler() {}

    // anti-spam per player
    private static final Map<UUID, Long> CD = new HashMap<>();
    private static final long CD_TICKS = 20L * 2L; // 2s

    public static void handle(MinecraftServer server, ServerPlayer player, WorkerActionC2SPayload payload) {
        if (server == null || player == null || payload == null) return;
        if (!(player.level() instanceof ServerLevel sl)) return;

        long now = server.getTickCount();
        long until = CD.getOrDefault(player.getUUID(), 0L);
        if (now < until) return;
        CD.put(player.getUUID(), now + CD_TICKS);

        Entity ent = sl.getEntity(payload.entityId());
        if (!(ent instanceof kingdomWorkerEntity w)) return;
        if (!w.getUUID().equals(payload.entityUuid())) return;
        if (player.distanceToSqr(w) > 100.0) return; // 10 blocks

        // must have a player kingdom
        var ks = kingdomState.get(server);
        var pk = ks.getPlayerKingdom(player.getUUID());
        if (pk == null) return;

        // resolve worker's kingdom
        UUID workerKid = w.getKingdomUUID();
        if (workerKid == null) {
            var at = ks.getKingdomAt(sl, w.blockPosition());
            if (at != null) workerKid = at.id;
        }
        if (workerKid == null) return;

        var workerK = ks.getKingdom(workerKid);
        if (workerK == null) return;

        // only ruler can manage worker actions (for now)
        if (workerK.owner == null || !workerK.owner.equals(player.getUUID())) {
            player.sendSystemMessage(Component.literal("They do not answer to you."));
            return;
        }

        String actionId = (payload.actionId() == null) ? "" : payload.actionId().trim();
        if (actionId.isEmpty()) return;

        // Allow-list by job (prevents spoofing)
        if (!allowedForJob(w.getJobId(), actionId)) return;

        switch (actionId) {
            case "TAX" -> {
                boolean ok = w.tryPayTaxTo(sl, player, workerK);
                if (ok) ks.markDirty();
            }

            case "PUSH_PRODUCTION" -> applyTemplate(server, pk.id, pk.id, PressureCatalog.PUSH_PRODUCTION(), player,
                    "You push production. The workers grumble.");

            case "EASE_WORKLOAD" -> applyTemplate(server, pk.id, pk.id, PressureCatalog.EASE_WORKLOAD(), player,
                    "You ease the workload. Spirits lift, output slows.");

            case "INCREASE_PATROLS" -> applyTemplate(server, pk.id, pk.id, PressureCatalog.INCREASE_PATROLS(), player,
                    "You order more patrols. The streets feel safer.");

            default -> {
                // unknown
            }
        }
    }

    private static void applyTemplate(MinecraftServer server,
                                      UUID causer, UUID causee,
                                      PressureCatalog.Template tpl,
                                      ServerPlayer player,
                                      String msg) {

        if (tpl == null) return;

        long now = server.getTickCount();

        // Gate: only apply to player kingdoms or known AI kingdoms (PressureUtil already gates relation,
        // but events should be gated too so we don't accumulate invisible noise)
        if (!shouldAllowEventTarget(server, causee)) return;

        var ps = KingdomPressureState.get(server);

        ps.addEvent(
            causer,
            causee,
            tpl.typeId(),
            tpl.effects(),
            KingdomPressureState.RelScope.GLOBAL,
            now,
            tpl.durationTicks()
        );

        KingdomEventsNet.sendMyKingdomEvents(server, player);

        // DEBUG: prove it's in state
        int n = ps.getEvents(causee).size();
        if (player != null) {
            if (msg != null && !msg.isBlank()) player.sendSystemMessage(Component.literal(msg));
            player.sendSystemMessage(Component.literal("[DEBUG] Active events on kingdom=" + causee + " -> " + n));
        }


    }

    /** For now: only player kingdom targets or known AI. */
    private static boolean shouldAllowEventTarget(MinecraftServer server, UUID kingdomId) {
        if (kingdomId == null) return false;

        var ai = aiKingdomState.get(server);
        boolean isAi = (ai.getById(kingdomId) != null);

        if (!isAi) return true; // player kingdom

        return KingdomPressureState.get(server).isKnownAi(kingdomId);
    }

    private static boolean allowedForJob(String job, String actionId) {
        String j = (job == null) ? "" : job;

        // tax is always allowed *if king + daily check passes*
        if ("TAX".equals(actionId)) return true;

        // worker actions
        if ("PUSH_PRODUCTION".equals(actionId) || "EASE_WORKLOAD".equals(actionId)) {
            return "farmer".equals(j) || "blacksmith".equals(j) || "fisherman".equals(j)
                    || "woodcutter".equals(j) || "miner".equals(j) || "trader".equals(j)
                    || "unknown".equals(j);
        }

        // security actions
        if ("INCREASE_PATROLS".equals(actionId)) {
            return "guard".equals(j) || "soldier".equals(j);
        }

        return false;
    }
}
