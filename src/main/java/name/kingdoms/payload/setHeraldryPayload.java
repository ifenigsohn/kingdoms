package name.kingdoms.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

public record setHeraldryPayload(ItemStack banner) implements CustomPacketPayload {

    public static final Type<setHeraldryPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("kingdoms", "set_heraldry"));

    public static final StreamCodec<RegistryFriendlyByteBuf, setHeraldryPayload> CODEC =
            StreamCodec.composite(
                    ItemStack.STREAM_CODEC,
                    setHeraldryPayload::banner,
                    setHeraldryPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
