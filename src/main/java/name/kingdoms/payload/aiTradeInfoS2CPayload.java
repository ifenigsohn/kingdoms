package name.kingdoms.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record aiTradeInfoS2CPayload(
        String name,
        int happiness,
        int security,
        double gold, double wood, double metal, double gems,
        double potions, double armor, double horses, double weapons,
        boolean hasBorder, int minX, int maxX, int minZ, int maxZ
) implements CustomPacketPayload {

    public static final Type<aiTradeInfoS2CPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("kingdoms", "ai_trade_info_s2c"));

    public static final StreamCodec<RegistryFriendlyByteBuf, aiTradeInfoS2CPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public aiTradeInfoS2CPayload decode(RegistryFriendlyByteBuf buf) {
                    String name = buf.readUtf();
                    int happiness = buf.readVarInt();
                    int security = buf.readVarInt();

                    double gold = buf.readDouble();
                    double wood = buf.readDouble();
                    double metal = buf.readDouble();
                    double gems = buf.readDouble();
                    double potions = buf.readDouble();
                    double armor = buf.readDouble();
                    double horses = buf.readDouble();
                    double weapons = buf.readDouble();

                    boolean hasBorder = buf.readBoolean();
                    int minX = buf.readVarInt();
                    int maxX = buf.readVarInt();
                    int minZ = buf.readVarInt();
                    int maxZ = buf.readVarInt();

                    return new aiTradeInfoS2CPayload(
                            name, happiness, security,
                            gold, wood, metal, gems,
                            potions, armor, horses, weapons,
                            hasBorder, minX, maxX, minZ, maxZ
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, aiTradeInfoS2CPayload p) {
                    buf.writeUtf(p.name());
                    buf.writeVarInt(p.happiness());
                    buf.writeVarInt(p.security());

                    buf.writeDouble(p.gold());
                    buf.writeDouble(p.wood());
                    buf.writeDouble(p.metal());
                    buf.writeDouble(p.gems());
                    buf.writeDouble(p.potions());
                    buf.writeDouble(p.armor());
                    buf.writeDouble(p.horses());
                    buf.writeDouble(p.weapons());

                    buf.writeBoolean(p.hasBorder());
                    buf.writeVarInt(p.minX());
                    buf.writeVarInt(p.maxX());
                    buf.writeVarInt(p.minZ());
                    buf.writeVarInt(p.maxZ());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
