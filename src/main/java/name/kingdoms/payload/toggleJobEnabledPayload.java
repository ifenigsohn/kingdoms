package name.kingdoms.payload;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record toggleJobEnabledPayload(BlockPos pos) implements CustomPacketPayload {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath("kingdoms", "toggle_job_enabled_c2s");

    public static final Type<toggleJobEnabledPayload> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, toggleJobEnabledPayload> CODEC =
            StreamCodec.of(toggleJobEnabledPayload::write, toggleJobEnabledPayload::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(RegistryFriendlyByteBuf buf, toggleJobEnabledPayload p) {
        buf.writeBlockPos(p.pos());
    }

    private static toggleJobEnabledPayload read(RegistryFriendlyByteBuf buf) {
        return new toggleJobEnabledPayload(buf.readBlockPos());
    }
}
