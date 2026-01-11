package name.kingdoms.payload;

import name.kingdoms.Kingdoms;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

public record mailPolicySyncS2CPayload(UUID toKingdomId, List<Entry> entries) implements CustomPacketPayload {

    public record Entry(int kindOrdinal, boolean allowed, String reason) {}

    public static final Type<mailPolicySyncS2CPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "mail_policy_sync"));

    private static void writeUuid(RegistryFriendlyByteBuf buf, UUID id) {
        buf.writeLong(id.getMostSignificantBits());
        buf.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(RegistryFriendlyByteBuf buf) {
        long msb = buf.readLong();
        long lsb = buf.readLong();
        return new UUID(msb, lsb);
    }

    private static final StreamCodec<RegistryFriendlyByteBuf, Entry> ENTRY_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, Entry::kindOrdinal,
                    ByteBufCodecs.BOOL, Entry::allowed,
                    ByteBufCodecs.STRING_UTF8, Entry::reason,
                    Entry::new
            );

    public static final StreamCodec<RegistryFriendlyByteBuf, mailPolicySyncS2CPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        writeUuid(buf, p.toKingdomId());
                        ENTRY_CODEC.apply(ByteBufCodecs.list()).encode(buf, p.entries());
                    },
                    (buf) -> {
                        UUID toId = readUuid(buf);
                        List<Entry> list = ENTRY_CODEC.apply(ByteBufCodecs.list()).decode(buf);
                        return new mailPolicySyncS2CPayload(toId, list);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
