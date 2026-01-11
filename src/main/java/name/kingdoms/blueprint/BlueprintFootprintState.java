package name.kingdoms.blueprint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted "do not route roads here" blueprint footprints per roadsRegionKey.
 * Stored as XZ rectangles; Y is irrelevant.
 */
public final class BlueprintFootprintState extends SavedData {
    private static final String DATA_NAME = "kingdoms_blueprint_footprints";

    public record Rect(int minX, int minZ, int maxX, int maxZ) {
        public boolean contains(int x, int z, int margin) {
            return x >= (minX - margin) && x <= (maxX + margin)
                && z >= (minZ - margin) && z <= (maxZ + margin);
        }
    }

    // roadsRegionKey -> list of rectangles
    private final Long2ObjectMap<List<Rect>> byRegion = new Long2ObjectOpenHashMap<>();

    // ---------------------------------------------------------------------
    // SavedData wiring (match KingdomsSpawnState style)
    // ---------------------------------------------------------------------

    private static final Codec<Rect> RECT_CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.INT.fieldOf("minX").forGetter(Rect::minX),
            Codec.INT.fieldOf("minZ").forGetter(Rect::minZ),
            Codec.INT.fieldOf("maxX").forGetter(Rect::maxX),
            Codec.INT.fieldOf("maxZ").forGetter(Rect::maxZ)
    ).apply(inst, Rect::new));

    public static final SavedDataType<BlueprintFootprintState> TYPE =
            new SavedDataType<>(
                    DATA_NAME,
                    BlueprintFootprintState::new,
                    ctx -> RecordCodecBuilder.create(instance -> instance.group(
                            Codec.LONG.listOf()
                                    .fieldOf("regionKeys")
                                    .forGetter(s -> {
                                        List<Long> keys = new ArrayList<>();
                                        s.byRegion.keySet().forEach(keys::add);
                                        return keys;
                                    }),
                            Codec.list(RECT_CODEC).listOf()
                                    .fieldOf("rectLists")
                                    .forGetter(s -> {
                                        List<List<Rect>> lists = new ArrayList<>();
                                        s.byRegion.keySet().forEach(k -> lists.add(new ArrayList<>(s.byRegion.get(k))));
                                        return lists;
                                    })
                    ).apply(instance, (regionKeys, rectLists) -> {
                        BlueprintFootprintState s = new BlueprintFootprintState(ctx);
                        int n = Math.min(regionKeys.size(), rectLists.size());
                        for (int i = 0; i < n; i++) {
                            s.byRegion.put(regionKeys.get(i), new ArrayList<>(rectLists.get(i)));
                        }
                        return s;
                    })),
                    DataFixTypes.LEVEL
            );

    public BlueprintFootprintState(SavedData.Context ctx) {
        // ctx.level / ctx.worldSeed available if needed
    }

    public static BlueprintFootprintState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // ---------------------------------------------------------------------
    // API
    // ---------------------------------------------------------------------

    public void addFootprint(long roadsRegionKey, int minX, int minZ, int maxX, int maxZ) {
        byRegion.computeIfAbsent(roadsRegionKey, k -> new ArrayList<>())
                .add(new Rect(minX, minZ, maxX, maxZ));
        setDirty();
    }

    /**
     * Remove an exact rectangle match (used when a blueprint job fails so we don't leave "ghost" blocked zones).
     */
    public boolean removeFootprint(long roadsRegionKey, int minX, int minZ, int maxX, int maxZ) {
        List<Rect> rects = byRegion.get(roadsRegionKey);
        if (rects == null) return false;

        for (int i = 0; i < rects.size(); i++) {
            Rect r = rects.get(i);
            if (r.minX() == minX && r.minZ() == minZ && r.maxX() == maxX && r.maxZ() == maxZ) {
                rects.remove(i);
                if (rects.isEmpty()) byRegion.remove(roadsRegionKey);
                setDirty();
                return true;
            }
        }
        return false;
    }

    public boolean isBlocked(long roadsRegionKey, int x, int z, int margin) {
        List<Rect> rects = byRegion.get(roadsRegionKey);
        if (rects == null) return false;
        for (Rect r : rects) {
            if (r.contains(x, z, margin)) return true;
        }
        return false;
    }

    public int count(long roadsRegionKey) {
        List<Rect> rects = byRegion.get(roadsRegionKey);
        return rects == null ? 0 : rects.size();
    }
}
