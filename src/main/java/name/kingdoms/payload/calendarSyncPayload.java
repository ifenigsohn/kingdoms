package name.kingdoms.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import name.kingdoms.Kingdoms;

public record calendarSyncPayload(int year, int month, int day) implements CustomPacketPayload {

    public static final Type<calendarSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "calendar_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, calendarSyncPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeInt(p.year);
                        buf.writeInt(p.month);
                        buf.writeInt(p.day);
                    },
                    (buf) -> new calendarSyncPayload(buf.readInt(), buf.readInt(), buf.readInt())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
