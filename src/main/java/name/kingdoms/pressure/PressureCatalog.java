package name.kingdoms.pressure;

import java.util.EnumMap;

public final class PressureCatalog {
    private PressureCatalog() {}

    // ---- durations (tune later) ----
    public static final long MINUTE = 20L * 60L;
    public static final long HOUR   = 20L * 60L * 60L;

    /** Worker: push output -> econ up, happiness down */
    public static Template PUSH_PRODUCTION() {
        return new Template(
                "push_production",
                10 * MINUTE,
                effects()
                        .putPct(KingdomPressureState.Stat.ECONOMY, +0.12) // +12% output
                        .putAdd(KingdomPressureState.Stat.HAPPINESS, -0.6) // -0.6 on 0..10
                        .build()
        );
    }

    /** Worker: ease workload -> happiness up, econ down */
    public static Template EASE_WORKLOAD() {
        return new Template(
                "ease_workload",
                10 * MINUTE,
                effects()
                        .putPct(KingdomPressureState.Stat.ECONOMY, -0.10)
                        .putAdd(KingdomPressureState.Stat.HAPPINESS, +0.8)
                        .build()
        );
    }

    /** Guards: increase patrols -> security up, econ down */
    public static Template INCREASE_PATROLS() {
        return new Template(
                "increase_patrols",
                12 * MINUTE,
                effects()
                        .putAdd(KingdomPressureState.Stat.SECURITY, +0.06) // +0.06 on 0..1
                        .putPct(KingdomPressureState.Stat.ECONOMY, -0.08)
                        .build()
        );
    }

    /** External: sow discontent -> happiness down, relations down (temporary bias) */
    public static Template SOW_DISCONTENT() {
        return new Template(
                "sow_discontent",
                15 * MINUTE,
                effects()
                        .putAdd(KingdomPressureState.Stat.HAPPINESS, -0.9)
                        .putAdd(KingdomPressureState.Stat.RELATIONS, -8) // -8 temporary eval bias
                        .build()
        );
    }

    // -----------------------------
    // POLICY: Pace (mutually exclusive)
    // Accessible via butcher/grain/fish/iron/gem NPCs
    // -----------------------------
    public static Template DOUBLE_PACE() {
        return new Template(
                "double_pace",
                10 * MINUTE,
                effects()
                        .putPct(KingdomPressureState.Stat.ECONOMY, +0.12)
                        .putAdd(KingdomPressureState.Stat.HAPPINESS, -0.6)
                        .build()
        );
    }

    public static Template LEISURELY_PACE() {
        return new Template(
                "leisurely_pace",
                10 * MINUTE,
                effects()
                        .putPct(KingdomPressureState.Stat.ECONOMY, -0.10)
                        .putAdd(KingdomPressureState.Stat.HAPPINESS, +0.8)
                        .build()
        );
    }

    // -----------------------------
    // POLICY: Patrols (mutually exclusive)
    // Accessible via guard NPC type
    // -----------------------------
    public static Template INCREASE_PATROLS_POLICY() {
        return new Template(
                "increase_patrols",
                12 * MINUTE,
                effects()
                        .putAdd(KingdomPressureState.Stat.SECURITY, +0.06)
                        .putPct(KingdomPressureState.Stat.ECONOMY, -0.08)
                        .build()
        );
    }

    public static Template DECREASE_PATROLS_POLICY() {
        return new Template(
                "decrease_patrols",
                12 * MINUTE,
                effects()
                        .putAdd(KingdomPressureState.Stat.SECURITY, -0.06)
                        .putPct(KingdomPressureState.Stat.ECONOMY, +0.05) // “less patrol cost” proxy
                        .build()
        );
    }

    // -----------------------------
    // POLICY: Rations (mutually exclusive)
    // Accessible via garrison/soldier NPC type
    // NOTE: regen/food-cost will be implemented next pass;
    // for now this is a proxy using security/econ/happiness.
    // -----------------------------
    public static Template DOUBLE_RATIONS() {
        return new Template(
                "double_rations",
                12 * MINUTE,
                effects()
                        .putAdd(KingdomPressureState.Stat.SECURITY, +0.03) // proxy: readiness
                        .putPct(KingdomPressureState.Stat.ECONOMY, -0.06)  // proxy: extra supply cost
                        .build()
        );
    }

    public static Template HALVE_RATIONS() {
        return new Template(
                "halve_rations",
                12 * MINUTE,
                effects()
                        .putAdd(KingdomPressureState.Stat.SECURITY, -0.03)
                        .putPct(KingdomPressureState.Stat.ECONOMY, +0.04)
                        .build()
        );
    }

    // -----------------------------
    // POLICY: Tavern (mutually exclusive)
    // Accessible via tavern NPC type
    // -----------------------------
    public static Template ALCOHOL_SUBSIDIES() {
        return new Template(
                "alcohol_subsidies",
                12 * MINUTE,
                effects()
                        .putAdd(KingdomPressureState.Stat.HAPPINESS, +0.9)
                        .putPct(KingdomPressureState.Stat.ECONOMY, -0.08)
                        .build()
        );
    }

    public static Template DRUNK_CRACKDOWNS() {
        return new Template(
                "drunk_crackdowns",
                12 * MINUTE,
                effects()
                        .putAdd(KingdomPressureState.Stat.HAPPINESS, -0.7)
                        .putPct(KingdomPressureState.Stat.ECONOMY, +0.06)
                        .build()
        );
    }

    // -----------------------------
    // POLICY: Chapel/Priest (mutually exclusive)
    // Accessible via chapel NPC type
    // Papal Authority: relations with all rulers (GLOBAL rel)
    // -----------------------------
    public static Template FREQUENT_SERVICES() {
        return new Template(
                "frequent_services",
                12 * MINUTE,
                effects()
                        .putAdd(KingdomPressureState.Stat.HAPPINESS, +0.8)
                        .putPct(KingdomPressureState.Stat.ECONOMY, -0.08)
                        .build()
        );
    }

    public static Template PAPAL_AUTHORITY() {
        return new Template(
                "papal_authority",
                12 * MINUTE,
                effects()
                        .putAdd(KingdomPressureState.Stat.RELATIONS, +10)
                        .putPct(KingdomPressureState.Stat.ECONOMY, -0.05) // proxy for gold cost
                        .build()
        );
    }

    // -----------------------------
    // POLICY: Nobility (mutually exclusive)
    // Diplomatic Envoys: global relations up, gold cost proxy
    // Vassal Contributions: econ up, happiness down
    // -----------------------------
    public static Template DIPLOMATIC_ENVOYS() {
        return new Template(
                "diplomatic_envoys",
                12 * MINUTE,
                effects()
                        .putAdd(KingdomPressureState.Stat.RELATIONS, +10)
                        .putPct(KingdomPressureState.Stat.ECONOMY, -0.05)
                        .build()
        );
    }

    public static Template VASSAL_CONTRIBUTIONS() {
        return new Template(
                "vassal_contributions",
                12 * MINUTE,
                effects()
                        .putPct(KingdomPressureState.Stat.ECONOMY, +0.10)
                        .putAdd(KingdomPressureState.Stat.HAPPINESS, -0.8)
                        .build()
        );
    }

    // -----------------------------
    // POLICY: Shop (mutually exclusive)
    // Market Subsidies: global relations up, econ down
    // Contraband Crackdowns: global relations down, security up
    // -----------------------------
    public static Template MARKET_SUBSIDIES() {
        return new Template(
                "market_subsidies",
                12 * MINUTE,
                effects()
                        .putAdd(KingdomPressureState.Stat.RELATIONS, +8)
                        .putPct(KingdomPressureState.Stat.ECONOMY, -0.08)
                        .build()
        );
    }

    public static Template CONTRABAND_CRACKDOWNS() {
        return new Template(
                "contraband_crackdowns",
                12 * MINUTE,
                effects()
                        .putAdd(KingdomPressureState.Stat.RELATIONS, -8)
                        .putAdd(KingdomPressureState.Stat.SECURITY, +0.04)
                        .build()
        );
    }


    // --------------- plumbing ---------------

    public record Template(String typeId, long durationTicks, EnumMap<KingdomPressureState.Stat, Double> effects) {}

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
}
