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

    // How often AIs attempt interactions
    private static final int INTERVAL_TICKS = 20 * 30 * 1; // every 30 seconds

    // Limit how many targets each AI considers per cycle
    private static final int MAX_TARGETS_PER_AI = 6;

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(AiDiplomacyTicker::tick);
    }

    private static void addLocalNews(MinecraftServer server, KingdomNewsState news,
                                    kingdomState.Kingdom srcK, String text) {
        if (srcK == null) return;
        if (text == null || text.isBlank()) return;

        // choose terminal if it exists, otherwise origin
        var dimKey = srcK.hasTerminal ? srcK.terminalDim : net.minecraft.world.level.Level.OVERWORLD;
        var pos    = srcK.hasTerminal ? srcK.terminalPos : srcK.origin;

        ServerLevel lvl = server.getLevel(dimKey);
        if (lvl == null) lvl = server.overworld();

        news.add(server.getTickCount(), text, lvl, pos.getX(), pos.getZ());
    }

    private static void tick(MinecraftServer server) {
        if (server.getTickCount() % INTERVAL_TICKS != 0) return;

        var ks = kingdomState.get(server);
        var aiState = aiKingdomState.get(server);
        var warState = WarState.get(server);
        var alliance = AllianceState.get(server);
        var news = KingdomNewsState.get(server.overworld());

        var aiRel = AiRelationsState.get(server);

        var rng = server.overworld().getRandom();

        // Collect AI kingdom ids from kingdom list (no aiState.getAll() needed)
        List<UUID> aiIds = new ArrayList<>();
        for (var k : ks.getAllKingdoms()) {
            if (k == null) continue;
            if (aiState.getById(k.id) != null) {
                aiIds.add(k.id);
            }
        }

        if (aiIds.size() < 2) return;

        // For each AI sender
        for (UUID fromId : aiIds) {

            // Build target list excluding self
            ArrayList<UUID> targets = new ArrayList<>(aiIds.size() - 1);
            for (UUID toId : aiIds) {
                if (!toId.equals(fromId)) targets.add(toId);
            }

            // Shuffle targets deterministically-ish for variety but stable per tick
            Collections.shuffle(targets, new Random(server.getTickCount() ^ fromId.hashCode()));

            int limit = Math.min(MAX_TARGETS_PER_AI, targets.size());

            for (int i = 0; i < limit; i++) {
                UUID toId = targets.get(i);

                // Pick a kind to attempt (keep it simple at first; tune weights later)
                Letter.Kind kind = pickKind(rng);

                // POLICY ENFORCEMENT (AI→AI)
                var decision = DiplomacyAiSendRules.canSend(server, fromId, toId, kind);
                if (!decision.allowed()) {
                    continue; // skip illegal actions
                }

                var fromAi = aiState.getById(fromId);
                var toAi   = aiState.getById(toId);
                if (fromAi == null || toAi == null) continue;

                // Kingdom objects for stamping news location
                var fromK = ks.getKingdom(fromId);
                var toK   = ks.getKingdom(toId);

                // Recipient perspective: recipient is "decider"
                int rel = aiRel.get(fromId, toId);
                boolean alliedNow = alliance.isAllied(fromId, toId);
                boolean atWarWithOther = warState.isAtWar(fromId, toId);
                boolean atWarWithAnyone = warState.isAtWarWithAny(toId); // recipient side

                int aiAllyCount = alliance.alliesOf(toId).size();
                int enemyApprox = atWarWithAnyone ? 1 : 0;

                int recipientSoldiers = Math.max(0, toAi.aliveSoldiers);
                int senderSoldiersEst = Math.max(0, fromAi.aliveSoldiers);

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
                // Pick simple terms
                // --------------------
                ResourceType aType = ResourceType.GOLD;
                double aAmt = 0;
                ResourceType bType = null;
                double bAmt = 0;
                double maxAmt = 0;

                if (kind == Letter.Kind.OFFER || kind == Letter.Kind.REQUEST || kind == Letter.Kind.ULTIMATUM) {
                    ResourceType[] vals = ResourceType.values();
                    aType = vals[rng.nextInt(vals.length)];
                    aAmt = 5 + rng.nextInt(46); // 5..50
                }

                if (kind == Letter.Kind.CONTRACT) {
                    ResourceType[] vals = ResourceType.values();
                    aType = vals[rng.nextInt(vals.length)];
                    bType = vals[rng.nextInt(vals.length)];
                    if (bType == aType) bType = ResourceType.GOLD;

                    aAmt = 5 + rng.nextInt(31);  // 5..35
                    bAmt = 5 + rng.nextInt(31);  // 5..35
                    maxAmt = aAmt * (2 + rng.nextInt(6)); // 2..7 trades worth
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

                // If accepted but cannot execute, force refuse.
                if (accepted && !canExecuteIfAccepted) {
                    accepted = false;
                    counter = false;
                }

                String fromName = nameOf(ks, aiState, fromId);
                String toName   = nameOf(ks, aiState, toId);

                // --------------------
                // Apply AI↔AI relation delta for normal kinds
                // Skip special kinds handled below
                // --------------------
                if (kind != Letter.Kind.ALLIANCE_PROPOSAL
                        && kind != Letter.Kind.WAR_DECLARATION
                        && kind != Letter.Kind.ULTIMATUM
                        && res.relationDelta() != 0) {
                    aiRel.add(fromId, toId, res.relationDelta());
                }

                // --------------------
                // Special kinds with fixed effects
                // --------------------

                // WAR_DECLARATION: always declares war + fixed hostility
                if (kind == Letter.Kind.WAR_DECLARATION) {
                    warState.declareWar(server, fromId, toId);
                    aiRel.add(fromId, toId, -80);
                    addLocalNews(server, news, fromK,
                            "[WAR] " + fromName + " declared war on " + toName + ".");
                    continue;
                }

                // ALLIANCE_PROPOSAL: fixed accept/refuse bumps (+30 / -10)
                if (kind == Letter.Kind.ALLIANCE_PROPOSAL) {
                    if (accepted) {
                        if (!alliance.canAlly(fromId, toId)) {
                            accepted = false;
                            counter = false;
                        }
                    }

                    if (accepted) {
                        alliance.addAlliance(fromId, toId);
                        aiRel.add(fromId, toId, +30);
                        addLocalNews(server, news, fromK,
                                "[ALLIANCE] " + fromName + " formed an alliance with " + toName + ".");
                    } else {
                        aiRel.add(fromId, toId, -10);
                        // log refusals sometimes to reduce spam
                        if (rng.nextFloat() < 0.25f) {
                            addLocalNews(server, news, (toK != null ? toK : fromK),
                                    "[DIPLOMACY] " + toName + " refused an alliance with " + fromName + ".");
                        }
                    }
                    continue;
                }

                // ALLIANCE_BREAK: break immediately; small relation hit
                if (kind == Letter.Kind.ALLIANCE_BREAK) {
                    alliance.breakAlliance(fromId, toId);
                    aiRel.add(fromId, toId, -10);
                    addLocalNews(server, news, fromK,
                            "[ALLIANCE] " + fromName + " broke alliance with " + toName + ".");
                    continue;
                }

                // PEACE: make peace (policy ensures at war)
                if (kind == Letter.Kind.WHITE_PEACE || kind == Letter.Kind.SURRENDER) {
                    warState.makePeace(fromId, toId);
                    addLocalNews(server, news, fromK,
                            "[PEACE] " + fromName + " made peace with " + toName + ".");
                    continue;
                }

                // ULTIMATUM: accept => transfer; refuse => war; both => fixed -80
                if (kind == Letter.Kind.ULTIMATUM) {
                    if (accepted && canExecuteIfAccepted) {
                        add(toAi, aType, -aAmt);
                        add(fromAi, aType, +aAmt);
                        aiRel.add(fromId, toId, -80);
                        addLocalNews(server, news, (toK != null ? toK : fromK),
                                "[ULTIMATUM] " + toName + " yielded to " + fromName + " (" + fmt(aAmt) + " " + aType + ").");
                    } else {
                        warState.declareWar(server, fromId, toId);
                        aiRel.add(fromId, toId, -80);
                        addLocalNews(server, news, fromK,
                                "[WAR] " + fromName + " went to war with " + toName + " after an ultimatum.");
                    }
                    aiState.setDirty();
                    continue;
                }

                // --------------------
                // Economic execution when accepted
                // --------------------
                if (accepted && canExecuteIfAccepted) {
                    switch (kind) {
                        case REQUEST -> {
                            // recipient gives A to sender
                            add(toAi, aType, -aAmt);
                            add(fromAi, aType, +aAmt);
                            aiRel.add(fromId, toId, +3);
                            addLocalNews(server, news, (toK != null ? toK : fromK),
                                    "[TRADE] " + toName + " fulfilled a request from " + fromName + " (" + fmt(aAmt) + " " + aType + ").");
                        }
                        case OFFER -> {
                            // sender gives A to recipient
                            add(fromAi, aType, -aAmt);
                            add(toAi, aType, +aAmt);
                            aiRel.add(fromId, toId, +3);
                            addLocalNews(server, news, fromK,
                                    "[TRADE] " + fromName + " sent an offer to " + toName + " (" + fmt(aAmt) + " " + aType + ").");
                        }
                        case CONTRACT -> {
                            // recipient gives A to sender
                            add(toAi, aType, -aAmt);
                            add(fromAi, aType, +aAmt);

                            // sender gives B to recipient
                            if (bType != null) {
                                add(fromAi, bType, -bAmt);
                                add(toAi, bType, +bAmt);
                            }

                            aiRel.add(fromId, toId, +3);
                            addLocalNews(server, news, fromK,
                                    "[TRADE] " + fromName + " and " + toName + " signed a contract: "
                                            + fmt(bAmt) + " " + bType + " → " + fmt(aAmt) + " " + aType
                                            + " (cap " + fmt(maxAmt) + ").");
                        }
                        default -> {
                            // For non-economic diplomacy kinds (compliment/insult/warning), log rarely to reduce spam.
                            if (kind == Letter.Kind.COMPLIMENT && rng.nextFloat() < 0.15f) {
                                addLocalNews(server, news, fromK,
                                        "[DIPLOMACY] " + fromName + " praised " + toName + ".");
                            } else if (kind == Letter.Kind.INSULT && rng.nextFloat() < 0.15f) {
                                addLocalNews(server, news, fromK,
                                        "[DIPLOMACY] " + fromName + " insulted " + toName + ".");
                            } else if (kind == Letter.Kind.WARNING && rng.nextFloat() < 0.10f) {
                                addLocalNews(server, news, fromK,
                                        "[DIPLOMACY] " + fromName + " warned " + toName + ".");
                            }
                        }
                    }

                    aiState.setDirty();
                }
            }
        }
    }

    // Weighted kind picker (keep it simple; policy will reject illegal ones anyway)
    private static Letter.Kind pickKind(net.minecraft.util.RandomSource rng) {
        float r = rng.nextFloat();

        // ~2% wars
        if (r < 0.02f) return Letter.Kind.WAR_DECLARATION;

        // next ~2% alliances
        if (r < 0.04f) return Letter.Kind.ALLIANCE_PROPOSAL;

        // next ~1% alliance breaks
        if (r < 0.05f) return Letter.Kind.ALLIANCE_BREAK;

        // next ~2% peace attempts
        if (r < 0.07f) return Letter.Kind.WHITE_PEACE;

        // next ~3% insults (news flavor)
        if (r < 0.10f) return Letter.Kind.INSULT;

        // otherwise trade-ish / misc diplomacy
        // (policy may still block some by AI↔AI relations)
        return Letter.Kind.OFFER;
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
