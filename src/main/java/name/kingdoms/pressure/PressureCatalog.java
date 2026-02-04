package name.kingdoms.pressure;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

public final class PressureCatalog {
    private PressureCatalog() {}

    // ---- durations (tune later) ----
    public static final long MINUTE = 20L * 60L;
    public static final long HOUR   = 20L * 60L * 60L;

    /* -----------------------------
       Registry (lookup by typeId)
     ----------------------------- */
    private static final Map<String, Template> BY_TYPE = new HashMap<>();

    private static Template reg(Template t) {
        if (t == null || t.typeId() == null) return t;
        BY_TYPE.put(t.typeId(), t);
        return t;
    }

    public static Template byTypeId(String typeId) {
        if (typeId == null) return null;
        return BY_TYPE.get(typeId);
    }

    /* -----------------------------
       Template
     ----------------------------- */

    public record Template(
            String typeId,
            long durationTicks,
            EnumMap<KingdomPressureState.Stat, Double> effects,
            Map<String, String> barkPools // group -> poolId
    ) {
        public Template(String typeId, long durationTicks, EnumMap<KingdomPressureState.Stat, Double> effects) {
            this(typeId, durationTicks, effects, Map.of());
        }

        /** Return poolId for a worker group ("worker","military","tavern","chapel","shop","nobility") */
        public String barkPoolForGroup(String group) {
            if (group == null || barkPools == null) return null;
            return barkPools.get(group);
        }

        /** Add/override bark pool mapping for a group. */
        public Template withBark(String group, String poolId) {
            if (group == null || group.isBlank() || poolId == null || poolId.isBlank()) return this;

            var m = new java.util.HashMap<>(this.barkPools == null ? java.util.Map.of() : this.barkPools);
            m.put(group, poolId);
            return new Template(this.typeId, this.durationTicks, this.effects, java.util.Collections.unmodifiableMap(m));
        }
    }

    /* -----------------------------
       Templates (registered once)
     ----------------------------- */

    /** Worker: push output -> econ up, happiness down */
    private static final Template PUSH_PRODUCTION_T =
            reg(new Template(
                    "push_production",
                    10 * MINUTE,
                    effects()
                            .putPct(KingdomPressureState.Stat.ECONOMY, +0.12) // +12% output
                            .putAdd(KingdomPressureState.Stat.HAPPINESS, -0.6) // -0.6 on 0..10
                            .build()
            ).withBark("worker", "pace_hard_worker"));

    /** Worker: ease workload -> happiness up, econ down */
    private static final Template EASE_WORKLOAD_T =
            reg(new Template(
                    "ease_workload",
                    10 * MINUTE,
                    effects()
                            .putPct(KingdomPressureState.Stat.ECONOMY, -0.10)
                            .putAdd(KingdomPressureState.Stat.HAPPINESS, +0.8)
                            .build()
            ).withBark("worker", "pace_easy_worker"));

    /** Guards: increase patrols -> security up, econ down */
    private static final Template INCREASE_PATROLS_T =
            reg(new Template(
                    "increase_patrols",
                    12 * MINUTE,
                    effects()
                            .putAdd(KingdomPressureState.Stat.SECURITY, +0.06) // +0.06 on 0..1
                            .putPct(KingdomPressureState.Stat.ECONOMY, -0.08)
                            .build()
            ).withBark("military", "patrols_up_military"));

    /** External: sow discontent -> happiness down, relations down (temporary bias) */
    private static final Template SOW_DISCONTENT_T =
            reg(new Template(
                    "sow_discontent",
                    15 * MINUTE,
                    effects()
                            .putAdd(KingdomPressureState.Stat.HAPPINESS, -0.9)
                            .putAdd(KingdomPressureState.Stat.RELATIONS, -8) // -8 temporary eval bias
                            .build()
            )); // optional bark later (e.g. "worker", "nobility")

    // -----------------------------
    // POLICY: Pace (mutually exclusive)
    // -----------------------------
    private static final Template DOUBLE_PACE_T =
            reg(new Template(
                    "double_pace",
                    10 * MINUTE,
                    effects()
                            .putPct(KingdomPressureState.Stat.ECONOMY, +0.12)
                            .putAdd(KingdomPressureState.Stat.HAPPINESS, -0.6)
                            .build()
            ).withBark("worker", "pace_hard_worker"));

    private static final Template LEISURELY_PACE_T =
            reg(new Template(
                    "leisurely_pace",
                    10 * MINUTE,
                    effects()
                            .putPct(KingdomPressureState.Stat.ECONOMY, -0.10)
                            .putAdd(KingdomPressureState.Stat.HAPPINESS, +0.8)
                            .build()
            ).withBark("worker", "pace_easy_worker"));

    // -----------------------------
    // POLICY: Patrols (mutually exclusive)
    // -----------------------------
    private static final Template INCREASE_PATROLS_POLICY_T =
            reg(new Template(
                    "increase_patrols",
                    12 * MINUTE,
                    effects()
                            .putAdd(KingdomPressureState.Stat.SECURITY, +0.06)
                            .putPct(KingdomPressureState.Stat.ECONOMY, -0.08)
                            .build()
            ).withBark("military", "patrols_up_military"));

    private static final Template DECREASE_PATROLS_POLICY_T =
            reg(new Template(
                    "decrease_patrols",
                    12 * MINUTE,
                    effects()
                            .putAdd(KingdomPressureState.Stat.SECURITY, -0.06)
                            .putPct(KingdomPressureState.Stat.ECONOMY, +0.05) // “less patrol cost” proxy
                            .build()
            ).withBark("military", "patrols_down_military"));

    // -----------------------------
    // POLICY: Rations (mutually exclusive)
    // -----------------------------
    private static final Template DOUBLE_RATIONS_T =
            reg(new Template(
                    "double_rations",
                    12 * MINUTE,
                    effects()
                            .putAdd(KingdomPressureState.Stat.SECURITY, +0.03) // proxy: readiness
                            .putPct(KingdomPressureState.Stat.ECONOMY, -0.06)  // proxy: extra supply cost
                            .build()
            ).withBark("military", "rations_up_military"));

    private static final Template HALVE_RATIONS_T =
            reg(new Template(
                    "halve_rations",
                    12 * MINUTE,
                    effects()
                            .putAdd(KingdomPressureState.Stat.SECURITY, -0.03)
                            .putPct(KingdomPressureState.Stat.ECONOMY, +0.04)
                            .build()
            ).withBark("military", "rations_down_military"));

    // -----------------------------
    // POLICY: Tavern (mutually exclusive)
    // -----------------------------
    private static final Template ALCOHOL_SUBSIDIES_T =
            reg(new Template(
                    "alcohol_subsidies",
                    12 * MINUTE,
                    effects()
                            .putAdd(KingdomPressureState.Stat.HAPPINESS, +0.9)
                            .putPct(KingdomPressureState.Stat.ECONOMY, -0.08)
                            .build()
            ).withBark("tavern", "booze_subsidy_tavern"));

    private static final Template DRUNK_CRACKDOWNS_T =
            reg(new Template(
                    "drunk_crackdowns",
                    12 * MINUTE,
                    effects()
                            .putAdd(KingdomPressureState.Stat.HAPPINESS, -0.7)
                            .putPct(KingdomPressureState.Stat.ECONOMY, +0.06)
                            .build()
            ).withBark("tavern", "booze_crackdown_tavern"));

    // -----------------------------
    // POLICY: Chapel (mutually exclusive)
    // -----------------------------
    private static final Template FREQUENT_SERVICES_T =
            reg(new Template(
                    "frequent_services",
                    12 * MINUTE,
                    effects()
                            .putAdd(KingdomPressureState.Stat.HAPPINESS, +0.8)
                            .putPct(KingdomPressureState.Stat.ECONOMY, -0.08)
                            .build()
            ).withBark("chapel", "services_chapel"));

    private static final Template PAPAL_AUTHORITY_T =
            reg(new Template(
                    "papal_authority",
                    12 * MINUTE,
                    effects()
                            .putAdd(KingdomPressureState.Stat.RELATIONS, +10)
                            .putPct(KingdomPressureState.Stat.ECONOMY, -0.05) // proxy for gold cost
                            .build()
            ).withBark("chapel", "services_chapel")); // reuse for now, or make a special pool later

    // -----------------------------
    // POLICY: Nobility (mutually exclusive)
    // -----------------------------
    private static final Template DIPLOMATIC_ENVOYS_T =
            reg(new Template(
                    "diplomatic_envoys",
                    12 * MINUTE,
                    effects()
                            .putAdd(KingdomPressureState.Stat.RELATIONS, +10)
                            .putPct(KingdomPressureState.Stat.ECONOMY, -0.05)
                            .build()
            ).withBark("nobility", "envoys_nobility"));

    private static final Template VASSAL_CONTRIBUTIONS_T =
            reg(new Template(
                    "vassal_contributions",
                    12 * MINUTE,
                    effects()
                            .putPct(KingdomPressureState.Stat.ECONOMY, +0.10)
                            .putAdd(KingdomPressureState.Stat.HAPPINESS, -0.8)
                            .build()
            ).withBark("nobility", "vassal_levy_nobility"));

    // -----------------------------
    // POLICY: Shop (mutually exclusive)
    // -----------------------------
    private static final Template MARKET_SUBSIDIES_T =
            reg(new Template(
                    "market_subsidies",
                    12 * MINUTE,
                    effects()
                            .putAdd(KingdomPressureState.Stat.RELATIONS, +8)
                            .putPct(KingdomPressureState.Stat.ECONOMY, -0.08)
                            .build()
            ).withBark("shop", "market_subsidy_shop"));

    private static final Template CONTRABAND_CRACKDOWNS_T =
            reg(new Template(
                    "contraband_crackdowns",
                    12 * MINUTE,
                    effects()
                            .putAdd(KingdomPressureState.Stat.RELATIONS, -8)
                            .putAdd(KingdomPressureState.Stat.SECURITY, +0.04)
                            .build()
            ).withBark("shop", "contraband_crackdown_shop"));

    // ---- GLOBAL EVENTS ----

    private static final Template PLAGUE_T =
            reg(new Template(
                    "global_plague",
                    20 * MINUTE, // 20 min
                    effects()
                            .putPct(KingdomPressureState.Stat.ECONOMY, -0.18)
                            .putAdd(KingdomPressureState.Stat.HAPPINESS, -1.0)
                            .putAdd(KingdomPressureState.Stat.SECURITY, -0.03)
                            .build()
            ).withBark("worker", "global_plague_worker")
            .withBark("shop", "global_plague_shop"));

    private static final Template BOUNTIFUL_HARVEST_T =
            reg(new Template(
                    "global_bountiful_harvest",
                    15 * MINUTE,
                    effects()
                            .putPct(KingdomPressureState.Stat.ECONOMY, +0.12)
                            .putAdd(KingdomPressureState.Stat.HAPPINESS, +0.7)
                            .build()
            ).withBark("worker", "global_harvest_worker"));

    private static final Template BANDIT_WAVE_T =
            reg(new Template(
                    "global_bandit_wave",
                    15 * MINUTE,
                    effects()
                            .putAdd(KingdomPressureState.Stat.SECURITY, -0.07)
                            .putAdd(KingdomPressureState.Stat.HAPPINESS, -0.5)
                            .build()
            ).withBark("military", "global_bandits_military")
            .withBark("shop", "global_bandits_shop"));

    private static final Template FESTIVAL_T =
            reg(new Template(
                    "global_festival",
                    10 * MINUTE,
                    effects()
                            .putAdd(KingdomPressureState.Stat.HAPPINESS, +1.1)
                            .putPct(KingdomPressureState.Stat.ECONOMY, -0.05)
                            .build()
            ).withBark("tavern", "global_festival_tavern")
            .withBark("worker", "global_festival_worker"));

    private static final Template DROUGHT_T =
            reg(new Template(
                    "global_drought",
                    20 * MINUTE,
                    effects()
                            .putPct(KingdomPressureState.Stat.ECONOMY, -0.14)
                            .putAdd(KingdomPressureState.Stat.HAPPINESS, -0.6)
                            .build()
            ).withBark("worker", "global_drought_worker"));
        
    private static final Template AI_AID_SUPPLIES_T =
        reg(new Template("ai_aid_supplies", 10 * MINUTE,
            effects()
                .putPct(KingdomPressureState.Stat.ECONOMY, +0.10)
                .putAdd(KingdomPressureState.Stat.HAPPINESS, +0.6)
                .putAdd(KingdomPressureState.Stat.SECURITY, +0.02)
                .build()
        ).withBark("worker", "ai_aid_worker")
        .withBark("military", "ai_aid_military"));

    private static final Template AI_ENVOY_PRAISE_T =
        reg(new Template("ai_envoy_praise", 12 * MINUTE,
            effects()
                .putAdd(KingdomPressureState.Stat.RELATIONS, +10)
                .putAdd(KingdomPressureState.Stat.HAPPINESS, +0.3)
                .build()
        ).withBark("nobility", "ai_praise_nobility"));

    private static final Template AI_TRADE_EMBARGO_T =
        reg(new Template("ai_trade_embargo", 12 * MINUTE,
            effects()
                .putPct(KingdomPressureState.Stat.ECONOMY, -0.12)
                .putAdd(KingdomPressureState.Stat.RELATIONS, -6)
                .build()
        ).withBark("shop", "ai_embargo_shop"));

    private static final Template AI_FUND_BANDITS_T =
        reg(new Template("ai_fund_bandits", 12 * MINUTE,
            effects()
                .putAdd(KingdomPressureState.Stat.SECURITY, -0.06)
                .putAdd(KingdomPressureState.Stat.HAPPINESS, -0.4)
                .putAdd(KingdomPressureState.Stat.RELATIONS, -6)
                .build()
        ).withBark("military", "ai_bandits_military")
        .withBark("shop", "ai_bandits_shop"));

    private static final Template AI_BORDER_RAIDS_T =
        reg(new Template("ai_border_raids", 10 * MINUTE,
            effects()
                .putPct(KingdomPressureState.Stat.ECONOMY, -0.10)
                .putAdd(KingdomPressureState.Stat.SECURITY, -0.05)
                .putAdd(KingdomPressureState.Stat.HAPPINESS, -0.5)
                .putAdd(KingdomPressureState.Stat.RELATIONS, -10)
                .build()
        ).withBark("military", "ai_raids_military")
        .withBark("worker", "ai_raids_worker"));

    // ====== 10 NEW AI→PLAYER PRESSURE TEMPLATES (PressureCatalog additions) ======
    // Paste these into PressureCatalog.java (inside the class), near your other templates.
    // Assumes you have: reg(...), Template.withBark(...), effects() builder, and byTypeId().

    private static final Template AI_GIFT_GRAIN_T =
            reg(new Template(
                    "ai_gift_grain",
                    10 * MINUTE,
                    effects()
                            .putPct(KingdomPressureState.Stat.ECONOMY, +0.06)
                            .putAdd(KingdomPressureState.Stat.HAPPINESS, +0.5)
                            .build()
            ).withBark("worker", "ai_gift_grain_worker")
            .withBark("shop", "ai_gift_grain_shop"));

    private static final Template AI_SEND_MERCENARIES_T =
            reg(new Template(
                    "ai_send_mercenaries",
                    10 * MINUTE,
                    effects()
                            .putAdd(KingdomPressureState.Stat.SECURITY, +0.06)
                            .putPct(KingdomPressureState.Stat.ECONOMY, -0.04)
                            .build()
            ).withBark("military", "ai_send_mercs_military"));

    private static final Template AI_TRAINING_ADVISORS_T =
            reg(new Template(
                    "ai_training_advisors",
                    12 * MINUTE,
                    effects()
                            .putAdd(KingdomPressureState.Stat.SECURITY, +0.04)
                            .putAdd(KingdomPressureState.Stat.HAPPINESS, +0.2)
                            .build()
            ).withBark("military", "ai_training_advisors_military"));

    private static final Template AI_WAR_INTEL_T =
            reg(new Template(
                    "ai_war_intel",
                    10 * MINUTE,
                    effects()
                            .putAdd(KingdomPressureState.Stat.SECURITY, +0.05)
                            .putAdd(KingdomPressureState.Stat.HAPPINESS, +0.1)
                            .build()
            ).withBark("military", "ai_war_intel_military"));

    private static final Template AI_PILGRIM_BLESSING_T =
            reg(new Template(
                    "ai_pilgrim_blessing",
                    12 * MINUTE,
                    effects()
                            .putAdd(KingdomPressureState.Stat.HAPPINESS, +0.8)
                            .putAdd(KingdomPressureState.Stat.RELATIONS, +6)
                            .putPct(KingdomPressureState.Stat.ECONOMY, -0.03)
                            .build()
            ).withBark("chapel", "ai_pilgrim_blessing_chapel")
            .withBark("worker", "ai_pilgrim_blessing_worker"));

    private static final Template AI_SPREAD_RUMORS_T =
            reg(new Template(
                    "ai_spread_rumors",
                    12 * MINUTE,
                    effects()
                            .putAdd(KingdomPressureState.Stat.HAPPINESS, -0.7)
                            .putAdd(KingdomPressureState.Stat.RELATIONS, -8)
                            .build()
            ).withBark("nobility", "ai_spread_rumors_nobility")
            .withBark("worker", "ai_spread_rumors_worker"));

    private static final Template AI_SPY_NETWORK_T =
            reg(new Template(
                    "ai_spy_network",
                    12 * MINUTE,
                    effects()
                            .putAdd(KingdomPressureState.Stat.SECURITY, -0.04)
                            .putAdd(KingdomPressureState.Stat.RELATIONS, -6)
                            .build()
            ).withBark("military", "ai_spy_network_military")
            .withBark("shop", "ai_spy_network_shop"));

    private static final Template AI_SABOTAGE_STORES_T =
            reg(new Template(
                    "ai_sabotage_stores",
                    10 * MINUTE,
                    effects()
                            .putPct(KingdomPressureState.Stat.ECONOMY, -0.10)
                            .putAdd(KingdomPressureState.Stat.HAPPINESS, -0.4)
                            .putAdd(KingdomPressureState.Stat.RELATIONS, -8)
                            .build()
            ).withBark("worker", "ai_sabotage_stores_worker")
            .withBark("shop", "ai_sabotage_stores_shop"));

    private static final Template AI_BOUNTY_HUNTERS_T =
            reg(new Template(
                    "ai_bounty_hunters",
                    12 * MINUTE,
                    effects()
                            .putAdd(KingdomPressureState.Stat.SECURITY, -0.05)
                            .putAdd(KingdomPressureState.Stat.HAPPINESS, -0.3)
                            .putAdd(KingdomPressureState.Stat.RELATIONS, -6)
                            .build()
            ).withBark("military", "ai_bounty_hunters_military"));

    private static final Template AI_SMUGGLER_FLOOD_T =
            reg(new Template(
                    "ai_smuggler_flood",
                    12 * MINUTE,
                    effects()
                            .putAdd(KingdomPressureState.Stat.SECURITY, -0.04)
                            .putPct(KingdomPressureState.Stat.ECONOMY, -0.06)
                            .putAdd(KingdomPressureState.Stat.RELATIONS, -6)
                            .build()
            ).withBark("shop", "ai_smuggler_flood_shop")
            .withBark("worker", "ai_smuggler_flood_worker"));


    // Optional: public getters (only if you like the pattern)
    public static Template AI_GIFT_GRAIN()        { return AI_GIFT_GRAIN_T; }
    public static Template AI_SEND_MERCENARIES()  { return AI_SEND_MERCENARIES_T; }
    public static Template AI_TRAINING_ADVISORS() { return AI_TRAINING_ADVISORS_T; }
    public static Template AI_WAR_INTEL()         { return AI_WAR_INTEL_T; }
    public static Template AI_PILGRIM_BLESSING()  { return AI_PILGRIM_BLESSING_T; }
    public static Template AI_SPREAD_RUMORS()     { return AI_SPREAD_RUMORS_T; }
    public static Template AI_SPY_NETWORK()       { return AI_SPY_NETWORK_T; }
    public static Template AI_SABOTAGE_STORES()   { return AI_SABOTAGE_STORES_T; }
    public static Template AI_BOUNTY_HUNTERS()    { return AI_BOUNTY_HUNTERS_T; }
    public static Template AI_SMUGGLER_FLOOD()    { return AI_SMUGGLER_FLOOD_T; }



    /* -----------------------------
       Public getters (keep your old API)
     ----------------------------- */

    public static Template PUSH_PRODUCTION()        { return PUSH_PRODUCTION_T; }
    public static Template EASE_WORKLOAD()          { return EASE_WORKLOAD_T; }
    public static Template INCREASE_PATROLS()       { return INCREASE_PATROLS_T; }
    public static Template SOW_DISCONTENT()         { return SOW_DISCONTENT_T; }

    public static Template DOUBLE_PACE()            { return DOUBLE_PACE_T; }
    public static Template LEISURELY_PACE()         { return LEISURELY_PACE_T; }

    public static Template INCREASE_PATROLS_POLICY(){ return INCREASE_PATROLS_POLICY_T; }
    public static Template DECREASE_PATROLS_POLICY(){ return DECREASE_PATROLS_POLICY_T; }

    public static Template DOUBLE_RATIONS()         { return DOUBLE_RATIONS_T; }
    public static Template HALVE_RATIONS()          { return HALVE_RATIONS_T; }

    public static Template ALCOHOL_SUBSIDIES()      { return ALCOHOL_SUBSIDIES_T; }
    public static Template DRUNK_CRACKDOWNS()       { return DRUNK_CRACKDOWNS_T; }

    public static Template FREQUENT_SERVICES()      { return FREQUENT_SERVICES_T; }
    public static Template PAPAL_AUTHORITY()        { return PAPAL_AUTHORITY_T; }

    public static Template DIPLOMATIC_ENVOYS()      { return DIPLOMATIC_ENVOYS_T; }
    public static Template VASSAL_CONTRIBUTIONS()   { return VASSAL_CONTRIBUTIONS_T; }

    public static Template MARKET_SUBSIDIES()       { return MARKET_SUBSIDIES_T; }
    public static Template CONTRABAND_CRACKDOWNS()  { return CONTRABAND_CRACKDOWNS_T; }
    public static Template GLOBAL_PLAGUE() { return PLAGUE_T; }
    public static Template GLOBAL_HARVEST() { return BOUNTIFUL_HARVEST_T; }
    public static Template GLOBAL_BANDITS() { return BANDIT_WAVE_T; }
    public static Template GLOBAL_FESTIVAL() { return FESTIVAL_T; }
    public static Template GLOBAL_DROUGHT() { return DROUGHT_T; }


    /* -----------------------------
       Effects builder (unchanged)
     ----------------------------- */

    /** Small builder to keep templates clean. */
    public static Eff effects() { return new Eff(); }

    public static final class Eff {
        private final EnumMap<KingdomPressureState.Stat, Double> m =
                new EnumMap<>(KingdomPressureState.Stat.class);

        /** Additive (HAPPINESS/SECURITY/RELATIONS) */
        public Eff putAdd(KingdomPressureState.Stat stat, double delta) {
            m.put(stat, delta);
            return this;
        }

        /** Percent for ECONOMY: +0.10 means +10% */
        public Eff putPct(KingdomPressureState.Stat stat, double pct) {
            m.put(stat, pct);
            return this;
        }

        public EnumMap<KingdomPressureState.Stat, Double> build() { return m; }
    }

    // -----------------------------
    // AI↔AI inter-kingdom pressure
    // -----------------------------
    private static final Template AI_GOSSIP_PRAISE_T =
            reg(new Template(
                    "ai_gossip_praise",
                    12 * MINUTE,
                    effects()
                            .putAdd(KingdomPressureState.Stat.RELATIONS, +10)
                            .build()
            ));

    private static final Template AI_GOSSIP_SLANDER_T =
            reg(new Template(
                    "ai_gossip_slander",
                    12 * MINUTE,
                    effects()
                            .putAdd(KingdomPressureState.Stat.RELATIONS, -10)
                            .build()
            ));

}
