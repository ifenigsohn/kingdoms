package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record KingdomEventsSyncS2CPayload(List<Entry> events) implements CustomPacketPayload {

    public record Entry(
            String typeId,
            UUID causer,
            String scope, // "GLOBAL" or "CAUSER_ONLY"
            double econMult,
            double happinessDelta,
            double securityDelta,
            int relationsDelta,
            int secondsRemaining
    ) {}

    public static final Type<KingdomEventsSyncS2CPayload> TYPE =
            new Type<>(Kingdoms.id("kingdom_events_sync_s2c"));

    private static UUID readUUID(RegistryFriendlyByteBuf buf) {
        return new UUID(buf.readLong(), buf.readLong());
    }
    private static void writeUUID(RegistryFriendlyByteBuf buf, UUID id) {
        buf.writeLong(id.getMostSignificantBits());
        buf.writeLong(id.getLeastSignificantBits());
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, KingdomEventsSyncS2CPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public KingdomEventsSyncS2CPayload decode(RegistryFriendlyByteBuf buf) {
                    int n = buf.readVarInt();
                    List<Entry> list = new ArrayList<>(Math.max(0, n));
                    for (int i = 0; i < n; i++) {
                        String typeId = buf.readUtf(32767);
                        UUID causer = readUUID(buf);
                        String scope = buf.readUtf(64);

                        double econMult = buf.readDouble();
                        double hap = buf.readDouble();
                        double sec = buf.readDouble();
                        int rel = buf.readVarInt();

                        int secs = buf.readVarInt();

                        list.add(new Entry(typeId, causer, scope, econMult, hap, sec, rel, secs));
                    }
                    return new KingdomEventsSyncS2CPayload(list);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, KingdomEventsSyncS2CPayload value) {
                    List<Entry> list = (value.events() == null) ? List.of() : value.events();
                    buf.writeVarInt(list.size());
                    for (Entry e : list) {
                        buf.writeUtf(e.typeId() == null ? "" : e.typeId(), 32767);
                        writeUUID(buf, e.causer() == null ? new UUID(0L,0L) : e.causer());
                        buf.writeUtf(e.scope() == null ? "" : e.scope(), 64);

                        buf.writeDouble(e.econMult());
                        buf.writeDouble(e.happinessDelta());
                        buf.writeDouble(e.securityDelta());
                        buf.writeVarInt(e.relationsDelta());

                        buf.writeVarInt(e.secondsRemaining());
                    }
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
