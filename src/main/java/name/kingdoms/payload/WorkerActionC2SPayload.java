package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record WorkerActionC2SPayload(
        int entityId,
        UUID entityUuid,
        String actionId
) implements CustomPacketPayload {

    public static final Type<WorkerActionC2SPayload> TYPE =
            new Type<>(Kingdoms.id("worker_action_c2s"));

    private static UUID readUUID(RegistryFriendlyByteBuf buf) {
        return new UUID(buf.readLong(), buf.readLong());
    }

    private static void writeUUID(RegistryFriendlyByteBuf buf, UUID id) {
        buf.writeLong(id.getMostSignificantBits());
        buf.writeLong(id.getLeastSignificantBits());
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, WorkerActionC2SPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public WorkerActionC2SPayload decode(RegistryFriendlyByteBuf buf) {
                    int entityId = buf.readVarInt();
                    UUID entityUuid = readUUID(buf);
                    String actionId = buf.readUtf(32767);
                    return new WorkerActionC2SPayload(entityId, entityUuid, actionId);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, WorkerActionC2SPayload value) {
                    buf.writeVarInt(value.entityId());
                    writeUUID(buf, value.entityUuid());
                    buf.writeUtf(value.actionId() == null ? "" : value.actionId(), 32767);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
