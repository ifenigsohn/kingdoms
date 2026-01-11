package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record treasuryBuyJobPayload(BlockPos treasuryPos, String jobId, int qty) implements CustomPacketPayload {

    public static final Type<treasuryBuyJobPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "treasury_buy_job"));

    public static final StreamCodec<RegistryFriendlyByteBuf, treasuryBuyJobPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.treasuryPos());
                        buf.writeUtf(p.jobId());
                        buf.writeVarInt(p.qty());
                    },
                    (buf) -> new treasuryBuyJobPayload(
                            buf.readBlockPos(),
                            buf.readUtf(),
                            buf.readVarInt()
                    )
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
