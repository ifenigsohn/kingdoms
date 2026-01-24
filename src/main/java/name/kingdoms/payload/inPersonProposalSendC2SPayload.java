package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import name.kingdoms.diplomacy.Letter;
import name.kingdoms.diplomacy.ResourceType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record inPersonProposalSendC2SPayload(
        UUID requestId,
        UUID toKingdomId,
        int kingEntityId,
        Letter.Kind kind,
        ResourceType aType,
        double aAmount,
        ResourceType bType,
        double bAmount,
        double maxAmount,
        Letter.CasusBelli cb,
        String note
) implements CustomPacketPayload {

    public static final Type<inPersonProposalSendC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "inperson_proposal_send"));

    public static final StreamCodec<FriendlyByteBuf, inPersonProposalSendC2SPayload> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {
                buf.writeUUID(p.requestId());
                buf.writeUUID(p.toKingdomId());
                buf.writeInt(p.kingEntityId());

                buf.writeEnum(p.kind());
                buf.writeEnum(p.aType());
                buf.writeDouble(p.aAmount());

                buf.writeBoolean(p.bType() != null);
                if (p.bType() != null) buf.writeEnum(p.bType());
                buf.writeDouble(p.bAmount());
                buf.writeDouble(p.maxAmount());

                buf.writeBoolean(p.cb() != null);
                if (p.cb() != null) buf.writeEnum(p.cb());

                buf.writeUtf(p.note() == null ? "" : p.note(), 2048);
            }, buf -> {
                UUID reqId = buf.readUUID();
                UUID toKid = buf.readUUID();
                int kingEid = buf.readInt();

                Letter.Kind kind = buf.readEnum(Letter.Kind.class);
                ResourceType aType = buf.readEnum(ResourceType.class);
                double aAmt = buf.readDouble();

                ResourceType bType = buf.readBoolean() ? buf.readEnum(ResourceType.class) : null;
                double bAmt = buf.readDouble();
                double maxAmt = buf.readDouble();

                Letter.CasusBelli cb = buf.readBoolean() ? buf.readEnum(Letter.CasusBelli.class) : null;

                String note = buf.readUtf(2048);

                return new inPersonProposalSendC2SPayload(
                        reqId, toKid, kingEid,
                        kind, aType, aAmt,
                        bType, bAmt, maxAmt,
                        cb, note
                );

            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
