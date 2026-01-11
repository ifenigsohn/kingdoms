package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record mailSendResultS2CPayload(
        UUID requestId,     // lets client match a UI action if needed
        boolean accepted,
        String message      // small status string for UI/chat
) implements CustomPacketPayload {

    public static final Type<mailSendResultS2CPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "mail_send_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, mailSendResultS2CPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        writeUUID(buf, p.requestId());
                        buf.writeBoolean(p.accepted());
                        buf.writeUtf(p.message() == null ? "" : p.message(), 256);
                    },
                    (buf) -> new mailSendResultS2CPayload(
                            readUUID(buf),
                            buf.readBoolean(),
                            buf.readUtf(256)
                    )
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static void writeUUID(RegistryFriendlyByteBuf buf, UUID id) {
        buf.writeLong(id.getMostSignificantBits());
        buf.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUUID(RegistryFriendlyByteBuf buf) {
        long most = buf.readLong();
        long least = buf.readLong();
        return new UUID(most, least);
    }
}
