package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import name.kingdoms.diplomacy.Letter;
import name.kingdoms.diplomacy.ResourceType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record mailSendC2SPayload(
        UUID requestId,               // client-generated UUID for matching send result
        UUID toKingdomId,              // recipient AI kingdom id
        Letter.Kind kind,              // REQUEST / OFFER / CONTRACT / COMPLIMENT / INSULT / WARNING / ULTIMATUM / WAR_DECLARATION
        ResourceType aType,
        double aAmount,
        ResourceType bType,            // only used for CONTRACT, else null
        double bAmount,
        double maxAmount,              // only used for CONTRACT (cap), else 0

        // NEW
        Letter.CasusBelli cb,          // only used for WAR_DECLARATION, else null
        String note,                   // optional message for soft letters / war flavor
        long overrideExpiresTick       // for ULTIMATUM deadline; 0 means "server chooses"
) implements CustomPacketPayload {

    public static final Type<mailSendC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "mail_send"));

    private static final ResourceType BT_SENTINEL = ResourceType.GOLD;
    private static final Letter.CasusBelli CB_SENTINEL = Letter.CasusBelli.UNKNOWN;

    public static final StreamCodec<RegistryFriendlyByteBuf, mailSendC2SPayload> CODEC =
            StreamCodec.of(
                    // encode
                    (buf, p) -> {
                        buf.writeUUID(p.requestId());
                        buf.writeUUID(p.toKingdomId());

                        buf.writeEnum(p.kind());

                        buf.writeEnum(p.aType());
                        buf.writeDouble(p.aAmount());

                        boolean hasB = (p.kind() == Letter.Kind.CONTRACT) && (p.bType() != null);
                        buf.writeBoolean(hasB);
                        buf.writeEnum(hasB ? p.bType() : BT_SENTINEL);

                        buf.writeDouble(p.bAmount());
                        buf.writeDouble(p.maxAmount());

                        // NEW: cb (nullable)
                        boolean hasCb = (p.cb() != null);
                        buf.writeBoolean(hasCb);
                        buf.writeEnum(hasCb ? p.cb() : CB_SENTINEL);

                        // NEW: note (always written, normalized)
                        buf.writeUtf(p.note() == null ? "" : p.note(), 32767);

                        // NEW: override expires tick
                        buf.writeLong(p.overrideExpiresTick());
                    },
                    // decode
                    (buf) -> {
                        UUID requestId = buf.readUUID();
                        UUID toKingdom = buf.readUUID();

                        Letter.Kind kind = buf.readEnum(Letter.Kind.class);

                        ResourceType aType = buf.readEnum(ResourceType.class);
                        double aAmount = buf.readDouble();

                        boolean hasB = buf.readBoolean();
                        ResourceType bTypeRaw = buf.readEnum(ResourceType.class);

                        double bAmount = buf.readDouble();
                        double maxAmt = buf.readDouble();

                        ResourceType bType = (kind == Letter.Kind.CONTRACT && hasB) ? bTypeRaw : null;

                        boolean hasCb = buf.readBoolean();
                        Letter.CasusBelli cbRaw = buf.readEnum(Letter.CasusBelli.class);
                        Letter.CasusBelli cb = hasCb ? cbRaw : null;

                        String note = buf.readUtf(32767);
                        if (note == null) note = "";

                        long overrideExpiresTick = buf.readLong();

                        return new mailSendC2SPayload(
                                requestId,
                                toKingdom,
                                kind,
                                aType,
                                aAmount,
                                bType,
                                bAmount,
                                maxAmt,
                                cb,
                                note,
                                overrideExpiresTick
                        );
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
