package name.kingdoms.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record warOverviewSyncS2CPayload(
        int yourAlive, int yourMax,
        double potions,
         int soldierSkinId, 
        List<Entry> allies,
        List<Entry> enemies
) implements CustomPacketPayload {

    public record Entry(UUID kingdomId, String name, int alive, int max) {}

    public static final CustomPacketPayload.Type<warOverviewSyncS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath("kingdoms", "war_overview_sync")
            );

    public static final StreamCodec<RegistryFriendlyByteBuf, warOverviewSyncS2CPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public warOverviewSyncS2CPayload decode(RegistryFriendlyByteBuf buf) {
                    int yourAlive = buf.readInt();
                    int yourMax = buf.readInt();
                    double potions = buf.readDouble();
                    int soldierSkinId = buf.readInt();


                    int aN = buf.readVarInt();
                    List<Entry> allies = new ArrayList<>(aN);
                    for (int i = 0; i < aN; i++) {
                        UUID id = buf.readUUID();
                        String name = buf.readUtf(32767);
                        int alive = buf.readInt();
                        int max = buf.readInt();
                        allies.add(new Entry(id, name, alive, max));
                    }

                    int eN = buf.readVarInt();
                    List<Entry> enemies = new ArrayList<>(eN);
                    for (int i = 0; i < eN; i++) {
                        UUID id = buf.readUUID();
                        String name = buf.readUtf(32767);
                        int alive = buf.readInt();
                        int max = buf.readInt();
                        enemies.add(new Entry(id, name, alive, max));
                    }

                    return new warOverviewSyncS2CPayload(yourAlive, yourMax, potions, soldierSkinId, allies, enemies);

                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, warOverviewSyncS2CPayload value) {
                    buf.writeInt(value.yourAlive());
                    buf.writeInt(value.yourMax());
                    buf.writeDouble(value.potions());
                    buf.writeInt(value.soldierSkinId());

                    buf.writeVarInt(value.allies().size());
                    for (Entry e : value.allies()) {
                        buf.writeUUID(e.kingdomId());
                        buf.writeUtf(e.name(), 32767);
                        buf.writeInt(e.alive());
                        buf.writeInt(e.max());
                    }

                    buf.writeVarInt(value.enemies().size());
                    for (Entry e : value.enemies()) {
                        buf.writeUUID(e.kingdomId());
                        buf.writeUtf(e.name(), 32767);
                        buf.writeInt(e.alive());
                        buf.writeInt(e.max());
                    }
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
