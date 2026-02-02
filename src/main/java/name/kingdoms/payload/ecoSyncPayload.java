package name.kingdoms.payload;

import name.kingdoms.KingdomEconomyCalc;
import name.kingdoms.Kingdoms;
import name.kingdoms.jobDefinition;
import name.kingdoms.kingdomState;
import name.kingdoms.pressure.KingdomPressureState;
import name.kingdoms.pressure.PolicyModifiers;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

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

        private static double clamp10(double v) { return Math.max(0.0, Math.min(10.0, v)); }
        private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }

        private static double prodMultFromHappiness(double h) {
        if (h >= 7.0) return 1.0;
        if (h >= 4.0) return 0.75;
        return 0.5;
        }

        private static String securityBandFor(double s, double required) {
        if (s >= 0.40) return "High";
        if (s >= required) return "Medium";
        return "Low";
        }


        public static ecoSyncPayload fromKingdomWithProjected(MinecraftServer server, kingdomState.Kingdom k) {
                if (server == null || k == null) return zeros();

                var d = KingdomEconomyCalc.compute(server, k);

                String name = (k.name == null) ? "" : k.name;

                // security band based on effective value
                String band;
                double s = d.securityEff();
                double req = kingdomState.Kingdom.REQUIRED_SECURITY;
                if (s >= 0.40) band = "High";
                else if (s >= req) band = "Medium";
                else band = "Low";

                return new ecoSyncPayload(
                        // totals
                        k.gold, k.meat, k.grain, k.fish,
                        k.wood, k.metal, k.armor, k.weapons,
                        k.gems, k.horses, k.potions,

                        // projected deltas per second (already policy + pressure aware)
                        d.dGold(), d.dMeat(), d.dGrain(), d.dFish(),
                        d.dWood(), d.dMetal(), d.dArmor(), d.dWeapons(),
                        d.dGems(), d.dHorses(), d.dPotions(),

                        // happiness + prodMult (effective)
                        d.happinessEff(),
                        d.prodMultEff(),

                        // security (effective)
                        d.securityEff(),
                        req,
                        band,

                        // connection + name
                        true,
                        name
                );
    }
}
