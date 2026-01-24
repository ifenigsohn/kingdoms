package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record toggleRetinueC2SPayload(boolean enabled) implements CustomPacketPayload {

    public static final Type<toggleRetinueC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "toggle_retinue"));

    public static final StreamCodec<RegistryFriendlyByteBuf, toggleRetinueC2SPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeBoolean(p.enabled),
                    buf -> new toggleRetinueC2SPayload(buf.readBoolean())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
