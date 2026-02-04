package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record OpenKingSpeakActionsS2CPayload(
        int entityId,
        UUID entityUuid,
        UUID targetKingdomId,
        String targetKingdomName,
        String npcType,
        int relation,
        boolean allied,
        boolean atWar,
        List<String> actionIds
) implements CustomPacketPayload {

    public static final Type<OpenKingSpeakActionsS2CPayload> TYPE =
            new Type<>(Kingdoms.id("open_king_speak_actions_s2c"));

    private static UUID readUUID(RegistryFriendlyByteBuf buf) {
        return new UUID(buf.readLong(), buf.readLong());
    }
    private static void writeUUID(RegistryFriendlyByteBuf buf, UUID id) {
        UUID v = (id == null) ? new UUID(0L, 0L) : id;
        buf.writeLong(v.getMostSignificantBits());
        buf.writeLong(v.getLeastSignificantBits());
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenKingSpeakActionsS2CPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OpenKingSpeakActionsS2CPayload decode(RegistryFriendlyByteBuf buf) {
                    int eid = buf.readVarInt();
                    UUID eu = readUUID(buf);
                    UUID kid = readUUID(buf);

                    String kname = buf.readUtf(128);
                    String npcType = buf.readUtf(32);

                    int rel = buf.readVarInt();
                    boolean allied = buf.readBoolean();
                    boolean atWar = buf.readBoolean();

                    int n = buf.readVarInt();
                    List<String> ids = new ArrayList<>(Math.max(0, n));
                    for (int i = 0; i < n; i++) ids.add(buf.readUtf(160));

                    return new OpenKingSpeakActionsS2CPayload(eid, eu, kid, kname, npcType, rel, allied, atWar, ids);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OpenKingSpeakActionsS2CPayload value) {
                    buf.writeVarInt(value.entityId());
                    writeUUID(buf, value.entityUuid());
                    writeUUID(buf, value.targetKingdomId());

                    buf.writeUtf(value.targetKingdomName() == null ? "" : value.targetKingdomName(), 128);
                    buf.writeUtf(value.npcType() == null ? "" : value.npcType(), 32);

                    buf.writeVarInt(value.relation());
                    buf.writeBoolean(value.allied());
                    buf.writeBoolean(value.atWar());

                    List<String> ids = (value.actionIds() == null) ? List.of() : value.actionIds();
                    buf.writeVarInt(ids.size());
                    for (String s : ids) buf.writeUtf(s == null ? "" : s, 160);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
