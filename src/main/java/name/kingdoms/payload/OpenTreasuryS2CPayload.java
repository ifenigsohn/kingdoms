package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record OpenTreasuryS2CPayload(BlockPos pos) implements CustomPacketPayload {

    public static final Type<OpenTreasuryS2CPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "open_treasury"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenTreasuryS2CPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeBlockPos(p.pos()),
                    (buf) -> new OpenTreasuryS2CPayload(buf.readBlockPos())
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
