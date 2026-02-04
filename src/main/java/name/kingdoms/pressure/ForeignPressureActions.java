package name.kingdoms.pressure;

import name.kingdoms.diplomacy.DiplomacyRelationsState;
import name.kingdoms.war.WarState;
import name.kingdoms.diplomacy.AllianceState;
import name.kingdoms.kingdomState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

public final class ForeignPressureActions {
    private ForeignPressureActions() {}

    // ---- Channels ----
    public static final String CH_KING = "king";       // official
    public static final String CH_NOBLE = "noble";     // intrigue
    public static final String CH_GUARD = "guard";     // security / intimidation
    public static final String CH_VILLAGER = "villager"; // hearts/minds, agitation

    private static void applyAiFuzz(MinecraftServer server, UUID targetKid, EnumMap<KingdomPressureState.Stat, Double> effects) {
        if (server == null || targetKid == null || effects == null || effects.isEmpty()) return;

        var aiState = name.kingdoms.aiKingdomState.get(server);
        var aiK = aiState.getById(targetKid);
        if (aiK == null) return;

        // Map player-scale deltas -> AI 0..100 scale
        double hap = effects.getOrDefault(KingdomPressureState.Stat.HAPPINESS, 0.0);
        double sec = effects.getOrDefault(KingdomPressureState.Stat.SECURITY, 0.0);
        double econPct = effects.getOrDefault(KingdomPressureState.Stat.ECONOMY, 0.0);

        int dh = (int) Math.round(hap * 10.0);     // +0.8 => +8
        int ds = (int) Math.round(sec * 100.0);    // +0.04 => +4

        if (dh != 0) aiK.happiness = net.minecraft.util.Mth.clamp(aiK.happiness + dh, 0, 100);
        if (ds != 0) aiK.security  = net.minecraft.util.Mth.clamp(aiK.security + ds, 0, 100);

        // Damped economy impact: apply to gold only (safe & noticeable)
        if (Math.abs(econPct) > 1e-6) {
            double mult = 1.0 + econPct;
            if (mult < 0.10) mult = 0.10;
            aiK.gold = Math.max(0.0, aiK.gold * mult);
        }

        // Optional small soldier effect when security is hit hard (makes raids feel real)
        if (sec < -0.02) {
            int loss = (server.overworld().random.nextInt(3)); // 0..2
            aiK.aliveSoldiers = Math.max(0, aiK.aliveSoldiers - loss);
        }

        aiState.markDirty();
    }


    public record Action(
            String id,
            String channel,
            int minRel, int maxRel,     // inclusive bounds (use -999/999 for open)
            boolean requireAllied,
            boolean requireNotAllied,
            boolean requireWar,
            boolean forbidWar,
            boolean forbidAllied,       // useful for “sabotage”
            // what pressure event to apply to PLAYER (visible in UI)
            String playerPressureTypeId,
            KingdomPressureState.RelScope playerScope,
            // optional “fuzz” pressure event to apply to AI kingdom (not shown in player UI)
            String aiPressureTypeId,
            KingdomPressureState.RelScope aiScope,
            // real relation delta (player -> that AI kingdom) so AI “realizes”
            int relationDelta,
            // npc speech pool id (in our small map below)
            String speechPoolId
    ) {}

    // ---- 25 actions ----
    private static final List<Action> ACTIONS = List.of(
            // =========================
            // KING (OFFICIAL) — 10
            // =========================
            new Action("KING_TRIBUTE", CH_KING, 0, 999, false,false,false,false,false,
                    "ai_envoy_praise", KingdomPressureState.RelScope.CAUSER_ONLY,
                    null, null,
                    +1, "king_tribute"),

            new Action("KING_FORMAL_APOLOGY", CH_KING, -999, 4, false,false,false,false,false,
                    "ai_envoy_praise", KingdomPressureState.RelScope.CAUSER_ONLY,
                    null, null,
                    +1, "king_apology"),

            new Action("KING_TRADE_PACT", CH_KING, 3, 999, false,false,false,true,false,
                    "ai_aid_supplies", KingdomPressureState.RelScope.GLOBAL,
                    null, null,
                    +1, "king_trade"),

            new Action("KING_REQUEST_AID", CH_KING, 5, 999, true,false,true,false,false,
                    "ai_send_mercenaries", KingdomPressureState.RelScope.GLOBAL,
                    null, null,
                    +0, "king_aid"),

            new Action("KING_OFFER_AID", CH_KING, 6, 999, true,false,false,false,false,
                    "ai_gift_grain", KingdomPressureState.RelScope.GLOBAL,
                    null, null,
                    +1, "king_offer_aid"),

            new Action("KING_NONAGGRESSION", CH_KING, 6, 999, false,false,false,true,false,
                    "ai_envoy_praise", KingdomPressureState.RelScope.CAUSER_ONLY,
                    null, null,
                    +1, "king_pact"),

            new Action("KING_THREATEN", CH_KING, -999, 3, false,false,false,false,false,
                    "ai_spread_rumors", KingdomPressureState.RelScope.CAUSER_ONLY,
                    null, null,
                    -1, "king_threaten"),

            new Action("KING_DEMAND_CONCESSIONS", CH_KING, -999, 1, false,false,false,false,false,
                    "ai_trade_embargo", KingdomPressureState.RelScope.GLOBAL,
                    null, null,
                    -1, "king_demand"),

            new Action("KING_FORMAL_COMPLAINT", CH_KING, -999, 5, false,false,false,false,false,
                    "ai_spread_rumors", KingdomPressureState.RelScope.CAUSER_ONLY,
                    null, null,
                    -1, "king_complaint"),

            new Action("KING_DECLARE_RIVALRY", CH_KING, -999, 0, false,true,false,false,false,
                    "ai_border_raids", KingdomPressureState.RelScope.GLOBAL,
                    null, null,
                    -2, "king_rivalry"),

            // =========================
            // NOBLE (COURT) — 6
            // =========================
            new Action("NOBLE_BRIBE", CH_NOBLE, 0, 999, false,false,false,false,false,
                    "ai_envoy_praise", KingdomPressureState.RelScope.CAUSER_ONLY,
                    null, null,
                    +1, "noble_bribe"),

            new Action("NOBLE_FLATTER", CH_NOBLE, 2, 999, false,false,false,false,false,
                    "ai_envoy_praise", KingdomPressureState.RelScope.CAUSER_ONLY,
                    null, null,
                    +1, "noble_flatter"),

            new Action("NOBLE_PRESS_CLAIM", CH_NOBLE, -999, 2, false,false,false,false,false,
                    "ai_spread_rumors", KingdomPressureState.RelScope.CAUSER_ONLY,
                    null, null,
                    -1, "noble_claim"),

            new Action("NOBLE_SPREAD_SCANDAL", CH_NOBLE, -999, 1, false,true,false,false,true,
                    "ai_spread_rumors", KingdomPressureState.RelScope.GLOBAL,
                    "ai_spread_rumors", KingdomPressureState.RelScope.GLOBAL,
                    -1, "noble_scandal"),

            new Action("NOBLE_BUY_FAVORS", CH_NOBLE, 4, 999, false,false,false,false,false,
                    "ai_training_advisors", KingdomPressureState.RelScope.GLOBAL,
                    null, null,
                    +0, "noble_favors"),

            new Action("NOBLE_UNDERMINE_COURT", CH_NOBLE, -999, 0, false,true,false,false,true,
                    "ai_spy_network", KingdomPressureState.RelScope.GLOBAL,
                    "ai_spy_network", KingdomPressureState.RelScope.GLOBAL,
                    -1, "noble_undermine"),

            // =========================
            // GUARD — 5
            // =========================
            new Action("GUARD_PAY_OFF", CH_GUARD, 0, 999, false,false,false,false,false,
                    "ai_smuggler_flood", KingdomPressureState.RelScope.GLOBAL,
                    null, null,
                    -1, "guard_payoff"),

            new Action("GUARD_INCITE_BRAWL", CH_GUARD, -999, 3, false,true,false,false,true,
                    "ai_bounty_hunters", KingdomPressureState.RelScope.GLOBAL,
                    "ai_bounty_hunters", KingdomPressureState.RelScope.GLOBAL,
                    -1, "guard_brawl"),

            new Action("GUARD_REQUEST_ESCORT", CH_GUARD, 4, 999, true,false,false,false,false,
                    "ai_war_intel", KingdomPressureState.RelScope.GLOBAL,
                    null, null,
                    +0, "guard_escort"),

            new Action("GUARD_BORDER_HARASS", CH_GUARD, -999, 2, false,false,true,false,false,
                    "ai_border_raids", KingdomPressureState.RelScope.GLOBAL,
                    "ai_border_raids", KingdomPressureState.RelScope.GLOBAL,
                    -1, "guard_harass"),

            new Action("GUARD_SPREAD_WANTED", CH_GUARD, -999, 2, false,true,false,false,true,
                    "ai_spy_network", KingdomPressureState.RelScope.GLOBAL,
                    "ai_spy_network", KingdomPressureState.RelScope.GLOBAL,
                    -1, "guard_wanted"),

            // =========================
            // VILLAGER — 4
            // =========================
            new Action("VILLAGER_GIFTS", CH_VILLAGER, 2, 999, false,false,false,false,false,
                    "ai_gift_grain", KingdomPressureState.RelScope.GLOBAL,
                    null, null,
                    +1, "villager_gifts"),

            new Action("VILLAGER_STIR_PANIC", CH_VILLAGER, -999, 2, false,true,false,false,true,
                    "ai_spread_rumors", KingdomPressureState.RelScope.GLOBAL,
                    "ai_spread_rumors", KingdomPressureState.RelScope.GLOBAL,
                    -1, "villager_panic"),

            new Action("VILLAGER_RECRUIT_INFORMANTS", CH_VILLAGER, -999, 3, false,true,false,false,true,
                    "ai_spy_network", KingdomPressureState.RelScope.GLOBAL,
                    "ai_spy_network", KingdomPressureState.RelScope.GLOBAL,
                    -1, "villager_informants"),

            new Action("VILLAGER_FUND_BANDITS", CH_VILLAGER, -999, 1, false,true,false,false,true,
                    "ai_fund_bandits", KingdomPressureState.RelScope.GLOBAL,
                    "ai_fund_bandits", KingdomPressureState.RelScope.GLOBAL,
                    -2, "villager_bandits")
    );

    // Tiny dialogue pools for AI NPC responses (4–5 lines per pool)
    private static final Map<String, String[]> SPEECH = new HashMap<>();
    static {
        SPEECH.put("king_tribute", new String[]{
                "Your gesture is noted. Do not squander this goodwill.",
                "A proper offering. We will remember it.",
                "Tribute speaks louder than promises.",
                "Very well. Let this be the start of better terms.",
                "You honor my court with coin and humility."
        });
        SPEECH.put("king_apology", new String[]{
                "Apologies are cheap—yet sometimes necessary.",
                "We will see if your words hold.",
                "A wise retreat from arrogance.",
                "I accept—for now.",
                "Let it not happen again."
        });
        SPEECH.put("king_trade", new String[]{
                "Trade benefits the calm and punishes the foolish.",
                "We will open routes—under terms.",
                "Coin prefers peace. So do I.",
                "You ask for commerce; we ask for respect.",
                "Let the caravans roll—carefully."
        });
        SPEECH.put("king_aid", new String[]{
                "Aid is not charity. It is investment.",
                "We will send support—do not embarrass us.",
                "Hold your line. Our wagons will follow.",
                "You ask much. Very well.",
                "Do not mistake help for weakness."
        });
        SPEECH.put("king_offer_aid", new String[]{
                "Generous… and noticed.",
                "You play the friend well.",
                "Such gifts buy warm memories.",
                "The court approves of this kindness.",
                "A wise move—for both our realms."
        });
        SPEECH.put("king_pact", new String[]{
                "Peace is a blade kept sheathed—by choice.",
                "I accept. Betray it and regret it.",
                "Let our borders cool—for a time.",
                "Very well. We will not strike first.",
                "A pact is ink. Keep it from turning to blood."
        });
        SPEECH.put("king_threaten", new String[]{
                "Careful. Threats are a path to war.",
                "You bark loudly for a stranger.",
                "Speak like that again and you’ll lose your tongue.",
                "I am not impressed.",
                "You have nerve. Not enough sense."
        });
        SPEECH.put("king_demand", new String[]{
                "You demand? You forget your station.",
                "We do not yield to pressure.",
                "You will find us stubborn.",
                "No.",
                "Leave before you insult us further."
        });
        SPEECH.put("king_complaint", new String[]{
                "Your complaint is heard. Not necessarily respected.",
                "So. You wish to lecture my court?",
                "We will consider it—briefly.",
                "Mind your tone.",
                "Noted."
        });
        SPEECH.put("king_rivalry", new String[]{
                "So be it. We will remember your choice.",
                "Rivalry is easy. Consequences are not.",
                "You’ve chosen an enemy.",
                "Then we are done here.",
                "Leave my hall."
        });

        SPEECH.put("noble_bribe", new String[]{
                "Coin smooths many doors.",
                "I can… suggest your name in the right place.",
                "You didn’t get this from me.",
                "A small favor—paid in full.",
                "Very well. I’ll whisper."
        });
        SPEECH.put("noble_flatter", new String[]{
                "Flattery? At least you know the game.",
                "Ah—yes, the court does admire that.",
                "You speak like someone who wants allies.",
                "Bold words. Sometimes useful.",
                "Careful—praise can sound like a trap."
        });
        SPEECH.put("noble_claim", new String[]{
                "Claims are knives in velvet.",
                "You’re stirring dangerous talk.",
                "The court won’t like this.",
                "You’re pushing too hard.",
                "Tread lightly."
        });
        SPEECH.put("noble_scandal", new String[]{
                "Scandal spreads faster than plague.",
                "You want dirt? There’s always dirt.",
                "This will sour the halls.",
                "Very well. I’ll let it slip…",
                "You didn’t hear it from me."
        });
        SPEECH.put("noble_favors", new String[]{
                "A favor here, a favor there—power is a web.",
                "You’re learning.",
                "Court moves slowly. But it moves.",
                "Spend wisely. Influence is expensive.",
                "We can make things… easier."
        });
        SPEECH.put("noble_undermine", new String[]{
                "Undermining a court is a long game.",
                "This is reckless.",
                "You’ll make enemies.",
                "Very well—quietly.",
                "Don’t expect gratitude."
        });

        SPEECH.put("guard_payoff", new String[]{
                "I saw nothing. Understood?",
                "Keep it quick and don’t bring trouble here.",
                "You pay well. That helps.",
                "If the captain asks, you were never here.",
                "Move along."
        });
        SPEECH.put("guard_brawl", new String[]{
                "You want chaos? Easy enough.",
                "A few fists in the tavern, eh?",
                "This will bring attention.",
                "Fine. But don’t blame me when it spreads.",
                "One shove and the whole street erupts."
        });
        SPEECH.put("guard_escort", new String[]{
                "We can spare a few eyes for your roads.",
                "Escort duty. Fine.",
                "Stay close and don’t do anything stupid.",
                "If trouble comes, listen to orders.",
                "We’ll walk you through."
        });
        SPEECH.put("guard_harass", new String[]{
                "Harass the border? That’s war talk.",
                "We can make their roads hurt.",
                "Quick strike, quick withdrawal.",
                "Fine. But it’ll come back on us.",
                "You didn’t hear it from me."
        });
        SPEECH.put("guard_wanted", new String[]{
                "A wanted poster goes a long way.",
                "Names travel. So does fear.",
                "Fine. We’ll spread the notice.",
                "This will make them jumpy.",
                "Careful—hunters follow coin."
        });

        SPEECH.put("villager_gifts", new String[]{
                "Oh! That’s… generous.",
                "People will talk—kindly.",
                "We needed this. Truly.",
                "Maybe you’re not like the others.",
                "Thank you."
        });
        SPEECH.put("villager_panic", new String[]{
                "Why would you say that?!",
                "Don’t start trouble!",
                "That’s going to spread… fast.",
                "You’re making people scared.",
                "Stop. Please."
        });
        SPEECH.put("villager_informants", new String[]{
                "You want ears in the street?",
                "Someone always talks for coin.",
                "Fine… but it’s ugly work.",
                "I can ask around.",
                "Be careful who you trust."
        });
        SPEECH.put("villager_bandits", new String[]{
                "Bandits? Are you mad?",
                "That’ll get people killed.",
                "I… I don’t want this.",
                "This is wrong.",
                "Don’t involve me."
        });
    }

    public static List<String> listAllowed(MinecraftServer server, UUID playerKid, UUID targetKid, String channel, int rel, boolean allied, boolean atWar) {
        List<String> out = new ArrayList<>();
        for (Action a : ACTIONS) {
            if (!a.channel.equals(channel)) continue;

            if (rel < a.minRel || rel > a.maxRel) continue;
            if (a.requireAllied && !allied) continue;
            if (a.requireNotAllied && allied) continue;
            if (a.forbidAllied && allied) continue;

            if (a.requireWar && !atWar) continue;
            if (a.forbidWar && atWar) continue;

            out.add(a.id);
        }
        return out;
    }

    public static String pickNpcLine(String poolId, net.minecraft.util.RandomSource r) {
        if (poolId == null) return null;
        String[] arr = SPEECH.get(poolId);
        if (arr == null || arr.length == 0) return null;
        return arr[r.nextInt(arr.length)];
    }

    public static Action byId(String id) {
        if (id == null) return null;
        for (Action a : ACTIONS) if (id.equals(a.id)) return a;
        return null;
    }

    public static String pickKingLineForAction(String actionId, net.minecraft.util.RandomSource r) {
        var a = byId(actionId);
        if (a == null) return null;
        return pickNpcLine(a.speechPoolId(), r);
    }


   /** Apply action effects: pressure on player, (optional) fuzz pressure on AI, and real relation delta. */
    public static boolean apply(
            MinecraftServer server,
            ServerPlayer player,
            UUID playerKid,
            UUID targetKid,
            String channel,
            String actionId
    ) {
        Action a = byId(actionId);
        if (a == null) return false;
        if (!a.channel.equals(channel)) return false;

        var ks = kingdomState.get(server);
        var pk = ks.getKingdom(playerKid);
        if (pk == null) return false;

        long now = server.getTickCount();

        boolean atWar = false;
        boolean allied = false;
        try { atWar = WarState.get(server).isAtWar(playerKid, targetKid); } catch (Throwable ignored) {}
        try { allied = AllianceState.get(server).isAllied(playerKid, targetKid); } catch (Throwable ignored) {}

        // relation eval
        var relState = DiplomacyRelationsState.get(server);
        int baseRel = relState.getRelation(player.getUUID(), targetKid);
        int effRel = PressureUtil.effectiveRelation(server, baseRel, playerKid, targetKid);

        // re-check gates using current state
        if (effRel < a.minRel || effRel > a.maxRel) return false;
        if (a.requireAllied && !allied) return false;
        if (a.requireNotAllied && allied) return false;
        if (a.forbidAllied && allied) return false;
        if (a.requireWar && !atWar) return false;
        if (a.forbidWar && atWar) return false;

        var ps = KingdomPressureState.get(server);

        // cooldown: same type cannot be triggered again until it expires (per-causer)
        if (a.playerPressureTypeId != null && ps.hasActiveByCauser(targetKid, a.playerPressureTypeId, now)) {
            return false;
        }

        // Track which effects we want to use for AI fuzz (prefer aiPressureTypeId if present)
        EnumMap<KingdomPressureState.Stat, Double> fuzzEffects = null;

        // ---- Apply PLAYER-visible pressure event (causee = player) ----
        if (a.playerPressureTypeId != null) {
            var playerTpl = PressureCatalog.byTypeId(a.playerPressureTypeId);
            if (playerTpl != null) {
                ps.addEvent(
                        targetKid,          // causer = foreign kingdom
                        playerKid,          // causee = player kingdom (visible in UI)
                        playerTpl.typeId(),
                        playerTpl.effects(),
                        a.playerScope,
                        now,
                        playerTpl.durationTicks()
                );

                // If we don't have a dedicated AI-side template, use playerTpl effects as a fallback for fuzz
                if (fuzzEffects == null) fuzzEffects = playerTpl.effects();
            }
        }

        // ---- Apply optional AI pressure event (causee = AI kingdom) ----
        if (a.aiPressureTypeId != null) {
            var aiTpl = PressureCatalog.byTypeId(a.aiPressureTypeId);
            if (aiTpl != null) {
                ps.addEvent(
                        playerKid,          // causer = player kingdom
                        targetKid,          // causee = AI kingdom
                        aiTpl.typeId(),
                        aiTpl.effects(),
                        a.aiScope,
                        now,
                        aiTpl.durationTicks()
                );

                // Prefer using the AI template for fuzz if present
                fuzzEffects = aiTpl.effects();
            }
        }

        // ---- AI “fuzz” impact (immediate feel) ----
        if (fuzzEffects != null) {
            applyAiFuzz(server, targetKid, fuzzEffects);
        }

        ps.markDirty();

        // ---- Real relation shift so they “notice” ----
        if (a.relationDelta != 0) {
            relState.addRelation(player.getUUID(), targetKid, a.relationDelta);
        }

        return true;
    }

}
