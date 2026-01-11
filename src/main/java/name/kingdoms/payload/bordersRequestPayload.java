package name.kingdoms.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record bordersRequestPayload() implements CustomPacketPayload {
    public static final Type<bordersRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("kingdoms", "borders_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, bordersRequestPayload> STREAM_CODEC =
            StreamCodec.unit(new bordersRequestPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
