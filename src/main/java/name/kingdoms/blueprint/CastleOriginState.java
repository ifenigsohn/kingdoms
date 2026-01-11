package name.kingdoms.blueprint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class CastleOriginState extends SavedData {
    private static final String DATA_NAME = "kingdoms_castle_origins";

    // regionKey -> origin
    private final Long2ObjectMap<BlockPos> origins = new Long2ObjectOpenHashMap<>();

    // ----- Codec entry -----
    public record Entry(long regionKey, int x, int y, int z) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Codec.LONG.fieldOf("k").forGetter(Entry::regionKey),
                Codec.INT.fieldOf("x").forGetter(Entry::x),
                Codec.INT.fieldOf("y").forGetter(Entry::y),
                Codec.INT.fieldOf("z").forGetter(Entry::z)
        ).apply(inst, Entry::new));
    }

    // SavedDataType pattern for 1.21.10: factory takes SavedData.Context
    public static final SavedDataType<CastleOriginState> TYPE =
            new SavedDataType<>(
                    DATA_NAME,
                    CastleOriginState::new, // <-- this now binds to (SavedData.Context) ctor below
                    ctx -> RecordCodecBuilder.create(inst -> inst.group(
                            Entry.CODEC.listOf()
                                    .fieldOf("origins")
                                    .forGetter(CastleOriginState::asEntries)
                    ).apply(inst, CastleOriginState::new)),
                    DataFixTypes.SAVED_DATA_COMMAND_STORAGE
            );

    public static CastleOriginState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    /**
     * REQUIRED by 1.21.10 SavedDataType factory signature.
     * This is what gets used when the data doesn't exist yet.
     */
    public CastleOriginState(SavedData.Context ctx) {
        // empty
    }

    /**
     * Used by the CODEC to load data from disk.
     */
    private CastleOriginState(List<Entry> entries) {
        for (Entry e : entries) {
            origins.put(e.regionKey(), new BlockPos(e.x(), e.y(), e.z()));
        }
    }

    private List<Entry> asEntries() {
        List<Entry> out = new ArrayList<>(origins.size());
        for (Long2ObjectMap.Entry<BlockPos> e : origins.long2ObjectEntrySet()) {
            BlockPos p = e.getValue();
            out.add(new Entry(e.getLongKey(), p.getX(), p.getY(), p.getZ()));
        }
        return out;
    }

    public void put(long regionKey, BlockPos origin) {
        origins.put(regionKey, origin.immutable());
        setDirty();
    }

    public BlockPos get(long regionKey) {
        return origins.get(regionKey);
    }

    public boolean has(long regionKey) {
        return origins.containsKey(regionKey);
    }

    public Collection<BlockPos> allOrigins() {
        return origins.values();
    }
}
