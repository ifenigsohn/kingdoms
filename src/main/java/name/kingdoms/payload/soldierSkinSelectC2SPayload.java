package name.kingdoms.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record soldierSkinSelectC2SPayload(int soldierSkinId) implements CustomPacketPayload {

    public static final Type<soldierSkinSelectC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("kingdoms", "soldier_skin_select"));

    public static final StreamCodec<RegistryFriendlyByteBuf, soldierSkinSelectC2SPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public soldierSkinSelectC2SPayload decode(RegistryFriendlyByteBuf buf) {
                    return new soldierSkinSelectC2SPayload(buf.readInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, soldierSkinSelectC2SPayload value) {
                    buf.writeInt(value.soldierSkinId());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
