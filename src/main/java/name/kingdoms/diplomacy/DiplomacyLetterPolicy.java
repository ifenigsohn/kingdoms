package name.kingdoms.diplomacy;

import name.kingdoms.aiKingdomState;
import net.minecraft.util.RandomSource;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Single source of truth for choosing what kinds of OUTGOING letters an AI kingdom may send.
 *
 * Conservative on purpose:
 * - No random war declarations.
 * - Relation band gates first, then personality applies as weights.
 */
public final class DiplomacyLetterPolicy {

    private DiplomacyLetterPolicy() {}

    public enum RelationBand {
        HOSTILE, UNFRIENDLY, NEUTRAL, FRIENDLY, ALLIED;

        public static RelationBand of(int rel) {
            if (rel <= -70) return HOSTILE;
            if (rel <= -25) return UNFRIENDLY;
            if (rel < 25) return NEUTRAL;
            if (rel < 70) return FRIENDLY;
            return ALLIED;
        }
    }

    /**
     * Choose an outgoing letter kind given relation and personality.
     *
     * @param atWar   if true, suppresses alliance proposals
     * @param allied  if true, suppresses hostile escalations
     */
    public static Optional<Letter.Kind> chooseOutgoing(
            RandomSource rng,
            int relation,
            aiKingdomState.KingdomPersonality p,
            boolean atWar,
            boolean allied
    ) {
        if (rng == null || p == null) return Optional.empty();

        // Sanity: these states should not coexist, but if they do, don't send anything.
        if (atWar && allied) return Optional.empty();

        RelationBand band = RelationBand.of(relation);

        // Base weights by relation band
        EnumMap<Letter.Kind, Double> w = new EnumMap<>(Letter.Kind.class);
        switch (band) {
            case HOSTILE -> {
                w.put(Letter.Kind.WARNING, 1.0);
                w.put(Letter.Kind.INSULT, 0.6);
                w.put(Letter.Kind.ULTIMATUM, 0.25);
            }
            case UNFRIENDLY -> {
                w.put(Letter.Kind.WARNING, 1.0);
                w.put(Letter.Kind.CONTRACT, 0.7);
                w.put(Letter.Kind.INSULT, 0.15);
            }
            case NEUTRAL -> {
                w.put(Letter.Kind.CONTRACT, 1.0);
                w.put(Letter.Kind.REQUEST, 0.6);
                w.put(Letter.Kind.OFFER, 0.35);
            }
            case FRIENDLY -> {
                w.put(Letter.Kind.CONTRACT, 1.0);
                w.put(Letter.Kind.OFFER, 0.9);
                w.put(Letter.Kind.COMPLIMENT, 0.5);
            }
            case ALLIED -> {
                w.put(Letter.Kind.OFFER, 0.9);
                w.put(Letter.Kind.COMPLIMENT, 0.7);
                // Alliance proposal is allowed only if NOT at war.
                if (!atWar) w.put(Letter.Kind.ALLIANCE_PROPOSAL, 0.6);
            }
        }

        // -------------------------
        // Hard bans / sanity gates
        // -------------------------
        if (allied) {
            // allied -> never send insults/ultimatums/warnings
            w.remove(Letter.Kind.INSULT);
            w.remove(Letter.Kind.ULTIMATUM);
            w.remove(Letter.Kind.WARNING);
        }
        if (band == RelationBand.FRIENDLY || band == RelationBand.ALLIED) {
            // friendly+ should never insult/ultimatum
            w.remove(Letter.Kind.INSULT);
            w.remove(Letter.Kind.ULTIMATUM);
        }
        if (band == RelationBand.HOSTILE) {
            // hostile shouldn't compliment or propose alliance
            w.remove(Letter.Kind.COMPLIMENT);
            w.remove(Letter.Kind.ALLIANCE_PROPOSAL);
        }
        if (atWar) {
            // during war, suppress alliance offers
            w.remove(Letter.Kind.ALLIANCE_PROPOSAL);
        }

        // -------------------------
        // Personality modifiers
        // -------------------------
        double aggression  = p.aggression();
        double honor       = p.honor();
        double greed       = p.greed();
        double generosity  = p.generosity();
        double pragmatism  = p.pragmatism();
        double trustBias   = p.trustBias();

        // aggression: more warnings/ultimatums/insults
        scale(w, Letter.Kind.WARNING, 1.0 + aggression);
        scale(w, Letter.Kind.ULTIMATUM, 1.0 + aggression * 1.5);
        scale(w, Letter.Kind.INSULT, 1.0 + aggression * 0.8);

        // honor: reduces insults/ultimatums
        scale(w, Letter.Kind.INSULT, 1.0 - honor);
        scale(w, Letter.Kind.ULTIMATUM, 1.0 - (honor * 0.8));

        // greed + pragmatism: more contracts/requests
        scale(w, Letter.Kind.CONTRACT, 1.0 + greed + pragmatism);
        scale(w, Letter.Kind.REQUEST, 1.0 + greed);

        // generosity: more offers/compliments
        scale(w, Letter.Kind.OFFER, 1.0 + generosity);
        scale(w, Letter.Kind.COMPLIMENT, 1.0 + generosity);

        // trust: more friendly outreach
        scale(w, Letter.Kind.OFFER, 1.0 + trustBias * 0.5);
        scale(w, Letter.Kind.ALLIANCE_PROPOSAL, 1.0 + trustBias);

        return weightedPick(rng, w);
    }

    private static void scale(Map<Letter.Kind, Double> w, Letter.Kind k, double mult) {
        if (mult <= 0) {
            w.remove(k);
            return;
        }
        Double cur = w.get(k);
        if (cur == null) return;
        w.put(k, cur * mult);
    }

    private static Optional<Letter.Kind> weightedPick(RandomSource rng, EnumMap<Letter.Kind, Double> w) {
        double total = 0.0;
        for (double v : w.values()) if (v > 0) total += v;
        if (total <= 0.0) return Optional.empty();

        double roll = rng.nextDouble() * total;
        for (var e : w.entrySet()) {
            double v = e.getValue();
            if (v <= 0) continue;
            roll -= v;
            if (roll <= 0) return Optional.of(e.getKey());
        }
        return Optional.of(w.keySet().iterator().next());
    }
}
