package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record KingSpeakActionsRequestC2SPayload(
        int kingEntityId,
        UUID kingEntityUuid,
        UUID targetKingdomId,
        String npcType // "king" / "noble" / "guard" / "villager" (flavor & gating)
) implements CustomPacketPayload {

    public static final Type<KingSpeakActionsRequestC2SPayload> TYPE =
            new Type<>(Kingdoms.id("king_speak_actions_req_c2s"));

    private static UUID readUUID(RegistryFriendlyByteBuf buf) {
        return new UUID(buf.readLong(), buf.readLong());
    }
    private static void writeUUID(RegistryFriendlyByteBuf buf, UUID id) {
        UUID v = (id == null) ? new UUID(0L, 0L) : id;
        buf.writeLong(v.getMostSignificantBits());
        buf.writeLong(v.getLeastSignificantBits());
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, KingSpeakActionsRequestC2SPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public KingSpeakActionsRequestC2SPayload decode(RegistryFriendlyByteBuf buf) {
                    int eid = buf.readVarInt();
                    UUID eu = readUUID(buf);
                    UUID kid = readUUID(buf);
                    String type = buf.readUtf(32);
                    return new KingSpeakActionsRequestC2SPayload(eid, eu, kid, type);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, KingSpeakActionsRequestC2SPayload value) {
                    buf.writeVarInt(value.kingEntityId());
                    writeUUID(buf, value.kingEntityUuid());
                    writeUUID(buf, value.targetKingdomId());
                    buf.writeUtf(value.npcType() == null ? "" : value.npcType(), 32);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
