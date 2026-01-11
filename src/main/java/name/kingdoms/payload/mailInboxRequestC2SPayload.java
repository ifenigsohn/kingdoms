package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record mailInboxRequestC2SPayload() implements CustomPacketPayload {

    public static final Type<mailInboxRequestC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "mail_inbox_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, mailInboxRequestC2SPayload> CODEC =
            StreamCodec.unit(new mailInboxRequestC2SPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
