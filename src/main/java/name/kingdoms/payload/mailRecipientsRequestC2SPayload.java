package name.kingdoms.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import name.kingdoms.Kingdoms;

public record mailRecipientsRequestC2SPayload() implements CustomPacketPayload {

    public static final Type<mailRecipientsRequestC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "mail_recipients_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, mailRecipientsRequestC2SPayload> STREAM_CODEC =
            StreamCodec.unit(new mailRecipientsRequestC2SPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
