package name.kingdoms.payload;

import name.kingdoms.Kingdoms;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record mailPolicyRequestC2SPayload(UUID toKingdomId) implements CustomPacketPayload {
    public static final Type<mailPolicyRequestC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "mail_policy_request"));

    private static void writeUuid(RegistryFriendlyByteBuf buf, UUID id) {
        buf.writeLong(id.getMostSignificantBits());
        buf.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(RegistryFriendlyByteBuf buf) {
        long msb = buf.readLong();
        long lsb = buf.readLong();
        return new UUID(msb, lsb);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, mailPolicyRequestC2SPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> writeUuid(buf, p.toKingdomId()),
                    (buf) -> new mailPolicyRequestC2SPayload(readUuid(buf))
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
