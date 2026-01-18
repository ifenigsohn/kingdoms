package name.kingdoms.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record bordersSyncPayload(List<Entry> entries) implements CustomPacketPayload {

    public record Entry(
            java.util.UUID id,
            String name,
            boolean hasBorder,
            int minX, int maxX,
            int minZ, int maxZ,
            int colorARGB,
            boolean isYours
    ) {}

    public static final Type<bordersSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("kingdoms", "borders_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, bordersSyncPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.entries().size());
                        for (Entry e : payload.entries()) {
                            buf.writeUUID(e.id());
                            buf.writeUtf(e.name());
                            buf.writeBoolean(e.hasBorder());      // NEW
                            buf.writeInt(e.minX());
                            buf.writeInt(e.maxX());
                            buf.writeInt(e.minZ());
                            buf.writeInt(e.maxZ());
                            buf.writeInt(e.colorARGB());
                            buf.writeBoolean(e.isYours());
                        }
                    },
                    (buf) -> {
                        int n = buf.readVarInt();
                        List<Entry> out = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            java.util.UUID id = buf.readUUID();

                            out.add(new Entry(
                                    id,
                                    buf.readUtf(),
                                    buf.readBoolean(),           // NEW
                                    buf.readInt(), buf.readInt(),
                                    buf.readInt(), buf.readInt(),
                                    buf.readInt(),
                                    buf.readBoolean()
                            ));
                        }
                        return new bordersSyncPayload(out);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
