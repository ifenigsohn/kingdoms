package name.kingdoms.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;


import static name.kingdoms.Kingdoms.id;

public record EcoBreakdownS2CPayload(
        // Happiness
        double hBase,
        double hSecurityPenalty,
        double hPressureDelta,
        double hEff,

        // Security
        double sBase,
        double sPressureDelta,
        double sEff,

        // Economy multipliers
        double pmFromH,
        double pmFromS,
        double pmEff,
        double pressureEconMult,
        double tavernGoldInMult,
        double shopGoldOutMult,
        double finalMult
) implements CustomPacketPayload {

    public static final Type<EcoBreakdownS2CPayload> TYPE =
            new Type<>(id("eco_breakdown_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EcoBreakdownS2CPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeDouble(p.hBase);
                        buf.writeDouble(p.hSecurityPenalty);
                        buf.writeDouble(p.hPressureDelta);
                        buf.writeDouble(p.hEff);

                        buf.writeDouble(p.sBase);
                        buf.writeDouble(p.sPressureDelta);
                        buf.writeDouble(p.sEff);

                        buf.writeDouble(p.pmFromH);
                        buf.writeDouble(p.pmFromS);
                        buf.writeDouble(p.pmEff);
                        buf.writeDouble(p.pressureEconMult);
                        buf.writeDouble(p.tavernGoldInMult);
                        buf.writeDouble(p.shopGoldOutMult);
                        buf.writeDouble(p.finalMult);
                    },
                    buf -> new EcoBreakdownS2CPayload(
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),

                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),

                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble()
                    )
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
