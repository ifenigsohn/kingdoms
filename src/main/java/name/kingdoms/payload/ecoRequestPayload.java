package name.kingdoms.payload;
import name.kingdoms.Kingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ecoRequestPayload() implements CustomPacketPayload {
    public static final Type<ecoRequestPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "eco_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ecoRequestPayload> CODEC =
        StreamCodec.unit(new ecoRequestPayload());

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}