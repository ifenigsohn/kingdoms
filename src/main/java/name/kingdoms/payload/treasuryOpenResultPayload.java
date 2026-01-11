package name.kingdoms.payload;
import name.kingdoms.Kingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record treasuryOpenResultPayload() implements CustomPacketPayload {

    public static final Type<treasuryOpenResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "treasury_open_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, treasuryOpenResultPayload> CODEC =
            StreamCodec.unit(new treasuryOpenResultPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
