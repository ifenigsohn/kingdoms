package name.kingdoms.payload;
import name.kingdoms.Kingdoms;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record kingdomQueryResultPayload(BlockPos origin, boolean exists, String name) implements CustomPacketPayload {

    public static final Type<kingdomQueryResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "kingdom_query_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, kingdomQueryResultPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.origin());
                        buf.writeBoolean(p.exists());
                        buf.writeUtf(p.name(), 32);
                    },
                    (buf) -> new kingdomQueryResultPayload(
                            buf.readBlockPos(),
                            buf.readBoolean(),
                            buf.readUtf(32)
                    )
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
