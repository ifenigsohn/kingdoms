package name.kingdoms.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record warOverviewRequestC2SPayload() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<warOverviewRequestC2SPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath("kingdoms", "war_overview_req")
            );

    public static final StreamCodec<RegistryFriendlyByteBuf, warOverviewRequestC2SPayload> STREAM_CODEC =
            StreamCodec.unit(new warOverviewRequestC2SPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
