package name.kingdoms.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record requestBorderWandPayload() implements CustomPacketPayload {

    public static final Type<requestBorderWandPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("kingdoms", "request_border_wand"));

    public static final StreamCodec<RegistryFriendlyByteBuf, requestBorderWandPayload> CODEC =
            StreamCodec.unit(new requestBorderWandPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
