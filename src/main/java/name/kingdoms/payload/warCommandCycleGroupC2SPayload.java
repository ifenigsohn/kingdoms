package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record warCommandCycleGroupC2SPayload() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<warCommandCycleGroupC2SPayload> TYPE =
            new CustomPacketPayload.Type<>(Kingdoms.id("war_cmd_cycle"));

    // empty payload
    public static final StreamCodec<RegistryFriendlyByteBuf, warCommandCycleGroupC2SPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public warCommandCycleGroupC2SPayload decode(RegistryFriendlyByteBuf buf) {
                    return new warCommandCycleGroupC2SPayload();
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, warCommandCycleGroupC2SPayload value) {
                    // empty
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
