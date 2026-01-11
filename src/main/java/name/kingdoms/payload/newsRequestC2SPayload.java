package name.kingdoms.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import name.kingdoms.Kingdoms;

public record newsRequestC2SPayload(int limit) implements CustomPacketPayload {
    public static final Type<newsRequestC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "news_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, newsRequestC2SPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, newsRequestC2SPayload::limit,
                    newsRequestC2SPayload::new
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
