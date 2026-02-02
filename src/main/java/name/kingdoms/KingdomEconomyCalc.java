package name.kingdoms;

import name.kingdoms.pressure.KingdomPressureState;
import name.kingdoms.pressure.PolicyModifiers;
import net.minecraft.server.MinecraftServer;

public final class KingdomEconomyCalc {
    private KingdomEconomyCalc() {}

    public record Deltas(
            double dGold, double dMeat, double dGrain, double dFish,
            double dWood, double dMetal, double dArmor, double dWeapons,
            double dGems, double dHorses, double dPotions,
            double happinessEff,
            double securityEff,
            double prodMultEff
    ) {}

    public static Deltas compute(MinecraftServer server, kingdomState.Kingdom k) {
        long now = server.getTickCount();

        var mods = KingdomPressureState.get(server).getMods(k.id, now);
        var pol  = PolicyModifiers.compute(server, k.id);

        // --- pressure-aware security first (0..1) ---
        double sEff = clamp01(k.securityValue() + mods.securityDelta());

        // --- SECURITY -> HAPPINESS (sliding), then add happiness pressure delta ---
        // NOTE: k.happiness() currently includes a hard -2 when security is low.
        // We'll neutralize that here for now, then apply the sliding penalty using sEff.
        double hBase = k.happiness();

        double hEff = hBase;

        if (k.populationJobs() > 5) {
            double req = kingdomState.Kingdom.REQUIRED_SECURITY; // 0.30
            double deficit01 = clamp((req - sEff) / req, 0.0, 1.0); // 0..1
            double maxPenalty = 3.0; // tune; old was 2.0 flat
            hEff -= maxPenalty * deficit01;
        }

        // apply pressure happiness delta last
        hEff = clamp10(hEff + mods.happinessDelta());

        // --- HAPPINESS -> PRODUCTIVITY (sliding): 0..10 -> 0.40..1.20 ---
        double pmFromH = 0.40 + (hEff / 10.0) * (1.20 - 0.40); // = 0.40 + 0.08*hEff

        // --- SECURITY -> PRODUCTIVITY (sliding) ---
        // s 0..1 => 0.85..1.15 (tune)
        double pmFromS = lerp(0.85, 1.15, sEff);

        // bounded final productivity multiplier
        double pmEff = clamp(pmFromH * pmFromS, 0.40, 1.20);

        // other economy multipliers (pressure events)
        double mult = pmEff * mods.economyMult();

        double dGold = 0, dMeat = 0, dGrain = 0, dFish = 0;
        double dWood = 0, dMetal = 0, dArmor = 0, dWeapons = 0;
        double dGems = 0, dHorses = 0, dPotions = 0;

        for (var e : k.active.entrySet()) {
            var job = jobDefinition.byId(e.getKey());
            int count = e.getValue();
            if (job == null || count <= 0) continue;

            double secondsPerCycle = job.getWorkInterval() / 20.0;
            if (secondsPerCycle <= 0) continue;

            double netGold = job.netGold();

            if ("tavern".equals(job.getId()) && netGold < 0) netGold *= pol.tavernGoldInMult();
            if ("shop".equals(job.getId()) && netGold > 0) netGold *= pol.shopGoldOutMult();

            dGold += (netGold * count * mult) / secondsPerCycle;

            dMeat    += (job.netMeat()    * count * mult) / secondsPerCycle;
            dGrain   += (job.netGrain()   * count * mult) / secondsPerCycle;
            dFish    += (job.netFish()    * count * mult) / secondsPerCycle;

            dWood    += (job.netWood()    * count * mult) / secondsPerCycle;
            dMetal   += (job.netMetal()   * count * mult) / secondsPerCycle;
            dArmor   += (job.netArmor()   * count * mult) / secondsPerCycle;
            dWeapons += (job.netWeapons() * count * mult) / secondsPerCycle;

            dGems    += (job.netGems()    * count * mult) / secondsPerCycle;
            dHorses  += (job.netHorses()  * count * mult) / secondsPerCycle;
            dPotions += (job.netPotions() * count * mult) / secondsPerCycle;
        }

        return new Deltas(
                dGold, dMeat, dGrain, dFish,
                dWood, dMetal, dArmor, dWeapons,
                dGems, dHorses, dPotions,
                hEff, sEff, pmEff
        );
    }

    // ----------------
    // small helpers
    // ----------------
    private static double clamp10(double v) { return Math.max(0.0, Math.min(10.0, v)); }
    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
