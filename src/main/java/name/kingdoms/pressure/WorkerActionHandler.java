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

            // ---- Pace ----
            case "DOUBLE_PACE" -> applyTemplate(server, pk.id, pk.id, PressureCatalog.DOUBLE_PACE(), player,
                    "You order a double pace. Output rises; resentment grows.");

            case "LEISURELY_PACE" -> applyTemplate(server, pk.id, pk.id, PressureCatalog.LEISURELY_PACE(), player,
                    "You allow a leisurely pace. Output slows; spirits lift.");

            // ---- Patrols ----
            case "INCREASE_PATROLS" -> applyTemplate(server, pk.id, pk.id, PressureCatalog.INCREASE_PATROLS_POLICY(), player,
                    "You increase patrols. Streets feel safer.");

            case "DECREASE_PATROLS" -> applyTemplate(server, pk.id, pk.id, PressureCatalog.DECREASE_PATROLS_POLICY(), player,
                    "You decrease patrols. Coin is saved; crime rises.");

            // ---- Rations ----
            case "DOUBLE_RATIONS" -> applyTemplate(server, pk.id, pk.id, PressureCatalog.DOUBLE_RATIONS(), player,
                    "You double rations. Readiness improves; stores shrink.");

            case "HALVE_RATIONS" -> applyTemplate(server, pk.id, pk.id, PressureCatalog.HALVE_RATIONS(), player,
                    "You halve rations. Readiness suffers; stores recover.");

            // ---- Tavern ----
            case "ALCOHOL_SUBSIDIES" -> applyTemplate(server, pk.id, pk.id, PressureCatalog.ALCOHOL_SUBSIDIES(), player,
                    "You subsidize drink. Morale rises; coffers drain.");

            case "DRUNK_CRACKDOWNS" -> applyTemplate(server, pk.id, pk.id, PressureCatalog.DRUNK_CRACKDOWNS(), player,
                    "You crack down on drink. Morale falls; productivity improves.");

            // ---- Chapel ----
            case "FREQUENT_SERVICES" -> applyTemplate(server, pk.id, pk.id, PressureCatalog.FREQUENT_SERVICES(), player,
                    "You mandate frequent services. Unity rises; productivity falls.");

            case "PAPAL_AUTHORITY" -> applyTemplate(server, pk.id, pk.id, PressureCatalog.PAPAL_AUTHORITY(), player,
                    "You invoke papal authority. Courts take notice.");

            // ---- Nobility ----
            case "DIPLOMATIC_ENVOYS" -> applyTemplate(server, pk.id, pk.id, PressureCatalog.DIPLOMATIC_ENVOYS(), player,
                    "You dispatch envoys. Relations improve.");


            case "VASSAL_CONTRIBUTIONS" -> applyTemplate(server, pk.id, pk.id, PressureCatalog.VASSAL_CONTRIBUTIONS(), player,
                    "You demand vassal contributions. Coin rises; resentment grows.");

            // ---- Shop ----
            case "MARKET_SUBSIDIES" -> applyTemplate(server, pk.id, pk.id, PressureCatalog.MARKET_SUBSIDIES(), player,
                    "You subsidize the market. Goodwill rises; profits fall.");

            case "CONTRABAND_CRACKDOWNS" -> applyTemplate(server, pk.id, pk.id, PressureCatalog.CONTRABAND_CRACKDOWNS(), player,
                    "You crack down on contraband. Security rises; goodwill falls.");

            default -> { }
        }

    }

    private static void applyTemplate(MinecraftServer server,
                                  UUID causer, UUID causee,
                                  PressureCatalog.Template tpl,
                                  ServerPlayer player,
                                  String msg) {

    if (tpl == null) return;

    long now = server.getTickCount();
    var ps = KingdomPressureState.get(server);

    // GLOBAL per-type cooldown: cannot trigger same type again anywhere until it expires
    if (ps.hasActiveByCauser(causer, tpl.typeId(), now)) {
        if (player != null) player.sendSystemMessage(Component.literal("That action is already in effect."));
        return;
    }


    if (!shouldAllowEventTarget(server, causee)) return;

    String newType = tpl.typeId();
    String group = policyGroupForTypeId(newType);

    
    var list = ps.getOrCreateEventsMutable(causee);
    if (list == null) return;

   
    list.removeIf(e -> e == null || now >= e.endTick());
    ps.markDirty();


    // 1) same policy already active -> no-op
    for (var e : list) {
        if (e == null) continue;
        if (newType.equals(e.typeId())) {
            if (player != null) player.sendSystemMessage(Component.literal("That policy is already in effect."));
            return;
        }
    }

    // 2) mutually exclusive within group -> remove other policies in same group
    if (!group.isEmpty()) {
        list.removeIf(e -> e != null
                && group.equals(policyGroupForTypeId(e.typeId()))
                && !newType.equals(e.typeId()));
    }

    ps.markDirty();

 
    // 3) add new policy (cooldown until expire)
    UUID added = ps.tryAddEvent(
            causer,
            causee,
            newType,
            tpl.effects(),
            KingdomPressureState.RelScope.GLOBAL,
            now,
            tpl.durationTicks()
    );

    if (added == null) {
        if (player != null) player.sendSystemMessage(Component.literal("That policy is already in effect."));
        return;
    }


    // push updated events UI
    try { KingdomEventsNet.sendMyKingdomEvents(server, player); } catch (Throwable ignored) {}

    if (player != null && msg != null && !msg.isBlank()) {
        player.sendSystemMessage(Component.literal(msg));
    }
}


    private static String policyGroupForTypeId(String typeId) {
        if (typeId == null) return "";
        return switch (typeId) {
            case "double_pace", "leisurely_pace" -> "PACE";
            case "increase_patrols", "decrease_patrols" -> "PATROLS";
            case "double_rations", "halve_rations" -> "RATIONS";
            case "alcohol_subsidies", "drunk_crackdowns" -> "TAVERN";
            case "frequent_services", "papal_authority" -> "CHAPEL";
            case "diplomatic_envoys", "vassal_contributions" -> "NOBILITY";
            case "market_subsidies", "contraband_crackdowns" -> "SHOP";
            default -> "";
        };
    }

    private static void applyRelToAllKingdoms(MinecraftServer server, UUID fromKingdomId,
                                            PressureCatalog.Template tpl,
                                            ServerPlayer player,
                                            String msg) {
        if (server == null || fromKingdomId == null || tpl == null) return;

        long now = server.getTickCount();
        var ps = KingdomPressureState.get(server);
        // GLOBAL per-type cooldown: cannot trigger same type again anywhere until it expires
        if (ps.hasActiveByCauser(fromKingdomId, tpl.typeId(), now)) {
            if (player != null) player.sendSystemMessage(Component.literal("That action is already in effect."));
            return;
        }

        var ks = kingdomState.get(server);

        // Apply to every OTHER kingdom that exists in kingdomState
        boolean appliedAny = false;
        boolean skippedAny = false;

        // Apply to every OTHER kingdom that exists in kingdomState
        for (var k : ks.getAllKingdoms()) {
            if (k == null || k.id == null) continue;
            if (k.id.equals(fromKingdomId)) continue;

            // Gate AI: only apply to known AI (so you don't generate invisible noise)
            if (aiKingdomState.get(server).getById(k.id) != null && !ps.isKnownAi(k.id)) continue;

            UUID added = ps.tryAddEvent(
                    fromKingdomId,                 // causer
                    k.id,                          // causee
                    tpl.typeId(),
                    tpl.effects(),
                    KingdomPressureState.RelScope.CAUSER_ONLY,
                    now,
                    tpl.durationTicks()
            );

            if (added != null) appliedAny = true;
            else skippedAny = true; // already active for that target
        }

        if (player != null) {
        if (!appliedAny) {
            player.sendSystemMessage(Component.literal("That action is already in effect."));
            return;
        }

        if (msg != null && !msg.isBlank()) {
            // Optional: you can mention partial application if some were already active
            if (skippedAny) {
                player.sendSystemMessage(Component.literal(msg + " (Some kingdoms were already affected.)"));
            } else {
                player.sendSystemMessage(Component.literal(msg));
            }
        }
    }


        // Optional: push events UI refresh
        try { KingdomEventsNet.sendMyKingdomEvents(server, player); } catch (Throwable ignored) {}
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

        // tax is always allowed if you are king + daily gate passes
        if ("TAX".equals(actionId)) return true;

        // Pace policy
        if ("DOUBLE_PACE".equals(actionId) || "LEISURELY_PACE".equals(actionId)) {
            return "butcher".equals(j) || "farm".equals(j) || "fishing".equals(j)
                    || "wood".equals(j) || "metal".equals(j) || "gem".equals(j)
                    || "alchemy".equals(j) || "weapon".equals(j) || "armor".equals(j);
        }


        // Patrol policy
        if ("INCREASE_PATROLS".equals(actionId) || "DECREASE_PATROLS".equals(actionId)) {
            return "guard".equals(j);
        }

        // Rations policy
        if ("DOUBLE_RATIONS".equals(actionId) || "HALVE_RATIONS".equals(actionId)) {
            return "garrison".equals(j) || "guard".equals(j) || "training".equals(j);
        }


        // Tavern policy
        if ("ALCOHOL_SUBSIDIES".equals(actionId) || "DRUNK_CRACKDOWNS".equals(actionId)) {
            return "tavern".equals(j);
        }

        // Chapel policy
        if ("FREQUENT_SERVICES".equals(actionId) || "PAPAL_AUTHORITY".equals(actionId)) {
            return "chapel".equals(j);
        }


        // Nobility policy
        if ("DIPLOMATIC_ENVOYS".equals(actionId) || "VASSAL_CONTRIBUTIONS".equals(actionId)) {
            return "nobility".equals(j);
        }


        // Shop policy
        if ("MARKET_SUBSIDIES".equals(actionId) || "CONTRABAND_CRACKDOWNS".equals(actionId)) {
            return "shop".equals(j);
        }

        return false;
    }

}
