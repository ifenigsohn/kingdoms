package name.kingdoms.payload;
import name.kingdoms.Kingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record kingdomInfoRequestPayload() implements CustomPacketPayload {

    public static final Type<kingdomInfoRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "kingdom_info_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, kingdomInfoRequestPayload> CODEC =
            StreamCodec.unit(new kingdomInfoRequestPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
