package name.kingdoms;

import name.kingdoms.jobDefinition;
import name.kingdoms.kingdomState;
import name.kingdoms.pressure.PolicyModifiers;
import name.kingdoms.pressure.KingdomPressureState;
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

        double hEff = clamp10(k.happiness() + mods.happinessDelta());
        double sEff = clamp01(k.securityValue() + mods.securityDelta());
        double pmEff = prodMultFromHappiness(hEff);

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

    private static double clamp10(double v) { return Math.max(0.0, Math.min(10.0, v)); }
    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
    private static double prodMultFromHappiness(double h) {
        if (h >= 7.0) return 1.0;
        if (h >= 4.0) return 0.75;
        return 0.5;
    }
}
