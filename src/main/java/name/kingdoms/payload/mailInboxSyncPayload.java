package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import name.kingdoms.diplomacy.Letter;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record mailInboxSyncPayload(List<Letter> inbox) implements CustomPacketPayload {

    // ✅ Snapshot the list to prevent ConcurrentModificationException during Netty encode
    public mailInboxSyncPayload {
        inbox = (inbox == null) ? List.of() : List.copyOf(inbox);
    }

    public static final CustomPacketPayload.Type<mailInboxSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "mail_inbox_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, mailInboxSyncPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public mailInboxSyncPayload decode(RegistryFriendlyByteBuf buf) {
                    int n = buf.readVarInt();
                    ArrayList<Letter> list = new ArrayList<>(Math.max(0, n));
                    for (int i = 0; i < n; i++) {
                        list.add(Letter.CODEC.decode(buf));
                    }
                    return new mailInboxSyncPayload(list);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, mailInboxSyncPayload value) {
                    List<Letter> list = value.inbox(); // immutable snapshot now

                    int count = 0;
                    for (Letter l : list) if (l != null) count++;

                    buf.writeVarInt(count);

                    for (Letter l : list) {
                        if (l == null) continue;
                        Letter.CODEC.encode(buf, l);
                    }
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
