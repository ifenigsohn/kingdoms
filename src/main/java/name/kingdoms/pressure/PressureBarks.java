package name.kingdoms.pressure;


import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

public final class PressureBarks {
    private PressureBarks() {}

    // hard limit: 1 bark per kingdom per 2 minutes
    private static final long KINGDOM_BARK_CD_TICKS = 20L * 120L;

    // cooldown per kingdom
    private static final Map<UUID, Long> NEXT_BARK_TICK_BY_KINGDOM = new HashMap<>();

    // Keep it cheap: only attempt once per second per worker
    public static final int ATTEMPT_PERIOD_TICKS = 20;

    /** Called from kingdomWorkerEntity.tick() on SERVER. */
    public static void tryBark(ServerLevel level, name.kingdoms.entity.kingdomWorkerEntity w) {
        if (level == null || w == null) return;
        if (w.isRetinue()) return;

        UUID kid = w.getKingdomUUID();
        if (kid == null) return;

        long now = level.getGameTime();

        // kingdom-wide cooldown gate
        long next = NEXT_BARK_TICK_BY_KINGDOM.getOrDefault(kid, 0L);
        if (now < next) return;

        // only bark if a player is nearby (prevents “server spam” in empty areas)
        net.minecraft.world.entity.player.Player near = level.getNearestPlayer(w, 16.0);
        if (near == null) return;

        // get active pressure events on this kingdom
        var ps = KingdomPressureState.get(level.getServer());
        var events = ps.getEvents(kid);
        if (events == null || events.isEmpty()) return;

        // pick an active event that actually has a bark pool for this worker’s group
        String group = groupForJob(w.getJobId());
        if (group.isEmpty()) return;

        List<KingdomPressureState.PressureEvent> candidates = new ArrayList<>();
        long nowTickCount = level.getServer().getTickCount();

        for (var e : events) {
            if (e == null) continue;
            if (nowTickCount >= e.endTick()) continue;

            var tpl = PressureCatalog.byTypeId(e.typeId());
            if (tpl == null) continue;

            String pool = tpl.barkPoolForGroup(group);
            if (pool == null || pool.isBlank()) continue;

            candidates.add(e);
        }

        if (candidates.isEmpty()) return;

        // optional: light randomness so it doesn’t always bark immediately when CD expires
        // (still respects "no more than once every 2 min")
        if (level.random.nextFloat() > 0.55f) return;

        var chosen = candidates.get(level.random.nextInt(candidates.size()));
        var tpl = PressureCatalog.byTypeId(chosen.typeId());
        if (tpl == null) return;

        String pool = tpl.barkPoolForGroup(group);
        String line = BarkPools.pick(pool, level.random);
        if (line == null || line.isBlank()) return;

        // deliver to nearby players only
        sendLocalBark(level, w, line);

        // set kingdom cooldown
        NEXT_BARK_TICK_BY_KINGDOM.put(kid, now + KINGDOM_BARK_CD_TICKS);
    }

    private static void sendLocalBark(ServerLevel level, net.minecraft.world.entity.Entity speaker, String line) {
        String name = speaker.getName().getString();

        // Local chat-ish line. If you want “speech bubble” later, we can do packets + client rendering.
        Component msg = Component.literal(name + ": ").append(Component.literal(line));

        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(speaker) <= (24.0 * 24.0)) {
                p.sendSystemMessage(msg);
            }
        }
    }

    /** JobId -> bark group. Keep this aligned with jobDefinition canonical ids. */
    public static String groupForJob(String jobId) {
        if (jobId == null) return "";
        return switch (jobId) {
            // production/crafting workers
            case "farm", "butcher", "fishing",
                 "wood", "metal", "gem",
                 "alchemy", "weapon", "armor" -> "worker";

            // military-ish workers
            case "guard", "training", "garrison" -> "military";

            case "tavern" -> "tavern";
            case "chapel" -> "chapel";
            case "shop" -> "shop";
            case "nobility" -> "nobility";

            default -> "";
        };
    }

    /** Dialogue pools keyed by id. */
    public static final class BarkPools {
        private BarkPools() {}

        private static final Map<String, String[]> POOLS = new HashMap<>();

        static {
            // ---- PACE ----
            POOLS.put("pace_hard_worker", new String[]{
                    "No rest… the bell keeps ringing.",
                    "Faster, faster—my arms are lead.",
                    "If the quotas rise again, someone’s going to snap.",
                    "We’re worked like oxen, not folk.",
                    "Keep your head down and keep moving."
            });
            POOLS.put("pace_easy_worker", new String[]{
                    "Feels like we can breathe again.",
                    "Steady hands make better work, they say.",
                    "Not every day needs to be a sprint.",
                    "A kinder pace—maybe the lord’s listening.",
                    "We’ll finish when it’s done. Properly."
            });

            // ---- PATROLS ----
            POOLS.put("patrols_up_military", new String[]{
                    "Eyes open. Streets are ours tonight.",
                    "More rounds, fewer surprises.",
                    "Keep your torch high—show them we’re watching.",
                    "Crime scatters when steel walks.",
                    "Orders are orders. Move."
            });
            POOLS.put("patrols_down_military", new String[]{
                    "Half the watch, twice the trouble…",
                    "Feels too quiet. I don’t like it.",
                    "We’re stretched thin—don’t wander alone.",
                    "If something happens, we won’t reach you in time.",
                    "Stay near the lights."
            });

            // ---- RATIONS ----
            POOLS.put("rations_up_military", new String[]{
                    "Full bellies—steady hands.",
                    "Better rations. We’ll hold the line.",
                    "Feels like we matter again.",
                    "Eat up. Tomorrow’s work is blood.",
                    "Strength comes from the pot."
            });
            POOLS.put("rations_down_military", new String[]{
                    "Half rations? Then half the fight.",
                    "Hungry men make bad guards.",
                    "My stomach’s louder than the alarm bell.",
                    "They want loyalty on an empty bowl.",
                    "This won’t end well."
            });

            // ---- TAVERN ----
            POOLS.put("booze_subsidy_tavern", new String[]{
                    "Drinks are cheap—tongues get loose.",
                    "Tonight we forget the ledger.",
                    "A round for the realm!",
                    "Coin flows out, laughter flows in.",
                    "Careful—cheap ale brings expensive problems."
            });
            POOLS.put("booze_crackdown_tavern", new String[]{
                    "No singing, no smiling—just rules.",
                    "They’re counting cups now. Miserable work.",
                    "If they ban the ale, folk will find worse.",
                    "Quiet tables make loud grudges.",
                    "Even the fire feels cold tonight."
            });

            // ---- CHAPEL ----
            POOLS.put("services_chapel", new String[]{
                    "The bells call us again—don’t be late.",
                    "Prayer is cheaper than steel, they say.",
                    "Kneel, listen, endure.",
                    "The chapel’s full… like it’s a siege.",
                    "Faith holds what walls cannot."
            });

            // ---- NOBILITY ----
            POOLS.put("envoys_nobility", new String[]{
                    "Letters fly—friends may follow.",
                    "A warm word can do what swords can’t.",
                    "Envoys sent. Let’s see who smiles back.",
                    "Courts remember favors… and slights.",
                    "Polite ink, sharp intent."
            });
            POOLS.put("vassal_levy_nobility", new String[]{
                    "The vassals will grumble—then pay.",
                    "Coin demanded. Resentment is the interest.",
                    "They’ll send silver… and curses.",
                    "A heavy hand collects quickly, but it lingers.",
                    "Expect sour faces at the next hall."
            });

            // ---- SHOP ----
            POOLS.put("market_subsidy_shop", new String[]{
                    "Prices ease—folk breathe.",
                    "Subsidy’s in. Keep the shelves full.",
                    "Good for the street, bad for the purse.",
                    "Coin goes out, goodwill comes back.",
                    "If this holds, winter won’t bite as hard."
            });
            POOLS.put("contraband_crackdown_shop", new String[]{
                    "Watch your pockets. They’re searching carts.",
                    "Crackdown means fewer goods—and fewer smiles.",
                    "Someone’s always selling something… just quieter now.",
                    "If trade dries up, trouble grows.",
                    "Lawful streets… hungry stalls."
            });
            POOLS.put("global_plague_worker", new String[] {
                    "Keep your distance… folk are dropping sick.",
                    "No one’s shaking hands anymore.",
                    "They say it’s in the air. Or the water. Or both.",
                    "We burn herbs at night and pray it helps.",
                    "If you cough—leave. Please."
            });

            POOLS.put("global_plague_shop", new String[] {
                    "No crowds. No touching goods.",
                    "People buy candles and salt. Bad signs.",
                    "Prices don’t matter when fear’s the currency.",
                    "If this spreads, trade dies first.",
                    "I’m keeping the door barred after dusk."
            });

            POOLS.put("global_harvest_worker", new String[] {
                    "Look at that—bins are finally full.",
                    "For once, the work feels worth it.",
                    "Good harvest means fewer graves this winter.",
                    "We’re smiling again. Strange feeling.",
                    "If the weather holds, we’ll feast."
            });

            POOLS.put("global_bandits_military", new String[] {
                    "Bandits on the roads—keep your blade close.",
                    "They hit carts, not walls. Cowards.",
                    "We’re stretched thin. Don’t wander.",
                    "Torchlight and steel. That’s all that keeps order.",
                    "If you see smoke on the road—turn back."
            });

            POOLS.put("global_bandits_shop", new String[] {
                    "Caravans are late… or missing.",
                    "Fewer goods means harder choices.",
                    "Every missing cart is a hungry street.",
                    "If the roads stay unsafe, the market turns ugly.",
                    "Lock up early. Trust no one."
            });

            POOLS.put("global_festival_tavern", new String[] {
                    "A toast! Even the floorboards are dancing.",
                    "Music’s loud enough to scare the dead.",
                    "For one night, nobody talks about war.",
                    "Drink up—tomorrow the world returns.",
                    "If you’ve coin, spend it. If not, smile anyway."
            });

            POOLS.put("global_festival_worker", new String[] {
                    "Even the tools feel lighter today.",
                    "A festival buys a lot of forgiveness.",
                    "Let the children laugh while it lasts.",
                    "We’ll work again tomorrow. Tonight we live.",
                    "Try not to start fights. It’s a good day."
            });

            POOLS.put("global_drought_worker", new String[] {
                    "Ground’s cracked like old leather.",
                    "Water’s worth more than coin this week.",
                    "Fields are thirsty. People too.",
                    "If rain doesn’t come, trouble will.",
                    "We’re measuring meals now… not days."
            });

            POOLS.put("ai_aid_worker", new String[]{
                "A caravan came in—foreign seal on the crates.",
                "Supplies from afar… someone still favors us.",
                "We’ve got tools again. Proper ones.",
                "Coin’s moving. Feels like breathing room.",
                "Say what you will—help is help."
            });

            POOLS.put("ai_aid_military", new String[]{
                "Allied stores arrived. Good.",
                "More bolts, more bandages—keep them dry.",
                "They sent support. We won’t forget it.",
                "With this, we can hold the line.",
                "Tell the captain: supplies are in."
            });

            POOLS.put("ai_praise_nobility", new String[]{
                "An envoy arrived—smiling, flattering, watching.",
                "Courts speak well of our ruler today.",
                "A polite letter, a warmer tone.",
                "They’re praising us… for now.",
                "Friendship is ink. Let’s see if it holds."
            });

            POOLS.put("ai_embargo_shop", new String[]{
                "Foreign merchants turned back at the gates.",
                "No salt, no cloth—embargo, they say.",
                "Prices will climb. Folk will blame us.",
                "When trade stops, anger starts.",
                "They’re squeezing us without drawing steel."
            });

            POOLS.put("ai_bandits_military", new String[]{
                "Bandits are bold lately… too bold.",
                "Someone’s paying them. You can smell it.",
                "Roads aren’t safe. Patrol double.",
                "Hit-and-run, like trained hands.",
                "If we catch one, they’ll talk."
            });

            POOLS.put("ai_bandits_shop", new String[]{
                "Another cart missing. That’s not luck.",
                "Goods vanish, coin vanishes—same story.",
                "Bandits don’t get this organized alone.",
                "If the roads die, we starve.",
                "Lock up early. Trust nobody."
            });

            POOLS.put("ai_raids_worker", new String[]{
                "Smoke on the horizon… again.",
                "Fields trampled. Tools stolen.",
                "We rebuild what they burn. Every time.",
                "Border raids… like wolves testing a fence.",
                "If this keeps up, winter wins."
            });

            POOLS.put("ai_raids_military", new String[]{
                "Raiders crossed the line—fast and dirty.",
                "They want us tired, not dead.",
                "Track them. Don’t chase blind.",
                "They’ll hit again if we look weak.",
                "Steel up. We’re being tested."
            });

            // ====== ADD 10 BARK POOLS (PressureBarks.BarkPools) ======
            // Paste inside PressureBarks.BarkPools static { ... } initializer.

            POOLS.put("ai_gift_grain_worker", new String[]{
                    "Foreign wagons came in—grain sacks stacked high.",
                    "They sent food. For once, the stores look hopeful.",
                    "If this keeps up, winter won’t bite so hard.",
                    "Strange to feel… supported.",
                    "Say what you will—full bellies calm a town."
            });

            POOLS.put("ai_gift_grain_shop", new String[]{
                    "Bread’s cheaper today. That’s no accident.",
                    "A gift shipment—sealed and counted twice.",
                    "Supplies like this make friends fast.",
                    "We’ll stock the shelves while we can.",
                    "If it’s charity, it’s the useful kind."
            });

            POOLS.put("ai_send_mercs_military", new String[]{
                    "Mercenaries arrived—coin buys steel.",
                    "New blades in the yard. Keep an eye on them.",
                    "They’ll fight… as long as the purse holds.",
                    "No loyalty, just contracts. Still—helpful.",
                    "Let them stand the wall. We’ll watch their backs."
            });

            POOLS.put("ai_training_advisors_military", new String[]{
                    "Advisors came—drills are changing.",
                    "They’ve seen real battles. Listen and learn.",
                    "Formation work today. No excuses.",
                    "Better training beats bigger numbers.",
                    "If we learn fast, we bleed less."
            });

            POOLS.put("ai_war_intel_military", new String[]{
                    "Scouts brought maps—enemy routes marked.",
                    "We’ve got intel. That’s half a victory.",
                    "Ambush points on the road—stay sharp.",
                    "Someone’s watching the enemy for us.",
                    "This might save lives. Don’t waste it."
            });

            POOLS.put("ai_pilgrim_blessing_chapel", new String[]{
                    "Pilgrims arrived with blessings—and gossip.",
                    "The chapel’s full. Hope spreads like fire.",
                    "A friendly king’s word carries weight in prayer.",
                    "Light a candle—today feels gentler.",
                    "Faith travels farther than armies."
            });

            POOLS.put("ai_pilgrim_blessing_worker", new String[]{
                    "Folk are smiling again. It’s… unsettling.",
                    "Blessings or not, morale’s up.",
                    "Even tired hands work easier with hope.",
                    "Someone spoke well of us beyond the border.",
                    "Maybe the world hasn’t turned against us—yet."
            });

            POOLS.put("ai_spread_rumors_nobility", new String[]{
                    "Rumors from court—poison wrapped in silk.",
                    "They’re whispering about our ruler again.",
                    "A scandal can cost more than a siege.",
                    "Keep your words careful. Ears are everywhere.",
                    "Someone wants us looking weak."
            });

            POOLS.put("ai_spread_rumors_worker", new String[]{
                    "Heard the talk? It’s ugly, and it’s spreading.",
                    "People argue in the street like it matters to them.",
                    "Rumors don’t feed children, but they start fights.",
                    "Feels like someone is stirring the pot.",
                    "If this keeps up, trouble’s coming."
            });

            POOLS.put("ai_spy_network_military", new String[]{
                    "Too many strangers with sharp eyes.",
                    "Someone’s paying informants—check the gates.",
                    "Don’t speak plans in the open. Ever.",
                    "If they know our patrols, we change them.",
                    "Spies don’t win wars—but they start them."
            });

            POOLS.put("ai_spy_network_shop", new String[]{
                    "Odd buyers asking odd questions.",
                    "Someone’s casing the stalls, not shopping.",
                    "If you hear talk of routes—keep it to yourself.",
                    "A quiet spy costs the loudest coin.",
                    "Lock the ledger. Trust no one."
            });

            POOLS.put("ai_sabotage_stores_worker", new String[]{
                    "Tools went missing. Again.",
                    "Stores spoiled overnight—like it was planned.",
                    "We lose a day’s work to fix what they break.",
                    "Someone wants us hungry and tired.",
                    "If we catch them, it won’t be gentle."
            });

            POOLS.put("ai_sabotage_stores_shop", new String[]{
                    "Crates tampered with. Seals broken.",
                    "Stock’s ruined—someone knew where to hit.",
                    "We’ll raise prices or we’ll close. Simple as that.",
                    "This isn’t theft—it’s sabotage.",
                    "If it keeps happening, the market will riot."
            });

            POOLS.put("ai_bounty_hunters_military", new String[]{
                    "Bounty hunters in the region—trouble follows them.",
                    "They’re not guards. They’re predators.",
                    "If they’re paid to hunt, someone’s paying.",
                    "Keep the peace—don’t give them excuses.",
                    "I don’t like hired killers near our walls."
            });

            POOLS.put("ai_smuggler_flood_shop", new String[]{
                    "Smugglers everywhere—cheap goods, dirty hands.",
                    "Contraband’s flowing like water.",
                    "If law fails, honest trade dies.",
                    "Someone’s backing this. Feels organized.",
                    "Don’t ask where it came from—just lock up."
            });

            POOLS.put("ai_smuggler_flood_worker", new String[]{
                    "Folk buying strange goods at night.",
                    "Smugglers make coin, not friends.",
                    "When law bends, it snaps later.",
                    "Feels like the town’s slipping sideways.",
                    "If guards crack down, it’ll get ugly fast."
            });



        }

        public static String pick(String poolId, net.minecraft.util.RandomSource rand) {
            if (poolId == null) return null;
            String[] lines = POOLS.get(poolId);
            if (lines == null || lines.length == 0) return null;
            return lines[rand.nextInt(lines.length)];
        }
    }
}
