package name.kingdoms.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record diplomacyFreezeC2SPayload(int entityId, boolean open) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<diplomacyFreezeC2SPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath("kingdoms", "diplomacy_freeze_c2s")
            );

    public static final StreamCodec<RegistryFriendlyByteBuf, diplomacyFreezeC2SPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, diplomacyFreezeC2SPayload::entityId,
                    ByteBufCodecs.BOOL, diplomacyFreezeC2SPayload::open,
                    diplomacyFreezeC2SPayload::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
