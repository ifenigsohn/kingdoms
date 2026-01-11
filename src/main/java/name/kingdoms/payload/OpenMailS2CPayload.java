package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record OpenMailS2CPayload(int entityId, UUID entityUuid) implements CustomPacketPayload {

    public static final Type<OpenMailS2CPayload> TYPE =
            new Type<>(Kingdoms.id("open_mail_s2c"));

    private static final StreamCodec<RegistryFriendlyByteBuf, UUID> UUID_CODEC = new StreamCodec<>() {
        @Override public UUID decode(RegistryFriendlyByteBuf buf) {
            return new UUID(buf.readLong(), buf.readLong());
        }
        @Override public void encode(RegistryFriendlyByteBuf buf, UUID value) {
            buf.writeLong(value.getMostSignificantBits());
            buf.writeLong(value.getLeastSignificantBits());
        }
    };

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenMailS2CPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, OpenMailS2CPayload::entityId,
                    UUID_CODEC, OpenMailS2CPayload::entityUuid,
                    OpenMailS2CPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}