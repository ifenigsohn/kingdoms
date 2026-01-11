package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record warCommandMoveOrderC2SPayload(BlockPos pos) implements CustomPacketPayload {

    public static final Type<warCommandMoveOrderC2SPayload> TYPE =
            new Type<>(Kingdoms.id("war_cmd_move"));

    public static final StreamCodec<RegistryFriendlyByteBuf, warCommandMoveOrderC2SPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public warCommandMoveOrderC2SPayload decode(RegistryFriendlyByteBuf buf) {
                    return new warCommandMoveOrderC2SPayload(buf.readBlockPos());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, warCommandMoveOrderC2SPayload value) {
                    buf.writeBlockPos(value.pos());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
