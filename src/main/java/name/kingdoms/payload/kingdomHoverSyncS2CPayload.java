package name.kingdoms.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record kingdomHoverSyncS2CPayload(
        UUID kingdomId,
        String kingdomName,

        UUID rulerId,
        String rulerName,

        int relation,

        int soldiersAlive,
        int soldiersMax,

        int ticketsAlive,
        int ticketsMax,

        double gold,
        double meat, double grain, double fish,
        double wood, double metal,
        double armor, double weapons,
        double gems, double horses, double potions,

        // ✅ NEW: AI flag + skin id (0..9)
        boolean isAi,
        int aiSkinId
) implements CustomPacketPayload {

    public static final Type<kingdomHoverSyncS2CPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("kingdoms", "kingdom_hover_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, kingdomHoverSyncS2CPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeUUID(p.kingdomId());
                        buf.writeUtf(p.kingdomName());

                        buf.writeUUID(p.rulerId());
                        buf.writeUtf(p.rulerName());

                        buf.writeVarInt(p.relation());

                        buf.writeVarInt(p.soldiersAlive());
                        buf.writeVarInt(p.soldiersMax());

                        buf.writeVarInt(p.ticketsAlive());
                        buf.writeVarInt(p.ticketsMax());

                        buf.writeDouble(p.gold());
                        buf.writeDouble(p.meat());
                        buf.writeDouble(p.grain());
                        buf.writeDouble(p.fish());
                        buf.writeDouble(p.wood());
                        buf.writeDouble(p.metal());
                        buf.writeDouble(p.armor());
                        buf.writeDouble(p.weapons());
                        buf.writeDouble(p.gems());
                        buf.writeDouble(p.horses());
                        buf.writeDouble(p.potions());

                        // ✅ AI info
                        buf.writeBoolean(p.isAi());
                        buf.writeVarInt(p.aiSkinId());
                    },
                    (buf) -> new kingdomHoverSyncS2CPayload(
                            buf.readUUID(),
                            buf.readUtf(),

                            buf.readUUID(),
                            buf.readUtf(),

                            buf.readVarInt(),

                            buf.readVarInt(),
                            buf.readVarInt(),

                            buf.readVarInt(),
                            buf.readVarInt(),

                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),

                            // ✅ AI info
                            buf.readBoolean(),
                            buf.readVarInt()
                    )
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
