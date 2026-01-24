package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record requestProposalC2SPayload(UUID toKingdomId) implements CustomPacketPayload {

    public static final Type<requestProposalC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "request_proposal"));

    public static final StreamCodec<FriendlyByteBuf, requestProposalC2SPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeUUID(p.toKingdomId),
                    buf -> new requestProposalC2SPayload(buf.readUUID())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
