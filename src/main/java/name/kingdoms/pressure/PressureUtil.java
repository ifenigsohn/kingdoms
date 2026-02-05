package name.kingdoms.pressure;

import name.kingdoms.aiKingdomState;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

public final class PressureUtil {
    private PressureUtil() {}

    @Deprecated
    public static int effectiveRelation(MinecraftServer server, int baseRel, UUID toKingdomId) {
        // Optional: temporary logging to find missed call sites
        System.out.println("[PressureUtil] WARNING old effectiveRelation() used; CAUSER_ONLY ignored");

        return effectiveRelation(server, baseRel, null, toKingdomId);
    }


    /**
     * New signature: fromKingdomId may be null. If provided, CAUSER_ONLY relation events can apply.
     *
     * @param fromKingdomId The evaluator / sender / other side (player kingdom id or ai kingdom id)
     * @param toKingdomId   The causee/target kingdom being evaluated
     */
    public static int effectiveRelation(MinecraftServer server, int baseRel, UUID fromKingdomId, UUID toKingdomId) {
        if (server == null) return clampRel(baseRel);
        if (fromKingdomId == null || toKingdomId == null) return clampRel(baseRel);

        // Optional discovery gate: only gate PLAYER -> unknown AI
        if (isPlayerEvaluatingUnknownAi(server, fromKingdomId, toKingdomId)) {
            return clampRel(baseRel);
        }

        long now = server.getTickCount();
        var ps = KingdomPressureState.get(server);

        // IMPORTANT: scan events on the evaluator (fromKingdomId), not the target.
        var list = ps.getEvents(fromKingdomId);
        if (list.isEmpty()) return clampRel(baseRel);

        int delta = 0;

        for (var e : list) {
            if (e == null) continue;
            if (now >= e.endTick()) continue;

            var eff = e.effects();
            if (eff == null) continue;

            Double r = eff.get(KingdomPressureState.Stat.RELATIONS);
            if (r == null) continue;

            var scope = e.relScope();
            if (scope == null) scope = KingdomPressureState.RelScope.GLOBAL;

            if (scope == KingdomPressureState.RelScope.GLOBAL) {
                delta += (int) Math.round(r);
            } else {
                // CAUSER_ONLY applies when evaluating vs the event.causer (which should be the "other" kingdom)
                if (toKingdomId.equals(e.causer())) {
                    delta += (int) Math.round(r);
                }
            }
        }

        return clampRel(baseRel + delta);
    }

    /** Gate only PLAYER -> unknown AI. Never gate AI->AI or AI->player evaluations. */
    private static boolean isPlayerEvaluatingUnknownAi(MinecraftServer server, UUID fromKingdomId, UUID toKingdomId) {
        var ai = aiKingdomState.get(server);

        boolean fromIsAi = ai.getById(fromKingdomId) != null;
        boolean toIsAi   = ai.getById(toKingdomId) != null;

        // only gate when a PLAYER kingdom is evaluating an AI kingdom
        if (fromIsAi) return false;
        if (!toIsAi) return false;

        return !KingdomPressureState.get(server).isKnownAi(toKingdomId);
    }


    private static boolean isUnknownAi(MinecraftServer server, UUID kingdomId) {
        var ai = aiKingdomState.get(server);
        if (ai.getById(kingdomId) == null) return false; // not AI => player kingdom, allow

        // AI kingdom: only apply if marked known
        return !KingdomPressureState.get(server).isKnownAi(kingdomId);
    }

    public static int clampRel(int r) {
        if (r < -100) return -100;
        if (r > 100) return 100;
        return r;
    }
}
