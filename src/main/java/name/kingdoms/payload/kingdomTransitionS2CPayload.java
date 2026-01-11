package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record kingdomTransitionS2CPayload(boolean entering, String kingdomName)
        implements CustomPacketPayload {

    public static final Type<kingdomTransitionS2CPayload> TYPE =
            new Type<>(Kingdoms.id("kingdom_transition"));

    public static final StreamCodec<RegistryFriendlyByteBuf, kingdomTransitionS2CPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, kingdomTransitionS2CPayload::entering,
                    ByteBufCodecs.STRING_UTF8, kingdomTransitionS2CPayload::kingdomName,
                    kingdomTransitionS2CPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
