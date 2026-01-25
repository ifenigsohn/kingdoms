package name.kingdoms.ambient;

import java.util.List;

public final class AmbientEvents {
    private AmbientEvents() {}

    private static ScriptedAmbientEvent.SpawnVariant V(
            ScriptedAmbientEvent.Predicate gate,
            List<ScriptedAmbientEvent.SpawnPlan> spawns,
            String id
    ) {
        return new ScriptedAmbientEvent.SpawnVariant(gate, spawns, id);
    }


    private static final List<AmbientEvent> EVENTS = List.of(

            // ---- scripted (ADD THESE INSIDE THE LIST) ----
            
          // =====================================================
// NEW EVENTS (30): 15 KCD + 15 CK3
// Each event uses dialogueTag == id (easy mental model)
// =====================================================

// -----------------
// KCD (15)
// -----------------
new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "envoy_visit_scripted",
        5,
        ctx -> ctx.playerKingdom() != null && !ctx.inWarZone() && (ctx.nearKingdomBlock(90) || ctx.nearKingSpawner(120)),
        "envoy",
        "envoy_visit_scripted",
        List.of(
                V(ctx -> true, List.of(
                        new ScriptedAmbientEvent.SpawnPlan("envoy", () -> 1, 24, 60, false, false, false, false, false),
                        new ScriptedAmbientEvent.SpawnPlan("guard", () -> 1, 24, 60, false, false, false, false, false)
                ), "default")
        ),
        List.of(),
        ctx -> {
            var ks = name.kingdoms.kingdomState.get(ctx.server());
            var pk = ks.getPlayerKingdom(ctx.player().getUUID());
            if (pk == null) return null;

            var all = ks.getAllKingdoms();
            if (all == null || all.isEmpty()) return null;

            var arr = all.toArray(new name.kingdoms.kingdomState.Kingdom[0]);
            for (int tries = 0; tries < 30; tries++) {
                var k = arr[ctx.level().random.nextInt(arr.length)];
                if (k == null || k.id == null) continue;
                if (k.id.equals(pk.id)) continue;
                return k.id;
            }
            return null;
        },
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "refugees_war_edge",
        7,
        ctx -> !ctx.inWarZone() && ctx.nearWarZone(),
        "refugee",
        "refugees_war_edge",
        List.of(
                V(ctx -> true, List.of(
                        new ScriptedAmbientEvent.SpawnPlan("refugee", () -> 2, 10, 26, false, false, false, true, false),
                        new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 2, 10, 26, false, false, false, true, false),
                        new ScriptedAmbientEvent.SpawnPlan("scholar", () -> 1, 10, 26, false, false, false, true, false)
                ), "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.NEAREST_WAR_EDGE
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "soldier_patrol_roads",
        7,
        ctx -> !ctx.inWarZone(),
        "soldier",
        "soldier_patrol_roads",
        List.of(
                V(ctx -> ctx.inKingdom(), List.of(
                        new ScriptedAmbientEvent.SpawnPlan("soldier", () -> 3, 22, 48, true, false, false, false, false),
                        new ScriptedAmbientEvent.SpawnPlan("scout", () -> 1, 22, 48, true, false, false, false, false)
                ), "in_kingdom"),
                V(ctx -> true, List.of(
                        new ScriptedAmbientEvent.SpawnPlan("soldier", () -> 2, 22, 48, false, false, false, false, false),
                        new ScriptedAmbientEvent.SpawnPlan("scout", () -> 1, 22, 48, false, false, false, false, false)
                ), "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "wilderness_traveler",
        6,
        ctx -> !ctx.inWarZone(),
        "peasant",
        "wilderness_traveler",
        List.of(
                V(ctx -> !ctx.inKingdom(), List.of(
                        new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 1, 24, 60, false, true, false, false, false),
                        new ScriptedAmbientEvent.SpawnPlan("trader",  () -> 1, 24, 60, false, true, false, false, false)
                ), "wilderness"),
                V(ctx -> true, List.of(
                        new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 1, 24, 60, false, false, false, false, false),
                        new ScriptedAmbientEvent.SpawnPlan("trader",  () -> 1, 24, 60, false, false, false, false, false)
                ), "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "border_watch",
        8,
        ctx -> ctx.inKingdom() && !ctx.inWarZone() && (ctx.nearHereBorder(42) || ctx.nearPlayerBorder(42)),
        "guard",
        "border_watch",
        List.of(
                V(ctx -> ctx.relationHere() <= -15, List.of(
                        new ScriptedAmbientEvent.SpawnPlan("guard", () -> 3, 18, 36, true, false, true, false, false)
                ), "tense"),
                V(ctx -> true, List.of(
                        new ScriptedAmbientEvent.SpawnPlan("guard", () -> 2, 18, 36, true, false, true, false, false)
                ), "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "wilderness_scouts",
        5,
        ctx -> !ctx.inKingdom() && !ctx.inWarZone(),
        "scout",
        "wilderness_scouts",
        List.of(
                V(ctx -> true, List.of(
                        new ScriptedAmbientEvent.SpawnPlan("scout", () -> 2, 30, 70, false, true, false, false, false)
                ), "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "war_rumors_peasants",
        6,
        ctx -> ctx.inKingdom() && (ctx.nearWarZone() || ctx.kingdomAtWar()),
        "peasant",
        "war_rumors_peasants",
        List.of(
                V(ctx -> ctx.nearWarZone(), List.of(
                        new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 3, 18, 40, true, false, false, false, false),
                        new ScriptedAmbientEvent.SpawnPlan("soldier", () -> 1, 18, 40, true, false, false, false, false)
                ), "near_edge"),
                V(ctx -> true, List.of(
                        new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 3, 18, 40, true, false, false, false, false)
                ), "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "grain_shortage_gossip",
        5,
        ctx -> ctx.inKingdom() && !ctx.inWarZone(),
        "peasant",
        "grain_shortage_gossip",
        List.of(
                V(ctx -> true, List.of(
                        new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 2, 18, 40, true, false, false, false, false),
                        new ScriptedAmbientEvent.SpawnPlan("trader", () -> 1, 18, 40, true, false, false, false, false)
                ), "default")
        ),
        List.of(ctx2 -> {
            if (ctx2.playerKingdom() != null && ctx2.level().random.nextFloat() < 0.10f) {
                AmbientEffects.addToPlayerKingdomResource("food", 25).apply(ctx2);
            }
        }),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "scholar_prayer",
        6,
        ctx -> ctx.inKingdom() && !ctx.inWarZone() && (ctx.nearJobBlocks(56) || ctx.nearKingdomBlock(90)),
        "scholar",
        "scholar_prayer",
        List.of(
                V(ctx -> true, List.of(
                        new ScriptedAmbientEvent.SpawnPlan("scholar", () -> 1, 18, 42, true, false, false, false, false),
                        new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 2, 18, 42, true, false, false, false, false)
                ), "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "scholar_engineering_notes",
        4,
        ctx -> !ctx.inWarZone(),
        "scholar",
        "scholar_engineering_notes",
        List.of(
                V(ctx -> true, List.of(
                        new ScriptedAmbientEvent.SpawnPlan("scholar", () -> 1, 22, 55, false, false, false, false, false)
                ), "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "bridge_inspection",
        4,
        ctx -> !ctx.inWarZone(),
        "scholar",
        "bridge_inspection",
        List.of(
                V(ctx -> ctx.inKingdom(), List.of(
                        new ScriptedAmbientEvent.SpawnPlan("scholar", () -> 1, 18, 55, true, false, false, false, false),
                        new ScriptedAmbientEvent.SpawnPlan("guard", () -> 1, 18, 55, true, false, false, false, false)
                ), "in_kingdom"),
                V(ctx -> true, List.of(
                        new ScriptedAmbientEvent.SpawnPlan("scholar", () -> 1, 18, 55, false, false, false, false, false),
                        new ScriptedAmbientEvent.SpawnPlan("guard", () -> 1, 18, 55, false, false, false, false, false)
                ), "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "noble_retinue_passes",
        4,
        ctx -> ctx.inKingdom() && !ctx.inWarZone() && ctx.nearKingSpawner(140),
        "noble",
        "noble_retinue_passes",
        List.of(
                V(ctx -> true, List.of(
                        new ScriptedAmbientEvent.SpawnPlan("noble", () -> 1, 18, 45, true, false, false, false, false),
                        new ScriptedAmbientEvent.SpawnPlan("guard", () -> 2, 18, 45, true, false, false, false, false)
                ), "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "town_crier",
        5,
        ctx -> ctx.inKingdom() && !ctx.inWarZone() && ctx.nearKingdomBlock(80),
        "peasant",
        "town_crier",
        List.of(
                V(ctx -> true, List.of(
                        new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 2, 16, 38, true, false, false, false, false),
                        new ScriptedAmbientEvent.SpawnPlan("scholar", () -> 1, 16, 38, true, false, false, false, false)
                ), "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "trader_price_rumor",
        6,
        ctx -> ctx.inKingdom() && !ctx.inWarZone(),
        "trader",
        "trader_price_rumor",
        List.of(
                V(ctx -> true, List.of(
                        new ScriptedAmbientEvent.SpawnPlan("trader", () -> 1, 18, 46, true, false, false, false, false),
                        new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 2, 18, 46, true, false, false, false, false)
                ), "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "roadside_shrine",
        3,
        ctx -> !ctx.inWarZone(),
        "scholar",
        "roadside_shrine",
        List.of(
                V(ctx -> true, List.of(
                        new ScriptedAmbientEvent.SpawnPlan("scholar", () -> 1, 26, 70, false, false, false, false, false)
                ), "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "wounded_soldier",
        4,
        ctx -> ctx.nearWarZone() && !ctx.inWarZone(),
        "soldier",
        "wounded_soldier",
        List.of(
                V(ctx -> true, List.of(
                        new ScriptedAmbientEvent.SpawnPlan("soldier", () -> 1, 24, 55, false, false, false, true, false),
                        new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 1, 24, 55, false, false, false, true, false)
                ), "default")
        ),
        List.of(ctx2 -> {
            if (ctx2.level().random.nextFloat() < 0.10f) {
                AmbientEffects.addToPlayerKingdomResource("medicine", 1).apply(ctx2);
            }
        }),
        null,
        ScriptedAmbientEvent.SpawnAnchor.NEAREST_WAR_EDGE
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "deserter_whispers",
        3,
        ctx -> ctx.nearWarZone() && !ctx.inWarZone(),
        "peasant",
        "deserter_whispers",
        List.of(
                V(ctx -> true, List.of(
                        new ScriptedAmbientEvent.SpawnPlan("soldier", () -> 1, 26, 70, false, false, false, true, false)
                ), "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "scouts_report_border",
        5,
        ctx -> ctx.inKingdom() && !ctx.inWarZone(),
        "scout",
        "scouts_report_border",
        List.of(
                V(ctx -> true, List.of(
                        new ScriptedAmbientEvent.SpawnPlan("scout", () -> 2, 18, 42, true, false, true, false, false)
                ), "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "pilgrims_on_road",
        4,
        ctx -> !ctx.inWarZone(),
        "scholar",
        "pilgrims_on_road",
        List.of(
                V(ctx -> !ctx.inKingdom(), List.of(
                        new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 3, 30, 70, false, true, false, false, false),
                        new ScriptedAmbientEvent.SpawnPlan("scholar", () -> 1, 30, 70, false, true, false, false, false)
                ), "wilderness"),
                V(ctx -> true, List.of(
                        new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 3, 30, 70, false, false, false, false, false),
                        new ScriptedAmbientEvent.SpawnPlan("scholar", () -> 1, 30, 70, false, false, false, false, false)
                ), "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "market_argument",
        5,
        ctx -> ctx.inKingdom() && !ctx.inWarZone(),
        "peasant",
        "market_argument",
        List.of(
                V(ctx -> true, List.of(
                        new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 3, 16, 36, true, false, false, false, false),
                        new ScriptedAmbientEvent.SpawnPlan("trader", () -> 1, 16, 36, true, false, false, false, false)
                ), "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "guard_checks_papers",
        4,
        ctx -> ctx.inKingdom() && !ctx.inWarZone(),
        "guard",
        "guard_checks_papers",
        List.of(
                V(ctx -> ctx.relationHere() <= -10, List.of(
                        new ScriptedAmbientEvent.SpawnPlan("guard", () -> 2, 16, 40, true, false, false, false, false),
                        new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 1, 16, 40, true, false, false, false, false)
                ), "tense"),
                V(ctx -> true, List.of(
                        new ScriptedAmbientEvent.SpawnPlan("guard", () -> 2, 16, 40, true, false, false, false, false),
                        new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 1, 16, 40, true, false, false, false, false)
                ), "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "scholar_teaches_children",
        4,
        ctx -> ctx.inKingdom() && !ctx.inWarZone(),
        "scholar",
        "scholar_teaches_children",
        List.of(
                V(ctx -> true, List.of(
                        new ScriptedAmbientEvent.SpawnPlan("scholar", () -> 1, 16, 38, true, false, false, false, false),
                        new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 2, 16, 38, true, false, false, false, false)
                ), "default")
        ),
        List.of(ctx2 -> {
            if (ctx2.level().random.nextFloat() < 0.06f) {
                AmbientEffects.addToPlayerKingdomResource("research", 1).apply(ctx2);
            }
        }),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "funeral_procession",
        3,
        ctx -> ctx.inKingdom() && (ctx.kingdomAtWar() || ctx.nearWarZone()) && !ctx.inWarZone(),
        "scholar",
        "funeral_procession",
        List.of(
                V(ctx -> ctx.nearWarZone(), List.of(
                        new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 3, 16, 42, true, false, false, true, false),
                        new ScriptedAmbientEvent.SpawnPlan("scholar", () -> 1, 16, 42, true, false, false, true, false)
                ), "near_edge"),
                V(ctx -> true, List.of(
                        new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 3, 16, 42, true, false, false, false, false),
                        new ScriptedAmbientEvent.SpawnPlan("scholar", () -> 1, 16, 42, true, false, false, false, false)
                ), "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "pressgang_on_road",
        5,
        ctx -> ctx.inKingdom() && !ctx.inWarZone() && ctx.kingdomAtWar(),
        "guard",
        "pressgang_on_road",
        List.of(
                V(ctx -> ctx.relationHere() <= -10,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("guard", () -> 2, 16, 40, true, false, false, false, false),
                                new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 1, 16, 40, true, false, false, false, false)
                        ),
                        "hostile"),
                V(ctx -> true,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("guard", () -> 2, 16, 40, true, false, false, false, false),
                                new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 1, 16, 40, true, false, false, false, true)
                        ),
                        "default")
        ),
        List.of(
                ctx -> { if (ctx.level().random.nextFloat() < 0.08f) AmbientEffects.changeRelationWithHereKingdom(-1).apply(ctx); }
        ),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "hungry_night_camp",
        4,
        ctx -> !ctx.inKingdom() && !ctx.inWarZone() && !ctx.nearWarZone(),
        "peasant",
        "hungry_night_camp",
        List.of(
                V(ctx -> true,
                        List.of(new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 2, 22, 55, false, true, false, false, false)),
                        "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "grave_by_the_road",
        4,
        ctx -> !ctx.inWarZone() && ctx.nearWarZone(),
        "soldier",
        "grave_by_the_road",
        List.of(
                V(ctx -> true,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("soldier", () -> 1, 16, 36, false, false, false, true, false),
                                new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 1, 16, 36, false, false, false, true, false)
                        ),
                        "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.NEAREST_WAR_EDGE
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "field_argument",
        5,
        ctx -> ctx.inKingdom() && !ctx.inWarZone() && ctx.nearJobBlocks(72) && !ctx.nearWarZone(),
        "peasant",
        "field_argument",
        List.of(
                V(ctx -> true,
                        List.of(new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 2, 14, 34, true, false, false, false, true)),
                        "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "roadside_blessing",
        3,
        ctx -> !ctx.inWarZone() && !ctx.nearWarZone() && !ctx.inKingdom(),
        "scholar",
        "roadside_blessing",
        List.of(
                V(ctx -> true,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("scholar", () -> 1, 22, 60, false, true, false, false, false),
                                new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 1, 22, 60, false, true, false, false, false)
                        ),
                        "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "tax_collector_refused",
        4,
        ctx -> ctx.inKingdom() && !ctx.inWarZone() && !ctx.nearWarZone(),
        "scholar",
        "tax_collector_refused",
        List.of(
                V(ctx -> true,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("scholar", () -> 1, 16, 38, true, false, false, false, true),
                                new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 2, 16, 38, true, false, false, false, true)
                        ),
                        "default")
        ),
        List.of(
                ctx -> { if (ctx.playerKingdom() != null && ctx.level().random.nextFloat() < 0.05f) AmbientEffects.addToPlayerKingdomResource("gold", 5).apply(ctx); }
        ),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "drunk_soldier",
        3,
        ctx -> ctx.inKingdom() && !ctx.inWarZone() && (ctx.nearWarZone() || ctx.kingdomAtWar()),
        "soldier",
        "drunk_soldier",
        List.of(
                V(ctx -> true,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("soldier", () -> 1, 18, 46, true, false, false, false, true),
                                new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 1, 18, 46, true, false, false, false, true)
                        ),
                        "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "children_play_war",
        4,
        ctx -> ctx.inKingdom() && !ctx.inWarZone() && ctx.kingdomAtWar(),
        "peasant",
        "children_play_war",
        List.of(
                V(ctx -> true,
                        List.of(new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 3, 14, 30, true, false, false, false, true)),
                        "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "poacher_whispers",
        3,
        ctx -> !ctx.inKingdom() && !ctx.inWarZone(),
        "peasant",
        "poacher_whispers",
        List.of(
                V(ctx -> true,
                        List.of(new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 1, 24, 70, false, true, false, false, false)),
                        "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "bandit_victims",
        4,
        ctx -> !ctx.inWarZone() && !ctx.inKingdom(),
        "peasant",
        "bandit_victims",
        List.of(
                V(ctx -> ctx.nearWarZone(),
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 2, 20, 55, false, true, false, true, false),
                                new ScriptedAmbientEvent.SpawnPlan("soldier", () -> 1, 20, 55, false, true, false, true, false)
                        ),
                        "war_edge"),
                V(ctx -> true,
                        List.of(new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 2, 20, 55, false, true, false, false, false)),
                        "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "mill_breakdown",
        4,
        ctx -> ctx.inKingdom() && !ctx.inWarZone() && ctx.nearJobBlocks(72),
        "scholar",
        "mill_breakdown",
        List.of(
                V(ctx -> true,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("scholar", () -> 1, 14, 34, true, false, false, false, true),
                                new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 2, 14, 34, true, false, false, false, true)
                        ),
                        "default")
        ),
        List.of(
                ctx -> { if (ctx.playerKingdom() != null && ctx.level().random.nextFloat() < 0.06f) AmbientEffects.addToPlayerKingdomResource("wood", 8).apply(ctx); }
        ),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "plague_cart",
        2,
        ctx -> ctx.inKingdom() && !ctx.inWarZone(),
        "scholar",
        "plague_cart",
        List.of(
                V(ctx -> ctx.nearWarZone(),
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("scholar", () -> 1, 18, 44, true, false, false, false, true),
                                new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 2, 18, 44, true, false, false, false, true)
                        ),
                        "war_adjacent"),
                V(ctx -> true,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("scholar", () -> 1, 18, 44, true, false, false, false, true),
                                new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 1, 18, 44, true, false, false, false, true)
                        ),
                        "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "tavern_brawl",
        3,
        ctx -> ctx.inKingdom() && !ctx.inWarZone() && ctx.nearKingdomBlock(96),
        "peasant",
        "tavern_brawl",
        List.of(
                V(ctx -> ctx.relationHere() <= -15,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 3, 14, 30, true, false, false, false, true),
                                new ScriptedAmbientEvent.SpawnPlan("guard", () -> 1, 14, 30, true, false, false, false, true)
                        ),
                        "hostile"),
                V(ctx -> true,
                        List.of(new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 3, 14, 30, true, false, false, false, true)),
                        "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "blacksmith_shortage",
        4,
        ctx -> ctx.inKingdom() && !ctx.inWarZone() && ctx.nearJobBlocks(72) && (ctx.nearWarZone() || ctx.kingdomAtWar()),
        "peasant",
        "blacksmith_shortage",
        List.of(
                V(ctx -> true,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 2, 14, 34, true, false, false, false, true),
                                new ScriptedAmbientEvent.SpawnPlan("soldier", () -> 1, 14, 34, true, false, false, false, true)
                        ),
                        "default")
        ),
        List.of(
                ctx -> { if (ctx.playerKingdom() != null && ctx.level().random.nextFloat() < 0.05f) AmbientEffects.addToPlayerKingdomResource("metal", 6).apply(ctx); }
        ),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "lost_livestock",
        4,
        ctx -> ctx.inKingdom() && !ctx.inWarZone() && !ctx.nearWarZone(),
        "peasant",
        "lost_livestock",
        List.of(
                V(ctx -> true,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 2, 18, 46, true, false, false, false, true),
                                new ScriptedAmbientEvent.SpawnPlan("scout", () -> 1, 18, 46, true, false, false, false, true)
                        ),
                        "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

// -----------------
// CK3 (15)
// -----------------

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "envoy_complains_delay",
        4,
        ctx -> ctx.playerKingdom() != null && ctx.inKingdom() && !ctx.inWarZone(),
        "envoy",
        "envoy_complains_delay",
        List.of(
                V(ctx -> ctx.alliedHere(),
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("envoy", () -> 1, 18, 44, true, false, false, false, false),
                                new ScriptedAmbientEvent.SpawnPlan("guard", () -> 1, 18, 44, true, false, false, false, false)
                        ),
                        "allied"),
                V(ctx -> true,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("envoy", () -> 1, 18, 44, true, false, false, false, false)
                        ),
                        "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "noble_disputes_border",
        4,
        ctx -> !ctx.inWarZone() && (ctx.nearHereBorder(48) || ctx.nearPlayerBorder(48)),
        "noble",
        "noble_disputes_border",
        List.of(
                V(ctx -> ctx.relationHere() <= -20,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("noble", () -> 1, 18, 44, false, false, true, false, false),
                                new ScriptedAmbientEvent.SpawnPlan("guard", () -> 2, 18, 44, false, false, true, false, false)
                        ),
                        "hostile"),
                V(ctx -> true,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("noble", () -> 1, 18, 44, false, false, true, false, false),
                                new ScriptedAmbientEvent.SpawnPlan("guard", () -> 1, 18, 44, false, false, true, false, false)
                        ),
                        "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "court_gossip",
        4,
        ctx -> ctx.inKingdom() && !ctx.inWarZone() && ctx.nearKingSpawner(140),
        "scholar",
        "court_gossip",
        List.of(
                V(ctx -> true,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 2, 14, 32, true, false, false, false, true),
                                new ScriptedAmbientEvent.SpawnPlan("scholar", () -> 1, 14, 32, true, false, false, false, true)
                        ),
                        "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "merchant_bribes_guard",
        4,
        ctx -> ctx.inKingdom() && !ctx.inWarZone(),
        "trader",
        "merchant_bribes_guard",
        List.of(
                V(ctx -> ctx.relationHere() <= -15,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("trader", () -> 1, 14, 34, true, false, false, false, true),
                                new ScriptedAmbientEvent.SpawnPlan("guard", () -> 2, 14, 34, true, false, false, false, true)
                        ),
                        "hostile"),
                V(ctx -> true,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("trader", () -> 1, 14, 34, true, false, false, false, true),
                                new ScriptedAmbientEvent.SpawnPlan("guard", () -> 1, 14, 34, true, false, false, false, true)
                        ),
                        "default")
        ),
        List.of(
                ctx -> { if (ctx.level().random.nextFloat() < 0.06f) AmbientEffects.changeRelationWithHereKingdom(-1).apply(ctx); }
        ),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "treaty_rumors",
        4,
        ctx -> ctx.inKingdom() && !ctx.inWarZone() && (ctx.nearWarZone() || ctx.kingdomAtWar()),
        "scholar",
        "treaty_rumors",
        List.of(
                V(ctx -> true,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("scholar", () -> 1, 16, 40, true, false, false, false, true),
                                new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 2, 16, 40, true, false, false, false, true)
                        ),
                        "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "foreign_soldiers_passing",
        3,
        ctx -> ctx.inKingdom() && !ctx.inWarZone() && ctx.alliedHere(),
        "soldier",
        "foreign_soldiers_passing",
        List.of(
                V(ctx -> true,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("soldier", () -> 2, 18, 52, true, false, false, false, false),
                                new ScriptedAmbientEvent.SpawnPlan("guard", () -> 1, 18, 52, true, false, false, false, false)
                        ),
                        "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "steward_counts_grain",
        4,
        ctx -> ctx.inKingdom() && !ctx.inWarZone() && ctx.nearJobBlocks(72),
        "scholar",
        "steward_counts_grain",
        List.of(
                V(ctx -> true,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("scholar", () -> 1, 14, 34, true, false, false, false, true),
                                new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 1, 14, 34, true, false, false, false, true)
                        ),
                        "default")
        ),
        List.of(
                ctx -> { if (ctx.playerKingdom() != null && ctx.level().random.nextFloat() < 0.07f) AmbientEffects.addToPlayerKingdomResource("grain", 10).apply(ctx); }
        ),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "threatening_messenger",
        3,
        ctx -> ctx.playerKingdom() != null && ctx.inKingdom() && !ctx.inWarZone() && ctx.relationHere() <= -15,
        "envoy",
        "threatening_messenger",
        List.of(
                V(ctx -> true,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("envoy", () -> 1, 18, 44, true, false, false, false, false),
                                new ScriptedAmbientEvent.SpawnPlan("guard", () -> 1, 18, 44, true, false, false, false, false)
                        ),
                        "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "tribute_caravan",
        3,
        ctx -> ctx.inKingdom() && !ctx.inWarZone() && !ctx.nearWarZone(),
        "trader",
        "tribute_caravan",
        List.of(
                V(ctx -> ctx.alliedHere(),
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("trader", () -> 1, 18, 46, true, false, false, false, false),
                                new ScriptedAmbientEvent.SpawnPlan("guard", () -> 2, 18, 46, true, false, false, false, false)
                        ),
                        "allied"),
                V(ctx -> true,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("trader", () -> 1, 18, 46, true, false, false, false, false),
                                new ScriptedAmbientEvent.SpawnPlan("guard", () -> 1, 18, 46, true, false, false, false, false)
                        ),
                        "default")
        ),
        List.of(
                ctx -> { if (ctx.playerKingdom() != null && ctx.level().random.nextFloat() < 0.06f) AmbientEffects.addToPlayerKingdomResource("gold", 10).apply(ctx); }
        ),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "spy_caught",
        3,
        ctx -> ctx.inKingdom() && !ctx.inWarZone() && (ctx.relationHere() <= -10 || ctx.nearHereBorder(42)),
        "guard",
        "spy_caught",
        List.of(
                V(ctx -> ctx.relationHere() <= -15,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("guard", () -> 2, 16, 38, true, false, false, false, true),
                                new ScriptedAmbientEvent.SpawnPlan("envoy", () -> 1, 16, 38, true, false, false, false, true)
                        ),
                        "hostile"),
                V(ctx -> true,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("guard", () -> 2, 16, 38, true, false, false, false, true),
                                new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 1, 16, 38, true, false, false, false, true)
                        ),
                        "default")
        ),
        List.of(
                ctx -> { if (ctx.level().random.nextFloat() < 0.05f) AmbientEffects.changeRelationWithHereKingdom(-1).apply(ctx); }
        ),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "succession_rumor",
        3,
        ctx -> ctx.inKingdom() && !ctx.inWarZone() && ctx.nearKingSpawner(140),
        "noble",
        "succession_rumor",
        List.of(
                V(ctx -> true,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("noble", () -> 1, 18, 44, true, false, false, false, false),
                                new ScriptedAmbientEvent.SpawnPlan("scholar", () -> 1, 18, 44, true, false, false, false, false)
                        ),
                        "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "marriage_whispers",
        3,
        ctx -> ctx.inKingdom() && !ctx.inWarZone() && ctx.nearKingSpawner(140),
        "scholar",
        "marriage_whispers",
        List.of(
                V(ctx -> ctx.alliedHere(),
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("scholar", () -> 1, 14, 34, true, false, false, false, false),
                                new ScriptedAmbientEvent.SpawnPlan("envoy", () -> 1, 14, 34, true, false, false, false, false)
                        ),
                        "allied"),
                V(ctx -> true,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("scholar", () -> 1, 14, 34, true, false, false, false, false),
                                new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 2, 14, 34, true, false, false, false, true)
                        ),
                        "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "trade_embargo_rumor",
        3,
        ctx -> ctx.inKingdom() && !ctx.inWarZone() && (ctx.relationHere() <= -10 || ctx.nearHereBorder(42)),
        "trader",
        "trade_embargo_rumor",
        List.of(
                V(ctx -> true,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("trader", () -> 1, 16, 40, true, false, false, false, true),
                                new ScriptedAmbientEvent.SpawnPlan("peasant", () -> 2, 16, 40, true, false, false, false, true)
                        ),
                        "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
)),

new ScriptedAmbientEvent(new ScriptedAmbientEvent.Def(
        "papal_legate",
        2,
        ctx -> ctx.inKingdom() && !ctx.inWarZone() && !ctx.nearWarZone() && ctx.nearKingSpawner(140),
        "envoy",
        "papal_legate",
        List.of(
                V(ctx -> true,
                        List.of(
                                new ScriptedAmbientEvent.SpawnPlan("envoy", () -> 1, 18, 44, true, false, false, false, false),
                                new ScriptedAmbientEvent.SpawnPlan("scholar", () -> 1, 18, 44, true, false, false, false, false),
                                new ScriptedAmbientEvent.SpawnPlan("guard", () -> 1, 18, 44, true, false, false, false, false)
                        ),
                        "default")
        ),
        List.of(),
        null,
        ScriptedAmbientEvent.SpawnAnchor.PLAYER_RING
))

    );

    public static AmbientEvent pick(AmbientContext ctx, String lastId) {
            int total = 0;
            int[] w = new int[EVENTS.size()];

            for (int i = 0; i < EVENTS.size(); i++) {
                AmbientEvent e = EVENTS.get(i);
                int wi = Math.max(0, e.weight(ctx));

                if (lastId != null && lastId.equals(e.id())) wi = (int) Math.floor(wi * 0.35);

                w[i] = wi;
                total += wi;
            }

            if (total <= 0) return null;

            int roll = ctx.level().random.nextInt(total);
            for (int i = 0; i < EVENTS.size(); i++) {
                roll -= w[i];
                if (roll < 0) return EVENTS.get(i);
            }
            return EVENTS.get(0);
        }

        public static AmbientEvent pick(AmbientContext ctx) {
            return pick(ctx, null);
        }

        private static java.util.UUID getKingdomId(name.kingdoms.kingdomState.Kingdom k) {
        // Try common possibilities without forcing you to rename stuff.
        try {
            // Most likely (based on your other code): field is "id"
            var f = k.getClass().getField("id");
            Object v = f.get(k);
            return (v instanceof java.util.UUID u) ? u : null;
        } catch (Throwable ignored) {}

        try {
            // Sometimes it's "kingdomId"
            var f = k.getClass().getField("kingdomId");
            Object v = f.get(k);
            return (v instanceof java.util.UUID u) ? u : null;
        } catch (Throwable ignored) {}

        try {
            // Or a getter
            var m = k.getClass().getMethod("getId");
            Object v = m.invoke(k);
            return (v instanceof java.util.UUID u) ? u : null;
        } catch (Throwable ignored) {}

        try {
            var m = k.getClass().getMethod("id");
            Object v = m.invoke(k);
            return (v instanceof java.util.UUID u) ? u : null;
        } catch (Throwable ignored) {}

        return null;
    }

}
