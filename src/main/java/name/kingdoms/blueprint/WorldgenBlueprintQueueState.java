package name.kingdoms.blueprint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class WorldgenBlueprintQueueState extends SavedData {
    private static final String ID = "kingdoms_worldgen_blueprint_queue";

    public static final class Entry {
        public String dimId;     // e.g. "minecraft:overworld"
        public long regionKey;
        public String modId;
        public String blueprintId;
        public int x, y, z;
        public boolean includeAir;

        // ✅ Codec for Entry (SavedData is codec-driven in your mappings)
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Codec.STRING.fieldOf("dim").forGetter(e -> e.dimId),
                Codec.LONG.fieldOf("region").forGetter(e -> e.regionKey),
                Codec.STRING.fieldOf("mod").forGetter(e -> e.modId),
                Codec.STRING.fieldOf("bp").forGetter(e -> e.blueprintId),
                Codec.INT.fieldOf("x").forGetter(e -> e.x),
                Codec.INT.fieldOf("y").forGetter(e -> e.y),
                Codec.INT.fieldOf("z").forGetter(e -> e.z),
                Codec.BOOL.fieldOf("air").forGetter(e -> e.includeAir)
        ).apply(inst, (dim, region, mod, bp, x, y, z, air) -> {
            Entry e = new Entry();
            e.dimId = dim;
            e.regionKey = region;
            e.modId = mod;
            e.blueprintId = bp;
            e.x = x; e.y = y; e.z = z;
            e.includeAir = air;
            return e;
        }));
    }

    private final List<Entry> entries = new ArrayList<>();

    // ✅ Codec for the whole SavedData
    private static final Codec<WorldgenBlueprintQueueState> CODEC =
            Entry.CODEC.listOf()
                    .fieldOf("entries")
                    .xmap(list -> {
                        WorldgenBlueprintQueueState s = new WorldgenBlueprintQueueState();
                        s.entries.addAll(list);
                        return s;
                    }, s -> s.entries)
                    .codec();

    /**
     * ✅ SavedDataType in your mappings requires: (id, supplier, codec, dataFixType)
     *
     * DataFixTypes: use SAVED_DATA if present in your enum; otherwise LEVEL usually works.
     */
    private static final SavedDataType<WorldgenBlueprintQueueState> TYPE =
            new SavedDataType<>(ID, WorldgenBlueprintQueueState::new, CODEC, pickDataFixType());

    private static DataFixTypes pickDataFixType() {
        // If your DataFixTypes has SAVED_DATA, use it. Otherwise fallback to LEVEL.
        // This avoids you having to guess which enum constants exist.
        try {
            return DataFixTypes.valueOf("SAVED_DATA");
        } catch (Throwable ignored) {
            return DataFixTypes.LEVEL;
        }
    }

    public static WorldgenBlueprintQueueState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    private static String dimId(ServerLevel level) {
        return level.dimension().location().toString();
    }

    public Entry find(ServerLevel level, long regionKey) {
        String dim = dimId(level);
        for (Entry e : entries) {
            if (e.regionKey == regionKey && e.dimId.equals(dim)) return e;
        }
        return null;
    }

    public boolean hasRegion(ServerLevel level, long regionKey) {
        return find(level, regionKey) != null;
    }

    public void upsert(ServerLevel level, long regionKey, String modId, String bpId, int x, int y, int z, boolean includeAir) {
        String dim = dimId(level);

        for (Entry e : entries) {
            if (e.regionKey == regionKey && e.dimId.equals(dim)) {
                e.modId = modId;
                e.blueprintId = bpId;
                e.x = x; e.y = y; e.z = z;
                e.includeAir = includeAir;
                setDirty();
                return;
            }
        }

        Entry e = new Entry();
        e.dimId = dim;
        e.regionKey = regionKey;
        e.modId = modId;
        e.blueprintId = bpId;
        e.x = x; e.y = y; e.z = z;
        e.includeAir = includeAir;
        entries.add(e);
        setDirty();
    }

    public void remove(ServerLevel level, long regionKey) {
        String dim = dimId(level);
        for (Iterator<Entry> it = entries.iterator(); it.hasNext();) {
            Entry e = it.next();
            if (e.regionKey == regionKey && e.dimId.equals(dim)) {
                it.remove();
                setDirty();
                return;
            }
        }
    }

    public List<Entry> snapshot() {
        return new ArrayList<>(entries);
    }

    public static ServerLevel getLevelById(MinecraftServer server, String dimId) {
        ResourceLocation rl = ResourceLocation.parse(dimId);
        ResourceKey<Level> key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, rl);
        return server.getLevel(key);
    }
}
