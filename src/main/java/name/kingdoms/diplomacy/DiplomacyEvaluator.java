package name.kingdoms.diplomacy;

import name.kingdoms.aiKingdomState;
import name.kingdoms.borderSelection;
import net.minecraft.util.RandomSource;

import java.util.EnumMap;

public final class DiplomacyEvaluator {

    public enum Decision { ACCEPT, REFUSE, COUNTER }

    public record WarDecision( // for logging
        boolean shouldDeclare,
        double totalScore,
        double econScore,
        double relScore,
        double personalityScore
    ) {}

    public record Result(
            Decision decision,
            int relationDelta,
            ResourceType counterGiveType, double counterGiveAmt,
            ResourceType counterWantType, double counterWantAmt,
            String note
    ) {
        public static Result accept(int dRel, String note) {
            return new Result(Decision.ACCEPT, dRel, null, 0, null, 0, note);
        }
        public static Result refuse(int dRel, String note) {
            return new Result(Decision.REFUSE, dRel, null, 0, null, 0, note);
        }
        public static Result counter(int dRel,
                                     ResourceType giveT, double giveA,
                                     ResourceType wantT, double wantA,
                                     String note) {
            return new Result(Decision.COUNTER, dRel, giveT, giveA, wantT, wantA, note);
        }
    }

    /** All the “relevant factors” you wanted, packed into one context. */
    public record Context(
            int relation,                         // -100..100 (player<->ai)
            boolean allied,
            boolean atWarWithOther,               // ai vs this other kingdom
            boolean atWarWithAnyone,              // ai in any war (broader pressure)
            int aiAllyCount,                      // 0..3
            int aiEnemyCountApprox,               // 0..N (approx ok)
            int aiSoldiers,                       // aiK.aliveSoldiers (or max)
            int otherSoldiersEstimate             // player estimate (garrisons*50, etc)
    ) {}

    private DiplomacyEvaluator() {}

    


    // -------------------------
    // Policy knobs (tune these)
    // -------------------------

    private static final int GIFT_REFUSE_RELATION_BELOW = -85;
    private static final double REQUEST_MIN_FAIRNESS_TO_ACCEPT = 0.20;
    private static final double UNFAIR_CUTOFF = 0.85;
    private static final double GREAT_DEAL = 1.20;
    private static final double EPS = 1e-6;

    // Alliance rules / tuning
    private static final int ALLIANCE_MIN_RELATION = 55;
    private static final double ALLIANCE_MIN_TRUST = 0.55;

    // “War pressure” makes the AI hoard war materials and value them more
    private static final double WAR_HOARD_MULT = 1.35;

    // -------------------------
    // Resource targets (need model)
    // -------------------------
    private static final EnumMap<ResourceType, Double> TARGET = new EnumMap<>(ResourceType.class);
    static {
        TARGET.put(ResourceType.GOLD, 200.0);
        TARGET.put(ResourceType.WOOD, 250.0);
        TARGET.put(ResourceType.METAL, 140.0);
        TARGET.put(ResourceType.GRAIN, 320.0);
        TARGET.put(ResourceType.MEAT, 160.0);
        TARGET.put(ResourceType.FISH, 140.0);
        TARGET.put(ResourceType.GEMS, 40.0);
        TARGET.put(ResourceType.POTIONS, 25.0);
        TARGET.put(ResourceType.ARMOR, 60.0);
        TARGET.put(ResourceType.WEAPONS, 70.0);
        TARGET.put(ResourceType.HORSES, 35.0);
    }

    // -------------------------
    // Main entry
    // -------------------------

    public static Result decide(
            RandomSource rng,
            Letter.Kind kind,
            aiKingdomState.KingdomPersonality p,
            aiKingdomState.AiKingdom ai,
            Context ctx,
            ResourceType aType, double aAmt,
            ResourceType bType, double bAmt,
            double maxAmount,
            boolean inPerson
    ) {
        // Sanity / hard diplomatic gates
        if (ctx.allied) {
            // allied: never “refuse” compliments/requests purely on spite; lower hostility overall
            if (kind == Letter.Kind.INSULT || kind == Letter.Kind.WARNING || kind == Letter.Kind.ULTIMATUM || kind == Letter.Kind.WAR_DECLARATION) {
                return Result.refuse(-10, "We will not entertain hostility between allies.");
            }
        }

        // Soft kinds (always “processed”, not negotiated)
        if (kind == Letter.Kind.WHITE_PEACE || kind == Letter.Kind.SURRENDER) {
            return Result.accept(0, ""); // real decision handled by PeaceEvaluator in ResponseQueue
        }

        if (kind == Letter.Kind.COMPLIMENT) {
            int dRel = clampRel((int)Math.round(4 + 8*p.generosity() + 3*p.trustBias() - 2*p.aggression()));
            return Result.accept(dRel, "");
        }
        if (kind == Letter.Kind.INSULT) {
            double relNeg = clamp01((-ctx.relation) / 100.0); // stronger when already bad
            int dRel = clampRel((int)Math.round(
                    -8
                    - 8*p.aggression()
                    - 4*p.honor()
                    - 6*relNeg
            ));
            return Result.accept(dRel, "");
        }
        if (kind == Letter.Kind.WARNING) {
            int dRel = clampRel((int)Math.round(-2 - 5*p.honor()));
            return Result.accept(dRel, "");
        }
        if (kind == Letter.Kind.WAR_DECLARATION) {
            // war letters are “accepted” as acknowledgment, war happens elsewhere
            return Result.accept(-100, "");
        }

        // Alliance proposal (baseline decision; capacity/war enforcement can still happen in ResponseQueue)
        if (kind == Letter.Kind.ALLIANCE_PROPOSAL) {
            return decideAlliance(rng, p, ctx, inPerson);
        }

        // Normalize relationship into 0..1 trust-ish
        double rel01 = (ctx.relation + 100) / 200.0; // 0..1
        double trust = clamp01(0.10 + 0.75*rel01 + 0.30*p.trustBias());

        boolean negotiates = switch (kind) {
            case OFFER, REQUEST, CONTRACT, ULTIMATUM, ALLIANCE_PROPOSAL -> true;
            default -> false;
        };

        if (inPerson && negotiates) {
            // Showing up personally makes your intent clearer + signals commitment.
            trust = clamp01(trust + 0.12);  // tune 0.08–0.18
        }


        double greed = clamp01(p.greed());
        double gen   = clamp01(p.generosity());
        double honor = clamp01(p.honor());
        double prag  = clamp01(p.pragmatism());
        double agg = clamp01(p.aggression());
        if (inPerson && negotiates) {
            agg *= 0.75; // 25% less aggression impact in-person (tune 0.65–0.85)
        }


        // Military / war pressure
        double milRatio = militaryRatio(ctx.aiSoldiers, ctx.otherSoldiersEstimate); // >1 ai stronger
        double warPressure = (ctx.atWarWithAnyone ? 0.35 : 0.0)
                + (ctx.atWarWithOther ? 0.35 : 0.0)
                + clamp01(ctx.aiEnemyCountApprox / 4.0) * 0.30;

        // Compute deal values from AI perspective (in/out in gold-equivalent, but adjusted by need/scarcity)
        ValueDeal deal = computeDealValues(kind, ai, ctx, aType, aAmt, bType, bAmt, warPressure);

        // fairness > 1 => good for AI, < 1 => bad for AI
        double fairness = deal.valueInGold / Math.max(EPS, deal.valueOutGold);

        // -------------------------
        // Kind-specific policy
        // -------------------------

        if (kind == Letter.Kind.OFFER) {
            // OFFER (player gives A to AI): accept unless you *hate* them
            if (ctx.relation <= GIFT_REFUSE_RELATION_BELOW && !ctx.allied) {
                int dRel = clampRel((int)Math.round(-1 - 3*honor - 2*agg));
                return Result.refuse(dRel, "");
            }

            // War: accept war-material gifts much more readily
            int dRel = clampRel((int)Math.round(2 + 6*gen + 2*trust + (warPressure * 6.0)));
            return Result.accept(dRel, "");
        }

        if (kind == Letter.Kind.REQUEST) {
            // REQUEST (player asks AI to give A): usually refuse unless generous + trust + spare + not under war pressure
            double spare = spareFactor(ai, aType);
            boolean consider = trust > 0.55 && gen > 0.30 && spare > 0.60;

            // war makes AI much less likely to give away war materials or scarce things
            if (ctx.atWarWithAnyone) {
                consider = consider && !isWarMaterial(aType) && needFactor(ai, aType) < 0.55;
            }

            if (!consider || fairness < REQUEST_MIN_FAIRNESS_TO_ACCEPT) {
                int dRel = clampRel((int)Math.round(-2 - 2*agg - 1*(1.0-trust)));
                // counter: offer smaller amount sometimes when relations aren't awful
                if (ctx.relation > -60 && rng.nextFloat() < (0.10f + 0.25f*(float)trust + 0.15f*(float)prag)) {

                    // Base "we'll give some" (generosity/trust up, greed down)
                    double rawOffer = aAmt * (0.20 + 0.55*gen + 0.25*trust - 0.35*greed);
                    rawOffer = clamp(rawOffer, 1.0, aAmt);

                    // HARD CAP: never offer more than we can actually spare
                    double cap = maxGive(ai, p, ctx, aType, warPressure);
                    double offerAmt = Math.min(rawOffer, cap);

                    // If we truly can't spare anything, refuse instead of nonsense counter
                    if (offerAmt < 1.0) {
                        int dRel2 = clampRel((int)Math.round(-2 - 2*agg - 1*(1.0-trust)));
                        return Result.refuse(dRel2, "");
                    }

                    // Optional but recommended: REQUEST counter becomes "small contract"
                    // We give offerAmt, but ask for something we need so it's not just charity.
                    ResourceType wantT = pickMostNeeded(ai, ctx, warPressure);
                    if (wantT == aType) wantT = ResourceType.WOOD;

                    // Target fairness: greedier AIs demand more, friendlier AIs demand less
                    double neededFair = 1.05 + 0.55*greed - 0.40*trust - (ctx.allied ? 0.10 : 0.0);

                    double outGold = ResourceValues.goldValue(aType, offerAmt) * pain(ai, aType, ctx, warPressure);
                    double perUnitIn = ResourceValues.goldValue(wantT) * desirability(ai, wantT, ctx, warPressure);
                    if (perUnitIn <= EPS) perUnitIn = Math.max(EPS, ResourceValues.goldValue(wantT));

                    double wantAmt = Math.ceil((neededFair * outGold) / perUnitIn);

                    // Keep want reasonable (avoid insane asks)
                    wantAmt = clamp(wantAmt, 1.0, Math.max(10.0, offerAmt * 3.0));

                    int dRel2 = clampRel((int)Math.round(+1 + 2*trust));
                    return Result.counter(dRel2, aType, offerAmt, wantT, wantAmt, "");
                }

                return Result.refuse(dRel, "");
            }

            int dRel = clampRel((int)Math.round(1 + 4*gen));
            return Result.accept(dRel, "");
        }

        if (kind == Letter.Kind.ULTIMATUM) {
            // Ultimatums: consider power + honor + war pressure
            // - If AI is stronger and honorable: refuse more
            // - If AI is weaker and pragmatic: might accept if “not too painful”
            double painBoost = 1.0 + (honor * 0.4) + (agg * 0.2) + warPressure * 0.6;
            double weakBoost = (milRatio < 1.0) ? (1.0 - milRatio) : 0.0; // 0..1
            double ultThresh = 1.60 * painBoost - 0.35*prag - 0.25*trust - 0.30*weakBoost;

            boolean accept = fairness >= ultThresh;

            if (accept) {
                return Result.accept(0, "");
            } else {
                int dRel = clampRel((int)Math.round(-6 - 6*honor - 3*agg));
                return Result.refuse(dRel, "");
            }
        }

        if (kind == Letter.Kind.CONTRACT) {
            // If allied, be more lenient (accept slightly worse deals)
            double alliedBonus = ctx.allied ? 0.10 : 0.0;

            // War: hoard war materials -> make deals demanding those more attractive and giving those less attractive
            // (This is already in desirability/pain via warPressure)

            // Hard unfair rule
            if (fairness < (UNFAIR_CUTOFF - alliedBonus)) {
                int dRel = clampRel((int)Math.round(-2 - 2*agg - 1*honor));
                // Counter instead of refuse when relations aren't awful
                if (ctx.relation > -60 && rng.nextFloat() < (0.25f + 0.25f*(float)trust + 0.20f*(float)prag)) {
                if (bType != null && bAmt > 0 && aAmt > 0) {

                    // HARD CAP the give side
                    double cap = maxGive(ai, p, ctx, aType, warPressure);
                    double giveA = Math.min(aAmt, cap);

                    // If we can't give enough to make a meaningful contract, refuse
                    if (giveA < 1.0) {
                        int dRel2 = clampRel((int)Math.round(-2 - 2*agg - 1*honor));
                        return Result.refuse(dRel2, "");
                    }

                    // Recompute "deal" values based on clamped giveA, then compute want
                    ValueDeal deal2 = computeDealValues(kind, ai, ctx, aType, giveA, bType, bAmt, warPressure);
                    double want = computeCounterWantB(deal2, bType, bAmt, greed, UNFAIR_CUTOFF - alliedBonus);

                    int dRel2 = clampRel((int)Math.round(+1 + 2*trust));
                    return Result.counter(dRel2, aType, giveA, bType, want, "");
                }
            }

                return Result.refuse(dRel, "");
            }

            // Personality/trust threshold
            double thresh = 1.00
                    + 0.35*greed
                    - 0.30*trust
                    - 0.20*prag
                    - alliedBonus;

            if (fairness >= GREAT_DEAL) {
                int dRel = clampRel((int)Math.round(2 + 2*trust));
                return Result.accept(dRel, "");
            }

            boolean accept = fairness >= thresh;

            if (accept) {
                int dRel = clampRel((int)Math.round(1 + 2*trust));
                return Result.accept(dRel, "");
            }

            // Otherwise: sometimes counter, else refuse
            if (ctx.relation > -60 && rng.nextFloat() < (0.20f + 0.25f*(float)trust + 0.20f*(float)prag)) {
                if (bType != null && bAmt > 0) {
                    double want = Math.min(bAmt * 3.0, bAmt * (1.15 + 0.35*greed));
                    int dRel = clampRel((int)Math.round(+1 + 2*trust));
                    return Result.counter(dRel, aType, aAmt, bType, want, "");
                }
            }

            int dRel = clampRel((int)Math.round(-2 - 1*(1.0-trust) - 1*agg));
            return Result.refuse(dRel, "");
        }

        return Result.refuse(0, "");
    }

    // -------------------------
    // Alliance baseline decision
    // -------------------------
    private static Result decideAlliance(RandomSource rng, aiKingdomState.KingdomPersonality p, Context ctx, boolean inPerson) {
        double rel01 = (ctx.relation + 100) / 200.0;
        double trust = clamp01(0.10 + 0.75*rel01 + 0.30*p.trustBias());

        if (inPerson) {
            trust = clamp01(trust + 0.12);  // same bump as other negotiation
        }
                
        // If already allied, accept “formally” (idempotent)
        if (ctx.allied) {
            return Result.accept(+5, "");
        }

        // No alliances if you hate them, or if honor is high and trust is low
        if (ctx.relation < ALLIANCE_MIN_RELATION) {
            return Result.refuse(-5, "");
        }
        if (trust < ALLIANCE_MIN_TRUST) {
            return Result.refuse(-5, "");
        }

        // War pressure + trustBias makes alliances more attractive
        double warPressure = (ctx.atWarWithAnyone ? 0.30 : 0.0) + (ctx.aiEnemyCountApprox > 0 ? 0.20 : 0.0);
        double aAgg = clamp01(p.aggression());
        if (inPerson) aAgg *= 0.75;
        double desire =
                0.55
                        + 0.35*p.trustBias()
                        + 0.25*p.pragmatism()
                        + 0.20*warPressure
                        - 0.20*p.aggression(); // aggressors don’t like equal partners

        // Slight randomness so it doesn't feel deterministic
        double roll = rng.nextDouble();
        if (roll < clamp01(desire)) {
            return Result.accept(+10, "");
        } else {
            return Result.refuse(-10, "");
        }
    }

    

    // -------------------------
    // Deal value model
    // -------------------------

    private record ValueDeal(double valueInGold, double valueOutGold) {}

    private static ValueDeal computeDealValues(
            Letter.Kind kind,
            aiKingdomState.AiKingdom ai,
            Context ctx,
            ResourceType aType, double aAmt,
            ResourceType bType, double bAmt,
            double warPressure
    ) {
        // AI perspective for player->AI letters:
        // - OFFER: AI receives A (in), gives nothing (out=0)
        // - REQUEST: AI gives A (out), receives nothing (in=0)
        // - CONTRACT: AI gives A (out), receives B (in)
        // - ULTIMATUM: treat like REQUEST (AI gives A to comply)

        double in = 0.0;
        double out = 0.0;

        switch (kind) {
            case OFFER -> {
                in = ResourceValues.goldValue(aType, aAmt) * desirability(ai, aType, ctx, warPressure);
                out = 0.0;
            }
            case REQUEST, ULTIMATUM -> {
                in = 0.0;
                out = ResourceValues.goldValue(aType, aAmt) * pain(ai, aType, ctx, warPressure);
            }
            case CONTRACT -> {
                in = (bType == null) ? 0.0 : ResourceValues.goldValue(bType, bAmt) * desirability(ai, bType, ctx, warPressure);
                out = ResourceValues.goldValue(aType, aAmt) * pain(ai, aType, ctx, warPressure);
            }
            default -> { }
        }

        // Avoid fairness exploding for gifts
        if (out < EPS) out = 1.0;

        return new ValueDeal(in, out);
    }

    // -------------------------
    // Counter budget helpers
    // -------------------------

    /** How much of current stock we refuse to part with (reserve), based on personality + war. */
    private static double reserveFrac(aiKingdomState.KingdomPersonality p, Context ctx, ResourceType t, double warPressure) {
        double greed = clamp01(p.greed());
        double prag  = clamp01(p.pragmatism());

        // Baseline reserve (keep some buffer always)
        double frac = 0.12 + 0.28 * greed + 0.10 * prag;

        // War: hoard war materials harder
        if (ctx.atWarWithAnyone && isWarMaterial(t)) {
            frac += 0.10 + 0.20 * warPressure;
        }

        return clamp( frac, 0.10, 0.85 );
    }

    /** Maximum amount AI is willing/able to give *right now* for resource t. */
    private static double maxGive(aiKingdomState.AiKingdom ai, aiKingdomState.KingdomPersonality p, Context ctx, ResourceType t, double warPressure) {
        double have = Math.max(0.0, stock(ai, t));
        double reserve = have * reserveFrac(p, ctx, t, warPressure);
        double spendable = Math.max(0.0, have - reserve);

        // Also avoid giving away stuff we actually need (unless very friendly/generous — handled elsewhere)
        // This is a soft cap layered on top of reserve: if we need it, reduce max.
        double need = needFactor(ai, t); // 0..1
        spendable *= (1.0 - 0.60 * need);

        return Math.max(0.0, spendable);
    }

    private static ResourceType pickMostNeeded(aiKingdomState.AiKingdom ai, Context ctx, double warPressure) {
        ResourceType best = ResourceType.GOLD;
        double bestScore = -1e9;
        for (ResourceType t : ResourceType.values()) {
            double score = needFactor(ai, t);
            // during war, boost war materials a bit
            if (ctx.atWarWithAnyone && isWarMaterial(t)) score *= (1.0 + 0.35 * warPressure);
            if (score > bestScore) { bestScore = score; best = t; }
        }
        return best;
    }

    private static double clamp(double v, double lo, double hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }


    // -------------------------
    // Need / desirability / pain
    // -------------------------

    /** desirability > 1 if AI needs it; < 1 if AI is flush */
    private static double desirability(aiKingdomState.AiKingdom ai, ResourceType t, Context ctx, double warPressure) {
        double need = needFactor(ai, t);

        double base = 0.85 + 0.75 * need; // 0.85..1.60

        // War pressure increases desirability of war materials and gold/metal
        if (ctx.atWarWithAnyone && isWarMaterial(t)) {
            base *= (1.0 + warPressure * 0.8);
        }
        return base;
    }

    /** pain > 1 if AI needs it; < 1 if AI is flush */
    private static double pain(aiKingdomState.AiKingdom ai, ResourceType t, Context ctx, double warPressure) {
        double need = needFactor(ai, t);

        double base = 0.85 + 0.95 * need; // 0.85..1.80

        // War: hoard war materials aggressively
        if (ctx.atWarWithAnyone && isWarMaterial(t)) {
            base *= WAR_HOARD_MULT * (1.0 + warPressure * 0.6);
        }
        return base;
    }

    private static boolean isWarMaterial(ResourceType t) {
        return switch (t) {
            case METAL, WEAPONS, ARMOR, GOLD, HORSES, POTIONS -> true;
            default -> false;
        };
    }

    /** 0..1 where 1 means "we are low" and 0 means "well stocked" */
    private static double needFactor(aiKingdomState.AiKingdom ai, ResourceType t) {
        double stock = stock(ai, t);
        double target = TARGET.getOrDefault(t, 200.0);
        if (target <= 0) return 0.0;

        // need = (target-stock)/target, clamped
        return clamp01((target - stock) / target);
    }

    /** 0..1 where 1 means "we are very stocked/surplus" */
    private static double spareFactor(aiKingdomState.AiKingdom ai, ResourceType t) {
        double stock = stock(ai, t);
        double target = TARGET.getOrDefault(t, 200.0);
        if (target <= 0) return clamp01(stock / 200.0);
        return clamp01(stock / (target * 2.0));
    }

    private static double stock(aiKingdomState.AiKingdom k, ResourceType t) {
        return switch (t) {
            case GOLD -> k.gold;
            case MEAT -> k.meat;
            case GRAIN -> k.grain;
            case FISH -> k.fish;
            case WOOD -> k.wood;
            case METAL -> k.metal;
            case ARMOR -> k.armor;
            case WEAPONS -> k.weapons;
            case GEMS -> k.gems;
            case HORSES -> k.horses;
            case POTIONS -> k.potions;
        };
    }

    

    // -------------------------
    // Counter helper
    // -------------------------
    private static double computeCounterWantB(ValueDeal deal, ResourceType bType, double bAmt, double greed, double unfairCutoff) {
        double neededFair = Math.max(1.0, unfairCutoff + 0.10 + 0.35*greed);
        double targetValueIn = neededFair * deal.valueOutGold;
        double perUnit = ResourceValues.goldValue(bType);
        if (perUnit <= 0) return Math.min(bAmt * 3.0, bAmt * (1.15 + 0.35*greed));

        double want = Math.ceil(targetValueIn / perUnit);
        want = Math.max(want, bAmt * (1.10 + 0.25*greed));
        want = Math.min(want, bAmt * 3.0);
        return want;
    }

    // -------------------------
    // Military helper
    // -------------------------
    private static double militaryRatio(int aiSoldiers, int otherEstimate) {
        double a = Math.max(1.0, aiSoldiers);
        double b = Math.max(1.0, otherEstimate);
        return a / b;
    }

    // -------------------------
    // utils
    // -------------------------
    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static int clampRel(int d) {
        return Math.max(-100, Math.min(100, d));
    }

   

}
