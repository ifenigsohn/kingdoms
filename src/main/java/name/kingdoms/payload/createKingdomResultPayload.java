package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record createKingdomResultPayload(boolean ok, String message) implements CustomPacketPayload {

    public static final Type<createKingdomResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "create_kingdom_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, createKingdomResultPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBoolean(p.ok());
                        buf.writeUtf(p.message(), 256);
                    },
                    buf -> new createKingdomResultPayload(
                            buf.readBoolean(),
                            buf.readUtf(256)
                    )
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}