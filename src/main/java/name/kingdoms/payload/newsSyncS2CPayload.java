package name.kingdoms.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import name.kingdoms.Kingdoms;

import java.util.List;

public record newsSyncS2CPayload(List<String> lines) implements CustomPacketPayload {
    public static final Type<newsSyncS2CPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "news_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, newsSyncS2CPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), newsSyncS2CPayload::lines,
                    newsSyncS2CPayload::new
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
