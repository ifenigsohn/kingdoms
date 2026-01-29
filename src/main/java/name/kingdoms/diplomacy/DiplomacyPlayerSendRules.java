package name.kingdoms.diplomacy;

import name.kingdoms.aiKingdomState;
import name.kingdoms.kingdomState;
import name.kingdoms.war.WarState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public final class DiplomacyPlayerSendRules {
    private DiplomacyPlayerSendRules() {}

    public record Decision(boolean allowed, String reason) {
        public static Decision ok() { return new Decision(true, ""); }
        public static Decision no(String why) { return new Decision(false, why); }
    }

    // “long cooldown” knobs (tune freely)
    public static final long COMPLIMENT_COOLDOWN_TICKS = 20L * 60L * 8L; // 10 minutes
    public static final long INSULT_COOLDOWN_TICKS     = 20L * 60L * 8L; // 10 minutes

    /** “Deal” letters in your GDD */
    public static boolean isDealKind(Letter.Kind k) {
        return k == Letter.Kind.REQUEST || k == Letter.Kind.OFFER || k == Letter.Kind.CONTRACT;
    }

    public static Decision canSend(
            MinecraftServer server,
            ServerPlayer player,
            UUID toKingdomId,
            Letter.Kind kind
    ) {
        if (server == null || player == null || toKingdomId == null || kind == null) {
            return Decision.no("Invalid request.");
        }

        var ks = kingdomState.get(server);
        var fromK = ks.getPlayerKingdom(player.getUUID());
        if (fromK == null) return Decision.no("You are not in a kingdom.");

        var toK = ks.getKingdom(toKingdomId);
        if (toK == null) return Decision.no("That kingdom does not exist.");
        if (toK.id.equals(fromK.id)) return Decision.no("You can't send mail to your own kingdom.");

        // AI vs Player target
        var aiState = aiKingdomState.get(server);
        var aiK = aiState.getById(toK.id);
        boolean toIsAi = (aiK != null);

        // If player kingdom target: require owner online
        ServerPlayer toOwnerOnline = null;
        if (!toIsAi) {
            if (toK.owner == null) return Decision.no("That kingdom has no owner.");
            toOwnerOnline = server.getPlayerList().getPlayer(toK.owner);
            if (toOwnerOnline == null) return Decision.no("That kingdom's ruler is offline.");
        }

        // War state + alliance state
        var warState = WarState.get(server);
        boolean atWar = warState.isAtWar(fromK.id, toK.id);

        var alliance = AllianceState.get(server);
        boolean allied = alliance.isAllied(fromK.id, toK.id);

        // -------------------------
        // GDD rule enforcement
        // -------------------------

        // Peace letters only when at war
        if (kind == Letter.Kind.WHITE_PEACE || kind == Letter.Kind.SURRENDER) {
            if (!atWar) return Decision.no("Peace letters can only be sent to kingdoms you are at war with.");
            return Decision.ok();
        }

        // If at war, ONLY peace letters allowed (your current rule)
        if (atWar) {
            return Decision.no("You are at war. Only WHITE_PEACE or SURRENDER may be sent.");
        }

        // War declaration anytime, but not to allies
        if (kind == Letter.Kind.WAR_DECLARATION) {
            if (allied) return Decision.no("You cannot declare war on an ally.");
            return Decision.ok();
        }

        // Alliance break only while allied
        if (kind == Letter.Kind.ALLIANCE_BREAK) {
            if (!allied) return Decision.no("You are not allied with that kingdom.");
            return Decision.ok();
        }

        if (toIsAi) {
            // Relation gates (player<->target kingdom)
            var relState = DiplomacyRelationsState.get(server);
            int rel = relState.getRelation(player.getUUID(), toK.id);

            // Deal: rel > -40
            if (isDealKind(kind)) {
                if (rel <= -40) return Decision.no("Relation too low for a deal (must be above -40).");
            }

            // Ultimatum: rel < -30
            if (kind == Letter.Kind.ULTIMATUM) {
                if (rel >= -30) return Decision.no("Relation too high for an ultimatum (must be below -30).");
            }

            // Compliment: rel > -5
            if (kind == Letter.Kind.COMPLIMENT) {
                if (rel <= -5) return Decision.no("You can only compliment neutral+ kingdoms (relation must be above -1).");
            }

            // Alliance proposal: rel > +50
            if (kind == Letter.Kind.ALLIANCE_PROPOSAL) {
                if (rel <= 50) return Decision.no("Relation too low for an alliance (must be above +50).");
            }
        }

        // Cooldowns (compliment/insult)
        if (kind == Letter.Kind.COMPLIMENT || kind == Letter.Kind.INSULT) {
            ServerLevel mailLevel = server.overworld();
            var cd = DiplomacyCooldownState.get(mailLevel);

            // IMPORTANT: use the same clock everywhere (recommended)
            long nowTick = server.getTickCount();

            long rem = cd.remaining(fromK.id, toK.id, kind, nowTick);
            if (rem > 0) {
                return Decision.no("On cooldown (" + (rem / 20L) + "s remaining).");
            }
        }


        // Insult is always allowed (except cooldown), Warning always allowed, etc.
        return Decision.ok();
    }

    /** Call after a successful send to update cooldowns. */
    public static void markSentCooldowns(MinecraftServer server, UUID fromKingdomId, UUID toKingdomId, Letter.Kind kind) {
        if (kind != Letter.Kind.COMPLIMENT && kind != Letter.Kind.INSULT) return;

        ServerLevel mailLevel = server.overworld();
        var cd = DiplomacyCooldownState.get(mailLevel);
        long nowTick = server.getTickCount();
        long cooldown = (kind == Letter.Kind.COMPLIMENT)
                ? COMPLIMENT_COOLDOWN_TICKS
                : INSULT_COOLDOWN_TICKS;

        cd.markSent(fromKingdomId, toKingdomId, kind, nowTick, cooldown);

    }
}
