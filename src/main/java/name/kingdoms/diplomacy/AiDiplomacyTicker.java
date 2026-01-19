package name.kingdoms.diplomacy;

import name.kingdoms.aiKingdomState;
import name.kingdoms.kingdomState;
import name.kingdoms.news.KingdomNewsState;
import name.kingdoms.war.WarState;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

public final class AiDiplomacyTicker {
    private AiDiplomacyTicker() {}

        public record SimDiploEvent(
            long tick,
            Letter.Kind kind,
            UUID fromId,
            UUID toId,
            String outcome,     // ACCEPTED / DECLINED / COUNTERED / WAR_DECLARED / ALLIANCE_FORMED / ...
            int relBefore,
            int relAfter,
            int relDelta
        ) {}

    public record DecisionRow(
            long tick,
            UUID fromId,
            UUID toId,
            int rel,
            boolean allied,
            boolean atWarWithOther,
            int fromSoldiers,
            int toSoldiers,
            double pWar,
            double pUlt,
            java.util.Map<String, Double> weights // kindName -> weight
    ) {}


    private static String fmt3(double v) {
        return String.format(Locale.US, "%.3f", v);
    }

    private static String summarizeWeights(java.util.Map<Letter.Kind, Double> w) {
        if (w == null || w.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        // Keep it short but readable
        for (var k : new Letter.Kind[] {
                Letter.Kind.OFFER,
                Letter.Kind.REQUEST,
                Letter.Kind.CONTRACT,
                Letter.Kind.COMPLIMENT,
                Letter.Kind.ALLIANCE_PROPOSAL,
                Letter.Kind.WARNING,
                Letter.Kind.INSULT,
                Letter.Kind.ULTIMATUM,
                Letter.Kind.WAR_DECLARATION,
                Letter.Kind.WHITE_PEACE,
                Letter.Kind.SURRENDER
        }) {
            Double val = w.get(k);
            if (val == null) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(k.name()).append("=").append(fmt3(val));
        }
        return sb.toString();
    }


    public record DebugResult(
            int actions,
            java.util.List<String> log,
            java.util.List<SimDiploEvent> events,
            java.util.List<DecisionRow> decisions
    ) {}



    // How often AIs attempt interactions
    private static final int INTERVAL_TICKS = 20 * 60 * 5; // every 5 minutes DEBUG FOR DEV

    // Limit how many targets each AI considers per cycle
    private static final int MAX_TARGETS_PER_AI = 6;

    // Stagger schedule: each AI gets its own next-due tick.
    private static final java.util.HashMap<UUID, Long> NEXT_DUE = new java.util.HashMap<>();

    // Prevent news bursts: max number of AI->AI actions per server tick.
    private static final int BUDGET_PER_TICK = 4;

    // Jitter around the interval; set to 0.0 for exact spacing after initial stagger.
    private static final double JITTER_FRAC = 0.25;

    private static long initialJitter(net.minecraft.util.RandomSource r) {
        return r.nextInt(Math.max(1, INTERVAL_TICKS));
    }

    private static long nextDelay(net.minecraft.util.RandomSource r) {
        int jitter = (int) (INTERVAL_TICKS * JITTER_FRAC);
        int delta = INTERVAL_TICKS + (jitter == 0 ? 0 : (r.nextInt(jitter * 2 + 1) - jitter));
        return Math.max(20, delta); // at least 1s
    }

    public static java.util.Map<UUID, Long> exportSchedule() {
        return new java.util.HashMap<>(NEXT_DUE);
    }

    public static void importSchedule(java.util.Map<UUID, Long> snap) {
        NEXT_DUE.clear();
        if (snap != null) NEXT_DUE.putAll(snap);
    }

    public static void clearSchedule() {
        NEXT_DUE.clear();
    }


    private static void emitEvent(
            java.util.List<SimDiploEvent> events,
            long nowTick,
            Letter.Kind kind,
            UUID fromId,
            UUID toId,
            String outcome,
            int relBefore,
            int relAfter
    ) {
        if (events == null) return;
        events.add(new SimDiploEvent(
                nowTick,
                kind,
                fromId,
                toId,
                outcome,
                relBefore,
                relAfter,
                (relAfter - relBefore)
        ));
    }

    private static void emitAttemptEvent(
            java.util.List<SimDiploEvent> events,
            long nowTick,
            Letter.Kind kind,
            UUID fromId,
            UUID toId,
            String outcome,
            int relBefore,
            int relationDelta
    ) {
        if (events == null) return;
        int relAfterPred = net.minecraft.util.Mth.clamp(relBefore + relationDelta, -100, 100);
        events.add(new SimDiploEvent(
                nowTick,
                kind,
                fromId,
                toId,
                outcome,
                relBefore,
                relAfterPred,
                relationDelta
        ));
    }


    public interface DecisionSink {
        void accept(AiDecision d);
    }

    public record AiDecision(
            long tick,
            java.util.UUID fromId,
            java.util.UUID toId,
            String action,          // "WAR_EVAL", "LETTER_EVAL"
            String kind,            // e.g. "REQUEST", "OFFER", "WAR_DECLARATION" (optional)
            String result,          // "ALLOW" or "BLOCKED_*"
            String reason,          // human-readable short reason (optional)
            int rel,                // relation at eval time (optional)
            int fromSoldiers,
            int toSoldiers,
            double aggression,
            double greed,
            double honor,
            double pragmatism,
            // economy snapshot (optional but very useful)
            double fromGold,
            double fromMeat,
            double fromGrain,
            double fromFish
    ) {}


    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(AiDiplomacyTicker::tick);
    }

    private static void addLocalNews(MinecraftServer server, long nowTick, KingdomNewsState news,kingdomState.Kingdom srcK, String text) {
        if (srcK == null) return;
        if (text == null || text.isBlank()) return;

        // choose terminal if it exists, otherwise origin
        var dimKey = srcK.hasTerminal ? srcK.terminalDim : net.minecraft.world.level.Level.OVERWORLD;
        var pos    = srcK.hasTerminal ? srcK.terminalPos : srcK.origin;

        ServerLevel lvl = server.getLevel(dimKey);
        if (lvl == null) lvl = server.overworld();

        news.add(nowTick, text, lvl, pos.getX(), pos.getZ());
    }


    /**
     * Run one diplomacy cycle using the SAME logic as the normal ticker,
     * but with a caller-supplied time and optional schedule bypass.
     *
     * @param nowTick simulated "current tick"
     * @param bypassSchedule if true, ignores NEXT_DUE checks (treat all AIs as due)
     */
    public static DebugResult debugStep(MinecraftServer server, long nowTick, boolean bypassSchedule) {
        var log = new java.util.ArrayList<String>();
        var events = new java.util.ArrayList<SimDiploEvent>();

        int actions = runCore(server, nowTick, bypassSchedule, null, log, events, null);
        return new DebugResult(actions, log, events, null);
    }

    public static DebugResult debugStep(MinecraftServer server,
                                        long nowTick,
                                        boolean bypassSchedule,
                                        java.util.Set<java.util.UUID> onlyIds) {
        var log = new java.util.ArrayList<String>();
        var events = new java.util.ArrayList<SimDiploEvent>();

        int actions = runCore(server, nowTick, bypassSchedule, onlyIds, log, events, null);
        return new DebugResult(actions, log, events, null);
    }



    private static void tick(MinecraftServer server) {
        runCore(server, server.getTickCount(), false, null, null, null, null);
    }

    private static int runCore(MinecraftServer server,
                           long nowTick,
                           boolean bypassSchedule,
                           java.util.Set<java.util.UUID> onlyIds,
                           java.util.List<String> log,
                           java.util.List<SimDiploEvent> events,
                           java.util.List<DecisionRow> decisions) {
        int actions = 0;
        var ks = kingdomState.get(server);
        var aiState = aiKingdomState.get(server);
        var warState = WarState.get(server);
        var alliance = AllianceState.get(server);
        var news = KingdomNewsState.get(server.overworld());
        var aiRel = AiRelationsState.get(server);
            
        var rng = server.overworld().getRandom();
        long now = nowTick;

        warState.tickAiWars(server, nowTick);

        // Collect AI kingdom ids from kingdom list (no aiState.getAll() needed)
        List<UUID> aiIds = new ArrayList<>();
        for (var k : ks.getAllKingdoms()) {
            if (k == null) continue;
            if (aiState.getById(k.id) != null) {
                aiIds.add(k.id);
            }
        }
        if (aiIds.size() < 2) return actions;

        // Optional filter: restrict sim to a subset of AI kingdoms
        if (onlyIds != null) {
            aiIds.removeIf(id -> !onlyIds.contains(id));
            if (aiIds.size() < 2) return actions;
        }


        java.util.HashMap<UUID, Integer> allyCounts = new java.util.HashMap<>();
        java.util.HashMap<UUID, Integer> enemyCounts = new java.util.HashMap<>();
        for (UUID id : aiIds) {
            int allies = 0;
            int enemies = 0;
            for (UUID other : aiIds) {
                if (other.equals(id)) continue;
                if (alliance.isAllied(id, other)) allies++;
                if (warState.isAtWar(id, other)) enemies++;
            }
            allyCounts.put(id, allies);
            enemyCounts.put(id, enemies);
        }


        // Soft prune: remove schedules for AIs that no longer exist
        // (cheap cleanup)
        NEXT_DUE.keySet().removeIf(id -> aiState.getById(id) == null);

        int budget = BUDGET_PER_TICK;

        // For each AI sender (staggered)
        for (UUID fromId : aiIds) {

            long due = NEXT_DUE.getOrDefault(fromId, -1L);
            if (due < 0) {
                // first time seeing this AI: stagger across full interval
                due = now + initialJitter(rng);
                NEXT_DUE.put(fromId, due);
            }

            // not time yet (unless bypassing schedule for simulation)
            if (!bypassSchedule && now < due) continue;

            // schedule next run immediately so we don't double-run
            NEXT_DUE.put(fromId, now + nextDelay(rng));

            // Build target list excluding self
            ArrayList<UUID> targets = new ArrayList<>(aiIds.size() - 1);
            for (UUID toId : aiIds) {
                if (!toId.equals(fromId)) targets.add(toId);
            }

            // Shuffle targets deterministically-ish for variety but stable per tick
            Collections.shuffle(targets, new Random(now ^ fromId.hashCode()));

            int limit = Math.min(MAX_TARGETS_PER_AI, targets.size());

            boolean didSomething = false;

            for (int i = 0; i < limit; i++) {
                UUID toId = targets.get(i);

                var fromAi = aiState.getById(fromId);
                var toAi   = aiState.getById(toId);
                if (fromAi == null || toAi == null) continue;

                // Recipient perspective: recipient is "decider"
                int relBefore = net.minecraft.util.Mth.clamp(aiRel.get(fromId, toId), -100, 100);
                int rel = relBefore;
                String execOutcome = null; // set only if something actually executes

                boolean alliedNow = alliance.isAllied(fromId, toId);
                boolean atWarWithOther = warState.isAtWar(fromId, toId);
                boolean atWarWithAnyone = warState.isAtWarWithAny(toId); // recipient side

                int recipientSoldiers = Math.max(0, toAi.aliveSoldiers);
                int senderSoldiersEst = Math.max(0, fromAi.aliveSoldiers);


                var w = previewWeights(fromAi.personality, rel, alliedNow, atWarWithOther, senderSoldiersEst, recipientSoldiers);

                // compute total
                double total = 0.0;
                for (double vv : w.values()) total += Math.max(0.0, vv);

                // compute war/ultimatum probability mass
                double wWar = Math.max(0.0, w.getOrDefault(Letter.Kind.WAR_DECLARATION, 0.0));
                double wUlt = Math.max(0.0, w.getOrDefault(Letter.Kind.ULTIMATUM, 0.0));

                double pWar = (total <= 1e-9) ? 0.0 : (wWar / total);
                double pUlt = (total <= 1e-9) ? 0.0 : (wUlt / total);

                // TODO: REMOVE IF HASMAP BLOCK BELOW WORKS
                emitEvent(
                        events,
                        nowTick,
                        Letter.Kind.CONTRACT, // kind here is just a placeholder for the record; we’ll override outcome label
                        fromId,
                        toId,
                        "DECISION_KIND_PICK rel=" + rel
                                + " allied=" + alliedNow
                                + " atWar=" + atWarWithOther
                                + " sA=" + senderSoldiersEst
                                + " sB=" + recipientSoldiers
                                + " pWar=" + fmt3(pWar)
                                + " pUlt=" + fmt3(pUlt)
                                + " " + summarizeWeights(w),
                        relBefore,
                        relBefore
                );

                if (decisions != null) {
                    java.util.HashMap<String, Double> ww = new java.util.HashMap<>();
                    for (var e : w.entrySet()) {
                        ww.put(e.getKey().name(), Math.max(0.0, e.getValue()));
                    }

                    decisions.add(new DecisionRow(
                            nowTick,
                            fromId,
                            toId,
                            rel,
                            alliedNow,
                            atWarWithOther,
                            senderSoldiersEst,
                            recipientSoldiers,
                            pWar,
                            pUlt,
                            ww
                    ));
                }



                // Pick a kind to attempt (personality-weighted)
                Letter.Kind kind = pickKind(
                        rng,
                        fromAi.personality,
                        rel,
                        alliedNow,
                        atWarWithOther,
                        senderSoldiersEst,
                        recipientSoldiers
                );


                // --- Peace offer sanity gate ---
                if (kind == Letter.Kind.WHITE_PEACE || kind == Letter.Kind.SURRENDER) {

                    int senderAlive = Math.max(0, fromAi.aliveSoldiers);
                    int senderTotal = Math.max(1, fromAi.maxSoldiers);
                    int enemyAlive  = Math.max(0, toAi.aliveSoldiers);
                    int enemyTotal  = Math.max(1, toAi.maxSoldiers);

                    boolean shouldOffer = PeaceEvaluator.shouldOfferPeace(
                            rng,
                            senderAlive, senderTotal,
                            enemyAlive, enemyTotal
                    );

                    if (!shouldOffer) {
                        emitEvent(events, nowTick, kind, fromId, toId, "SKIP_PEACE_GATE", relBefore, relBefore);
                        continue;
                    }
                }


                // POLICY ENFORCEMENT (AI→AI)
                var decision = DiplomacyAiSendRules.canSend(server, fromId, toId, kind);
                if (!decision.allowed()) {
                    emitEvent(events, nowTick, kind, fromId, toId, "BLOCKED_POLICY", relBefore, relBefore);
                    continue;
                }



                // Kingdom objects for stamping news location
                var fromK = ks.getKingdom(fromId);
                var toK   = ks.getKingdom(toId);

                int aiAllyCount = allyCounts.getOrDefault(toId, 0);
                int enemyApprox = enemyCounts.getOrDefault(toId, 0);

                
                var ctxEval = new DiplomacyEvaluator.Context(
                        rel,
                        alliedNow,
                        atWarWithOther,
                        atWarWithAnyone,
                        aiAllyCount,
                        enemyApprox,
                        recipientSoldiers,
                        senderSoldiersEst
                );

                // --------------------
                // Pick realistic terms (need/surplus-based)
                // --------------------
                ResourceType aType = ResourceType.GOLD;
                double aAmt = 0;
                ResourceType bType = null;
                double bAmt = 0;
                double maxAmt = 0;

                boolean senderAtWar = warState.isAtWarWithAny(fromId);

                if (kind == Letter.Kind.REQUEST || kind == Letter.Kind.ULTIMATUM) {
                    // ask for what we need
                    aType = pickByNeed(rng, fromAi, senderAtWar);
                    aAmt = proposeNeedAmount(rng, fromAi, aType, fromAi.personality);

                } else if (kind == Letter.Kind.OFFER) {
                    // offer what we have extra of
                    aType = pickBySpare(rng, fromAi, senderAtWar);
                    aAmt = proposeSpareAmount(rng, fromAi, aType, fromAi.personality);

                } else if (kind == Letter.Kind.CONTRACT) {
                    // want: something we need; give: something we have surplus of
                    aType = pickByNeed(rng, fromAi, senderAtWar);
                    aAmt = proposeNeedAmount(rng, fromAi, aType, fromAi.personality);

                    bType = pickBySpare(rng, fromAi, senderAtWar);
                    if (bType == aType) bType = ResourceType.GOLD;

                    // choose offer amount roughly matching base values, nudged by personality
                    bAmt = proposeContractGiveAmount(rng, aType, aAmt, bType, fromAi.personality);

                    // keep your existing cap behavior, but based on requested amount
                    maxAmt = aAmt * (2 + rng.nextInt(6)); // 2..7 trades worth
                }

                // If we somehow generated a tiny/zero amount, skip letter
                boolean needsAmount =
                        (kind == Letter.Kind.OFFER)
                    || (kind == Letter.Kind.REQUEST)
                    || (kind == Letter.Kind.CONTRACT)
                    || (kind == Letter.Kind.ULTIMATUM);

                if (needsAmount && aAmt <= 0) {
                    emitEvent(events, nowTick, kind, fromId, toId, "SKIP_ZERO_AMOUNT", relBefore, relBefore);
                    continue;
                }



                // --------------------
                // Hard execution checks
                // --------------------
                boolean recipientCanGiveA = switch (kind) {
                    case REQUEST, CONTRACT, ULTIMATUM -> has(toAi, aType, aAmt);
                    default -> true;
                };

                boolean senderCanGive = switch (kind) {
                    case OFFER -> has(fromAi, aType, aAmt);
                    case CONTRACT -> (bType != null) && has(fromAi, bType, bAmt);
                    default -> true;
                };

                boolean canExecuteIfAccepted = recipientCanGiveA && senderCanGive;

                // --------------------
                // Decide (recipient AI decides)
                // --------------------
                var res = DiplomacyEvaluator.decide(
                        rng,
                        kind,
                        toAi.personality,
                        toAi,
                        ctxEval,
                        aType, aAmt,
                        bType, bAmt,
                        maxAmt
                );

                boolean accepted = (res.decision() == DiplomacyEvaluator.Decision.ACCEPT);
                boolean counter  = (res.decision() == DiplomacyEvaluator.Decision.COUNTER);

                 // Attempt outcome (what the recipient decided)
                String attemptOutcome =
                        counter ? "TRY_COUNTERED" :
                        accepted ? "TRY_ACCEPTED" :
                        "TRY_DECLINED";

                // If accepted but cannot execute, force refuse (and log why)
                if (accepted && !canExecuteIfAccepted) {
                    accepted = false;
                    counter = false;
                    attemptOutcome = "TRY_ACCEPTED_BUT_CANT_EXECUTE";
                }

                if (events != null) {
                    emitAttemptEvent(events, nowTick, kind, fromId, toId, attemptOutcome, relBefore, res.relationDelta());
                }
              
               
                String fromName = nameOf(ks, aiState, fromId);
                String toName   = nameOf(ks, aiState, toId);

                


                // Apply evaluator relation deltas for normal diplomacy outcomes.
                // (Skip specials where we apply fixed deltas ourselves.)
                if (kind != Letter.Kind.ALLIANCE_PROPOSAL
                        && kind != Letter.Kind.WAR_DECLARATION
                        && kind != Letter.Kind.WHITE_PEACE
                        && kind != Letter.Kind.SURRENDER
                        && res.relationDelta() != 0) {
                    aiRel.addScaled(fromId, toId, res.relationDelta());
                }



                // --------------------
                // Special kinds with fixed effects
                // --------------------

                if (kind == Letter.Kind.WAR_DECLARATION) {
                    warState.declareWar(server, fromId, toId);
                    aiRel.addScaled(fromId, toId, -80);
                    execOutcome = "WAR_DECLARED";

                    addLocalNews(server,nowTick, news, fromK,
                            "[WAR] " + fromName + " declared war on " + toName + ".");
                    didSomething = true;
                }
                else if (kind == Letter.Kind.ALLIANCE_PROPOSAL) {
                    if (accepted) {
                        if (!alliance.canAlly(fromId, toId)) {
                            accepted = false;
                            counter = false;
                        }
                        
                    }

                    if (accepted) {
                        alliance.addAlliance(fromId, toId);
                        aiRel.addScaled(fromId, toId, +30);
                        addLocalNews(server, nowTick, news, fromK,
                                "[ALLIANCE] " + fromName + " formed an alliance with " + toName + ".");
                                execOutcome = "ALLIANCE_FORMED";

                    } else {
                        aiRel.addScaled(fromId, toId, -10);
                          execOutcome = "ALLIANCE_DECLINED";
                        if (rng.nextFloat() < 0.25f) {
                            addLocalNews(server, nowTick, news, (toK != null ? toK : fromK),
                                    "[DIPLOMACY] " + toName + " refused an alliance with " + fromName + ".");
                                  
                        }
                    }
                    didSomething = true;
                }
                else if (kind == Letter.Kind.ALLIANCE_BREAK) {
                    alliance.breakAlliance(fromId, toId);
                    aiRel.addScaled(fromId, toId, -10);
                    addLocalNews(server, nowTick, news, fromK,
                            "[ALLIANCE] " + fromName + " broke alliance with " + toName + ".");
                            execOutcome = "ALLIANCE_BROKEN";
                    didSomething = true;
                }
                else if (kind == Letter.Kind.WHITE_PEACE || kind == Letter.Kind.SURRENDER) {

                int recipientAlive = Math.max(0, toAi.aliveSoldiers);
                int recipientTotal = Math.max(1, toAi.maxSoldiers);
                int senderAlive    = Math.max(0, fromAi.aliveSoldiers);
                int senderTotal    = Math.max(1, fromAi.maxSoldiers);

                var peaceDecision  = PeaceEvaluator.decideAccept(
                        rng,
                        recipientAlive, recipientTotal,
                        senderAlive, senderTotal
                );

                if (peaceDecision  == PeaceEvaluator.Decision.ACCEPT) {
                    warState.makePeace(fromId, toId);
                    addLocalNews(server, nowTick, news, fromK,
                            "[PEACE] " + fromName + " made peace with " + toName + ".");
                             execOutcome = "PEACE_ACCEPTED";
                } else {
                    // Peace refused → resentment
                    aiRel.addScaled(fromId, toId, -3);

                    if (rng.nextFloat() < 0.15f) {
                        addLocalNews(server, nowTick, news, (toK != null ? toK : fromK),
                                "[PEACE] " + toName + " refused peace with " + fromName + ".");
                                execOutcome = "PEACE_DECLINED";
                    }
                }

                didSomething = true;
            }


                else if (kind == Letter.Kind.ULTIMATUM) {
                    if (accepted && canExecuteIfAccepted) {
                        add(toAi, aType, -aAmt);
                        add(fromAi, aType, +aAmt);
                        int d = economicRelDelta(
                                Letter.Kind.ULTIMATUM,
                                toAi,     // giver (the one who paid)
                                fromAi,   // receiver (the bully)
                                aType, aAmt,
                                null, 0,
                                atWarWithOther,
                                relBefore
                        );
                        aiRel.addScaled(fromId, toId, d - 60); // keep it strongly negative

                        addLocalNews(server, nowTick, news, (toK != null ? toK : fromK),
                                "[ULTIMATUM] " + toName + " yielded to " + fromName + " (" + fmt(aAmt) + " " + aType + ").");
                                execOutcome = "ULTIMATUM_PAID";

                    } else {
                        warState.declareWar(server, fromId, toId);
                        aiRel.addScaled(fromId, toId, -80);
                        addLocalNews(server, nowTick, news, fromK,
                                "[WAR] " + fromName + " went to war with " + toName + " after an ultimatum.");
                                execOutcome = "ULTIMATUM_REFUSED_WAR";

                    }
                    aiState.setDirty();
                    didSomething = true;
                }
                else {
                    // Economic execution when accepted
                    if (accepted && canExecuteIfAccepted) {
                        switch (kind) {
                            case REQUEST -> {
                                add(toAi, aType, -aAmt);
                                add(fromAi, aType, +aAmt);
                                int d = economicRelDelta(
                                        Letter.Kind.REQUEST,
                                        toAi,     // giver = recipient AI (they gave goods)
                                        fromAi,   // receiver = sender AI (they received)
                                        aType, aAmt,
                                        null, 0,
                                        atWarWithOther,
                                        relBefore
                                );
                                aiRel.addScaled(fromId, toId, d);

                                addLocalNews(server, nowTick, news, (toK != null ? toK : fromK),
                                        "[TRADE] " + toName + " fulfilled a request from " + fromName + " (" + fmt(aAmt) + " " + aType + ").");

                                execOutcome = "REQUEST_ACCEPTED";
                                didSomething = true;
                            }
                            case OFFER -> {
                                add(fromAi, aType, -aAmt);
                                add(toAi, aType, +aAmt);
                                int d = economicRelDelta(
                                        Letter.Kind.OFFER,
                                        fromAi,   // giver = sender AI
                                        toAi,     // receiver = recipient AI
                                        aType, aAmt,
                                        null, 0,
                                        atWarWithOther,
                                        relBefore
                                );
                                aiRel.addScaled(fromId, toId, d);

                                addLocalNews(server, nowTick, news, fromK,
                                        "[TRADE] " + fromName + " sent an offer to " + toName + " (" + fmt(aAmt) + " " + aType + ").");

                                execOutcome = "REQUEST_ACCEPTED";
                                didSomething = true;
                            }
                            case CONTRACT -> {
                                add(toAi, aType, -aAmt);
                                add(fromAi, aType, +aAmt);
                                if (bType != null) {
                                    add(fromAi, bType, -bAmt);
                                    add(toAi, bType, +bAmt);
                                }
                                int d = economicRelDelta(
                                        Letter.Kind.CONTRACT,
                                        fromAi,   // giver = sender gives bType
                                        toAi,     // receiver gets bType, but also gives aType
                                        bType, bAmt,     // giveType/amt from sender to receiver
                                        aType, aAmt,     // takeType/amt receiver gives back
                                        atWarWithOther,
                                        relBefore
                                );
                                aiRel.addScaled(fromId, toId, d);

                                addLocalNews(server, nowTick, news, fromK,
                                        "[TRADE] " + fromName + " and " + toName + " signed a contract: "
                                                + fmt(bAmt) + " " + bType + " → " + fmt(aAmt) + " " + aType
                                                + " (cap " + fmt(maxAmt) + ").");

                                execOutcome = "CONTRACT_ACCEPTED";
                                didSomething = true;
                            }
                            default -> {
                                // for compliment/insult/warning: log rarely
                                if (kind == Letter.Kind.COMPLIMENT) {
                                    execOutcome = "COMPLIMENT_SENT";
                                    didSomething = true;
                                    addLocalNews(server, nowTick, news, fromK,
                                    "[DIPLOMACY] " + fromName + " praised " + toName + ".");
                                    
                                }
                                else if (kind == Letter.Kind.INSULT) {
                                    execOutcome = "INSULT_SENT";
                                    didSomething = true;
                                        addLocalNews(server, nowTick, news, fromK,
                                                "[DIPLOMACY] " + fromName + " insulted " + toName + ".");
                                }
                                else if (kind == Letter.Kind.WARNING) {
                                    execOutcome = "WARNING_SENT";
                                    didSomething = true;
                                        addLocalNews(server, nowTick, news, fromK,
                                                "[DIPLOMACY] " + fromName + " warned " + toName + ".");
                                    
                                }

                            }
                        }
                        if (didSomething) aiState.setDirty();
                    }
                }

                if (didSomething) {
                   
                    actions++;

                    // log line (optional)
                    if (log != null) {
                        log.add(kind.name() + " " + fromId + " -> " + toId + " (" + execOutcome + ") " + fromName + " -> " + toName);
                    }

                    // event capture (REL BEFORE/AFTER)
                    int relAfter = aiRel.get(fromId, toId);
                    int relDelta = relAfter - relBefore;

                    if (events != null) {
                        events.add(new SimDiploEvent(
                                nowTick,
                                kind,
                                fromId,
                                toId,
                                execOutcome,
                                relBefore,
                                relAfter,
                                relDelta
                        ));
                    }

                    // limit bursts
                    if (--budget <= 0) return actions;

                    // only one successful action per AI per due tick
                    break;
                }


            }
        }
        return actions;
    }

    public static Letter.Kind pickKind(
        net.minecraft.util.RandomSource rng,
        aiKingdomState.KingdomPersonality p,
        int rel,
        boolean allied,
        boolean atWarWithOther,
        int senderSoldiers,
        int recipientSoldiers
) {
    var weights = previewWeights(p, rel, allied, atWarWithOther, senderSoldiers, recipientSoldiers);
    double total = 0.0;
    for (double w : weights.values()) total += Math.max(0.0, w);

    if (total <= 1e-9) return Letter.Kind.CONTRACT;
   
    double roll = rng.nextDouble() * total;
    for (var e : weights.entrySet()) {
        double w = Math.max(0.0, e.getValue());
        if (w <= 0.0) continue;  
        roll -= w;
        if (roll <= 0.0) return e.getKey();
    }
    return Letter.Kind.CONTRACT;
    }

    public static java.util.LinkedHashMap<Letter.Kind, Double> previewWeights(
            aiKingdomState.KingdomPersonality personality,
            int rel,
            boolean allied,
            boolean atWarWithOther,
            int senderSoldiers,
            int recipientSoldiers
    ) {
        // personality traits (defaults match your old)
        double gen = personality == null ? 0.50 : personality.generosity();
        double grd = personality == null ? 0.50 : personality.greed();
        double tru = personality == null ? 0.50 : personality.trustBias();
        double hon = personality == null ? 0.50 : personality.honor();
        double agg = personality == null ? 0.35 : personality.aggression();
        double pra = personality == null ? 0.60 : personality.pragmatism();

        // --- helper signals ---
        // We want attractors around +/-40 (not +/-100)
        double pos = clamp01((rel - 10) / 60.0);     // ~0 at <=10, ~1 at >=70
        double neg = clamp01((-rel - 10) / 60.0);    // ~0 at >=-10, ~1 at <=-70

        // How close are we to the target bands (+/-40)?
        double nearPos40 = 1.0 - clamp01(Math.abs(rel - 40) / 45.0);   // peak near +40
        double nearNeg40 = 1.0 - clamp01(Math.abs(rel + 40) / 45.0);   // peak near -40

        // Reduce “spiral to extremes”: hostility actions fade when very low already.
        double deepNeg = clamp01((-rel - 70) / 30.0);  // 0 above -70, ->1 near -100
        double deepPos = clamp01((rel - 70) / 30.0);   // 0 below +70, ->1 near +100

        // military advantage (0..1)
        double adv = 0.0;
        int denom = Math.max(50, recipientSoldiers);
        if (senderSoldiers > recipientSoldiers) {
            adv = clamp01((senderSoldiers - recipientSoldiers) / (double) denom);
        }

        var map = new java.util.LinkedHashMap<Letter.Kind, Double>();

        // --------------------
        // WAR STATE
        // --------------------
        if (atWarWithOther) {
            // In war, keep actions limited; allow peace to be common to prevent endless wars.
            double peace = 0.85 + (hon - 0.5) * 0.55 + (tru - 0.5) * 0.25 - (agg - 0.35) * 0.85;
            double surrender = 0.12 + (1.0 - adv) * 0.35 + nearNeg40 * 0.10;
            double insult = 0.10 + nearNeg40 * 0.35 + (agg - 0.35) * 0.40;
            double warning = 0.10 + nearNeg40 * 0.25 + (agg - 0.35) * 0.25;

            // If relations are already rock-bottom, reduce “spam hostility”
            insult *= (1.0 - 0.65 * deepNeg);
            warning *= (1.0 - 0.40 * deepNeg);

            map.put(Letter.Kind.WHITE_PEACE, Math.max(0.0, peace));
            map.put(Letter.Kind.SURRENDER, Math.max(0.0, surrender));
            map.put(Letter.Kind.INSULT, Math.max(0.0, insult));
            map.put(Letter.Kind.WARNING, Math.max(0.0, warning));
            return map;
        }

        // --------------------
        // ALLIED STATE
        // --------------------
        if (allied) {
            // Allies should mostly stay friendly (~+40) but can drift and occasionally break.
            double offer = 0.95 + (pra - 0.60) * 0.70 + (gen - 0.5) * 0.55 + nearPos40 * 0.40;
            double contract = 0.85 + (pra - 0.60) * 0.90 + (grd - 0.5) * 0.15 + nearPos40 * 0.30;
            double compliment = 0.55 + pos * 0.80 + (hon - 0.5) * 0.25 + nearPos40 * 0.30;

            // alliance breaks should be rare and mostly driven by bad rel + aggression/low honor
            double relNeg = clamp01((-rel - 10) / 60.0);
            double breakAlly = 0.02 + relNeg * 0.22 + (agg - 0.35) * 0.10 - (hon - 0.5) * 0.28;

            map.put(Letter.Kind.OFFER, Math.max(0.0, offer));
            map.put(Letter.Kind.CONTRACT, Math.max(0.0, contract));
            map.put(Letter.Kind.COMPLIMENT, Math.max(0.0, compliment));
            map.put(Letter.Kind.ALLIANCE_BREAK, Math.max(0.0, breakAlly));
            return map;
        }

        // --------------------
        // NEUTRAL / GENERAL STATE
        // --------------------
        // Econ actions are the “stabilizer” (they keep you around +/-40 instead of drifting to extremes).
        // Social actions provide “texture” and allow flips.

        // Baseline econ
        double offer    = 0.50 + (pra - 0.60) * 0.70 + (gen - 0.5) * 0.30;
        double request  = 0.50 + (pra - 0.60) * 0.70 + (grd - 0.5) * 0.35;
        double contract = 0.45 + (pra - 0.60) * 0.85 + (grd - 0.5) * 0.20;

        // When already friendly, keep trading but not exploding to +100
        offer    += nearPos40 * 0.25;
        contract += nearPos40 * 0.35;

        // When hostile around -40, contracts become a realistic “cold trade” stabilizer
        contract += nearNeg40 * 0.40;
        request  += nearNeg40 * 0.20; // "we need it" even if we dislike you

        // Dampen econ when extremely hostile OR extremely friendly (avoid pinning at extremes)
        offer    *= (1.0 - 0.55 * deepPos) * (1.0 - 0.20 * deepNeg);
        contract *= (1.0 - 0.45 * deepPos) * (1.0 - 0.25 * deepNeg);

        // Alliances: mostly happen in the +40 band
        double alliance =
                0.05
                + nearPos40 * 0.45
                + pos * 0.35
                + (hon - 0.5) * 0.25
                + (tru - 0.5) * 0.22
                - (agg - 0.35) * 0.20;

        // Compliments: help maintain friendly attractor
        double compliment =
                0.18
                + nearPos40 * 0.45
                + pos * 0.30
                + (gen - 0.5) * 0.20
                + (hon - 0.5) * 0.15;

        // Hostility actions: peak around -40, fade when already at -100
        double insult =
                0.12
                + nearNeg40 * 0.75
                + neg * 0.35
                + (agg - 0.35) * 0.55
                + (0.5 - hon) * 0.25;

        double warning =
                0.10
                + nearNeg40 * 0.50
                + neg * 0.25
                + (agg - 0.35) * 0.25;

        insult *= (1.0 - 0.70 * deepNeg);  // stop “insult spiral to -100”
        warning *= (1.0 - 0.45 * deepNeg);

        // Ultimatum: rare, mostly when hostile and advantaged, but not when already at -100 (it becomes boring spam)
        double ultimatum =
                0.010
                + nearNeg40 * 0.10
                + neg * 0.08
                + adv * 0.18
                + (agg - 0.35) * 0.12
                - (hon - 0.5) * 0.18;

        ultimatum *= (1.0 - 0.80 * deepNeg);

        // War: occasional escalation, mostly from hostile basin + advantage
        double war =
                0.004
                + nearNeg40 * 0.06
                + neg * 0.07
                + adv * 0.18
                + (agg - 0.35) * 0.14
                - (hon - 0.5) * 0.16;

        // don’t declare war if relations aren't actually negative-ish
        if (rel > -20) war *= 0.15;

        map.put(Letter.Kind.OFFER, Math.max(0.0, offer));
        map.put(Letter.Kind.REQUEST, Math.max(0.0, request));
        map.put(Letter.Kind.CONTRACT, Math.max(0.0, contract));
        map.put(Letter.Kind.COMPLIMENT, Math.max(0.0, compliment));
        map.put(Letter.Kind.ALLIANCE_PROPOSAL, Math.max(0.0, alliance));

        map.put(Letter.Kind.INSULT, Math.max(0.0, insult));
        map.put(Letter.Kind.WARNING, Math.max(0.0, warning));
        map.put(Letter.Kind.ULTIMATUM, Math.max(0.0, ultimatum));
        map.put(Letter.Kind.WAR_DECLARATION, Math.max(0.0, war));

        return map;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }


    // --------------------
    // Proposal realism helpers
    // --------------------

    private static final java.util.EnumMap<ResourceType, Double> TARGET = new java.util.EnumMap<>(ResourceType.class);
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

    private static double target(ResourceType t) {
        return TARGET.getOrDefault(t, 100.0);
    }

    private static double stock(aiKingdomState.AiKingdom k, ResourceType t) {
        // Prefer direct fields for accuracy (same as DiplomacyEvaluator)
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

    private static double needFactor(aiKingdomState.AiKingdom k, ResourceType t) {
        double tar = Math.max(1.0, target(t));
        double s = stock(k, t);
        return clamp01((tar - s) / tar); // 0..1 (1 = very low)
    }

    private static double spareFactor(aiKingdomState.AiKingdom k, ResourceType t) {
        double tar = Math.max(1.0, target(t));
        double s = stock(k, t);
        return clamp01((s - tar) / tar); // 0..1 (1 = lots of surplus)
    }

    private static boolean isWarMaterial(ResourceType t) {
        return switch (t) {
            case METAL, WEAPONS, ARMOR, GOLD, HORSES, POTIONS -> true;
            default -> false;
        };
    }

    private static ResourceType pickByNeed(net.minecraft.util.RandomSource rng, aiKingdomState.AiKingdom k, boolean atWar) {
        double total = 0.0;
        java.util.ArrayList<ResourceType> types = new java.util.ArrayList<>();
        java.util.ArrayList<Double> weights = new java.util.ArrayList<>();

        for (ResourceType t : ResourceType.values()) {
            double w = 0.10 + 1.40 * needFactor(k, t);      // baseline + need
            if (atWar && isWarMaterial(t)) w += 0.45;       // war demand bump
            total += w;
            types.add(t);
            weights.add(w);
        }

        double roll = rng.nextDouble() * total;
        for (int i = 0; i < types.size(); i++) {
            roll -= weights.get(i);
            if (roll <= 0) return types.get(i);
        }
        return ResourceType.GOLD;
    }

    private static ResourceType pickBySpare(net.minecraft.util.RandomSource rng, aiKingdomState.AiKingdom k, boolean atWar) {
        double total = 0.0;
        java.util.ArrayList<ResourceType> types = new java.util.ArrayList<>();
        java.util.ArrayList<Double> weights = new java.util.ArrayList<>();

        for (ResourceType t : ResourceType.values()) {
            double spare = spareFactor(k, t);
            if (spare <= 0.05) continue;                    // ignore no-surplus items
            double w = 0.15 + 1.25 * spare;
            if (atWar && isWarMaterial(t)) w *= 0.35;       // don’t offer away war mats in war
            total += w;
            types.add(t);
            weights.add(w);
        }

        if (total <= 0.00001) return ResourceType.GOLD;

        double roll = rng.nextDouble() * total;
        for (int i = 0; i < types.size(); i++) {
            roll -= weights.get(i);
            if (roll <= 0) return types.get(i);
        }
        return ResourceType.GOLD;
    }

    /** Amount for requesting/ultimatum: based on how far below target we are, scaled by personality. */
    private static double proposeNeedAmount(net.minecraft.util.RandomSource rng, aiKingdomState.AiKingdom sender, ResourceType t,
                                        aiKingdomState.KingdomPersonality p) {
        double tar = target(t);
        double s = stock(sender, t);
        double deficit = Math.max(0.0, tar - s);

        double base = deficit * 0.15; // take 15% of deficit
        base = Math.max(5.0, Math.min(50.0, base));

        double greed = p == null ? 0.50 : p.greed();
        double prag  = p == null ? 0.60 : p.pragmatism();

        // greed asks more; pragmatism asks a bit less (more reasonable)
        double mult = 0.85 + 0.55 * greed - 0.20 * (prag - 0.60);

        // slight randomization
        double jitter = 0.85 + rng.nextDouble() * 0.30; // 0.85..1.15

        return Math.max(1.0, Math.round(base * mult * jitter));
    }

    /** Amount for offering: based on surplus above target, scaled by generosity. */
    private static double proposeSpareAmount(net.minecraft.util.RandomSource rng, aiKingdomState.AiKingdom sender, ResourceType t,
                                            aiKingdomState.KingdomPersonality p) {
        double tar = target(t);
        double s = stock(sender, t);
        double surplus = Math.max(0.0, s - tar);

        double base = surplus * 0.10; // offer 10% of surplus
        base = Math.max(5.0, Math.min(50.0, base));

        double gen = p == null ? 0.50 : p.generosity();
        double mult = 0.70 + 0.80 * gen;

        double jitter = 0.85 + rng.nextDouble() * 0.30;

        return Math.max(1.0, Math.round(base * mult * jitter));
    }

    /** Computes bAmt so the base-value ratio is roughly fair-ish. Requires ResourceValues.goldValue(...) to exist. */
    private static double proposeContractGiveAmount(net.minecraft.util.RandomSource rng,
                                                ResourceType wantType, double wantAmt,
                                                ResourceType giveType,
                                                aiKingdomState.KingdomPersonality p) {
        double gvWant = ResourceValues.goldValue(wantType);
        double gvGive = ResourceValues.goldValue(giveType);

        if (gvWant <= 0 || gvGive <= 0) {
            // fallback if values aren’t defined
            return Math.max(5.0, Math.min(35.0, Math.round(wantAmt)));
        }

        double greed = p == null ? 0.50 : p.greed();
        double gen   = p == null ? 0.50 : p.generosity();
        double prag  = p == null ? 0.60 : p.pragmatism();

        // This is “how fair to the recipient is the offer”, in base-value terms.
        // Greedy = slightly less fair. Generous/pragmatic = slightly more fair.
        double fairness = 1.00 - 0.15 * (greed - 0.50) + 0.08 * (gen - 0.50) + 0.08 * (prag - 0.60);
        fairness = Math.max(0.80, Math.min(1.15, fairness));

        double raw = wantAmt * (gvWant / gvGive) * fairness;
        // clamp to your existing contract range
        raw = Math.max(5.0, Math.min(35.0, raw));

        double jitter = 0.90 + rng.nextDouble() * 0.20; // 0.90..1.10
        return Math.max(1.0, Math.round(raw * jitter));
    }

    private static int economicRelDelta(
                        Letter.Kind kind,
                        aiKingdomState.AiKingdom giver,
                        aiKingdomState.AiKingdom receiver,
                        ResourceType giveType, double giveAmt,
                        ResourceType takeType, double takeAmt,
                        boolean atWarBetween,
                        int relBefore
                ) {
                    // Personalities
                    var gp = giver.personality;
                    var rp = receiver.personality;

                    double gGen = gp == null ? 0.50 : gp.generosity();
                    double gGreed = gp == null ? 0.50 : gp.greed();

                    double rTrust = rp == null ? 0.50 : rp.trustBias();
                    double rHonor = rp == null ? 0.50 : rp.honor();
                    double rGreed = rp == null ? 0.50 : rp.greed();
                    double rPrag  = rp == null ? 0.60 : rp.pragmatism();

                    // Convert to base gold value so "big trade" matters more than "5 fish"
                    double giveGV = ResourceValues.goldValue(giveType) * giveAmt;
                    double takeGV = (takeType == null ? 0.0 : ResourceValues.goldValue(takeType) * takeAmt);

                    // How valuable was it TO THE RECEIVER? (need amplifies)
                    double recvNeedGive = needFactor(receiver, giveType); // 0..1
                    double recvValue = giveGV * (0.85 + 0.75 * recvNeedGive);

                    // How painful was it TO THE GIVER? (need amplifies)
                    double giverNeedGive = needFactor(giver, giveType);
                    double giverPain = giveGV * (0.85 + 0.95 * giverNeedGive);

                    // For contracts: receiver also gives something (takeType) and feels pain from that
                    double recvPainTake = 0.0;
                    if (takeType != null) {
                        double recvNeedTake = needFactor(receiver, takeType);
                        recvPainTake = takeGV * (0.85 + 0.95 * recvNeedTake);
                    }

                    // A compact "goodwill signal" scale:
                    // - up to ~+6-ish for meaningful gifts/trades
                    // - small things barely move relation
                    double base = 0.0;

                    switch (kind) {
                        case OFFER -> {
                            // Gifts: receiver likes value; giver pain slightly reduces how often huge gifts happen
                            base = (recvValue / 250.0) * (0.8 + 0.7 * rTrust)     // trusting receivers appreciate
                                + (giverPain / 350.0) * (0.3 + 0.8 * gGen);      // generous givers get credit
                        }
                        case REQUEST -> {
                            // Receiver gave away goods: relation should go up, but depends on trust & fairness vibe
                            // If receiver was pressured (low trust/honor), less goodwill gained.
                            base = (giverPain / 300.0) * (0.6 + 0.6 * rTrust)     // "we helped you"
                                - (giverPain / 600.0) * (0.4 + 0.8 * rGreed);    // greedy receivers resent giving
                        }
                        case CONTRACT -> {
                            // Trade goodwill: depends on how good it was for receiver vs what receiver gave up.
                            double net = recvValue - recvPainTake; // positive = good deal for receiver
                            base = (net / 250.0) * (0.8 + 0.6 * rPrag)            // pragmatic appreciates good trades
                                + (Math.min(recvValue, recvPainTake) / 500.0) * (0.2 + 0.5 * rTrust); // stable trade builds trust
                        }
                        case ULTIMATUM -> {
                            // Even if complied, relationship worsens. Honor magnifies the insult.
                            base = -2.0
                                - (recvValue / 250.0) * (0.5 + 0.8 * rHonor)
                                - (takeGV / 250.0) * 0.2;
                        }
                        default -> base = 0.0;
                    }

                    // War context makes economic goodwill matter less (or resentment stronger)
                    if (atWarBetween) {
                        if (kind == Letter.Kind.OFFER || kind == Letter.Kind.CONTRACT) base *= 0.65;
                        if (kind == Letter.Kind.ULTIMATUM) base *= 1.20;
                    }

                    // Sender greed slightly reduces "good faith" impression; generosity increases it
                    if (kind == Letter.Kind.OFFER || kind == Letter.Kind.CONTRACT) {
                        base *= (0.85 + 0.35 * gGen);
                        base *= (1.05 - 0.25 * (gGreed - 0.50));
                    }

                    // Clamp and convert to relation points
                    int delta = (int) Math.round(base);

                    // Keep it sane
                    delta = Math.max(-12, Math.min(12, delta));

                    // Ensure small deals still do something sometimes (optional)
                    if (delta == 0) {
                    boolean lowRel = relBefore < 15; // tweakable: 10–25 are good ranges
                    if (lowRel) {
                        if (kind == Letter.Kind.CONTRACT) delta = 1;
                        if (kind == Letter.Kind.OFFER) delta = 1;
                    }
                }

                    return delta;
                }

    private static String nameOf(kingdomState ks, aiKingdomState aiState, UUID id) {
        var k = ks.getKingdom(id);
        if (k != null && k.name != null && !k.name.isBlank()) return k.name;

        String aiName = aiState.getNameById(id);
        if (aiName != null && !aiName.isBlank()) return aiName;

        return "Unknown";
    }

    private static String fmt(double v) {
        if (Math.abs(v - Math.round(v)) < 0.000001) return Long.toString(Math.round(v));
        return String.format(Locale.US, "%.2f", v);
    }

    private static boolean has(aiKingdomState.AiKingdom k, ResourceType t, double amt) {
        return name.kingdoms.network.networkInit.getAi(k, t) >= amt;
    }

    private static void add(aiKingdomState.AiKingdom k, ResourceType t, double delta) {
        name.kingdoms.network.networkInit.addAi(k, t, delta);
    }

   
    
    

}
