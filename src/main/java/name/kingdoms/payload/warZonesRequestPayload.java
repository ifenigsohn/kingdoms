package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record warZonesRequestPayload() implements CustomPacketPayload {
    public static final Type<warZonesRequestPayload> TYPE =
            new Type<>(Kingdoms.id("war_zones_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, warZonesRequestPayload> CODEC =
            StreamCodec.unit(new warZonesRequestPayload());

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
