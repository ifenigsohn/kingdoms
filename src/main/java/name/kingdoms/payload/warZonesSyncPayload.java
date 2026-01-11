package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record warZonesSyncPayload(List<Entry> zones) implements CustomPacketPayload {
    public static final Type<warZonesSyncPayload> TYPE =
            new Type<>(Kingdoms.id("war_zones_sync"));

    public record Entry(UUID enemyId, String enemyName, int minX, int minZ, int maxX, int maxZ) {}

    // ---- Entry codec (manual) ----
    public static final StreamCodec<RegistryFriendlyByteBuf, Entry> ENTRY_CODEC =
            new StreamCodec<>() {
                @Override
                public Entry decode(RegistryFriendlyByteBuf buf) {
                    UUID enemyId = buf.readUUID();
                    String enemyName = buf.readUtf(64);
                    int minX = buf.readVarInt();
                    int minZ = buf.readVarInt();
                    int maxX = buf.readVarInt();
                    int maxZ = buf.readVarInt();
                    return new Entry(enemyId, enemyName, minX, minZ, maxX, maxZ);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, Entry e) {
                    buf.writeUUID(e.enemyId());
                    buf.writeUtf(e.enemyName(), 64);
                    buf.writeVarInt(e.minX());
                    buf.writeVarInt(e.minZ());
                    buf.writeVarInt(e.maxX());
                    buf.writeVarInt(e.maxZ());
                }
            };

    // ---- Payload codec (manual list) ----
    public static final StreamCodec<RegistryFriendlyByteBuf, warZonesSyncPayload> CODEC =
            new StreamCodec<>() {
                private static final int MAX_ZONES = 256;

                @Override
                public warZonesSyncPayload decode(RegistryFriendlyByteBuf buf) {
                    int n = buf.readVarInt();
                    if (n < 0 || n > MAX_ZONES) n = 0;

                    List<Entry> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        list.add(ENTRY_CODEC.decode(buf));
                    }
                    return new warZonesSyncPayload(list);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, warZonesSyncPayload payload) {
                    List<Entry> list = payload.zones();
                    int n = Math.min(list.size(), MAX_ZONES);
                    buf.writeVarInt(n);
                    for (int i = 0; i < n; i++) {
                        ENTRY_CODEC.encode(buf, list.get(i));
                    }
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
