package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record warCommandGroupSyncS2CPayload(int group) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<warCommandGroupSyncS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(Kingdoms.id("war_cmd_group"));

    public static final StreamCodec<RegistryFriendlyByteBuf, warCommandGroupSyncS2CPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public warCommandGroupSyncS2CPayload decode(RegistryFriendlyByteBuf buf) {
                    return new warCommandGroupSyncS2CPayload(buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, warCommandGroupSyncS2CPayload value) {
                    buf.writeVarInt(value.group());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
