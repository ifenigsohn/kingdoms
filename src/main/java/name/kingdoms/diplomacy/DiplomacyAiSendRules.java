package name.kingdoms.diplomacy;

import name.kingdoms.aiKingdomState;
import name.kingdoms.pressure.PressureUtil;
import name.kingdoms.war.WarState;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

public final class DiplomacyAiSendRules {
    private DiplomacyAiSendRules() {}

    public record Decision(boolean allowed, String reason) {
        public static Decision ok() { return new Decision(true, ""); }
        public static Decision no(String why) { return new Decision(false, why); }
    }

    private static boolean isDealKind(Letter.Kind k) {
        return k == Letter.Kind.REQUEST || k == Letter.Kind.OFFER || k == Letter.Kind.CONTRACT;
    }

    public static Decision canSend(MinecraftServer server, UUID fromAiId, UUID toAiId, Letter.Kind kind) {
        if (server == null || fromAiId == null || toAiId == null || kind == null) return Decision.no("Invalid.");
        if (fromAiId.equals(toAiId)) return Decision.no("Same kingdom.");

        // Diplomatic range gate (AI uses kingdom IDs)
        if (!name.kingdoms.diplomacy.DiplomaticRangeUtil.canReach(server, fromAiId, toAiId)) {
            return Decision.no("Outside diplomatic range.");
        }


        var aiState = aiKingdomState.get(server);
        if (aiState.getById(fromAiId) == null) return Decision.no("Sender not AI.");
        if (aiState.getById(toAiId) == null) return Decision.no("Recipient not AI.");

        var warState = WarState.get(server);
        boolean atWar = warState.isAtWar(fromAiId, toAiId);

        var alliance = AllianceState.get(server);
        boolean allied = alliance.isAllied(fromAiId, toAiId);

        // Peace letters only when at war
        if (kind == Letter.Kind.WHITE_PEACE || kind == Letter.Kind.SURRENDER) {
            if (!atWar) return Decision.no("Not at war.");
            return Decision.ok();
        }

        // War declaration: never against ally
        if (kind == Letter.Kind.WAR_DECLARATION) {
            if (allied) return Decision.no("Cannot declare war on ally.");
            return Decision.ok();
        }

        // Alliance break only if allied
        if (kind == Letter.Kind.ALLIANCE_BREAK) {
            if (!allied) return Decision.no("Not allied.");
            return Decision.ok();
        }

        // Alliance proposal: block while at war (matches your player rules)
        if (kind == Letter.Kind.ALLIANCE_PROPOSAL) {
            if (atWar) return Decision.no("Cannot ally while at war.");
        }

        // Relation gates (AI↔AI)
        int baseRel = AiRelationsState.get(server).get(fromAiId, toAiId);
        int rel = PressureUtil.effectiveRelation(server, baseRel, fromAiId, toAiId);


        // Keep the same gates you want for AI diplomacy:
        if (isDealKind(kind)) {
            if (rel <= -40) return Decision.no("Relation too low for a deal.");
        }

        if (kind == Letter.Kind.ULTIMATUM) {
            if (rel >= -30) return Decision.no("Relation too high for ultimatum.");
        }

        if (kind == Letter.Kind.COMPLIMENT) {
            if (rel <= -1) return Decision.no("Relation too low to compliment.");
        }

        if (kind == Letter.Kind.ALLIANCE_PROPOSAL) {
            if (rel <= 50) return Decision.no("Relation too low for alliance.");
        }

        return Decision.ok();
    }
}
