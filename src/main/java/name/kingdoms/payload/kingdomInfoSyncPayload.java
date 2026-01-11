package name.kingdoms.payload;
import name.kingdoms.Kingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record kingdomInfoSyncPayload(boolean hasKingdom, String name) implements CustomPacketPayload {

    public static final Type<kingdomInfoSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "kingdom_info_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, kingdomInfoSyncPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBoolean(p.hasKingdom());
                        buf.writeUtf(p.name(), 32);
                    },
                    (buf) -> new kingdomInfoSyncPayload(
                            buf.readBoolean(),
                            buf.readUtf(32)
                    )
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
