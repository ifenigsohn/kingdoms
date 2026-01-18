package name.kingdoms.payload;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record royalGuardToggleC2SPayload(boolean enabled) implements CustomPacketPayload {

    public static final Type<royalGuardToggleC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("kingdoms", "royal_guard_toggle"));

    public static final StreamCodec<net.minecraft.network.FriendlyByteBuf, royalGuardToggleC2SPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, royalGuardToggleC2SPayload::enabled,
                    royalGuardToggleC2SPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
