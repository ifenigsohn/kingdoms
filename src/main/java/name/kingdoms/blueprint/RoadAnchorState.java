package name.kingdoms.blueprint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.*;

/**
 * Persists road anchors grouped by regionKey.
 * Each successful blueprint placement contributes one anchor (for now).
 */
public final class RoadAnchorState extends SavedData {

    /** One stored anchor record */
    public record Entry(long regionKey, long packedPos) {
        public static final Codec<Entry> CODEC =
                RecordCodecBuilder.create(inst -> inst.group(
                        Codec.LONG.fieldOf("regionKey").forGetter(Entry::regionKey),
                        Codec.LONG.fieldOf("pos").forGetter(Entry::packedPos)
                ).apply(inst, Entry::new));
    }

    // regionKey -> list of packed BlockPos longs
    private final Map<Long, ArrayList<Long>> anchorsByRegion = new HashMap<>();

    public RoadAnchorState() {}

    /** Add an anchor (dedup per region). */
    public void add(long regionKey, BlockPos pos) {
        if (pos == null) return;
        long packed = pos.asLong();

        ArrayList<Long> list = anchorsByRegion.computeIfAbsent(regionKey, k -> new ArrayList<>());
        if (!list.contains(packed)) {
            list.add(packed);
            setDirty();
        }
    }

    /** All anchors for this regionKey (decoded to BlockPos). */
    public List<BlockPos> getAnchors(long regionKey) {
        ArrayList<Long> list = anchorsByRegion.get(regionKey);
        if (list == null || list.isEmpty()) return Collections.emptyList();

        ArrayList<BlockPos> out = new ArrayList<>(list.size());
        for (long packed : list) out.add(BlockPos.of(packed));
        return out;
    }

    public int count(long regionKey) {
        ArrayList<Long> list = anchorsByRegion.get(regionKey);
        return list == null ? 0 : list.size();
    }

    public void clearRegion(long regionKey) {
        if (anchorsByRegion.remove(regionKey) != null) setDirty();
    }

    /** For debugging / serialization */
    public List<Entry> snapshot() {
        ArrayList<Entry> out = new ArrayList<>();
        for (var e : anchorsByRegion.entrySet()) {
            long regionKey = e.getKey();
            for (long packed : e.getValue()) out.add(new Entry(regionKey, packed));
        }
        return out;
    }

    // ---------- CODEC + STORAGE (1.21+) ----------

    private static final Codec<RoadAnchorState> CODEC =
            RecordCodecBuilder.create(inst -> inst.group(
                    Entry.CODEC.listOf().fieldOf("anchors").forGetter(RoadAnchorState::snapshot)
            ).apply(inst, (list) -> {
                RoadAnchorState s = new RoadAnchorState();
                for (Entry e : list) {
                    s.anchorsByRegion
                            .computeIfAbsent(e.regionKey(), k -> new ArrayList<>())
                            .add(e.packedPos());
                }
                return s;
            }));

    private static final SavedDataType<RoadAnchorState> TYPE =
            new SavedDataType<RoadAnchorState>(
                    "kingdoms_road_anchor_state",
                    RoadAnchorState::new,
                    CODEC,
                    null
            );

    public static RoadAnchorState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }
}
