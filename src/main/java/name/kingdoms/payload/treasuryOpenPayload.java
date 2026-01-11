package name.kingdoms.payload;
import name.kingdoms.Kingdoms;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record treasuryOpenPayload(BlockPos pos) implements CustomPacketPayload {



    public static final Type<treasuryOpenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "treasury_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, treasuryOpenPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeBlockPos(p.pos()),
                    buf -> new treasuryOpenPayload(buf.readBlockPos())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}




