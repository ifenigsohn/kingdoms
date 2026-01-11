package name.kingdoms.payload;

import name.kingdoms.Kingdoms;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record treasuryShopSyncPayload(List<Entry> entries) implements CustomPacketPayload {

    public record Entry(
            String jobId,
            double costGold, double costMeat, double costGrain, double costFish,
            double costWood, double costMetal, double costArmor, double costWeapons,
            double costGems, double costHorses, double costPotions
    ) {}

    public static final Type<treasuryShopSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "treasury_shop_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, treasuryShopSyncPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarInt(p.entries().size());
                        for (Entry e : p.entries()) {
                            buf.writeUtf(e.jobId());
                            buf.writeDouble(e.costGold());   buf.writeDouble(e.costMeat());   buf.writeDouble(e.costGrain()); buf.writeDouble(e.costFish());
                            buf.writeDouble(e.costWood());   buf.writeDouble(e.costMetal());  buf.writeDouble(e.costArmor()); buf.writeDouble(e.costWeapons());
                            buf.writeDouble(e.costGems());   buf.writeDouble(e.costHorses()); buf.writeDouble(e.costPotions());
                        }
                    },
                    (buf) -> {
                        int n = buf.readVarInt();
                        List<Entry> out = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            String jobId = buf.readUtf();
                            out.add(new Entry(
                                    jobId,
                                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                                    buf.readDouble(), buf.readDouble(), buf.readDouble()
                            ));
                        }
                        return new treasuryShopSyncPayload(out);
                    }
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
