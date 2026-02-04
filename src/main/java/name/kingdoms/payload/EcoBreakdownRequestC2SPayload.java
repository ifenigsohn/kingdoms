package name.kingdoms.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;


import static name.kingdoms.Kingdoms.id;

public record EcoBreakdownRequestC2SPayload() implements CustomPacketPayload {
    public static final Type<EcoBreakdownRequestC2SPayload> TYPE =
            new Type<>(id("eco_breakdown_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EcoBreakdownRequestC2SPayload> CODEC =
            StreamCodec.unit(new EcoBreakdownRequestC2SPayload());

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
