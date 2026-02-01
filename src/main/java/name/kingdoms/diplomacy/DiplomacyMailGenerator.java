package name.kingdoms.diplomacy;

import name.kingdoms.aiKingdomState;
import name.kingdoms.kingdomState;
import name.kingdoms.network.serverMail;
import name.kingdoms.pressure.PressureUtil;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class DiplomacyMailGenerator {

    // tune
    private static final int PERIOD_TICKS = 20 * 60 * 8; // every 8 minutes DEBUG FOR DEV
    private static final double CHANCE_PER_PERIOD = 0.25;
    private static final long EXPIRE_TICKS = 20L * 60L * 10L; // 10 min

    private static final double RANGE = 6000.0;
    private static final double RANGE_SQ = RANGE * RANGE;

    // Per (player, AI kingdom) schedule so letters are staggered.
    // key = playerUUID + "|" + aiKingdomUUID
    private static final java.util.HashMap<String, Long> NEXT_DUE_TICK = new java.util.HashMap<>();

    private static String pairKey(UUID playerId, UUID aiKingdomId) {
        return playerId.toString() + "|" + aiKingdomId.toString();
    }

    private static long initialJitter(RandomSource r) {
        // Spread initial due times across the whole period
        return r.nextInt(Math.max(1, PERIOD_TICKS));
    }

    private static long nextWithJitter(RandomSource r) {
        // +/- 25% jitter around PERIOD_TICKS
        int jitter = (int) (PERIOD_TICKS * 0.25);
        int delta = PERIOD_TICKS + (jitter == 0 ? 0 : (r.nextInt(jitter * 2 + 1) - jitter));
        return Math.max(20, delta); // never schedule less than 1s
    }


    private DiplomacyMailGenerator() {}

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(DiplomacyMailGenerator::onServerTick);
    }

    // ------------------------------------------
    // AI stock helpers
    // ------------------------------------------
    private static double getStock(aiKingdomState.AiKingdom k, ResourceType t) {
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

    private static double clamp(double v, double lo, double hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static Letter.CasusBelli pickCbForAi(RandomSource r, int rel, aiKingdomState.KingdomPersonality personality) {
        // Numeric personality (no string parsing)
        double hon = personality == null ? 0.50 : personality.honor();
        double agg = personality == null ? 0.35 : personality.aggression();
        double grd = personality == null ? 0.50 : personality.greed();
        double pra = personality == null ? 0.60 : personality.pragmatism();
        double tru = personality == null ? 0.50 : personality.trustBias();

        // Base weights
        double borderBias   = 0.20;
        double insultBias   = 0.20;
        double treatyBias   = 0.10;
        double resourceBias = 0.10;
        double unknownBias  = 0.40;

        // Relations push toward "real" reasons when hostile
        if (rel < -50) {
            insultBias += 0.22;
            borderBias += 0.18;
            unknownBias -= 0.20;
        } else if (rel < -15) {
            insultBias += 0.12;
            borderBias += 0.10;
            unknownBias -= 0.10;
        } else if (rel > 25) {
            // Friendly relations make "unknown" more likely (less justification needed)
            unknownBias += 0.08;
            insultBias -= 0.04;
            borderBias -= 0.04;
        }

        // Personality biases
        // Aggressive kingdoms justify with border/insult
        insultBias += (agg - 0.35) * 0.35;
        borderBias += (agg - 0.35) * 0.25;

        // Honorable kingdoms prefer treaty justifications
        treatyBias += (hon - 0.50) * 0.35;

        // Greedy + pragmatic kingdoms focus on resources
        resourceBias += (grd - 0.50) * 0.25;
        resourceBias += (pra - 0.60) * 0.20;

        // Trust reduces "treaty grievance" and "insult grievance" slightly
        treatyBias -= (tru - 0.50) * 0.10;
        insultBias -= (tru - 0.50) * 0.08;

        // Clamp to non-negative
        borderBias = Math.max(0.0, borderBias);
        insultBias = Math.max(0.0, insultBias);
        treatyBias = Math.max(0.0, treatyBias);
        resourceBias = Math.max(0.0, resourceBias);
        unknownBias = Math.max(0.0, unknownBias);

        // Normalize
        double sum = borderBias + insultBias + treatyBias + resourceBias + unknownBias;
        if (sum <= 0.000001) return Letter.CasusBelli.UNKNOWN;

        double x = r.nextDouble() * sum;

        x -= borderBias;
        if (x < 0) return Letter.CasusBelli.BORDER_VIOLATION;

        x -= insultBias;
        if (x < 0) return Letter.CasusBelli.INSULT;

        x -= treatyBias;
        if (x < 0) return Letter.CasusBelli.BROKEN_TREATY;

        x -= resourceBias;
        if (x < 0) return Letter.CasusBelli.RESOURCE_DISPUTE;

        return Letter.CasusBelli.UNKNOWN;
    }



    // ------------------------------------------
    // Need model (aligns with evaluator)
    // ------------------------------------------
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

    private static double needFactor(aiKingdomState.AiKingdom ai, ResourceType t) {
        double target = TARGET.getOrDefault(t, 200.0);
        if (target <= 0) return 0.0;
        double stock = getStock(ai, t);
        return clamp01((target - stock) / target); // 0..1
    }

    private static double surplusFactor(aiKingdomState.AiKingdom ai, ResourceType t) {
        double target = TARGET.getOrDefault(t, 200.0);
        if (target <= 0) return clamp01(getStock(ai, t) / 200.0);
        double stock = getStock(ai, t);
        return clamp01((stock - target) / target); // 0..1
    }

    private static ResourceType pickMostNeeded(aiKingdomState.AiKingdom k) {
        ResourceType best = ResourceType.GOLD;
        double bestNeed = -1.0;
        for (ResourceType t : ResourceType.values()) {
            double n = needFactor(k, t);
            if (n > bestNeed) { bestNeed = n; best = t; }
        }
        return best;
    }

    private static ResourceType pickMostSurplus(aiKingdomState.AiKingdom k) {
        ResourceType best = ResourceType.GOLD;
        double bestSur = -1.0;
        for (ResourceType t : ResourceType.values()) {
            double s = surplusFactor(k, t);
            if (s > bestSur) { bestSur = s; best = t; }
        }
        return best;
    }

    // ------------------------------------------
    // Tick
    // ------------------------------------------
    private static void onServerTick(MinecraftServer server) {
        aiKingdomState ai = aiKingdomState.get(server);

        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) return;

        RandomSource rand = level.getRandom();
        long nowTick = server.getTickCount();

        int budget = 6; // max letters generated per server tick across all players

        kingdomState ks = kingdomState.get(server);
        DiplomacyMailboxState mailbox = DiplomacyMailboxState.get(level);
        mailbox.tickPendingDeliveries(server, level, nowTick);
        mailbox.tickPendingToAi(server, nowTick);

        // relations are now single source of truth
        DiplomacyRelationsState relState = DiplomacyRelationsState.get(server);

        // war + alliance context for gating letter kinds
        var warState = name.kingdoms.war.WarState.get(server);
        var alliance = name.kingdoms.diplomacy.AllianceState.get(server);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            kingdomState.Kingdom playerKingdom = ks.getPlayerKingdom(player.getUUID());
            if (playerKingdom == null) continue; // only players with kingdoms

            boolean playerWarEligible =
                playerKingdom.active.getOrDefault("garrison", 0) > 0
            || playerKingdom.placed.getOrDefault("garrison", 0) > 0;


            List<kingdomState.Kingdom> nearby = findNearbyKingdoms(server, ks, player, playerKingdom);

            for (kingdomState.Kingdom from : nearby) {
                // only AI kingdoms can send letters
                if (!ai.isAiKingdom(from.id)) continue;

                // -------------------------
                // STAGGER: per (player, AI) schedule
                // -------------------------
                String k = pairKey(player.getUUID(), from.id);
                long due = NEXT_DUE_TICK.getOrDefault(k, -1L);

                if (due < 0) {
                    // first time seeing this pair: stagger initial due tick
                    due = nowTick + initialJitter(rand);
                    NEXT_DUE_TICK.put(k, due);
                }

                // not time yet
                if (nowTick < due) continue;

                // schedule next attempt immediately (so we don't retry same tick)
                NEXT_DUE_TICK.put(k, nowTick + nextWithJitter(rand));

                // chance gate (only when due)
                if (rand.nextDouble() > CHANCE_PER_PERIOD) continue;


                // need AI entry (for personality + stocks)
                aiKingdomState.AiKingdom aiK = ai.getById(from.id);
                if (aiK == null) continue;

                int baseRel = relState.getRelation(player.getUUID(), from.id);
                int rel = PressureUtil.effectiveRelation(server, baseRel, from.id);

                
                boolean atWar = warState.isAtWar(playerKingdom.id, from.id);
                boolean allied = alliance.isAllied(playerKingdom.id, from.id);

                // -------------------------------------------------
                // WAR MODE: AI only sends peace letters to enemies
                // -------------------------------------------------
                if (atWar) {
                    // Strength model: AI alive/tickets vs player's estimated tickets
                    int aiAlive = Math.max(0, aiK.aliveSoldiers);
                    int aiTotal = Math.max(1, aiK.maxSoldiers);

                    int garrisons = playerKingdom.active.getOrDefault("garrison", 0);
                    int enemyTotal = Math.max(1, garrisons * 50);
                    int enemyAlive = enemyTotal; // until you track player losses

                    if (!PeaceEvaluator.shouldOfferPeace(rand, aiAlive, aiTotal, enemyAlive, enemyTotal)) {
                        continue;
                    }

                    double surrenderChance = 0.25;

                    // If AI is losing badly, surrender more often
                    double aiFrac = (aiK.maxSoldiers <= 0) ? 1.0 : (aiK.aliveSoldiers / (double) aiK.maxSoldiers);
                    if (aiFrac < 0.35) surrenderChance += 0.25;
                    else if (aiFrac < 0.55) surrenderChance += 0.10;

                    // Personality bias
                    String p = (aiK.personality == null) ? "" : aiK.personality.toString().toLowerCase();
                    if (p.contains("aggressive") || p.contains("warlike")) surrenderChance -= 0.10;
                    if (p.contains("pragmatic") || p.contains("trader")) surrenderChance += 0.05;

                    surrenderChance = Math.max(0.05, Math.min(0.80, surrenderChance));

                    Letter.Kind peaceKind = (rand.nextDouble() < surrenderChance) ? Letter.Kind.SURRENDER : Letter.Kind.WHITE_PEACE;


                    String fromName = (from.name != null && !from.name.isBlank()) ? from.name : "Unknown Kingdom";
                    long expires = nowTick + EXPIRE_TICKS;

                    String toName = (playerKingdom.name != null && !playerKingdom.name.isBlank())
                            ? playerKingdom.name
                            : "your kingdom";

                    String note = AiLetterText.generate(
                            rand,
                            peaceKind,
                            fromName,
                            toName,
                            rel,
                            aiK.personality,
                            null
                    );

                    Letter peaceLetter = (peaceKind == Letter.Kind.SURRENDER)
                            ? Letter.surrender(from.id, true, fromName, player.getUUID(), nowTick, expires, note)
                            : Letter.whitePeace(from.id, true, fromName, player.getUUID(), nowTick, expires, note);

                    mailbox.addLetter(player.getUUID(), peaceLetter);
                    serverMail.syncInbox(player, mailbox.getInbox(player.getUUID()));
                    if (--budget <= 0) return;
                    continue; // do not send normal diplomacy letters while at war
                }

                boolean playerPresent = isPlayerPresentWithKingdom(player, from);

                Optional<Letter.Kind> kindOpt = DiplomacyLetterPolicy.chooseOutgoing(
                        rand,
                        rel,
                        aiK.personality,
                        atWar,
                        allied,
                        playerPresent
                );

                

                if (kindOpt.isEmpty()) continue;

               Letter.Kind kind = kindOpt.get();

                // Block escalation until player has at least 1 garrison
                if (!playerWarEligible && (kind == Letter.Kind.ULTIMATUM || kind == Letter.Kind.WAR_DECLARATION)) {
                    continue;
                }

                

                String fromName = (from.name != null && !from.name.isBlank()) ? from.name : "Unknown Kingdom";
                String toName = (playerKingdom.name != null && !playerKingdom.name.isBlank())
                ? playerKingdom.name
                : "your kingdom";


                Letter letter = buildOutgoingLetter(rand, kind, aiK, from.id, fromName, player.getUUID(), nowTick, rel, toName);
                if (letter == null) continue;

                mailbox.addLetter(player.getUUID(), letter);
                serverMail.syncInbox(player, mailbox.getInbox(player.getUUID()));
                if (--budget <= 0) return;

            }
        }
    }

    private static boolean isPlayerPresentWithKingdom(ServerPlayer player, kingdomState.Kingdom k) {
        // “present” = within 128 blocks of the kingdom origin (court area proxy)
        double dx = (k.origin.getX() + 0.5) - player.getX();
        double dz = (k.origin.getZ() + 0.5) - player.getZ();
        return (dx * dx + dz * dz) <= (128.0 * 128.0);
    }


    // ------------------------------------------
    // Letter building (policy-selected kind)
    // ------------------------------------------
    private static Letter buildOutgoingLetter(
        RandomSource r,
        Letter.Kind kind,
        aiKingdomState.AiKingdom aiK,
        UUID fromKingdomId,
        String fromName,
        UUID toPlayer,
        long nowTick,
        int rel,
        String toName
    ){
        long expires = nowTick + EXPIRE_TICKS;

        // pick "want" by highest NEED, and "give" by highest SURPLUS
        ResourceType want = pickMostNeeded(aiK);
        ResourceType give = pickMostSurplus(aiK);

        // avoid same type; fall back to GOLD or WOOD
        if (want == give) {
            want = (want != ResourceType.GOLD) ? ResourceType.GOLD : ResourceType.WOOD;
        }

        double giveStock = getStock(aiK, give);
        double wantNeed = needFactor(aiK, want);      // 0..1
        double giveSur  = surplusFactor(aiK, give);   // 0..1

        // amounts: scale with need/surplus + randomness
        double baseGive = 8 + (giveSur * 60.0);
        double baseWant = 8 + (wantNeed * 70.0);

        double giveAmt = clamp(baseGive * (0.75 + r.nextDouble() * 0.60), 5, 90);
        double wantAmt = clamp(baseWant * (0.75 + r.nextDouble() * 0.60), 5, 120);

        // contracts: max amount caps number of trades; keep moderate
        double maxAmt = clamp(60 + (r.nextDouble() * 240.0), 60, 350);

        // IMPORTANT: If AI is giving A (OFFER/CONTRACT), don’t offer more than it has.
        if (kind == Letter.Kind.OFFER || kind == Letter.Kind.CONTRACT) {
            giveAmt = clamp(giveAmt, 1, Math.max(1, giveStock));
        }


        return switch (kind) {
            case REQUEST -> {
                // AI demands A from player (player pays AI)
                String note = AiLetterText.generateEconomic(
                        r, Letter.Kind.REQUEST, fromName, toName, rel, aiK.personality,
                        want, wantAmt, null, 0.0, 0.0
                );
                yield Letter.request(fromKingdomId, true, fromName, toPlayer, want, wantAmt, nowTick, expires, note);
            }

           case OFFER -> {
                // AI offers A to player (AI pays player)
                // Clamp offer by what AI actually has (optional but recommended)
                double offerAmt = clamp(giveAmt, 1, Math.max(1, giveStock));

                String note = AiLetterText.generateEconomic(
                        r, Letter.Kind.OFFER, fromName, toName, rel, aiK.personality,
                        give, offerAmt, null, 0.0, 0.0
                );
                yield Letter.offer(fromKingdomId, true, fromName, toPlayer, give, offerAmt, nowTick, expires, note);
            }
            case CONTRACT -> {
                // AI gives A, wants B
                double outWant = clamp(wantAmt, 1, 160);

                String note = AiLetterText.generateEconomic(
                        r, Letter.Kind.CONTRACT, fromName, toName, rel, aiK.personality,
                        give, giveAmt, want, outWant, maxAmt
                );

                yield Letter.contract(fromKingdomId, true, fromName, toPlayer,
                        give, giveAmt,
                        want, outWant,
                        maxAmt,
                        nowTick, expires,
                        note
                );

            }

            case COMPLIMENT -> {
                String note = AiLetterText.generate(r, Letter.Kind.COMPLIMENT, fromName, toName, rel, aiK.personality, null);
                yield Letter.compliment(fromKingdomId, true, fromName, toPlayer, nowTick, expires, note);
            }
            case INSULT -> {
                String note = AiLetterText.generate(r, Letter.Kind.INSULT, fromName, toName, rel, aiK.personality, null);
                yield Letter.insult(fromKingdomId, true, fromName, toPlayer, nowTick, expires, note);
            }
            case WARNING -> {
                String note = AiLetterText.generate(r, Letter.Kind.WARNING, fromName, toName, rel, aiK.personality, null);
                yield Letter.warning(fromKingdomId, true, fromName, toPlayer, nowTick, expires, note);
            }

            case ULTIMATUM -> {
                String note = AiLetterText.generate(r, Letter.Kind.ULTIMATUM, fromName, toName, rel, aiK.personality, null);
                yield Letter.ultimatum(
                        fromKingdomId, true, fromName, toPlayer,
                        want, clamp(wantAmt, 10, 160),
                        nowTick, expires,
                        note
                );
            }

            case ALLIANCE_PROPOSAL -> {
                String note = AiLetterText.generate(r, Letter.Kind.ALLIANCE_PROPOSAL, fromName, toName, rel, aiK.personality, null);
                yield Letter.allianceProposal(fromKingdomId, true, fromName, toPlayer, nowTick, expires, note);
            }

            case ALLIANCE_BREAK -> {
                // You do NOT have a factory for alliance break; create a direct Letter with note
                String note = AiLetterText.generate(r, Letter.Kind.ALLIANCE_BREAK, fromName, toName, rel, aiK.personality, null);
                yield new Letter(
                        UUID.randomUUID(), fromKingdomId, toPlayer,
                        true, fromName,
                        Letter.Kind.ALLIANCE_BREAK, Letter.Status.PENDING, nowTick, expires,
                        ResourceType.GOLD, 0.0,
                        null, 0.0,
                        0.0,
                        null,
                        "",
                        note
                );
            }

            case WHITE_PEACE -> {
                String note = AiLetterText.generate(r, Letter.Kind.WHITE_PEACE, fromName, toName, rel, aiK.personality, null);
                yield Letter.whitePeace(fromKingdomId, true, fromName, toPlayer, nowTick, expires, note);
            }

            case SURRENDER -> {
                String note = AiLetterText.generate(r, Letter.Kind.SURRENDER, fromName, toName, rel, aiK.personality, null);
                yield Letter.surrender(fromKingdomId, true, fromName, toPlayer, nowTick, expires, note);
            }

            case WAR_DECLARATION -> {
                // choose a CB (simple relation-based pick; tune later)
                Letter.CasusBelli cb = pickCbForAi(r, rel, aiK.personality);

                String note = AiLetterText.generate(r, Letter.Kind.WAR_DECLARATION, fromName, toName, rel, aiK.personality, cb);
                yield Letter.warDeclaration(fromKingdomId, true, fromName, toPlayer, cb, nowTick, expires, note);
            }

            default -> null;
        };

    }

    // ------------------------------------------
    // Find nearby kingdoms
    // ------------------------------------------
    private static List<kingdomState.Kingdom> findNearbyKingdoms(
            MinecraftServer server,
            kingdomState ks,
            ServerPlayer player,
            kingdomState.Kingdom playerKingdom
    ) {
        List<kingdomState.Kingdom> out = new ArrayList<>();

        double px = player.getX();
        double pz = player.getZ();

        for (kingdomState.Kingdom k : ks.getAllKingdoms()) {
            if (k.id.equals(playerKingdom.id)) continue; // not your own kingdom

            double dx = (k.origin.getX() + 0.5) - px;
            double dz = (k.origin.getZ() + 0.5) - pz;

            if ((dx * dx + dz * dz) <= RANGE_SQ) out.add(k);
        }

        return out;
    }

    // ------------------------------------------
    // UI button: "Make proposal now"
    // ------------------------------------------
    public static Letter makeImmediateProposal(ServerLevel level, MinecraftServer server, UUID fromAiId, UUID toPlayer, long nowTick) {
        aiKingdomState ai = aiKingdomState.get(server);
        aiKingdomState.AiKingdom aiK = ai.getById(fromAiId);
        if (aiK == null) return null;

        RandomSource r = level.getRandom();
        long expires = nowTick + (20L * 60L * 10L);
        
        

       

        // Use relations + personality policy here too
        // Use relations + personality policy here too
        DiplomacyRelationsState relState = DiplomacyRelationsState.get(server);
        int baseRel = relState.getRelation(toPlayer, fromAiId);
        int rel = PressureUtil.effectiveRelation(server, baseRel, fromAiId);


        var warState = name.kingdoms.war.WarState.get(server);
        var alliance = name.kingdoms.diplomacy.AllianceState.get(server);

        var ks = name.kingdoms.kingdomState.get(server);
        var playerK = ks.getPlayerKingdom(toPlayer);

        boolean atWar = (playerK != null) && warState.isAtWar(playerK.id, fromAiId);
        boolean allied = (playerK != null) && alliance.isAllied(playerK.id, fromAiId);

        // ---- playerPresent: only meaningful if player exists + not null ----
        ServerPlayer sp = server.getPlayerList().getPlayer(toPlayer);
        boolean playerPresent = false;

        if (sp != null) {
            // Best: if you can fetch the AI kingdom's Kingdom record by id, use it.
            // If you don't have a direct method, see notes below.
            kingdomState.Kingdom fromKingdom = ks.getKingdom(fromAiId); // <-- you need this method (or equivalent)
            if (fromKingdom != null) {
                playerPresent = isPlayerPresentWithKingdom(sp, fromKingdom);
            }
        }

        // -------------------------------------------------
        // WAR MODE: proposal button only returns peace letters
        // -------------------------------------------------
        if (atWar) {
            int aiAlive = Math.max(0, aiK.aliveSoldiers);
            int aiTotal = Math.max(1, aiK.maxSoldiers);

            int garrisons = (playerK == null) ? 0 : playerK.active.getOrDefault("garrison", 0);
            int enemyTotal = Math.max(1, garrisons * 50);
            int enemyAlive = enemyTotal;

            // If AI isn't inclined to offer peace right now, return null (no proposal)
            if (!PeaceEvaluator.shouldOfferPeace(r, aiAlive, aiTotal, enemyAlive, enemyTotal)) {
                return null;
            }

            String fromNameWar = (aiK.name != null && !aiK.name.isBlank()) ? aiK.name : "Unknown Kingdom";
            long expiresWar = nowTick + (20L * 60L * 10L);

            String toName = (playerK != null && playerK.name != null && !playerK.name.isBlank())
                    ? playerK.name
                    : "your kingdom";

            Letter.Kind peaceKind = (r.nextDouble() < 0.25) ? Letter.Kind.SURRENDER : Letter.Kind.WHITE_PEACE;
            String note = AiLetterText.generate(
                    r,
                    peaceKind,
                    fromNameWar,
                    toName,
                    rel,
                    aiK.personality,
                    null
            );

            if (peaceKind == Letter.Kind.SURRENDER) {
                return Letter.surrender(fromAiId, true, fromNameWar, toPlayer, nowTick, expiresWar, note);
            }
            return Letter.whitePeace(fromAiId, true, fromNameWar, toPlayer, nowTick, expiresWar, note);
        }


        Optional<Letter.Kind> kindOpt = DiplomacyLetterPolicy.chooseOutgoing(
                r, rel, aiK.personality, atWar, allied, playerPresent
        );

        if (kindOpt.isEmpty()) return null;

        String fromName = (aiK.name != null && !aiK.name.isBlank()) ? aiK.name : "Unknown Kingdom";
        String toName = (playerK != null && playerK.name != null && !playerK.name.isBlank())
        ? playerK.name
        : "your kingdom";

        Letter letter = buildOutgoingLetter(r, kindOpt.get(), aiK, fromAiId, fromName, toPlayer, nowTick, rel, toName);
        if (letter != null) return letter;

        return Letter.warning(fromAiId, true, fromName, toPlayer, nowTick, expires, "...");
    }
}
