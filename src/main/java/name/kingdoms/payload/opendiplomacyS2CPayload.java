package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record opendiplomacyS2CPayload(int entityId, UUID entityUuid, UUID kingdomID, String kingdomName, int relation)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<opendiplomacyS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(Kingdoms.id("open_diplomacy_s2c"));

    // MC-safe UUID codec (no ByteBufCodecs.UUID dependency)
    private static final StreamCodec<RegistryFriendlyByteBuf, UUID> UUID_CODEC = new StreamCodec<>() {
        @Override
        public UUID decode(RegistryFriendlyByteBuf buf) {
            long msb = buf.readLong();
            long lsb = buf.readLong();
            return new UUID(msb, lsb);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, UUID value) {
            buf.writeLong(value.getMostSignificantBits());
            buf.writeLong(value.getLeastSignificantBits());
        }
    };

    public static final StreamCodec<RegistryFriendlyByteBuf, opendiplomacyS2CPayload> STREAM_CODEC =
        new StreamCodec<>() {
            @Override
            public opendiplomacyS2CPayload decode(RegistryFriendlyByteBuf buf) {
                int entityId = buf.readVarInt();
                UUID entityUuid = UUID_CODEC.decode(buf);
                UUID kingdomID = UUID_CODEC.decode(buf);
                String kingdomName = buf.readUtf();
                int relation = buf.readInt();
                return new opendiplomacyS2CPayload(entityId, entityUuid, kingdomID, kingdomName, relation);
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buf, opendiplomacyS2CPayload value) {
                buf.writeVarInt(value.entityId());
                UUID_CODEC.encode(buf, value.entityUuid());
                UUID_CODEC.encode(buf, value.kingdomID());
                buf.writeUtf(value.kingdomName());
                buf.writeInt(value.relation());
            }
        };


    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
