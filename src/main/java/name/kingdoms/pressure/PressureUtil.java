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
        if (server == null || toKingdomId == null) return clampRel(baseRel);

        // Gate: don't apply pressure logic to AI kingdoms the player hasn't discovered.
        // Player kingdoms always allowed.
        if (isUnknownAi(server, toKingdomId)) {
            return clampRel(baseRel);
        }

        long now = server.getTickCount();
        var ps = KingdomPressureState.get(server);
        var list = ps.getEvents(toKingdomId);
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
                // CAUSER_ONLY: only apply if fromKingdomId matches event.causer
                if (fromKingdomId != null && fromKingdomId.equals(e.causer())) {
                    delta += (int) Math.round(r);
                }
            }
        }

        // Also apply GLOBAL relation modifiers on the FROM side.
        // This lets "envoys", "legitimacy crisis", etc. affect how this kingdom relates to everyone.
        if (fromKingdomId != null && !isUnknownAi(server, fromKingdomId)) {
            var fromList = ps.getEvents(fromKingdomId);
            for (var e : fromList) {
                if (e == null) continue;
                if (now >= e.endTick()) continue;

                var eff = e.effects();
                if (eff == null) continue;

                Double r = eff.get(KingdomPressureState.Stat.RELATIONS);
                if (r == null) continue;

                var scope = e.relScope();
                if (scope == null) scope = KingdomPressureState.RelScope.GLOBAL;

                // Only GLOBAL applies from the sender side (outgoing posture).
                if (scope == KingdomPressureState.RelScope.GLOBAL) {
                    delta += (int) Math.round(r);
                }
            }
        }


        return clampRel(baseRel + delta);
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
