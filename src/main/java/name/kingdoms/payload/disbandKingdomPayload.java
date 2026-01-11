package name.kingdoms.payload;
import name.kingdoms.Kingdoms;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record disbandKingdomPayload(BlockPos origin) implements CustomPacketPayload {

    public static final Type<disbandKingdomPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "disband_kingdom"));

    public static final StreamCodec<RegistryFriendlyByteBuf, disbandKingdomPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeBlockPos(p.origin()),
                    (buf) -> new disbandKingdomPayload(buf.readBlockPos())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
