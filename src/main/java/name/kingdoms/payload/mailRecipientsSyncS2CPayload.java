package name.kingdoms.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;      
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import name.kingdoms.Kingdoms;

import net.minecraft.world.item.ItemStack;             

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record mailRecipientsSyncS2CPayload(List<Entry> recipients) implements CustomPacketPayload {

    
    public record Entry(UUID kingdomId, String name, boolean isAi, int relation, int headSkinId, ItemStack heraldry) {}

    public static final Type<mailRecipientsSyncS2CPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "mail_recipients_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, mailRecipientsSyncS2CPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public mailRecipientsSyncS2CPayload decode(RegistryFriendlyByteBuf buf) {
                    int n = buf.readVarInt();
                    List<Entry> list = new ArrayList<>(Math.max(0, n));
                    for (int i = 0; i < n; i++) {
                        UUID id = readUUID(buf);
                        String name = buf.readUtf(32767);

                        boolean isAi = buf.readBoolean();
                        int rel = buf.readVarInt();
                        int headSkinId = buf.readVarInt();
                        ItemStack heraldry = ItemStack.STREAM_CODEC.decode(buf);
                        list.add(new Entry(id, name, isAi, rel, headSkinId, heraldry));

                    }
                    return new mailRecipientsSyncS2CPayload(list);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, mailRecipientsSyncS2CPayload value) {
                    List<Entry> list = value.recipients();
                    buf.writeVarInt(list.size());
                    for (Entry e : list) {
                        writeUUID(buf, e.kingdomId());
                        buf.writeUtf(e.name(), 32767);

                        // encode
                        buf.writeBoolean(e.isAi());
                        buf.writeVarInt(e.relation());
                        buf.writeVarInt(e.headSkinId());
                        ItemStack s = (e.heraldry() == null) ? ItemStack.EMPTY : e.heraldry();
                        ItemStack.STREAM_CODEC.encode(buf, s);

                    }
                }

                private UUID readUUID(RegistryFriendlyByteBuf buf) {
                    long most = buf.readLong();
                    long least = buf.readLong();
                    return new UUID(most, least);
                }

                private void writeUUID(RegistryFriendlyByteBuf buf, UUID id) {
                    buf.writeLong(id.getMostSignificantBits());
                    buf.writeLong(id.getLeastSignificantBits());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
