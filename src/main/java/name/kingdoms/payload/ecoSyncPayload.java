package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import name.kingdoms.jobDefinition;
import name.kingdoms.kingdomState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ecoSyncPayload(
        // totals
        double gold, double meat, double grain, double fish,
        double wood, double metal, double armor, double weapons,
        double gems, double horses, double potions,

        // projected deltas per SECOND
        double dGold, double dMeat, double dGrain, double dFish,
        double dWood, double dMetal, double dArmor, double dWeapons,
        double dGems, double dHorses, double dPotions,

        // happiness + production multiplier
        double happiness,
        double prodMult,

        // NEW: security
        double securityValue,
        double requiredSecurity,
        String securityBand,

        // connection + name
        boolean connected,
        String kingdomName
) implements CustomPacketPayload {

    public static final Type<ecoSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "eco_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ecoSyncPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        // totals
                        buf.writeDouble(p.gold());    buf.writeDouble(p.meat());   buf.writeDouble(p.grain()); buf.writeDouble(p.fish());
                        buf.writeDouble(p.wood());    buf.writeDouble(p.metal());  buf.writeDouble(p.armor()); buf.writeDouble(p.weapons());
                        buf.writeDouble(p.gems());    buf.writeDouble(p.horses()); buf.writeDouble(p.potions());

                        // projected
                        buf.writeDouble(p.dGold());    buf.writeDouble(p.dMeat());   buf.writeDouble(p.dGrain()); buf.writeDouble(p.dFish());
                        buf.writeDouble(p.dWood());    buf.writeDouble(p.dMetal());  buf.writeDouble(p.dArmor()); buf.writeDouble(p.dWeapons());
                        buf.writeDouble(p.dGems());    buf.writeDouble(p.dHorses()); buf.writeDouble(p.dPotions());

                        // happiness + productivity
                        buf.writeDouble(p.happiness());
                        buf.writeDouble(p.prodMult());

                        // security
                        buf.writeDouble(p.securityValue());
                        buf.writeDouble(p.requiredSecurity());
                        buf.writeUtf(p.securityBand() == null ? "" : p.securityBand(), 16);

                        // connection + name
                        buf.writeBoolean(p.connected());
                        buf.writeUtf(p.kingdomName() == null ? "" : p.kingdomName(), 64);
                    },
                    (buf) -> new ecoSyncPayload(
                            // totals
                            buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble(),

                            // projected
                            buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble(),

                            // happiness + productivity
                            buf.readDouble(),
                            buf.readDouble(),

                            // security
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readUtf(16),

                            // connection + name
                            buf.readBoolean(),
                            buf.readUtf(64)
                    )
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static ecoSyncPayload zeros() {
        return new ecoSyncPayload(
                // totals
                0,0,0,0,
                0,0,0,0,
                0,0,0,

                // projected
                0,0,0,0,
                0,0,0,0,
                0,0,0,

                // happiness + prod
                0,
                1,

                // security
                0.4,   // securityValue baseline display
                0.3,   // requiredSecurity
                "High",

                // connected + name
                false,
                ""
        );
    }

    public static ecoSyncPayload fromKingdomWithProjected(kingdomState.Kingdom k) {
        double dGold = 0, dMeat = 0, dGrain = 0, dFish = 0;
        double dWood = 0, dMetal = 0, dArmor = 0, dWeapons = 0;
        double dGems = 0, dHorses = 0, dPotions = 0;

        // include happiness multiplier in projections (so UI matches real output)
        double pm = k.productionMultiplier();

        for (var e : k.active.entrySet()) {
            var job = jobDefinition.byId(e.getKey());
            int count = e.getValue();
            if (job == null || count <= 0) continue;

            double secondsPerCycle = job.getWorkInterval() / 20.0; // ticks -> seconds
            if (secondsPerCycle <= 0) continue;

            dGold    += (job.netGold()    * count * pm) / secondsPerCycle;
            dMeat    += (job.netMeat()    * count * pm) / secondsPerCycle;
            dGrain   += (job.netGrain()   * count * pm) / secondsPerCycle;
            dFish    += (job.netFish()    * count * pm) / secondsPerCycle;

            dWood    += (job.netWood()    * count * pm) / secondsPerCycle;
            dMetal   += (job.netMetal()   * count * pm) / secondsPerCycle;
            dArmor   += (job.netArmor()   * count * pm) / secondsPerCycle;
            dWeapons += (job.netWeapons() * count * pm) / secondsPerCycle;

            dGems    += (job.netGems()    * count * pm) / secondsPerCycle;
            dHorses  += (job.netHorses()  * count * pm) / secondsPerCycle;
            dPotions += (job.netPotions() * count * pm) / secondsPerCycle;
        }

        String name = (k.name == null) ? "" : k.name;

        return new ecoSyncPayload(
                // totals
                k.gold, k.meat, k.grain, k.fish,
                k.wood, k.metal, k.armor, k.weapons,
                k.gems, k.horses, k.potions,

                // projected
                dGold, dMeat, dGrain, dFish,
                dWood, dMetal, dArmor, dWeapons,
                dGems, dHorses, dPotions,

                // happiness + prod
                k.happiness(),
                pm,

                // security
                k.securityValue(),
                kingdomState.Kingdom.REQUIRED_SECURITY,
                k.securityBand(),

                // connected + name
                true,
                name
        );
    }
}
