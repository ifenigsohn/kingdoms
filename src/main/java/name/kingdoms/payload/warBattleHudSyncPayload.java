package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record warBattleHudSyncPayload(
        boolean active,
        int friendTickets,
        int enemyTickets,
        float friendMorale,
        float enemyMorale
) implements CustomPacketPayload {

    public static final Type<warBattleHudSyncPayload> TYPE =
            new Type<>(Kingdoms.id("war_battle_hud_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, warBattleHudSyncPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public warBattleHudSyncPayload decode(RegistryFriendlyByteBuf buf) {
                    boolean active = buf.readBoolean();
                    int ft = buf.readVarInt();
                    int et = buf.readVarInt();
                    float fm = buf.readFloat();
                    float em = buf.readFloat();
                    return new warBattleHudSyncPayload(active, ft, et, fm, em);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, warBattleHudSyncPayload p) {
                    buf.writeBoolean(p.active());
                    buf.writeVarInt(p.friendTickets());
                    buf.writeVarInt(p.enemyTickets());
                    buf.writeFloat(p.friendMorale());
                    buf.writeFloat(p.enemyMorale());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
