package name.kingdoms.payload;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record openKingdomMenuPayload(BlockPos pos) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<openKingdomMenuPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath("kingdoms", "open_kingdom_menu")
            );

    public static final StreamCodec<FriendlyByteBuf, openKingdomMenuPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeBlockPos(payload.pos()),
                    buf -> new openKingdomMenuPayload(buf.readBlockPos())
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
