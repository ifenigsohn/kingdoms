package name.kingdoms.ambient;

import name.kingdoms.aiKingdomState;
import net.minecraft.server.level.ServerLevel;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class AmbientDialogue {
    private AmbientDialogue() {}

    public enum Tone {
        WILDERNESS,
        WAR_EDGE,
        OWN_KINGDOM,
        FOREIGN_ALLIED,
        FOREIGN_NEUTRAL,
        FOREIGN_HOSTILE
    }

    /** Tag-only entry point. Prefer calling this from events. */
    /** Tag-only entry point. Prefer calling this from events. */
    public static String pickLine(AmbientContext ctx, String role, String dialogueTag) {
    role = (role == null) ? "villager" : role.toLowerCase(Locale.ROOT);

    if (dialogueTag == null || dialogueTag.isBlank()) {
        return fallbackLine(ctx, role);
    }

    

    // =====================================================
    // GENERATED GOSSIP (personality-driven)
    // =====================================================
    if ("gossip_peasants_about_king".equals(dialogueTag)
            || "gossip_nobles_about_king".equals(dialogueTag)) {
        return GossipLines.generate(ctx, role, dialogueTag);
    }

    String tagged = pickTagged(ctx, role, dialogueTag);
    if (tagged != null) return tagged;

    return fallbackLine(ctx, role);
}


    private static void mergeTag(String tag, Map<String, String[]> extra) {
        Map<String, String[]> base = TAG_POOLS.get(tag);
        if (base == null) {
            TAG_POOLS.put(tag, new java.util.HashMap<>(extra));
            return;
        }
        java.util.HashMap<String, String[]> merged = new java.util.HashMap<>(base);
        merged.putAll(extra); // extras override existing keys if present
        TAG_POOLS.put(tag, merged);
    }


    private static String pickTagged(AmbientContext ctx, String role, String tag) {
        Map<String, String[]> byKey = TAG_POOLS.get(tag);
        if (byKey == null) return null;

        Tone tone = pickTone(ctx);
        KingdomMoodBands bands = KingdomMoodBands.from(ctx);

        // Most specific → least specific
        // Most specific → least specific
        String[] pool = null;

        // Special-case: in YOUR kingdom and YOU are the sovereign → address player as king
        if (tone == Tone.OWN_KINGDOM && playerIsKing(ctx)) {
        pool = byKey.get(role + ":OWN_KINGDOM_PLAYER_KING");
        }

        if (pool == null) pool = byKey.get(role + ":" + tone.name());
        if (pool == null && bands.poverty) pool = byKey.get(role + ":POOR");
        if (pool == null && bands.excess)  pool = byKey.get(role + ":RICH");
        if (pool == null) pool = byKey.get(role);

        if (pool == null) pool = byKey.get("any:" + tone.name());
        if (pool == null) pool = byKey.get("any");

        if (pool == null || pool.length == 0) return null;
        return pool[ctx.level().random.nextInt(pool.length)];


    }

    private static Tone pickTone(AmbientContext ctx) {
        if (ctx.nearWarZone()) return Tone.WAR_EDGE;

        if (!ctx.inKingdom()) return Tone.WILDERNESS;

        // In a kingdom
        if (ctx.playerKingdom() != null && ctx.hereKingdom() != null
                && ctx.playerKingdom().id.equals(ctx.hereKingdom().id)) {
            return Tone.OWN_KINGDOM;
        }

        // Foreign kingdom
        if (ctx.alliedHere()) return Tone.FOREIGN_ALLIED;

        int rel = ctx.relationHere();
        if (rel <= -20) return Tone.FOREIGN_HOSTILE;
        if (rel >= 20) return Tone.FOREIGN_ALLIED;
        return Tone.FOREIGN_NEUTRAL;
    }

    /** Cheap mood bands without adding new SavedData. */
    private static final class KingdomMoodBands {
        final boolean poverty;
        final boolean excess;

        private KingdomMoodBands(boolean poverty, boolean excess) {
            this.poverty = poverty;
            this.excess = excess;
        }

        static KingdomMoodBands from(AmbientContext ctx) {
            if (ctx.aiHere() != null) {
                double food = ctx.aiHere().meat + ctx.aiHere().grain + ctx.aiHere().fish;
                boolean pov = (ctx.aiHere().gold < 250 && food < 300);
                boolean exc = (ctx.aiHere().gold > 1200 || food > 1500);
                return new KingdomMoodBands(pov, exc);
            }
            return new KingdomMoodBands(false, false);
        }
    }

    private static boolean playerIsKing(AmbientContext ctx) {
        if (ctx.playerKingdom() == null || ctx.player() == null) return false;

        UUID playerId = ctx.player().getUUID();
        Object pk = ctx.playerKingdom();

        // Try common field names
        for (String fName : new String[]{"kingId", "rulerId", "leaderId", "sovereignId", "ownerId"}) {
                try {
                var f = pk.getClass().getField(fName);
                Object v = f.get(pk);
                if (v instanceof UUID id) return playerId.equals(id);
                } catch (Throwable ignored) {}
        }

        // Try common getter names
        for (String mName : new String[]{"getKingId", "getRulerId", "getLeaderId", "getSovereignId", "getOwnerId"}) {
                try {
                var m = pk.getClass().getMethod(mName);
                Object v = m.invoke(pk);
                if (v instanceof UUID id) return playerId.equals(id);
                } catch (Throwable ignored) {}
        }

        return false;
        }



    /** If a tag/role pool is missing, we still show *some* line. */
    private static String fallbackLine(AmbientContext ctx, String role) {
        Tone tone = pickTone(ctx);
        // Keep it extremely generic and non-repetitive by role + tone.
        return switch (tone) {
            case WAR_EDGE -> switch (role) {
                case "soldier", "guard", "scout" -> "Stay back. It’s bad out there.";
                case "refugee", "peasant" -> "We’re just trying to get away.";
                default -> "Smoke on the wind…";
            };
            case WILDERNESS -> switch (role) {
                case "scout" -> "Tracks ahead. Keep quiet.";
                case "trader" -> "I don’t camp near roads anymore.";
                default -> "Quiet out here. Too quiet.";
            };
            default -> switch (role) {
                case "guard" -> "Move along.";
                case "trader" -> "Coin talks, friend.";
                case "envoy" -> "I carry words, not steel.";
                case "noble" -> "Mind yourself in these lands.";
                case "scholar" -> "All things are measured.";
                default -> "Keep your head down.";
            };
        };
    }

    // =====================================================
    // TAG_POOLS — tag-only dialogue
    // keys: "role:TONE", "role:POOR", "role:RICH", "role", "any:TONE", "any"
    // =====================================================
    private static final Map<String, Map<String, String[]>> TAG_POOLS = new HashMap<>();

    static {
    TAG_POOLS.put("pressgang_on_road", Map.of(
            "guard:OWN_KINGDOM", new String[]{
                    "Order from the crown. Hands and name.",
                    "The levy’s due. Don’t make it worse.",
                    "Argue later. Move now."
            },
            "guard:FOREIGN_HOSTILE", new String[]{
                    "Not your land, not your choice. Move.",
                    "You’re lucky we’re taking names first.",
                    "One more word and you’ll be dragged."
            },
            "peasant:POOR", new String[]{
                    "I’ve children… please…",
                    "There’s nothing left in me to give.",
                    "They’ll take me and my family starves."
            },
            "peasant", new String[]{
                    "Let me speak to my wife first.",
                    "I’m not a soldier. I’m not.",
                    "I’ll pay—just don’t take me."
            },
            "any:WAR_EDGE", new String[]{
                    "War makes men into numbers.",
                    "You can hear the levy before you see it."
            },
            "any", new String[]{
                    "Keep walking. Don’t look too long."
            }
    ));

    TAG_POOLS.put("hungry_night_camp", Map.of(
            "peasant:WILDERNESS", new String[]{
                    "We’ll eat in town tomorrow. We must.",
                    "I don’t light fires anymore. Too many eyes.",
                    "If wolves don’t get us, men will."
            },
            "peasant:POOR", new String[]{
                    "The little one keeps asking for bread.",
                    "We boiled roots. It tastes like shame.",
                    "We traded our knife for a crust."
            },
            "any:WILDERNESS", new String[]{
                    "Quiet roads mean someone owns the silence."
            },
            "any", new String[]{
                    "Cold night. Colder world."
            }
    ));

    TAG_POOLS.put("grave_by_the_road", Map.of(
            "soldier:WAR_EDGE", new String[]{
                    "He marched fine yesterday.",
                    "No songs now. Just counting.",
                    "Don’t stare—unless you want to remember."
            },
            "peasant:WAR_EDGE", new String[]{
                    "We buried him with a borrowed shovel.",
                    "His boots were still warm.",
                    "War takes names and leaves holes."
            },
            "any:WAR_EDGE", new String[]{
                    "Smoke carries farther than truth."
            },
            "any", new String[]{
                    "A road remembers who fell on it."
            }
    ));

    TAG_POOLS.put("field_argument", Map.of(
            "peasant:OWN_KINGDOM", new String[]{
                    "You took more than your share!",
                    "The steward won’t hear me—so you will.",
                    "Hands off my sack!"
            },
            "peasant:POOR", new String[]{
                    "There’s not enough. Not for all of us.",
                    "We fight over crumbs like dogs.",
                    "I’d kill for a loaf and hate myself after."
            },
            "any", new String[]{
                    "Work makes tempers sharp."
            }
    ));

    TAG_POOLS.put("roadside_blessing", Map.of(
            "scholar:WILDERNESS", new String[]{
                    "May the road forget your name.",
                    "A blessing is lighter than armor, but it helps.",
                    "Step careful. The world listens out here."
            },
            "peasant:WILDERNESS", new String[]{
                    "If it keeps bandits away, I’ll take it.",
                    "Blessings don’t fill bellies. Still… thank you.",
                    "Say one for my family, will you?"
            },
            "any", new String[]{
                    "Travel changes people. Usually for the worse."
            }
    ));

    TAG_POOLS.put("tax_collector_refused", Map.of(
            "scholar:FOREIGN_HOSTILE", new String[]{
                    "I only write what I see. And I see poverty.",
                    "Your court will read this and pretend surprise.",
                    "A kingdom squeezes until something breaks."
            },
            "scholar:OWN_KINGDOM", new String[]{
                    "Ledgers don’t lie, but men do.",
                    "If there’s nothing to take, I’ll write that too.",
                    "Tax is a blade—best used carefully."
            },
            "peasant:POOR", new String[]{
                    "Take the roof too, why not?",
                    "We already paid in blood.",
                    "There’s nothing left to count."
            },
            "any", new String[]{
                    "Coin always finds a way uphill."
            }
    ));

    TAG_POOLS.put("drunk_soldier", Map.of(
            "soldier:WAR_EDGE", new String[]{
                    "I didn’t even know his name.",
                    "I keep hearing it when it’s quiet.",
                    "They said it’d feel like glory."
            },
            "peasant:WAR_EDGE", new String[]{
                    "He’s not mean—just broken.",
                    "War follows people home.",
                    "Leave him. He’ll sleep it off or not."
            },
            "any", new String[]{
                    "Some victories rot inside you."
            }
    ));

    TAG_POOLS.put("children_play_war", Map.of(
            "peasant:OWN_KINGDOM", new String[]{
                    "Stop that—don’t say those words.",
                    "You don’t know what you’re playing at.",
                    "Go play something kinder."
            },
            "any:WAR_EDGE", new String[]{
                    "Even children learn the sounds of war."
            },
            "any", new String[]{
                    "A game becomes a lesson too easily."
            }
    ));

    TAG_POOLS.put("poacher_whispers", Map.of(
            "peasant:WILDERNESS", new String[]{
                    "Keep your eyes down. Forest has ears.",
                    "I saw tracks—men, not deer.",
                    "If you hear a twig snap twice, run."
            },
            "peasant:POOR", new String[]{
                    "I hunt because hunger hunts me.",
                    "A snare is cheaper than a loaf.",
                    "The lord’s laws don’t fill my belly."
            },
            "any", new String[]{
                    "Wilderness has rules—none of them written."
            }
    ));

    TAG_POOLS.put("bandit_victims", Map.of(
            "peasant:WILDERNESS", new String[]{
                    "They took everything. Even the shoes.",
                    "We begged. They laughed.",
                    "Roads aren’t roads anymore. They’re traps."
            },
            "soldier:WAR_EDGE", new String[]{
                    "Bandits breed where banners burn.",
                    "I can’t chase ghosts and fight a war.",
                    "If you travel, travel armed."
            },
            "any", new String[]{
                    "A kingdom weakens first on its roads."
            }
    ));

    TAG_POOLS.put("mill_breakdown", Map.of(
            "scholar:OWN_KINGDOM", new String[]{
                    "One broken gear, and hunger follows.",
                    "We fix mills before we fix pride.",
                    "A kingdom is wood and math."
            },
            "peasant:POOR", new String[]{
                    "No milling means no bread.",
                    "We’ll be chewing grain like animals.",
                    "Tell the lord to eat promises."
            },
            "any", new String[]{
                    "Infrastructure is invisible—until it fails."
            }
    ));

    TAG_POOLS.put("plague_cart", Map.of(
            "scholar:WAR_EDGE", new String[]{
                    "War and sickness travel together.",
                    "Keep distance. Pray from far away.",
                    "When bodies pile up, disease takes interest."
            },
            "peasant:POOR", new String[]{
                    "Don’t touch him—please…",
                    "We can’t afford sickness.",
                    "If God is watching, He’s turned away."
            },
            "any", new String[]{
                    "Some problems don’t care who rules."
            }
    ));

    TAG_POOLS.put("tavern_brawl", Map.of(
            "peasant:OWN_KINGDOM", new String[]{
                    "He called my mother a thief!",
                    "Put that down before someone dies!",
                    "It’s always the same—coin, pride, drink."
            },
            "guard:FOREIGN_HOSTILE", new String[]{
                    "Enough. This isn’t your tavern.",
                    "Hands where I can see them.",
                    "One more swing and you’re in chains."
            },
            "any", new String[]{
                    "A crowd turns ugly fast."
            }
    ));

    TAG_POOLS.put("blacksmith_shortage", Map.of(
            "soldier:WAR_EDGE", new String[]{
                    "No nails, no horseshoes—no marching.",
                    "We fight with scraps now.",
                    "Armor’s thin. So are we."
            },
            "peasant:POOR", new String[]{
                    "Metal’s dearer than bread.",
                    "They’ll take our pots next.",
                    "War eats iron and leaves hunger."
            },
            "any", new String[]{
                    "Shortages start quiet, then get loud."
            }
    ));

    TAG_POOLS.put("lost_livestock", Map.of(
            "peasant:OWN_KINGDOM", new String[]{
                    "If I don’t find it, I don’t eat.",
                    "Tracks went toward the treeline.",
                    "Someone’s been cutting fences again."
            },
            "scout:OWN_KINGDOM", new String[]{
                    "I’ll look. But I’m not chasing ghosts all night.",
                    "If it’s wolves, you’ll find blood.",
                    "If it’s men, you’ll find lies."
            },
            "any", new String[]{
                    "A missing animal is a missing winter."
            }
    ));

    // ----- CK3 tags -----

    TAG_POOLS.put("envoy_complains_delay", Map.of(
            "envoy:FOREIGN_ALLIED", new String[]{
                    "My lord expected an answer weeks ago.",
                    "We are friends—don’t make us strangers.",
                    "Delay turns goodwill sour."
            },
            "envoy:FOREIGN_NEUTRAL", new String[]{
                    "Courts remember silence as an insult.",
                    "An unsigned promise is worth nothing.",
                    "I need a decision, not a shrug."
            },
            "any", new String[]{
                    "Diplomacy is patience wearing expensive clothes."
            }
    ));

    TAG_POOLS.put("noble_disputes_border", Map.of(
            "noble:FOREIGN_HOSTILE", new String[]{
                    "This land is ours by blood and ink.",
                    "Your border is a joke drawn in mud.",
                    "Tell your lord I’m watching."
            },
            "noble:FOREIGN_NEUTRAL", new String[]{
                    "My grandfather hunted here.",
                    "Borders are stories until men bleed for them.",
                    "A steward’s map can start a war."
            },
            "any", new String[]{
                    "Borders are arguments with swords waiting behind them."
            }
    ));

    TAG_POOLS.put("court_gossip", Map.of(
            "scholar:OWN_KINGDOM", new String[]{
                    "They say the steward hasn’t slept in days.",
                    "Someone’s writing letters by candlelight again.",
                    "Court smiles hide sharp teeth."
            },
            "peasant:OWN_KINGDOM", new String[]{
                    "The castle’s quiet. Too quiet.",
                    "They feast while we count coppers.",
                    "I heard shouting behind stone walls."
            },
            "any", new String[]{
                    "News travels fastest where it matters least."
            }
    ));

    TAG_POOLS.put("merchant_bribes_guard", Map.of(
            "trader:FOREIGN_HOSTILE", new String[]{
                    "Coin is the only language you speak, yes?",
                    "Let’s both forget this happened.",
                    "A small fee for a small kindness."
            },
            "guard:FOREIGN_HOSTILE", new String[]{
                    "You’re paying for silence.",
                    "You’ll pay twice if I’m caught.",
                    "Move along. Quickly."
            },
            "any", new String[]{
                    "Corruption is just tax without receipts."
            }
    ));

    TAG_POOLS.put("treaty_rumors", Map.of(
            "scholar:WAR_EDGE", new String[]{
                    "Treaties begin as rumors and end as graves—or relief.",
                    "Peace is negotiated by hungry men.",
                    "War is logistics wearing a crown."
            },
            "peasant:WAR_EDGE", new String[]{
                    "They say peace is being written already.",
                    "I’ll believe it when the shouting stops.",
                    "Words won’t bring the dead back."
            },
            "any", new String[]{
                    "Every war ends—most end badly."
            }
    ));

    TAG_POOLS.put("foreign_soldiers_passing", Map.of(
            "soldier:FOREIGN_ALLIED", new String[]{
                    "Different colors, same mud.",
                    "Allies today. Maybe not tomorrow.",
                    "We march under borrowed promises."
            },
            "guard:FOREIGN_ALLIED", new String[]{
                    "Let them pass. Watch them anyway.",
                    "Allies still count your gates.",
                    "Friendly boots still wear grooves."
            },
            "any", new String[]{
                    "An alliance is a door that swings both ways."
            }
    ));

    TAG_POOLS.put("steward_counts_grain", Map.of(
            "scholar:POOR", new String[]{
                    "We’ll last the winter. Barely.",
                    "If grain is counted, someone intends to take it.",
                    "Short stores make sharp laws."
            },
            "scholar:RICH", new String[]{
                    "The stores are full. For now.",
                    "Prosperity buys time, not safety.",
                    "Full barns attract hungry neighbors."
            },
            "any", new String[]{
                    "Bread is policy you can chew."
            }
    ));

    TAG_POOLS.put("threatening_messenger", Map.of(
            "envoy:FOREIGN_HOSTILE", new String[]{
                    "I was told to speak plainly, so I will.",
                    "Some messages aren’t meant to be refused.",
                    "Your answer decides whether men march."
            },
            "guard:FOREIGN_HOSTILE", new String[]{
                    "Don’t touch the seal.",
                    "He speaks; we leave. That’s the order.",
                    "Eyes forward."
            },
            "any", new String[]{
                    "Ultimatums are diplomacy with a knife out."
            }
    ));

    TAG_POOLS.put("tribute_caravan", Map.of(
            "trader:FOREIGN_ALLIED", new String[]{
                    "Gold moves easier than armies.",
                    "A gift today buys a friend tomorrow.",
                    "Count it twice. Someone will."
            },
            "guard:FOREIGN_NEUTRAL", new String[]{
                    "Stay back. This is accounted for.",
                    "Hands off the chests.",
                    "Caravans draw thieves like flies."
            },
            "any", new String[]{
                    "Tribute is peace rented by the month."
            }

            
    ));

    TAG_POOLS.put("spy_caught", Map.of(
            "guard:FOREIGN_HOSTILE", new String[]{
                    "Caught you where you shouldn’t be.",
                    "Talk, or you’ll hang quiet.",
                    "Spies don’t get graves—just warnings."
            },
            "envoy:FOREIGN_HOSTILE", new String[]{
                    "I’m no spy. I’m a messenger.",
                    "You’ll regret this mistake.",
                    "Do you know whose seal that is?"
            },
            "any", new String[]{
                    "Paranoia is a kingdom’s second wall."
            }
            
    ));

    TAG_POOLS.put("succession_rumor", Map.of(
            "noble:OWN_KINGDOM", new String[]{
                    "If the heir falters, the realm will bite.",
                    "A crown is lighter than a rumor—until it isn’t.",
                    "Succession is where loyalty goes to die."
            },
            "scholar:OWN_KINGDOM", new String[]{
                    "A line of inheritance is a line of blood.",
                    "Courts sharpen knives when kings cough.",
                    "Names matter more than swords, sometimes."
            },
            "any", new String[]{
                    "A realm breathes. A realm can choke."
            }
    ));

    TAG_POOLS.put("marriage_whispers", Map.of(
            "scholar:FOREIGN_ALLIED", new String[]{
                    "A marriage can end a war before it starts.",
                    "Two houses share a bed; a realm shares peace.",
                    "They’re negotiating vows like treaties."
            },
            "peasant:OWN_KINGDOM", new String[]{
                    "They marry for land, not love.",
                    "Feasts for them—empty bowls for us.",
                    "If it keeps war away, fine."
            },
            "any", new String[]{
                    "Dynasties are built in bedrooms and graveyards."
            }
    ));

    TAG_POOLS.put("trade_embargo_rumor", Map.of(
            "trader:FOREIGN_HOSTILE", new String[]{
                    "Prices will jump by tomorrow.",
                    "They’re choking the roads with ‘inspections’.",
                    "An embargo is war with clean hands."
            },
            "peasant:POOR", new String[]{
                    "Bread’s dear. Salt’s gone.",
                    "We’ll pay for their politics with our stomachs.",
                    "Tell the lords to eat their papers."
            },
            "any", new String[]{
                    "When trade dies, bandits thrive."
            }
    ));

    TAG_POOLS.put("papal_legate", Map.of(
            "envoy:OWN_KINGDOM", new String[]{
                    "I bring counsel, not coin.",
                    "A realm’s virtue is tested in peace, not war.",
                    "Your lord’s reputation travels ahead of him."
            },
            "scholar:OWN_KINGDOM", new String[]{
                    "A blessing can be leverage.",
                    "Even piety has a price at court.",
                    "Words from holy men move armies."
            },
            "any", new String[]{
                    "Faith and politics share the same table."
            }
    ));

    // =====================================================
    // TAG_POOLS — ports for the “older” AmbientEvents ids
    // =====================================================

    TAG_POOLS.put("envoy_visit_scripted", Map.of(
            "envoy:FOREIGN_ALLIED", new String[]{
                    "My lord sends respects—and expects them returned.",
                    "A friendly seal. Let’s keep it that way.",
                    "We come as friends, not beggars."
            },
            "envoy:FOREIGN_NEUTRAL", new String[]{
                    "I require a signature, not a story.",
                    "Courts dislike waiting.",
                    "Say yes, say no—just say it clearly."
            },
            "envoy:FOREIGN_HOSTILE", new String[]{
                    "I’ll deliver your answer, whatever it is.",
                    "Careful. Some words can’t be taken back.",
                    "You are not the only realm with patience."
            },
            "guard", new String[]{
                    "State your business with the envoy.",
                    "No sudden moves. Seals can lie.",
                    "Keep the peace."
            },
            "any", new String[]{
                    "A messenger at the gate always means trouble—or opportunity."
            }
    ));

    TAG_POOLS.put("refugees_war_edge", Map.of(
            "refugee:WAR_EDGE", new String[]{
                    "We ran when the fires rose.",
                    "They took the young first.",
                    "Don’t go that way. Just… don’t."
            },
            "peasant:WAR_EDGE", new String[]{
                    "They came with banners and left with our grain.",
                    "My sister didn’t make it out.",
                    "War doesn’t end. It moves."
            },
            "scholar:WAR_EDGE", new String[]{
                    "Displacement is the first tax of war.",
                    "A burned field feeds nobody—victor or not.",
                    "When people flee, a realm is already bleeding."
            },
            "any:WAR_EDGE", new String[]{
                    "You can smell a war before you see it."
            },
            "any", new String[]{
                    "Eyes down. Keep walking."
            }
    ));

    TAG_POOLS.put("soldier_patrol_roads", Map.of(
            "soldier", new String[]{
                    "Road’s watched. Don’t test it.",
                    "Bandits hit where patrols get lazy.",
                    "Keep moving. No loitering."
            },
            "scout", new String[]{
                    "Tracks ahead—fresh.",
                    "Wind’s wrong. Too quiet.",
                    "If you see crows circling, turn back."
            },
            "any", new String[]{
                    "A guarded road means someone’s afraid."
            }
    ));

    TAG_POOLS.put("wilderness_traveler", Map.of(
            "peasant:WILDERNESS", new String[]{
                    "No banner out here. Just teeth and weather.",
                    "I sleep in trees when I can.",
                    "If you hear singing, don’t follow it."
            },
            "trader:WILDERNESS", new String[]{
                    "Profit’s thin when roads are hungry.",
                    "I trade fast and camp farther.",
                    "Every mile costs something."
            },
            "any:WILDERNESS", new String[]{
                    "The wilderness doesn’t care who rules."
            },
            "any", new String[]{
                    "Safe travels. You’ll need luck."
            }
    ));
    
    TAG_POOLS.put("wilderness_camp_small", Map.of(
            "peasant:WILDERNESS", new String[]{
                    "No banner out here. Just teeth and weather.",
                    "Not much to share, I’m afraid, but you’re welcome to the fire a spell",
                    "Keep walking. We’ve had enough trouble tonight.",
                    "Hands where I can see ’em.",
                    "Profit’s thin when roads are hungry.",
                    "I trade fast and camp farther.",
                    "Road’s safer with company, but we don’t ask questions.",
                    "Please… don’t hurt us.",
                    "Saints watch us tonight…",
                    "Ho! Stranger! Come drink, before it’s gone!",
                    "Sing with us or leave—either way’s fine.",
                    "That fire’s the warmest thing in my life.",

            },
            "any:WILDERNESS", new String[]{
                    "The wilderness doesn’t care who rules."
            },
            "any", new String[]{
                    "Safe travels. You’ll need luck."
            }
    ));

    TAG_POOLS.put("border_watch", Map.of(
            "guard:FOREIGN_NEUTRAL", new String[]{
                    "Border’s close. Watch your step.",
                    "You cross that line, you answer for it.",
                    "Names and purpose."
            },
            "guard:FOREIGN_HOSTILE", new String[]{
                    "One foot over and we’ll assume the worst.",
                    "Turn around. Now.",
                    "We’re not welcoming today."
            },
            "guard:FOREIGN_ALLIED", new String[]{
                    "Allied colors. Still—no surprises.",
                    "Pass, but keep your hands visible.",
                    "Borders make everyone nervous."
            },
            "any", new String[]{
                    "A border is just a fight waiting for paperwork."
            }
    ));

    TAG_POOLS.put("scholar_prayer", Map.of(
            "scholar:OWN_KINGDOM", new String[]{
                    "Bless the harvest. Bless the hands that earn it.",
                    "A prayer costs nothing—so rulers love them.",
                    "May order hold. May hunger wait."
            },
            "peasant:OWN_KINGDOM", new String[]{
                    "If prayers worked, we’d be full already.",
                    "Still… I’ll take whatever helps.",
                    "Say one for my boy."
            },
            "any", new String[]{
                    "Even the proud bow their heads sometimes."
            }
    ));

    TAG_POOLS.put("scholar_engineering_notes", Map.of(
            "scholar", new String[]{
                    "Stone fails where the load lies, not where men complain.",
                    "A well-built bridge is a promise kept.",
                    "Math is mercy when it keeps roofs standing."
            },
            "any", new String[]{
                    "A kingdom built poorly collapses honestly."
            }
    ));

    TAG_POOLS.put("bridge_inspection", Map.of(
            "scholar", new String[]{
                    "This span will hold—if nobody ‘improves’ it.",
                    "Rot hides under pride and paint.",
                    "If it falls, it won’t ask who’s noble."
            },
            "guard", new String[]{
                    "Don’t lean on it if you value your bones.",
                    "Move along. Inspection in progress.",
                    "No crowds."
            },
            "any", new String[]{
                    "Maintenance is boring—until it isn’t."
            }
    ));

    TAG_POOLS.put("noble_retinue_passes", Map.of(
            "noble", new String[]{
                    "Make way. The realm has business.",
                    "A slow road is an insult.",
                    "Eyes forward. You saw nothing."
            },
            "guard", new String[]{
                    "Clear the path!",
                    "Keep your distance from the retinue.",
                    "No questions."
            },
            "any", new String[]{
                    "Power travels loud, even when it whispers."
            }
    ));

    TAG_POOLS.put("town_crier", Map.of(
            "peasant:OWN_KINGDOM", new String[]{
                    "Hear it! New taxes, same hunger!",
                    "They promise safety again—like last time.",
                    "If you miss the news, you’ll feel it anyway."
            },
            "scholar:OWN_KINGDOM", new String[]{
                    "A proclamation is a ruler’s shadow.",
                    "Words first—men later.",
                    "Listen. Policy hides in announcements."
            },
            "any", new String[]{
                    "A crier’s voice is cheaper than change."
            }
    ));

    TAG_POOLS.put("trader_price_rumor", Map.of(
            "trader:POOR", new String[]{
                    "Bread’s up again. Coin’s down.",
                    "If you want salt, buy it now.",
                    "Bad harvests make liars of honest men."
            },
            "trader:RICH", new String[]{
                    "Good year for sellers. Bad year for buyers.",
                    "Stores are full—prices still climb.",
                    "Profit loves uncertainty."
            },
            "any", new String[]{
                    "Prices rise faster than banners fall."
            }
    ));

    TAG_POOLS.put("market_argument", Map.of(
            "peasant", new String[]{
                    "That’s robbery and you know it!",
                    "My family won’t eat ‘tomorrow’!",
                    "You weigh the scale with your thumb!"
            },
            "trader", new String[]{
                    "Pay the price or walk away.",
                    "I don’t set harvests—I set numbers.",
                    "Take it or leave it. Others will buy."
            },
            "any", new String[]{
                    "Markets are where desperation learns math."
            }
    ));

    TAG_POOLS.put("guard_checks_papers", Map.of(
            "guard", new String[]{
                    "Papers. Now.",
                    "You look lost—or dishonest.",
                    "No seal, no entry."
            },
            "trader", new String[]{
                    "Of course I have papers. I always have papers.",
                    "If I’m delayed, the goods spoil—and so does my mood.",
                    "Check it quick. I’ve paid enough already."
            },
            "peasant", new String[]{
                    "I’ve no papers. I’ve only hands.",
                    "I’m just trying to pass…",
                    "Please. I did nothing."
            },
            "any", new String[]{
                    "A checkpoint is fear made official."
            }
    ));

    TAG_POOLS.put("scholar_teaches_children", Map.of(
            "scholar", new String[]{
                    "Letters first. Swords later.",
                    "A mind fed early feeds a realm later.",
                    "Count your grain, count your days, count your promises."
            },
            "peasant", new String[]{
                    "If they can read, maybe they can leave this life.",
                    "Teach them something kinder than war.",
                    "My boy’s quick—faster than me."
            },
            "any", new String[]{
                    "Education is rebellion with clean hands."
            }
    ));

    TAG_POOLS.put("funeral_procession", Map.of(
            "peasant:WAR_EDGE", new String[]{
                    "No songs left. Only steps.",
                    "He died far from home, but we bury him anyway.",
                    "War sends bodies back in pieces."
            },
            "scholar:WAR_EDGE", new String[]{
                    "A funeral is a census the realm refuses to read.",
                    "Grief is the truest report from war.",
                    "Every procession is a warning."
            },
            "any:WAR_EDGE", new String[]{
                    "Lower your voice. The dead are nearby."
            },
            "any", new String[]{
                    "People remember who made them bury their young."
            }
    ));

    TAG_POOLS.put("wilderness_scouts", Map.of(
            "scout:WILDERNESS", new String[]{
                    "Tracks everywhere—none of them good.",
                    "If you're lost, stop wandering. It gets people killed.",
                    "Smoke two hills over. Fresh."
            },
            "scout:WAR_EDGE", new String[]{
                    "War pushes animals out. Men too.",
                    "You can feel the edge before you see it.",
                    "Keep low. Listen."
            },
            "any:WILDERNESS", new String[]{
                    "The woods don't care who rules."
            },
            "any", new String[]{
                    "Quiet is never free."
            }
    ));

    TAG_POOLS.put("war_rumors_peasants", Map.of(
            "peasant:WAR_EDGE", new String[]{
                    "They say the levies are coming again.",
                    "Someone saw banners at dawn.",
                    "If the fighting comes here, we're finished."
            },
            "soldier:WAR_EDGE", new String[]{
                    "Rumors spread faster than arrows.",
                    "If you hear shouting, you're already late.",
                    "Stay behind walls if you've got sense."
            },
            "peasant:POOR", new String[]{
                    "War or winter—either way we starve.",
                    "They take our grain and call it duty.",
                    "My hands are empty and they still ask for more."
            },
            "any", new String[]{
                    "A war starts as talk, then it becomes weather."
            }
    ));

    TAG_POOLS.put("grain_shortage_gossip", Map.of(
            "peasant:POOR", new String[]{
                    "We’re thinning the porridge again.",
                    "The miller says there’s nothing to grind.",
                    "If bread gets dearer, people get uglier."
            },
            "trader:POOR", new String[]{
                    "Buy grain now. Tomorrow it’ll be gone.",
                    "Shortage makes honest men into thieves.",
                    "Everyone blames me for the numbers."
            },
            "trader:RICH", new String[]{
                    "Stores are full, but prices climb anyway.",
                    "Prosperity makes people greedy, not kind.",
                    "A good harvest just changes who profits."
            },
            "any", new String[]{
                    "Hunger is politics you can feel."
            }
    ));

    TAG_POOLS.put("roadside_shrine", Map.of(
            "scholar:WILDERNESS", new String[]{
                    "A little faith to keep the road honest.",
                    "Shrines are where travelers leave fear behind.",
                    "Some prayers are just habits of survival."
            },
            "scholar:WAR_EDGE", new String[]{
                    "War makes saints out of cowards and corpses out of brave men.",
                    "If you pray, pray for distance.",
                    "Even faith keeps its head down near a battle."
            },
            "any:WILDERNESS", new String[]{
                    "People build shrines where they feel small."
            },
            "any", new String[]{
                    "Better a candle than nothing."
            }
    ));

    TAG_POOLS.put("hunter_camp_tent", Map.of(
        "peasant:WILDERNESS", new String[] {
                "Quiet. You’ll scare the game.",
                "Fresh tracks—deer, maybe boar.",
                "Camp’s small, but it’s warm enough."
        },
        "any:WILDERNESS", new String[] {
                "Smoke carries. Keep it low."
        }
        ));


    TAG_POOLS.put("wounded_soldier", Map.of(
            "soldier:WAR_EDGE", new String[]{
                    "Don’t look. I’m still here, aren’t I?",
                    "It didn’t hurt until it was over.",
                    "Tell them… tell them it wasn’t worth it."
            },
            "peasant:WAR_EDGE", new String[]{
                    "We dragged him from the ditch ourselves.",
                    "No healer. No wagon. Just luck.",
                    "War leaves leftovers."
            },
            "any:WAR_EDGE", new String[]{
                    "After the fighting, the real suffering starts."
            },
            "any", new String[]{
                    "Move on. If you can."
            }
    ));

    TAG_POOLS.put("deserter_whispers", Map.of(
            "soldier:WAR_EDGE", new String[]{
                    "I’m done. Let them hang me—better than marching.",
                    "They don’t feed you. They don’t bury you. They just spend you.",
                    "If they ask, you didn’t see me."
            },
            "soldier:FOREIGN_HOSTILE", new String[]{
                    "Wrong war. Wrong lord. Wrong grave.",
                    "Tell your guards I’m already dead.",
                    "A deserter only gets one peace."
            },
            "any:WAR_EDGE", new String[]{
                    "Desertion is a war’s first confession."
            },
            "any", new String[]{
                    "Keep that to yourself."
            }
    ));

    TAG_POOLS.put("scouts_report_border", Map.of(
            "scout:FOREIGN_NEUTRAL", new String[]{
                    "Riders near the border. Could be nothing.",
                    "Fresh prints on the road—too many boots.",
                    "We saw smoke where there shouldn’t be smoke."
            },
            "scout:FOREIGN_HOSTILE", new String[]{
                    "They’re probing the line. Testing us.",
                    "If they cross, it won’t be politely.",
                    "Tell your lord: they’re watching."
            },
            "any", new String[]{
                    "Borders are quiet until they aren’t."
            }
    ));

    TAG_POOLS.put("pilgrims_on_road", Map.of(
            "peasant:WILDERNESS", new String[]{
                    "We walk because staying felt worse.",
                    "A shrine ahead, they say. Hope tastes the same everywhere.",
                    "If we reach it, maybe things change."
            },
            "scholar:WILDERNESS", new String[]{
                    "Pilgrims carry stories like candles.",
                    "Faith is a map for people with no roads.",
                    "Even kings fear what crowds believe."
            },
            "any:WAR_EDGE", new String[]{
                    "Pilgrims turn around when the roads smell like smoke."
            },
            "any", new String[]{
                    "Some journeys are just survival with ceremony."
            }
    ));

    // =====================================================
    // COVERAGE PACKS — add missing role:tone variants to reduce fallbacks
    // Paste at END of static { ... } after your TAG_POOLS.put(...) entries.
    // =====================================================

    // ---------- Core / older tags ----------
    mergeTag("envoy_visit_scripted", Map.of(
            "envoy:OWN_KINGDOM", new String[]{
                    "Your court asked for news—so here I am.",
                    "Seals, schedules, and headaches. As usual.",
                    "I bring words. Please keep the swords sheathed."
            },
            "envoy:FOREIGN_ALLIED", new String[]{
                    "An allied seal—let’s keep it friendly.",
                    "We come in goodwill, not weakness.",
                    "My lord sends respect… and expects it returned."
            },
            "envoy:FOREIGN_NEUTRAL", new String[]{
                    "I require a signature, not a story.",
                    "Courts dislike waiting. Decide clearly.",
                    "Say yes or no—just say it plainly."
            },
            "guard:OWN_KINGDOM", new String[]{
                    "Envoy business. Don’t crowd.",
                    "Let him speak, then let him leave.",
                    "No trouble around official matters."
            }
    ));

    mergeTag("border_watch", Map.of(
            "guard:OWN_KINGDOM", new String[]{
                    "Border duty. Boring until it isn’t.",
                    "Report anything strange. Anything.",
                    "If you see riders, count them."
            },
            "guard:WAR_EDGE", new String[]{
                    "Keep back from the line. Fighting’s close.",
                    "No one crosses today without orders.",
                    "If arrows start flying, run first—ask later."
            }
    ));

    mergeTag("soldier_patrol_roads", Map.of(
            "soldier:OWN_KINGDOM", new String[]{
                    "Road’s watched. Keep it peaceful.",
                    "Patrol’s on duty—move along.",
                    "Bandits get bold when we get lazy."
            },
            "soldier:FOREIGN_NEUTRAL", new String[]{
                    "Foreign road, foreign rules—don’t test them.",
                    "Keep moving. No loitering.",
                    "We’re watching for trouble."
            },
            "scout:WAR_EDGE", new String[]{
                    "War pushes men onto roads like rats.",
                    "Keep low—eyes on the treeline.",
                    "If you hear crows, something’s dead ahead."
            }
    ));

    mergeTag("wilderness_traveler", Map.of(
            "peasant:WAR_EDGE", new String[]{
                    "I’m heading away from the smoke. Always away.",
                    "You can taste battle in the wind out here.",
                    "Roads near war belong to wolves and men."
            },
            "trader:WAR_EDGE", new String[]{
                    "Profit dies where arrows fly.",
                    "I’m detouring—costs more, lives longer.",
                    "War makes every mile expensive."
            }
    ));

    mergeTag("wilderness_scouts", Map.of(
            "scout:WAR_EDGE", new String[]{
                    "War pushes animals out. Men too.",
                    "You can feel the edge before you see it.",
                    "Keep low. Listen first."
            },
            "soldier:WAR_EDGE", new String[]{
                    "We’re screening the woods for raiders.",
                    "If you travel, travel quiet.",
                    "No banners here—just ambushes."
            }
    ));

    mergeTag("refugees_war_edge", Map.of(
            "refugee:WILDERNESS", new String[]{
                    "We’re lost. But it’s better than burning.",
                    "We sleep wherever fear lets us.",
                    "We only stop when the children can’t walk."
            },
            "peasant:FOREIGN_NEUTRAL", new String[]{
                    "We don’t care whose crown it is. Just let us pass.",
                    "We’ve already paid in smoke and screaming.",
                    "Don’t send us back."
            }
    ));

    mergeTag("war_rumors_peasants", Map.of(
            "peasant:OWN_KINGDOM", new String[]{
                    "They’re talking war again. Like it’s weather.",
                    "I heard the steward counting men, not grain.",
                    "If banners come, we’re the ones who bleed."
            },
            "soldier:OWN_KINGDOM", new String[]{
                    "Rumors are noise. Keep working, keep calm.",
                    "If fighting comes, you’ll know soon enough.",
                    "Stay behind walls if you’ve got sense."
            }
    ));

    mergeTag("grain_shortage_gossip", Map.of(
            "peasant:OWN_KINGDOM", new String[]{
                    "Porridge again. Always porridge.",
                    "If the stores shrink, tempers grow.",
                    "Bread gets dearer—people get uglier."
            },
            "any:OWN_KINGDOM", new String[]{
                    "A hungry town is a dangerous town.",
                    "Short grain makes sharp laws."
            }
    ));

    mergeTag("scholar_prayer", Map.of(
            "scholar:FOREIGN_NEUTRAL", new String[]{
                    "Order holds until hunger challenges it.",
                    "Even foreign fields need blessing.",
                    "Prayers are cheap; consequences are not."
            },
            "scholar:FOREIGN_HOSTILE", new String[]{
                    "A hostile court still eats the same bread.",
                    "Pride starves faster than bodies.",
                    "May wisdom reach those who refuse it."
            }
    ));

    mergeTag("bridge_inspection", Map.of(
            "scholar:WAR_EDGE", new String[]{
                    "War breaks bridges faster than rot does.",
                    "A collapsed span kills more than soldiers.",
                    "If it fails, supply dies with it."
            },
            "guard:WAR_EDGE", new String[]{
                    "No crowds. Not today.",
                    "Move. Bridges are targets near war.",
                    "Keep your distance and your head down."
            }
    ));

    mergeTag("noble_retinue_passes", Map.of(
            "noble:FOREIGN_HOSTILE", new String[]{
                    "Out of the way. This road is not yours.",
                    "Stare again and you’ll regret it.",
                    "My patience ends quickly in чужих lands."
            },
            "any:FOREIGN_NEUTRAL", new String[]{
                    "Power travels loud—even when it whispers."
            }
    ));

    mergeTag("town_crier", Map.of(
            "peasant:FOREIGN_NEUTRAL", new String[]{
                    "Hear it! New rules for old hunger!",
                    "They promise safety like they invented it.",
                    "If you miss the news, you’ll feel it anyway."
            },
            "any:OWN_KINGDOM", new String[]{
                    "A proclamation is a ruler’s shadow.",
                    "Words first—men later."
            }
    ));

    mergeTag("trader_price_rumor", Map.of(
            "trader:OWN_KINGDOM", new String[]{
                    "Buy now or pay later. That’s how it goes.",
                    "Harvest sets the truth; coin just repeats it.",
                    "I don’t like the numbers either."
            },
            "trader:FOREIGN_NEUTRAL", new String[]{
                    "Foreign markets, foreign prices—worse every week.",
                    "Road tolls climb faster than grain.",
                    "Uncertainty is great for profit. Bad for peace."
            }
    ));

    mergeTag("roadside_shrine", Map.of(
            "any:WAR_EDGE", new String[]{
                    "Pray for distance. That’s the best mercy.",
                    "War makes saints out of fear.",
                    "Even faith keeps its head down near a battle."
            },
            "any:FOREIGN_NEUTRAL", new String[]{
                    "Shrines are where travelers leave fear behind."
            }
    ));

    mergeTag("wounded_soldier", Map.of(
            "soldier:FOREIGN_NEUTRAL", new String[]{
                    "Don’t pity me—just keep moving.",
                    "War doesn’t care whose border you’re on.",
                    "Tell your lord… tell him it costs too much."
            },
            "peasant:FOREIGN_NEUTRAL", new String[]{
                    "We dragged him out ourselves. No one else would.",
                    "No healer, no wagon—just luck.",
                    "War leaves leftovers."
            }
    ));

    mergeTag("deserter_whispers", Map.of(
            "any:FOREIGN_NEUTRAL", new String[]{
                    "A deserter only gets one peace.",
                    "If they ask, you didn’t see anything."
            }
    ));

    mergeTag("scouts_report_border", Map.of(
            "scout:OWN_KINGDOM", new String[]{
                    "Riders near the border. Too many boots.",
                    "Fresh smoke where there shouldn’t be smoke.",
                    "They’re probing the line."
            },
            "any:OWN_KINGDOM", new String[]{
                    "Borders are quiet until they aren’t."
            }
    ));

    mergeTag("pilgrims_on_road", Map.of(
            "any:FOREIGN_NEUTRAL", new String[]{
                    "Pilgrims carry stories like candles.",
                    "Faith is a map for people with no roads."
            },
            "any:OWN_KINGDOM", new String[]{
                    "Some journeys are survival with ceremony."
            }
    ));

    mergeTag("market_argument", Map.of(
            "peasant:OWN_KINGDOM", new String[]{
                    "My family won’t eat ‘tomorrow’!",
                    "That’s robbery and you know it!",
                    "You weigh the scale with your thumb!"
            },
            "trader:OWN_KINGDOM", new String[]{
                    "Harvest sets the price, not my smile.",
                    "Pay it or walk away.",
                    "I sell goods, not mercy."
            },
            "any:FOREIGN_NEUTRAL", new String[]{
                    "Markets are where desperation learns math."
            }
    ));

    mergeTag("guard_checks_papers", Map.of(
            "guard:OWN_KINGDOM", new String[]{
                    "Routine check. Don’t make it a problem.",
                    "Show the seal and you’ll be on your way.",
                    "We’ve had trouble lately. Papers."
            },
            "guard:FOREIGN_NEUTRAL", new String[]{
                    "Foreign faces draw attention. Papers.",
                    "Neutral doesn’t mean trusted.",
                    "State your business and be brief."
            },
            "guard:FOREIGN_HOSTILE", new String[]{
                    "Hostile lands don’t stroll through our gates.",
                    "You’re lucky we’re asking first.",
                    "One wrong seal and you’re done."
            }
    ));

    mergeTag("scholar_teaches_children", Map.of(
            "scholar:OWN_KINGDOM", new String[]{
                    "Letters first. Swords later.",
                    "A mind fed early feeds a realm later.",
                    "Count your grain, count your promises."
            },
            "scholar:FOREIGN_NEUTRAL", new String[]{
                    "Education makes peasants dangerous—in the best way.",
                    "A child who reads fears less.",
                    "Numbers outlive kings."
            }
    ));

    mergeTag("funeral_procession", Map.of(
            "any:OWN_KINGDOM", new String[]{
                    "Lower your voice. Grief is nearby.",
                    "People remember who made them bury their young."
            },
            "any:FOREIGN_NEUTRAL", new String[]{
                    "Every procession is a warning."
            }
    ));

    mergeTag("pressgang_on_road", Map.of(
            "guard:FOREIGN_NEUTRAL", new String[]{
                    "Levy order. Names and hands.",
                    "Don’t make me chase you.",
                    "You’ll walk, or you’ll be dragged."
            },
            "guard:OWN_KINGDOM", new String[]{
                    "Order from the crown. Don’t argue with paper.",
                    "The levy’s due. Move.",
                    "We don’t like it either. Still, we do it."
            }
    ));

    mergeTag("hungry_night_camp", Map.of(
            "any:WAR_EDGE", new String[]{
                    "No fires near war. Light invites steel.",
                    "Quiet roads mean someone owns the silence.",
                    "If wolves don’t get you, men will."
            }
    ));

    mergeTag("grave_by_the_road", Map.of(
            "any:WILDERNESS", new String[]{
                    "A road remembers who fell on it.",
                    "Some markers are warnings, not memorials."
            }
    ));

    mergeTag("field_argument", Map.of(
            "any:FOREIGN_NEUTRAL", new String[]{
                    "Work makes tempers sharp.",
                    "A full belly forgives more."
            }
    ));

    mergeTag("roadside_blessing", Map.of(
            "any:WAR_EDGE", new String[]{
                    "Pray for distance. That’s the best blessing.",
                    "A blessing is lighter than armor—still helps.",
                    "Step careful. The world listens out here."
            }
    ));

    mergeTag("tax_collector_refused", Map.of(
            "scholar:FOREIGN_NEUTRAL", new String[]{
                    "I only write what I see. And I see poverty.",
                    "Courts squeeze until something breaks.",
                    "Ledgers don’t lie—men do."
            },
            "peasant:OWN_KINGDOM", new String[]{
                    "Take the roof too, why not?",
                    "We already paid in blood.",
                    "There’s nothing left to count."
            }
    ));

    mergeTag("drunk_soldier", Map.of(
            "soldier:OWN_KINGDOM", new String[]{
                    "It doesn’t leave you when you get home.",
                    "They said it’d feel like glory.",
                    "I keep hearing it when it’s quiet."
            }
    ));

    mergeTag("children_play_war", Map.of(
            "any:OWN_KINGDOM", new String[]{
                    "A game becomes a lesson too easily.",
                    "Even children learn the sounds of war."
            }
    ));

    mergeTag("poacher_whispers", Map.of(
            "peasant:FOREIGN_NEUTRAL", new String[]{
                    "Forest has ears. Keep your eyes down.",
                    "If you hear a twig snap twice, run.",
                    "I hunt because hunger hunts me."
            }
    ));

    mergeTag("bandit_victims", Map.of(
            "any:WAR_EDGE", new String[]{
                    "Bandits breed where banners burn.",
                    "When war weakens roads, thieves take the crown."
            }
    ));

    mergeTag("mill_breakdown", Map.of(
            "any:FOREIGN_NEUTRAL", new String[]{
                    "Infrastructure is invisible—until it fails.",
                    "Bread depends on boring things working."
            }
    ));

    mergeTag("plague_cart", Map.of(
            "any:WAR_EDGE", new String[]{
                    "War and sickness travel together.",
                    "Keep distance. Pray from far away."
            }
    ));

    mergeTag("tavern_brawl", Map.of(
            "guard:OWN_KINGDOM", new String[]{
                    "Enough! This ends now.",
                    "Hands where I can see them.",
                    "One more swing and you’re in chains."
            }
    ));

    mergeTag("blacksmith_shortage", Map.of(
            "any:OWN_KINGDOM", new String[]{
                    "Shortages start quiet, then get loud.",
                    "War eats iron and leaves hunger."
            }
    ));

    mergeTag("lost_livestock", Map.of(
            "any:FOREIGN_NEUTRAL", new String[]{
                    "A missing animal is a missing winter.",
                    "Tracks tell truth. People rarely do."
            }
    ));

    // ---------- CK3 tags ----------
    mergeTag("envoy_complains_delay", Map.of(
            "envoy:OWN_KINGDOM", new String[]{
                    "Your own court is slow. Even friends get tired.",
                    "A sealed letter is lighter than a delayed answer.",
                    "Silence becomes policy if you let it."
            }
    ));

    mergeTag("noble_disputes_border", Map.of(
            "noble:OWN_KINGDOM", new String[]{
                    "Borders are promises written in mud.",
                    "A steward’s map can start a war.",
                    "My family’s claim is older than your fence."
            }
    ));

    mergeTag("court_gossip", Map.of(
            "scholar:FOREIGN_NEUTRAL", new String[]{
                    "Courts are mirrors—everyone looks, nobody sees.",
                    "A smile hides a knife as easily as a sleeve.",
                    "Politics is manners weaponized."
            }
    ));

    mergeTag("merchant_bribes_guard", Map.of(
            "trader:FOREIGN_NEUTRAL", new String[]{
                    "A little gratitude for a little speed.",
                    "If gates slow me, goods spoil. Help me out.",
                    "We can both profit from fewer questions."
            }
    ));

    mergeTag("treaty_rumors", Map.of(
            "any:FOREIGN_NEUTRAL", new String[]{
                    "Peace starts as rumor and ends as paperwork.",
                    "Treaties are just wars that got tired."
            }
    ));

    mergeTag("foreign_soldiers_passing", Map.of(
            "any:FOREIGN_NEUTRAL", new String[]{
                    "Allies today. Maybe not tomorrow.",
                    "Different colors, same mud."
            }
    ));

    mergeTag("steward_counts_grain", Map.of(
            "any:OWN_KINGDOM", new String[]{
                    "Bread is policy you can chew.",
                    "If grain is counted, someone intends to take it."
            }
    ));

    mergeTag("threatening_messenger", Map.of(
            "envoy:FOREIGN_NEUTRAL", new String[]{
                    "Consider this a warning delivered politely.",
                    "My lord prefers answers in ink, not blood.",
                    "Refuse if you like—just don’t pretend surprise later."
            },
            "guard:OWN_KINGDOM", new String[]{
                    "Let him speak. Then he goes.",
                    "No heroics. This is politics.",
                    "Hands off the seal."
            }
    ));

    mergeTag("tribute_caravan", Map.of(
            "guard:OWN_KINGDOM", new String[]{
                    "The treasury counts this. Don’t get close.",
                    "Caravan’s under crown protection. Keep moving.",
                    "Eyes off the chests. Hands visible."
            },
            "guard:FOREIGN_ALLIED", new String[]{
                    "Allied caravan, still guarded. No trouble.",
                    "Pass, but don’t linger near the wagons.",
                    "Friends today—still keep your distance."
            },
            "guard:FOREIGN_HOSTILE", new String[]{
                    "You’re not welcome near this cargo.",
                    "Step back. Now.",
                    "Try it and we’ll call it theft."
            },
            "trader:OWN_KINGDOM", new String[]{
                    "A gift for the realm—count it, seal it, done.",
                    "Gold moves smoother when it’s ‘tribute’.",
                    "Let’s call it friendship and keep roads open."
            },
            "trader:FOREIGN_NEUTRAL", new String[]{
                    "It’s a courtesy, not a weakness.",
                    "Coin is cheaper than funerals.",
                    "Tell your lord it arrived intact."
            }
    ));

    mergeTag("soldier_patrol_roads", Map.of(
                // --- Player is king in own kingdom (direct address) ---
                "soldier:OWN_KINGDOM_PLAYER_KING", new String[]{
                        "My liege. Roads are secure—no bandit activity reported.",
                        "Your Grace, patrol reports: quiet routes and clear crossings.",
                        "Sire. We’ve driven off prowlers near the treeline.",
                        "My king. We’ll keep the roads safe for your people.",
                        "Your Majesty—orders? We’ll carry them out."
                },

                // --- Own kingdom (generic / if player not sovereign) ---
                "soldier:OWN_KINGDOM", new String[]{
                        "Road’s watched. Keep it peaceful.",
                        "Patrol’s on duty—move along.",
                        "Bandits get bold when we get lazy.",
                        "If you see trouble, report it to the guard.",
                        "We keep the roads clear so the realm can breathe."
                },

                // --- Foreign kingdom: allied ---
                "soldier:FOREIGN_ALLIED", new String[]{
                        "Allied traveler—good. Keep to the road and there’s no trouble.",
                        "We’re friends of your crown today. Don’t make it complicated.",
                        "Pass freely. Still—no weapons drawn near the villages.",
                        "Allied banners don’t mean no rules. Walk straight and we’ll nod.",
                        "Safe roads help both realms. Keep it that way."
                },

                // --- Foreign kingdom: neutral ---
                "soldier:FOREIGN_NEUTRAL", new String[]{
                        "Foreign face. Stay on the road and we won’t speak twice.",
                        "Neutral doesn’t mean trusted. Don’t loiter.",
                        "Pass through—quietly.",
                        "Keep your hands visible. It avoids misunderstandings.",
                        "Trade if you must. But don’t test our patience."
                },

                // --- Foreign kingdom: hostile ---
                "soldier:FOREIGN_HOSTILE", new String[]{
                        "Hostile colors don’t belong on our roads. Move.",
                        "We’re watching you. Closely.",
                        "One wrong step and we call it scouting.",
                        "Crossing our land is a privilege you haven’t earned.",
                        "If you’re here to cause trouble, you’ll die tired."
                },

                // Optional: scouts can also be tone-aware (nice flavor)
                "scout:FOREIGN_HOSTILE", new String[]{
                        "Foreign footsteps travel loud. We heard you coming.",
                        "Hostile lands aren’t forgiving—turn back while you can.",
                        "We track everything that moves out here. Including you."
                },
                "scout:FOREIGN_ALLIED", new String[]{
                        "Allied traveler—keep to the path. The woods don’t know treaties.",
                        "Road’s safe enough. Off-road is another story."
                }
        ));


    mergeTag("spy_caught", Map.of(
            "guard:OWN_KINGDOM", new String[]{
                    "You’re in the wrong place for an honest person.",
                    "Talk fast. The dungeon is patient.",
                    "No one ‘wanders’ into restricted halls."
            },
            "guard:FOREIGN_NEUTRAL", new String[]{
                    "I don’t care who you serve—explain yourself.",
                    "Neutral or not, you need permission.",
                    "Give me one reason not to bind you."
            },
            "envoy:FOREIGN_NEUTRAL", new String[]{
                    "This is a misunderstanding—search my papers.",
                    "I carry seals, not secrets.",
                    "Detain me and you’ll answer to my lord."
            }
    ));

    mergeTag("succession_rumor", Map.of(
            "any:FOREIGN_NEUTRAL", new String[]{
                    "Succession is where loyalty goes to die.",
                    "A cough in court can move armies."
            }
    ));

    mergeTag("marriage_whispers", Map.of(
            "any:FOREIGN_NEUTRAL", new String[]{
                    "Dynasties are built in bedrooms and graveyards.",
                    "A marriage can end a war before it starts."
            }
    ));

    mergeTag("trade_embargo_rumor", Map.of(
            "any:FOREIGN_NEUTRAL", new String[]{
                    "An embargo is war with clean hands.",
                    "When trade dies, bandits thrive."
            }
    ));

    mergeTag("papal_legate", Map.of(
            "any:FOREIGN_NEUTRAL", new String[]{
                    "Faith and politics share the same table.",
                    "Holy words can move unholy armies."
            }
    ));


}
    private static final class GossipLines {

        enum Band { LOW, MED, HIGH }

        static String generate(AmbientContext ctx, String role, String tag) {
            boolean nobles = "gossip_nobles_about_king".equals(tag) || "noble".equalsIgnoreCase(role);

            String kingName = resolveKingName(ctx);
            String place = ctx.inKingdom() ? "in these lands" : "out here";

            // Pick a trait to gossip about for variety
            String[] traits = new String[] {"aggression", "honor", "greed", "pragmatism", "trustBias"};
            String trait = traits[ctx.level().random.nextInt(traits.length)];

            double v = readTrait01(ctx, trait); // 0..1 (best effort)
            Band b = band(v);

            return switch (trait) {
                case "aggression" -> nobles
                        ? nobleAggression(ctx, kingName, b, place)
                        : peasantAggression(ctx, kingName, b, place);

                case "honor" -> nobles
                        ? nobleHonor(ctx, kingName, b, place)
                        : peasantHonor(ctx, kingName, b, place);

                case "greed" -> nobles
                        ? nobleGreed(ctx, kingName, b, place)
                        : peasantGreed(ctx, kingName, b, place);

                case "pragmatism" -> nobles
                        ? noblePragmatism(ctx, kingName, b, place)
                        : peasantPragmatism(ctx, kingName, b, place);

                case "trustBias" -> nobles
                        ? nobleTrust(ctx, kingName, b, place)
                        : peasantTrust(ctx, kingName, b, place);

                default -> nobles
                        ? "King " + kingName + " plays the realm like a board."
                        : "They say King " + kingName + " cares little for folk like us.";
            };
        }

        // ---------- banding ----------
        private static Band band(double v01) {
            if (v01 >= 0.67) return Band.HIGH;
            if (v01 <= 0.33) return Band.LOW;
            return Band.MED;
        }

        // ---------- best-effort trait reader ----------
        private static double readTrait01(AmbientContext ctx, String fieldName) {
            Object ai = ctx.aiHere();
            if (ai == null) return 0.5; // neutral if unknown

            // Try common numeric fields: double/float/int
            try {
                var f = ai.getClass().getField(fieldName);
                Object v = f.get(ai);
                if (v instanceof Number n) return clamp01(n.doubleValue());
            } catch (Throwable ignored) {}

            // Also try getters (getAggression(), aggression(), etc.)
            try {
                var m = ai.getClass().getMethod("get" + cap(fieldName));
                Object v = m.invoke(ai);
                if (v instanceof Number n) return clamp01(n.doubleValue());
            } catch (Throwable ignored) {}
            try {
                var m = ai.getClass().getMethod(fieldName);
                Object v = m.invoke(ai);
                if (v instanceof Number n) return clamp01(n.doubleValue());
            } catch (Throwable ignored) {}

            return 0.5;
        }

        private static double clamp01(double x) {
            if (x < 0) return 0;
            if (x > 1) return 1;
            return x;
        }

        private static String cap(String s) {
            if (s == null || s.isEmpty()) return s;
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }

        // ---------- king name ----------
        private static String resolveKingName(AmbientContext ctx) {
            // Best option: AI kingdom name / ruler name (best effort)
            Object ai = ctx.aiHere();
            if (ai != null) {
                String n = readString(ai, "rulerName");
                if (n == null) n = readString(ai, "kingName");
                if (n == null) n = readString(ai, "name");
                if (n != null && !n.isBlank()) return n;
            }

            // Fallback: kingdom name if present
            if (ctx.hereKingdom() != null) {
                try {
                    var f = ctx.hereKingdom().getClass().getField("name");
                    Object v = f.get(ctx.hereKingdom());
                    if (v instanceof String s && !s.isBlank()) return s;
                } catch (Throwable ignored) {}
            }

            return "Unknown";
        }

        private static String readString(Object obj, String field) {
            try {
                var f = obj.getClass().getField(field);
                Object v = f.get(obj);
                return (v instanceof String s) ? s : null;
            } catch (Throwable ignored) {}
            try {
                var m = obj.getClass().getMethod("get" + cap(field));
                Object v = m.invoke(obj);
                return (v instanceof String s) ? s : null;
            } catch (Throwable ignored) {}
            return null;
        }

        // ---------- line templates ----------
       private static String peasantAggression(AmbientContext ctx, String k, Band b, String place) {
            String[] lines = switch (b) {
                case HIGH -> new String[]{
                        "King " + k + " is a dastardly, heinous ruler—always itching for war " + place + ".",
                        "King " + k + " keeps the realm sharp with blood and threats " + place + ".",
                        "King " + k + " would rather burn a border than bless a harvest " + place + ".",
                        "King " + k + " sends men marching like it’s a pastime " + place + ".",
                        "King " + k + " thinks peace is weakness—so we never get it " + place + "."
                };
                case MED -> new String[]{
                        "King " + k + " has a temper. Best not cross his banners " + place + ".",
                        "King " + k + " rattles swords when pride gets poked " + place + ".",
                        "King " + k + " can be calm—until he isn’t " + place + ".",
                        "King " + k + " likes to threaten before he bargains " + place + ".",
                        "King " + k + " keeps the peace… but keeps a hand on the hilt " + place + "."
                };
                case LOW -> new String[]{
                        "King " + k + " avoids bloodshed, they say. A rare thing " + place + ".",
                        "King " + k + " would rather sign than slaughter " + place + ".",
                        "King " + k + " doesn’t rush to war—thank the heavens " + place + ".",
                        "King " + k + " lets hotheads cool before he moves " + place + ".",
                        "King " + k + " calls war a last resort, not a hobby " + place + "."
                };
            };
            return lines[ctx.level().random.nextInt(lines.length)];
        }

        private static String nobleAggression(AmbientContext ctx, String k, Band b, String place) {
            String[] lines = switch (b) {
                case HIGH -> new String[]{
                        "King " + k + " hungers for conflict; even allies become convenient tools " + place + ".",
                        "King " + k + " treats peace like an inconvenience " + place + ".",
                        "King " + k + " prefers conquest to compromise " + place + ".",
                        "King " + k + " turns every dispute into a campaign " + place + ".",
                        "King " + k + " escalates first—then asks who’s offended " + place + "."
                };
                case MED -> new String[]{
                        "King " + k + " prefers pressure over peace—measured, but dangerous " + place + ".",
                        "King " + k + " flexes the army to win negotiations " + place + ".",
                        "King " + k + " will strike if it’s profitable, not poetic " + place + ".",
                        "King " + k + " keeps rivals uneasy, and calls it stability " + place + ".",
                        "King " + k + " tolerates peace, so long as it serves him " + place + "."
                };
                case LOW -> new String[]{
                        "King " + k + " won’t gamble the realm on war. Sensible… if dull " + place + ".",
                        "King " + k + " values order over glory " + place + ".",
                        "King " + k + " would rather build coffers than battlefields " + place + ".",
                        "King " + k + " avoids wars that cannot be cleanly won " + place + ".",
                        "King " + k + " is cautious—he doesn’t spill blood for sport " + place + "."
                };
            };
            return lines[ctx.level().random.nextInt(lines.length)];
        }

        private static String peasantHonor(AmbientContext ctx, String k, Band b, String place) {
            String[] lines = switch (b) {
                case HIGH -> new String[]{
                        "King " + k + " keeps his word, even when it costs him. Imagine that " + place + ".",
                        "King " + k + " pays debts he doesn’t have to pay " + place + ".",
                        "King " + k + " doesn’t break oaths lightly " + place + ".",
                        "King " + k + " treats promises like chains—binding, but fair " + place + ".",
                        "King " + k + " won’t cheat a man just because he can " + place + "."
                };
                case MED -> new String[]{
                        "King " + k + " talks of honor, but coins still speak loud " + place + ".",
                        "King " + k + " is decent… until it gets expensive " + place + ".",
                        "King " + k + " keeps some oaths and forgets others " + place + ".",
                        "King " + k + " does right when eyes are watching " + place + ".",
                        "King " + k + " likes to look honorable, at least " + place + "."
                };
                case LOW -> new String[]{
                        "King " + k + " swears oaths like he swats flies. Nothing sticks " + place + ".",
                        "King " + k + " lies with a smile and calls it politics " + place + ".",
                        "King " + k + " breaks promises the moment it suits him " + place + ".",
                        "King " + k + " sells his word cheap " + place + ".",
                        "King " + k + " makes vows just to trap fools " + place + "."
                };
            };
            return lines[ctx.level().random.nextInt(lines.length)];
        }

        private static String nobleHonor(AmbientContext ctx, String k, Band b, String place) {
            String[] lines = switch (b) {
                case HIGH -> new String[]{
                        "King " + k + " treats vows as law. It makes him predictable—valuable " + place + ".",
                        "King " + k + " will not stain his name for convenience " + place + ".",
                        "King " + k + " honors treaties even when they chafe " + place + ".",
                        "King " + k + " prefers reputation to profit " + place + ".",
                        "King " + k + " keeps his dignity intact—rare at court " + place + "."
                };
                case MED -> new String[]{
                        "King " + k + " is honorable when it’s convenient; political when it isn’t " + place + ".",
                        "King " + k + " bargains with honor like it’s a currency " + place + ".",
                        "King " + k + " keeps vows—unless power demands otherwise " + place + ".",
                        "King " + k + " tries to be principled, but courts erode stone " + place + ".",
                        "King " + k + " maintains appearances, which is half of honor anyway " + place + "."
                };
                case LOW -> new String[]{
                        "King " + k + " has no honor—only advantage. Courts should take note " + place + ".",
                        "King " + k + " treats oaths as tools, not truths " + place + ".",
                        "King " + k + " will betray an ally and call it necessity " + place + ".",
                        "King " + k + " mistakes cruelty for strength " + place + ".",
                        "King " + k + " makes promises to break them later " + place + "."
                };
            };
            return lines[ctx.level().random.nextInt(lines.length)];
        }

        private static String peasantGreed(AmbientContext ctx, String k, Band b, String place) {
            String[] lines = switch (b) {
                case HIGH -> new String[]{
                        "King " + k + " would tax the air if he could. We feel it " + place + ".",
                        "King " + k + " counts our bread before we eat it " + place + ".",
                        "King " + k + " squeezes the realm until it squeals " + place + ".",
                        "King " + k + " takes and takes—then asks why we’re thin " + place + ".",
                        "King " + k + " loves coin more than people " + place + "."
                };
                case MED -> new String[]{
                        "King " + k + " likes coin—don’t they all? Still, it pinches " + place + ".",
                        "King " + k + " raises dues when the court gets hungry " + place + ".",
                        "King " + k + " takes his share, and then a bit extra " + place + ".",
                        "King " + k + " keeps taxes steady… until he wants something new " + place + ".",
                        "King " + k + " isn’t the worst, but the collectors never miss " + place + "."
                };
                case LOW -> new String[]{
                        "King " + k + " doesn’t squeeze us as hard as most. Small mercies " + place + ".",
                        "King " + k + " lets villages breathe between payments " + place + ".",
                        "King " + k + " asks less than other lords would " + place + ".",
                        "King " + k + " cares more for stability than hoarding " + place + ".",
                        "King " + k + " doesn’t bleed the poor dry—rare crown, that " + place + "."
                };
            };
            return lines[ctx.level().random.nextInt(lines.length)];
        }

        private static String nobleGreed(AmbientContext ctx, String k, Band b, String place) {
            String[] lines = switch (b) {
                case HIGH -> new String[]{
                        "King " + k + " counts coin the way priests count sins—endlessly " + place + ".",
                        "King " + k + " measures loyalty in silver " + place + ".",
                        "King " + k + " turns every policy into profit " + place + ".",
                        "King " + k + " hoards wealth like a dragon with paperwork " + place + ".",
                        "King " + k + " will sell peace if the price is right " + place + "."
                };
                case MED -> new String[]{
                        "King " + k + " enjoys wealth, but understands appearances " + place + ".",
                        "King " + k + " taxes carefully—enough to fill coffers, not enough to revolt " + place + ".",
                        "King " + k + " likes gifts, but keeps the realm functioning " + place + ".",
                        "King " + k + " is acquisitive, not careless " + place + ".",
                        "King " + k + " collects coin as a habit, not a frenzy " + place + "."
                };
                case LOW -> new String[]{
                        "King " + k + " isn’t driven by gold. That makes him… harder to buy " + place + ".",
                        "King " + k + " values legitimacy more than luxury " + place + ".",
                        "King " + k + " spends on the realm instead of his table " + place + ".",
                        "King " + k + " cannot be bribed easily—useful, and annoying " + place + ".",
                        "King " + k + " doesn’t chase wealth like the rest of them " + place + "."
                };
            };
            return lines[ctx.level().random.nextInt(lines.length)];
        }

        private static String peasantPragmatism(AmbientContext ctx, String k, Band b, String place) {
            String[] lines = switch (b) {
                case HIGH -> new String[]{
                        "King " + k + " does what works, not what’s pretty. Sometimes that helps us " + place + ".",
                        "King " + k + " fixes problems fast—even if it bruises pride " + place + ".",
                        "King " + k + " cares about results, not speeches " + place + ".",
                        "King " + k + " builds first and boasts later " + place + ".",
                        "King " + k + " keeps things running. That’s more than most " + place + "."
                };
                case MED -> new String[]{
                        "King " + k + " tries to be practical, but pride still trips him " + place + ".",
                        "King " + k + " solves some things and ignores others " + place + ".",
                        "King " + k + " listens to advisors—until he doesn’t " + place + ".",
                        "King " + k + " makes sensible moves, then undoes them for show " + place + ".",
                        "King " + k + " wants order, but gets distracted by court noise " + place + "."
                };
                case LOW -> new String[]{
                        "King " + k + " lives in stories, not barns. We pay for it " + place + ".",
                        "King " + k + " loves grand plans and forgets the mud " + place + ".",
                        "King " + k + " rules by rumor and ceremony " + place + ".",
                        "King " + k + " ignores what’s broken until it collapses " + place + ".",
                        "King " + k + " thinks pride feeds people. It doesn’t " + place + "."
                };
            };
            return lines[ctx.level().random.nextInt(lines.length)];
        }

        private static String noblePragmatism(AmbientContext ctx, String k, Band b, String place) {
            String[] lines = switch (b) {
                case HIGH -> new String[]{
                        "King " + k + " is ruthlessly practical—policy first, sentiment last " + place + ".",
                        "King " + k + " treats the realm like a machine to be tuned " + place + ".",
                        "King " + k + " cuts what doesn’t work, no matter who cries " + place + ".",
                        "King " + k + " values stability over virtue-signaling " + place + ".",
                        "King " + k + " makes hard choices quickly—often correctly " + place + "."
                };
                case MED -> new String[]{
                        "King " + k + " balances principle and outcome. Adequate, if cautious " + place + ".",
                        "King " + k + " will compromise if it preserves the realm " + place + ".",
                        "King " + k + " follows advisors, but keeps a firm leash " + place + ".",
                        "King " + k + " avoids extremes—sometimes to a fault " + place + ".",
                        "King " + k + " is careful with reforms, careful with enemies " + place + "."
                };
                case LOW -> new String[]{
                        "King " + k + " rules by impulse and pageantry. Dangerous in the long run " + place + ".",
                        "King " + k + " confuses spectacle for strength " + place + ".",
                        "King " + k + " changes course with every new favorite " + place + ".",
                        "King " + k + " ignores costs until the bill comes due " + place + ".",
                        "King " + k + " treats governance like theater " + place + "."
                };
            };
            return lines[ctx.level().random.nextInt(lines.length)];
        }

        private static String peasantTrust(AmbientContext ctx, String k, Band b, String place) {
            String[] lines = switch (b) {
                case HIGH -> new String[]{
                        "King " + k + " trusts folk too easily. Bandits love that " + place + ".",
                        "King " + k + " believes promises like a child—dangerous for us " + place + ".",
                        "King " + k + " lets strangers talk sweet and walk away rich " + place + ".",
                        "King " + k + " thinks everyone means well. They don’t " + place + ".",
                        "King " + k + " gives second chances to wolves in hats " + place + "."
                };
                case MED -> new String[]{
                        "King " + k + " trusts some, suspects most. Same as any ruler " + place + ".",
                        "King " + k + " listens first, then checks the locks " + place + ".",
                        "King " + k + " gives trust in measured cups " + place + ".",
                        "King " + k + " keeps friends close and ledgers closer " + place + ".",
                        "King " + k + " is wary, but not cruel about it " + place + "."
                };
                case LOW -> new String[]{
                        "King " + k + " sees spies in every shadow. Makes everyone nervous " + place + ".",
                        "King " + k + " trusts no one—so everyone pays for it " + place + ".",
                        "King " + k + " keeps changing guards like the wind changes " + place + ".",
                        "King " + k + " hears treason in casual talk " + place + ".",
                        "King " + k + " rules with suspicion as law " + place + "."
                };
            };
            return lines[ctx.level().random.nextInt(lines.length)];
        }

        private static String nobleTrust(AmbientContext ctx, String k, Band b, String place) {
            String[] lines = switch (b) {
                case HIGH -> new String[]{
                        "King " + k + " extends trust readily—useful, if exploitable " + place + ".",
                        "King " + k + " assumes loyalty until proven otherwise " + place + ".",
                        "King " + k + " welcomes strangers to the table too quickly " + place + ".",
                        "King " + k + " prefers openness to paranoia—sometimes unwisely " + place + ".",
                        "King " + k + " believes diplomacy will tame wolves " + place + "."
                };
                case MED -> new String[]{
                        "King " + k + " is cautious. A court survives on caution " + place + ".",
                        "King " + k + " trusts with conditions, not feelings " + place + ".",
                        "King " + k + " keeps allies close, rivals closer " + place + ".",
                        "King " + k + " tests loyalty quietly, like a professional " + place + ".",
                        "King " + k + " neither gullible nor cruel—simply careful " + place + "."
                };
                case LOW -> new String[]{
                        "King " + k + " trusts no one. Paranoia becomes policy " + place + ".",
                        "King " + k + " sees plots where there are only whispers " + place + ".",
                        "King " + k + " keeps the court fearful to keep it obedient " + place + ".",
                        "King " + k + " would rather accuse than be surprised " + place + ".",
                        "King " + k + " builds walls inside walls, and calls it wisdom " + place + "."
                };
            };
            return lines[ctx.level().random.nextInt(lines.length)];
        }

    }
    
}
