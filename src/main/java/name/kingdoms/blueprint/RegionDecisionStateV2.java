package name.kingdoms.blueprint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted per-region decision + plan.
 *
 * RegionKey: pack(rx,rz) (overworld only).
 *
 * Status:
 *  UNKNOWN     : no decision yet
 *  LOSE        : never spawn in this region
 *  WIN_PENDING : will spawn (planned), not placed yet
 *  WIN_PLACING : enqueued / in progress
 *  WIN_SPAWNED : placed
 */
public final class RegionDecisionStateV2 extends SavedData {

    public enum Status {
        UNKNOWN((byte)0),
        LOSE((byte)1),
        WIN_PENDING((byte)2),
        WIN_PLACING((byte)3),
        WIN_SPAWNED((byte)4);

        public final byte id;
        Status(byte id) { this.id = id; }

        public static Status fromId(byte b) {
            return switch (b) {
                case 1 -> LOSE;
                case 2 -> WIN_PENDING;
                case 3 -> WIN_PLACING;
                case 4 -> WIN_SPAWNED;
                default -> UNKNOWN;
            };
        }
    }

    /** One record per regionKey. */
    public record Entry(
            long regionKey,
            int rx, int rz,
            byte status,
            int plannedX, int plannedZ,
            String bpId
    ) {
        public Status statusEnum() { return Status.fromId(status); }

        public static final Codec<Entry> CODEC =
                RecordCodecBuilder.create(inst -> inst.group(
                        Codec.LONG.fieldOf("k").forGetter(Entry::regionKey),
                        Codec.INT.fieldOf("rx").forGetter(Entry::rx),
                        Codec.INT.fieldOf("rz").forGetter(Entry::rz),
                        Codec.BYTE.fieldOf("s").forGetter(Entry::status),
                        Codec.INT.fieldOf("x").forGetter(Entry::plannedX),
                        Codec.INT.fieldOf("z").forGetter(Entry::plannedZ),
                        Codec.STRING.fieldOf("bp").forGetter(Entry::bpId)
                ).apply(inst, Entry::new));
    }

    private final Long2ObjectMap<Entry> map = new Long2ObjectOpenHashMap<>();

    public Entry get(long regionKey) {
        return map.get(regionKey);
    }

    public Status getStatus(long regionKey) {
        Entry e = map.get(regionKey);
        return (e == null) ? Status.UNKNOWN : e.statusEnum();
    }

    public void put(Entry e) {
        map.put(e.regionKey(), e);
        setDirty();
    }

    public void setStatus(long regionKey, Status st) {
        Entry e = map.get(regionKey);
        if (e == null) return;
        if (e.status == st.id) return;
        map.put(regionKey, new Entry(e.regionKey, e.rx, e.rz, st.id, e.plannedX, e.plannedZ, e.bpId));
        setDirty();
    }

    public void remove(long regionKey) {
        if (map.remove(regionKey) != null) setDirty();
    }

    /** For iterating all entries (e.g. rebuild pending queue on startup). */
    public Iterable<Entry> entries() {
        return map.values();
    }

    // ----- Persistence -----

    private List<Entry> snapshot() {
        ArrayList<Entry> out = new ArrayList<>(map.size());
        for (Entry e : map.values()) out.add(e);
        return out;
    }

    private static final Codec<RegionDecisionStateV2> CODEC =
            RecordCodecBuilder.create(inst -> inst.group(
                    Entry.CODEC.listOf().fieldOf("entries").forGetter(RegionDecisionStateV2::snapshot)
            ).apply(inst, (list) -> {
                RegionDecisionStateV2 s = new RegionDecisionStateV2();
                for (Entry e : list) s.map.put(e.regionKey(), e);
                return s;
            }));

    private static final SavedDataType<RegionDecisionStateV2> TYPE =
            new SavedDataType<>(
                    "kingdoms_region_decisions_v2",
                    RegionDecisionStateV2::new,
                    CODEC,
                    null
            );

    public static RegionDecisionStateV2 get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }
}
