package name.kingdoms.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record kingdomHoverRequestC2SPayload(UUID kingdomId) implements CustomPacketPayload {

    public static final Type<kingdomHoverRequestC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("kingdoms", "kingdom_hover_req"));

    public static final StreamCodec<RegistryFriendlyByteBuf, kingdomHoverRequestC2SPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeUUID(payload.kingdomId()),
                    (buf) -> new kingdomHoverRequestC2SPayload(buf.readUUID())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
