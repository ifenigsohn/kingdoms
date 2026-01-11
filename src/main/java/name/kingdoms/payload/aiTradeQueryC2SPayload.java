package name.kingdoms.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record aiTradeQueryC2SPayload(int kingEntityId) implements CustomPacketPayload {

    public static final Type<aiTradeQueryC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("kingdoms", "ai_trade_query_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, aiTradeQueryC2SPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, aiTradeQueryC2SPayload::kingEntityId,
                    aiTradeQueryC2SPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
