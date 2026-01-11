package name.kingdoms.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record mailActionC2SPayload(UUID letterId, Action action) implements CustomPacketPayload {

    public enum Action { ACCEPT, REFUSE, ACKNOWLEDGE }

    public static final CustomPacketPayload.Type<mailActionC2SPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("kingdoms", "mail_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, mailActionC2SPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeUUID(p.letterId());
                        buf.writeEnum(p.action());
                    },
                    (buf) -> new mailActionC2SPayload(
                            buf.readUUID(),
                            buf.readEnum(Action.class)
                    )
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
