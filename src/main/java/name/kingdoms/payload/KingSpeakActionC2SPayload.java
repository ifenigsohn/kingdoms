package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record KingSpeakActionC2SPayload(
        int entityId,
        UUID entityUuid,
        UUID targetKingdomId,
        String npcType,
        String actionId
) implements CustomPacketPayload {

    public static final Type<KingSpeakActionC2SPayload> TYPE =
            new Type<>(Kingdoms.id("king_speak_action_c2s"));

    private static UUID readUUID(RegistryFriendlyByteBuf buf) {
        return new UUID(buf.readLong(), buf.readLong());
    }
    private static void writeUUID(RegistryFriendlyByteBuf buf, UUID id) {
        UUID v = (id == null) ? new UUID(0L, 0L) : id;
        buf.writeLong(v.getMostSignificantBits());
        buf.writeLong(v.getLeastSignificantBits());
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, KingSpeakActionC2SPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public KingSpeakActionC2SPayload decode(RegistryFriendlyByteBuf buf) {
                    int eid = buf.readVarInt();
                    UUID eu = readUUID(buf);
                    UUID kid = readUUID(buf);
                    String npcType = buf.readUtf(32);
                    String act = buf.readUtf(64);
                    return new KingSpeakActionC2SPayload(eid, eu, kid, npcType, act);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, KingSpeakActionC2SPayload value) {
                    buf.writeVarInt(value.entityId());
                    writeUUID(buf, value.entityUuid());
                    writeUUID(buf, value.targetKingdomId());
                    buf.writeUtf(value.npcType() == null ? "" : value.npcType(), 32);
                    buf.writeUtf(value.actionId() == null ? "" : value.actionId(), 64);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
