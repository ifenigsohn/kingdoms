package name.kingdoms.blueprint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.List;

public final class RegionActivityState extends SavedData {
    private static final String DATA_NAME = "kingdoms_region_activity";

    // regionKey -> active job count
    private final Long2IntMap active = new Long2IntOpenHashMap();

    public RegionActivityState() {}

    // ---- Codec helpers ----

    private record Entry(long regionKey, int count) {
        static final Codec<Entry> CODEC =
                RecordCodecBuilder.create(inst -> inst.group(
                        Codec.LONG.fieldOf("regionKey").forGetter(Entry::regionKey),
                        Codec.INT.fieldOf("count").forGetter(Entry::count)
                ).apply(inst, Entry::new));
    }

    private List<Entry> snapshot() {
        List<Entry> out = new ArrayList<>(active.size());
        active.long2IntEntrySet().forEach(e -> {
            int c = e.getIntValue();
            if (c > 0) out.add(new Entry(e.getLongKey(), c));
        });
        return out;
    }

    private static final Codec<RegionActivityState> CODEC =
            RecordCodecBuilder.create(inst -> inst.group(
                    Entry.CODEC.listOf().fieldOf("entries").forGetter(RegionActivityState::snapshot)
            ).apply(inst, (entries) -> {
                RegionActivityState s = new RegionActivityState();
                for (Entry e : entries) {
                    if (e.count() > 0) s.active.put(e.regionKey(), e.count());
                }
                return s;
            }));

    // ✅ Match the same SavedDataType pattern that already compiles in your project
    public static final SavedDataType<RegionActivityState> TYPE =
            new SavedDataType<>(
                    DATA_NAME,
                    RegionActivityState::new,
                    CODEC,
                    null
            );

    public static RegionActivityState get(ServerLevel level) {
        // ✅ In your mappings, computeIfAbsent only takes the SavedDataType
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public int getActive(long regionKey) {
        return active.getOrDefault(regionKey, 0);
    }

    /** Call when a region blueprint job is created/enqueued for that region. */
    public void begin(long regionKey) {
        int c = active.getOrDefault(regionKey, 0);
        active.put(regionKey, c + 1);
        setDirty();
    }

    /** Call when that region blueprint job finishes (success OR fail/cancel). */
    public void end(long regionKey) {
        int c = active.getOrDefault(regionKey, 0);
        if (c <= 1) active.remove(regionKey);
        else active.put(regionKey, c - 1);
        setDirty();
    }

    /** Used on load if you want to rebuild counts from persisted queue. */
    public void setCount(long regionKey, int count) {
        if (count <= 0) active.remove(regionKey);
        else active.put(regionKey, count);
        setDirty();
    }
}
