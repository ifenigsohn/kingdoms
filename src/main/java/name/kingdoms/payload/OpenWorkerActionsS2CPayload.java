package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record OpenWorkerActionsS2CPayload(
        int entityId,
        UUID entityUuid,
        UUID workerKingdomId,
        String jobId,
        boolean isRetinue,
        boolean canTax,
        boolean canOpenTreasury,
        boolean canOpenMail,
        boolean canOpenWarOverview,
        List<String> actionIds // e.g. ["PUSH_PRODUCTION","EASE_WORKLOAD"]
) implements CustomPacketPayload {

    public static final Type<OpenWorkerActionsS2CPayload> TYPE =
            new Type<>(Kingdoms.id("open_worker_actions_s2c"));

    private static UUID readUUID(RegistryFriendlyByteBuf buf) {
        return new UUID(buf.readLong(), buf.readLong());
    }

    private static void writeUUID(RegistryFriendlyByteBuf buf, UUID id) {
        buf.writeLong(id.getMostSignificantBits());
        buf.writeLong(id.getLeastSignificantBits());
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenWorkerActionsS2CPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OpenWorkerActionsS2CPayload decode(RegistryFriendlyByteBuf buf) {
                    int entityId = buf.readVarInt();
                    UUID entityUuid = readUUID(buf);
                    UUID workerKingdomId = readUUID(buf);
                    String jobId = buf.readUtf(32767);

                    boolean isRetinue = buf.readBoolean();
                    boolean canTax = buf.readBoolean();
                    boolean canOpenTreasury = buf.readBoolean();
                    boolean canOpenMail = buf.readBoolean();
                    boolean canOpenWarOverview = buf.readBoolean();

                    int n = buf.readVarInt();
                    List<String> actions = new ArrayList<>(Math.max(0, n));
                    for (int i = 0; i < n; i++) actions.add(buf.readUtf(32767));

                    return new OpenWorkerActionsS2CPayload(
                            entityId, entityUuid, workerKingdomId, jobId,
                            isRetinue, canTax, canOpenTreasury, canOpenMail, canOpenWarOverview,
                            actions
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OpenWorkerActionsS2CPayload value) {
                    buf.writeVarInt(value.entityId());
                    writeUUID(buf, value.entityUuid());
                    writeUUID(buf, value.workerKingdomId());
                    buf.writeUtf(value.jobId() == null ? "" : value.jobId(), 32767);

                    buf.writeBoolean(value.isRetinue());
                    buf.writeBoolean(value.canTax());
                    buf.writeBoolean(value.canOpenTreasury());
                    buf.writeBoolean(value.canOpenMail());
                    buf.writeBoolean(value.canOpenWarOverview());

                    List<String> list = (value.actionIds() == null) ? List.of() : value.actionIds();
                    buf.writeVarInt(list.size());
                    for (String s : list) buf.writeUtf(s == null ? "" : s, 32767);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
