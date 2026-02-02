package name.kingdoms.diplomacy;

import name.kingdoms.aiKingdomState;
import name.kingdoms.kingdomState;
import name.kingdoms.network.serverMail;
import name.kingdoms.network.networkInit;
import name.kingdoms.payload.ecoSyncPayload;
import name.kingdoms.pressure.PressureUtil;
import name.kingdoms.news.KingdomNewsState;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.UUID;

public final class DiplomacyResponseQueue {

    /** DEBUG: instant AI responses (DEV ONLY) */
    public static boolean DEBUG_INSTANT = false;

    private record PendingMail(
            UUID playerId,
            UUID aiId,
            Letter.Kind kind,
            ResourceType aType, double aAmount,
            ResourceType bType, double bAmount,
            double maxAmount,
            Letter.CasusBelli cb,
            String note,
            boolean inPerson,
            long dueTick
    ) {}


    private record PendingProposal(
            UUID playerId,
            UUID aiId,
            long dueTick
    ) {}

    private static final ArrayDeque<PendingMail> MAIL = new ArrayDeque<>();
    private static final ArrayDeque<PendingProposal> PROPOSALS = new ArrayDeque<>();

    private DiplomacyResponseQueue() {}

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(DiplomacyResponseQueue::tickInternal);
    }

    /** Manually process any items that are due right now (useful for DEBUG_INSTANT). */
    public static void processDueNow(MinecraftServer server) {
        tickInternal(server);
    }

    /** For DEBUG_INSTANT or tests: process any due items immediately. */
    public static void flushNow(MinecraftServer server) {
        tickInternal(server);
    }

    /** Queue a response to a player letter (normal mail delay). */
    public static void queueMail(MinecraftServer server,
                                 UUID playerId, UUID aiId,
                                 Letter.Kind kind,
                                 ResourceType aType, double aAmount,
                                 ResourceType bType, double bAmount,
                                 double maxAmount,
                                 Letter.CasusBelli cb,
                                 String note) {

        long now = server.getTickCount();
        long delay = computeDelayTicks(server, false);
        MAIL.add(new PendingMail(
                playerId, aiId, kind,
                aType, aAmount,
                bType, bAmount,
                maxAmount,
                cb,
                note == null ? "" : note,
                false,
                now + delay
        ));
    }

    public static void queueMailInPerson(MinecraftServer server,
                                        UUID playerId, UUID aiId,
                                        Letter.Kind kind,
                                        ResourceType aType, double aAmount,
                                        ResourceType bType, double bAmount,
                                        double maxAmount,
                                        Letter.CasusBelli cb,
                                        String note) {

        long now = server.getTickCount();
        long delay = computeDelayTicks(server, true); // fast
        MAIL.add(new PendingMail(
                playerId, aiId, kind,
                aType, aAmount,
                bType, bAmount,
                maxAmount,
                cb,
                note == null ? "" : note,
                true,
                now + delay
        ));
    }


    /** Queue a fast “proposal from AI” (diplo screen button). */
    public static void queueProposal(MinecraftServer server, UUID playerId, UUID aiId) {
        long now = server.getTickCount();
        long delay = computeDelayTicks(server, true);
        PROPOSALS.add(new PendingProposal(playerId, aiId, now + delay));
    }

    private static long computeDelayTicks(MinecraftServer server, boolean fast) {
        if (DEBUG_INSTANT) return 0L;

        var r = server.overworld().getRandom();
        if (fast) {
            // ~10 seconds (8–12)
            int sec = 8 + r.nextInt(5);
            return sec * 20L;
        }

        // 30s–10m
        int sec = 30 + r.nextInt((60 * 10) - 30 + 1);
        return sec * 20L;
    }

    private static String fmt(double v) {
        if (Math.abs(v - Math.round(v)) < 0.000001) return Long.toString(Math.round(v));
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    private static String buildOutcomeSubject(Letter.Kind kind, boolean accepted) {
        String verb = accepted ? "Accepted" : "Refused";
        String what = switch (kind) {
            case OFFER -> "Offer";
            case REQUEST -> "Request";
            case CONTRACT -> "Contract";
            case ULTIMATUM -> "Ultimatum";
            case ALLIANCE_PROPOSAL -> "Alliance Proposal";
            case WHITE_PEACE -> "White Peace";
            case SURRENDER -> "Surrender";
            case WAR_DECLARATION -> "War Declaration";
            case ALLIANCE_BREAK -> "Alliance Break";
            case COMPLIMENT -> "Compliment";
            case INSULT -> "Insult";
            case WARNING -> "Warning";
            default -> kind.name();
        };
        return verb + ": " + what;
    }


    /**
     * Stamp news with a "local" position so distance filtering works.
     * For player↔AI interactions, the most intuitive anchor is the player's kingdom terminal (if present),
     * otherwise the player's kingdom origin.
     */
    private static void addLocalNews(MinecraftServer server, KingdomNewsState news,
                                    kingdomState.Kingdom srcK, String text) {
        if (srcK == null) return;
        if (text == null || text.isBlank()) return;

        var dimKey = srcK.hasTerminal ? srcK.terminalDim : net.minecraft.world.level.Level.OVERWORLD;
        var pos    = srcK.hasTerminal ? srcK.terminalPos : srcK.origin;

        ServerLevel lvl = server.getLevel(dimKey);
        if (lvl == null) lvl = server.overworld();

        news.add(server.getTickCount(), text, lvl, pos.getX(), pos.getZ());
    }

    private static void tickInternal(MinecraftServer server) {
        long now = server.getTickCount();
        ServerLevel level = server.overworld();
        if (level == null) return;

        var ks = kingdomState.get(server);
        var aiState = aiKingdomState.get(server);
        var mailbox = DiplomacyMailboxState.get(level);
        var news = KingdomNewsState.get(level);

        var relState = DiplomacyRelationsState.get(server);
        var alliance = name.kingdoms.diplomacy.AllianceState.get(server);
        var warState = name.kingdoms.war.WarState.get(server);

        // ---- proposals ----
        for (Iterator<PendingProposal> it = PROPOSALS.iterator(); it.hasNext();) {
            PendingProposal p = it.next();
            if (p.dueTick > now) continue;

            Letter proposal = DiplomacyMailGenerator.makeImmediateProposal(level, server, p.aiId, p.playerId, now);
            if (proposal != null) {
                mailbox.addLetter(p.playerId, proposal);

                ServerPlayer sp = server.getPlayerList().getPlayer(p.playerId);
                if (sp != null) {
                    serverMail.syncInbox(sp, mailbox.getInbox(p.playerId));
                }
            }

            it.remove();
        }

        // ---- mail responses ----
        for (Iterator<PendingMail> it = MAIL.iterator(); it.hasNext();) {
            PendingMail p = it.next();
            if (p.dueTick > now) continue;

            var playerK = ks.getPlayerKingdom(p.playerId);
            var aiK = aiState.getById(p.aiId);

            // If player left kingdom or AI disappeared, drop.
            if (playerK == null || aiK == null) {
                it.remove();
                continue;
            }

            // -----------------------------
            // HARD ability checks (execute)
            // -----------------------------

            // Can AI afford what it would need to GIVE? (REQUEST/CONTRACT/ULTIMATUM)
            boolean aiCanGiveA = switch (p.kind) {
                case REQUEST, CONTRACT, ULTIMATUM -> networkInit.getAi(aiK, p.aType) >= p.aAmount;
                default -> true;
            };

            // Can player afford what it would need to GIVE? (OFFER gives A; CONTRACT gives B)
            boolean playerCanGive = switch (p.kind) {
                case OFFER -> getPlayerStock(playerK, p.aType) >= p.aAmount;
                case CONTRACT -> (p.bType != null) && (getPlayerStock(playerK, p.bType) >= p.bAmount);
                default -> true;
            };

            boolean canExecuteIfAccepted = aiCanGiveA && playerCanGive;

            // -----------------------------
            // Get relation + personality
            // -----------------------------
            int baseRel = relState.getRelation(p.playerId, p.aiId);
            int rel = PressureUtil.effectiveRelation(server, baseRel, playerK.id, p.aiId);


            var pers = aiK.personality;

            // -----------------------------
            // Evaluate (server RNG, not Math.random)
            // -----------------------------
            var rng = server.overworld().getRandom();

            // --- build context for "relevant factors" ---
            boolean alliedNow = alliance.isAllied(playerK.id, aiK.id);
            boolean atWarWithOther = warState.isAtWar(playerK.id, aiK.id);
            boolean atWarWithAnyone = warState.isAtWarWithAny(aiK.id);

            // simple enemy count approximation for now (0/1 is fine)
            int enemyApprox = atWarWithAnyone ? 1 : 0;

            // AI soldiers (from ai state)
            int aiSoldiers = Math.max(0, aiK.aliveSoldiers);

            // Player estimate: garrisons * 50 (fallback 0 if not present)
            int garrisons = playerK.active.getOrDefault("garrison", 0);
            int otherSoldiersEst = garrisons * 50;

            // AI ally count
            int aiAllyCount = alliance.alliesOf(aiK.id).size();

            var ctxEval = new DiplomacyEvaluator.Context(
                    rel,
                    alliedNow,
                    atWarWithOther,
                    atWarWithAnyone,
                    aiAllyCount,
                    enemyApprox,
                    aiSoldiers,
                    otherSoldiersEst
            );

            var res = DiplomacyEvaluator.decide(
                    rng,
                    p.kind,
                    pers,
                    aiK,
                    ctxEval,
                    p.aType, p.aAmount,
                    p.bType, p.bAmount,
                    p.maxAmount,
                    p.inPerson
            );

            // Apply relation delta (clamped inside DiplomacyRelationsState)
            if (p.kind != Letter.Kind.ALLIANCE_PROPOSAL
                    && p.kind != Letter.Kind.WAR_DECLARATION
                    && p.kind != Letter.Kind.ULTIMATUM
                    && res.relationDelta() != 0) {
                relState.addRelation(p.playerId, p.aiId, res.relationDelta());
            }

            boolean accepted = (res.decision() == DiplomacyEvaluator.Decision.ACCEPT);
            boolean counter = (res.decision() == DiplomacyEvaluator.Decision.COUNTER);

            // If evaluator accepted but we cannot actually execute the transfer, force refusal.
            String extraNote = res.note() == null ? "" : res.note();
            if (accepted && !canExecuteIfAccepted) {
                accepted = false;
                counter = false;
                if (extraNote.isBlank()) {
                    extraNote = "We cannot fulfill those terms.";
                } else {
                    extraNote = extraNote + " (We cannot fulfill those terms.)";
                }
            }

            // -----------------------------
            // Peace letters (player -> AI): WHITE_PEACE / SURRENDER
            // -----------------------------
            if (p.kind == Letter.Kind.WHITE_PEACE || p.kind == Letter.Kind.SURRENDER) {

                // Only meaningful if actually at war
                if (!warState.isAtWar(playerK.id, aiK.id)) {
                    accepted = false;
                    counter = false;
                    if (extraNote.isBlank()) extraNote = "We are not at war.";
                } else {
                    int aiAlive = Math.max(0, aiK.aliveSoldiers);
                    int aiTotal = Math.max(1, aiK.maxSoldiers);

                    // Player estimate: garrisons*50 tickets (alive assumed = total for now)
                    int garrisonsPeace = playerK.active.getOrDefault("garrison", 0);
                    int enemyTotal = Math.max(1, garrisonsPeace * 50);
                    int enemyAlive = enemyTotal;

                    var pd = PeaceEvaluator.decideAccept(
                            rng,
                            aiAlive, aiTotal,
                            enemyAlive, enemyTotal
                    );

                    accepted = (pd == PeaceEvaluator.Decision.ACCEPT);
                    counter = false;

                    if (accepted) {

                        if (p.kind == Letter.Kind.SURRENDER) {
                            name.kingdoms.network.networkInit.applyDefeatPenalty(server, playerK.id);
                        }
                        warState.makePeace(playerK.id, aiK.id);
                        if (extraNote.isBlank()) extraNote = "Peace accepted.";
                        relState.addRelation(p.playerId, p.aiId, +10);

                        // --- Sync updated war zones to the player immediately after AI accepts peace ---
                        {
                            ServerPlayer spPeace = server.getPlayerList().getPlayer(p.playerId);
                            if (spPeace != null) {
                                var out = new java.util.ArrayList<name.kingdoms.payload.warZonesSyncPayload.Entry>();
                                var ks2 = kingdomState.get(server);
                                var ai2 = aiKingdomState.get(server);

                                for (var zv : warState.getZonesFor(server, playerK.id)) {
                                    var enemyId = zv.enemyId();
                                    var zone = zv.zone();

                                    var ek = ks2.getKingdom(enemyId);
                                    String enemyName =
                                            (ek != null && ek.name != null && !ek.name.isBlank())
                                                    ? ek.name
                                                    : ai2.getNameById(enemyId);

                                    out.add(new name.kingdoms.payload.warZonesSyncPayload.Entry(
                                            enemyId, enemyName,
                                            zone.minX(), zone.minZ(), zone.maxX(), zone.maxZ()
                                    ));
                                }

                                ServerPlayNetworking.send(spPeace, new name.kingdoms.payload.warZonesSyncPayload(out));
                            }
                        }

                    } else {
                        if (extraNote.isBlank()) extraNote = "We will fight on.";
                    }
                }

                // peace letters are non-economic
                canExecuteIfAccepted = true;
            }

            // -----------------------------
            // War declaration (player -> AI): declare war immediately + fixed relation hit
            // -----------------------------
            if (p.kind == Letter.Kind.WAR_DECLARATION) {
                warState.declareWar(server, playerK.id, aiK.id);
                relState.addRelation(p.playerId, p.aiId, -80);

                canExecuteIfAccepted = true;
                accepted = true;
                counter = false;

                if (extraNote.isBlank()) extraNote = "War declared.";
            }

            // -----------------------------
            // Alliance proposal handling (player -> AI)
            // -----------------------------
            if (p.kind == Letter.Kind.ALLIANCE_PROPOSAL) {

                if (accepted) {
                    if (warState.isAtWar(playerK.id, aiK.id)) {
                        accepted = false;
                        counter = false;
                        if (extraNote.isBlank()) extraNote = "We cannot ally while at war.";
                    }
                    else if (!alliance.canAlly(playerK.id, aiK.id)) {
                        accepted = false;
                        counter = false;
                        if (extraNote.isBlank()) extraNote = "We cannot maintain more alliances.";
                    } else {
                        alliance.addAlliance(playerK.id, aiK.id);
                        relState.addRelation(p.playerId, p.aiId, +30);
                        if (extraNote.isBlank()) extraNote = "Alliance accepted.";
                    }
                } else {
                    relState.addRelation(p.playerId, p.aiId, -10);
                    if (extraNote.isBlank()) extraNote = "We decline.";
                }

                canExecuteIfAccepted = true;
            }

            // -----------------------------
            // Apply economic transfer IF accepted
            // -----------------------------
            if (accepted) {
                String pn = (playerK.name == null || playerK.name.isBlank()) ? "Player Kingdom" : playerK.name;
                String an = (aiK.name == null || aiK.name.isBlank()) ? "AI Kingdom" : aiK.name;

                switch (p.kind) {
                    case REQUEST -> {
                        // AI gives A to player
                        networkInit.addAi(aiK, p.aType, -p.aAmount);
                        EconomyMutator.add(playerK, p.aType, +p.aAmount);
                        relState.addRelation(p.playerId, p.aiId, +3);

                        addLocalNews(server, news,
                                playerK,
                                "[TRADE] " + an + " fulfilled a request from " + pn + " (" +
                                        fmt(p.aAmount) + " " + p.aType + ")."
                        );

                        ks.markDirty();
                        aiState.setDirty();
                    }
                    case OFFER -> {
                        // Player gives A to AI
                        EconomyMutator.add(playerK, p.aType, -p.aAmount);
                        networkInit.addAi(aiK, p.aType, +p.aAmount);
                        relState.addRelation(p.playerId, p.aiId, +3);

                        addLocalNews(server, news,
                                playerK,
                                "[TRADE] " + pn + " sent an offer to " + an + " (" +
                                        fmt(p.aAmount) + " " + p.aType + ")."
                        );

                        ks.markDirty();
                        aiState.setDirty();
                    }

                    case CONTRACT -> {
                        // AI gives A to player
                        networkInit.addAi(aiK, p.aType, -p.aAmount);
                        EconomyMutator.add(playerK, p.aType, +p.aAmount);
                        relState.addRelation(p.playerId, p.aiId, +3);

                        // Player gives B to AI
                        if (p.bType != null) {
                            EconomyMutator.add(playerK, p.bType, -p.bAmount);
                            networkInit.addAi(aiK, p.bType, +p.bAmount);
                        }

                        addLocalNews(server, news,
                                playerK,
                                "[TRADE] " + pn + " and " + an + " signed a contract: " +
                                        fmt(p.bAmount) + " " + p.bType + " → " + fmt(p.aAmount) + " " + p.aType +
                                        " (cap " + fmt(p.maxAmount) + ")."
                        );

                        ks.markDirty();
                        aiState.setDirty();
                    }
                    default -> {}
                }
            }

            // Ultimatum special: refused -> war; accepted -> transfer (same as REQUEST)
            if (p.kind == Letter.Kind.ULTIMATUM) {
                if (accepted) {
                    networkInit.addAi(aiK, p.aType, -p.aAmount);
                    EconomyMutator.add(playerK, p.aType, +p.aAmount);
                    ks.markDirty();
                    aiState.setDirty();
                    relState.addRelation(p.playerId, p.aiId, -80);
                } else {
                    warState.declareWar(server, playerK.id, aiK.id);
                    relState.addRelation(p.playerId, p.aiId, -80);
                }
            }

            // -----------------------------
            // Build response letters (AiLetterText-driven)
            // -----------------------------
            String aiName = (aiK.name == null || aiK.name.isBlank()) ? "Unknown Kingdom" : aiK.name;
            String toName = (playerK.name == null || playerK.name.isBlank()) ? "your kingdom" : playerK.name;
            String subject = buildOutcomeSubject(p.kind, accepted);


            long defaultExpires = now + (20L * 60L * 10L);

            // 1) Always send an OUTCOME letter (ACCEPTED / REFUSED) with AiLetterText outcome body.
            String outcomeNote = AiLetterText.generateOutcome(
                    rng,
                    p.kind,
                    accepted,
                    aiName,
                    toName,
                    rel,
                    aiK.personality,
                    p.aType, p.aAmount,
                    p.bType, p.bAmount,
                    p.maxAmount,
                    p.cb
            );

            // If you still want to append evaluator-specific explanation sometimes:
            if (!extraNote.isBlank()) {
                // Keep it short; append as a second sentence.
                outcomeNote = outcomeNote.isBlank() ? extraNote : (outcomeNote + " " + extraNote);
            }

            // Outcome letter mirrors the original terms but is resolved (not actionable)
            Letter outcome = new Letter(
                    UUID.randomUUID(),
                    aiK.id,
                    p.playerId,
                    false,
                    aiName,
                    p.kind,
                    accepted ? Letter.Status.ACCEPTED : Letter.Status.REFUSED,
                    now,
                    0L,
                    p.aType, p.aAmount,
                    p.bType, p.bAmount,
                    p.maxAmount,
                    p.cb,
                    subject,
                    outcomeNote
            );


            mailbox.addLetter(p.playerId, outcome);

            // 2) If COUNTER, send a second PENDING letter with the new terms (actionable)
            if (counter) {
                // Decide what the counter letter actually is
                Letter counterLetter = null;

                if (p.kind == Letter.Kind.CONTRACT && res.counterWantType() != null && res.counterWantAmt() > 0) {
                    // Counter-contract: same A, different B
                    ResourceType aType = p.aType;
                    double aAmt = p.aAmount;
                    ResourceType bType = res.counterWantType();
                    double bAmt = res.counterWantAmt();
                    double cap = p.maxAmount;

                    String counterNote = AiLetterText.generateEconomic(
                            rng, Letter.Kind.CONTRACT, aiName, toName, rel, aiK.personality,
                            aType, aAmt, bType, bAmt, cap
                    );

                    counterLetter = Letter.contract(
                            aiK.id, true, aiName, p.playerId,
                            aType, aAmt,
                            bType, bAmt,
                            cap,
                            now, defaultExpires,
                            counterNote
                    );

                } else if (p.kind == Letter.Kind.REQUEST && res.counterGiveType() != null && res.counterGiveAmt() > 0) {
                    // Player REQUESTed AI give A; AI counters by OFFERing a smaller amount
                    ResourceType giveT = res.counterGiveType();
                    double giveA = res.counterGiveAmt();

                    String counterNote = AiLetterText.generateEconomic(
                            rng, Letter.Kind.OFFER, aiName, toName, rel, aiK.personality,
                            giveT, giveA, null, 0.0, 0.0
                    );

                    counterLetter = Letter.offer(
                            aiK.id, true, aiName, p.playerId,
                            giveT, giveA,
                            now, defaultExpires,
                            counterNote
                    );
                }

                if (counterLetter != null) {
                    // keep it actionable
                    // (Status is already PENDING in the factory)
                    mailbox.addLetter(p.playerId, counterLetter);
                }
            }

            // Sync player inbox + economy view
            ServerPlayer sp = server.getPlayerList().getPlayer(p.playerId);
            if (sp != null) {
                serverMail.syncInbox(sp, mailbox.getInbox(p.playerId));
                ServerPlayNetworking.send(sp, ecoSyncPayload.fromKingdomWithProjected(server, playerK));
            }

            it.remove();

        }
    }

    // ------------------------------------------
    // Player stock helper (no dependency on get())
    // ------------------------------------------
    private static double getPlayerStock(kingdomState.Kingdom k, ResourceType t) {
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
}
