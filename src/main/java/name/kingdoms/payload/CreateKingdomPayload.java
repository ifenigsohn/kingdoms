package name.kingdoms.payload;
import name.kingdoms.Kingdoms;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record CreateKingdomPayload(BlockPos origin, String name) implements CustomPacketPayload {

    public static final Type<CreateKingdomPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "create_kingdom"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CreateKingdomPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.origin());
                        buf.writeUtf(p.name(), 32);
                    },
                    (buf) -> new CreateKingdomPayload(
                            buf.readBlockPos(),
                            buf.readUtf(32)
                    )
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}