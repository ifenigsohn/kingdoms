package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record diploActionC2SPayload(int entityId, byte actionId) implements CustomPacketPayload {

    public static final Type<diploActionC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "diplo_action_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, diploActionC2SPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, diploActionC2SPayload::entityId,
                    ByteBufCodecs.BYTE, diploActionC2SPayload::actionId,
                    diploActionC2SPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static final class Actions {
        public static final byte TRADE = 1;
        public static final byte PAY_TRIBUTE = 2;
        public static final byte ASK_PROTECTION = 3;
        public static final byte COMPLIMENT = 4;
        public static final byte INSULT = 5;

        // NEW (so diploScreen compiles)
        public static final byte ALLIANCE_PROPOSAL = 6;
        public static final byte DECLARE_WAR = 7;
        public static final byte WHITE_PEACE = 8;
        public static final byte SURRENDER = 9;

        // If you still use this from earlier:
        public static final byte REQUEST_PROPOSAL = 10;

        private Actions() {}
    }

}
