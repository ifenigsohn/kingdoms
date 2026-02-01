package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record KingdomEventsRequestC2SPayload() implements CustomPacketPayload {

    public static final Type<KingdomEventsRequestC2SPayload> TYPE =
            new Type<>(Kingdoms.id("kingdom_events_request_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KingdomEventsRequestC2SPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override public KingdomEventsRequestC2SPayload decode(RegistryFriendlyByteBuf buf) {
                    return new KingdomEventsRequestC2SPayload();
                }
                @Override public void encode(RegistryFriendlyByteBuf buf, KingdomEventsRequestC2SPayload value) { }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
