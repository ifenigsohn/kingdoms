package name.kingdoms.blueprint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.List;

public final class KingdomsSpawnState extends SavedData {
    private static final String DATA_NAME = "kingdoms_spawn_state";

    // Completed regions (castle fully placed)
    private final LongSet spawnedRegions = new LongOpenHashSet();

    // In-flight regions (job enqueued, owned by placer pipeline)
    private final LongSet queuedRegions = new LongOpenHashSet();

    public static final SavedDataType<KingdomsSpawnState> TYPE =
            new SavedDataType<>(
                    DATA_NAME,
                    KingdomsSpawnState::new,
                    ctx -> RecordCodecBuilder.create(instance -> instance.group(
                            Codec.LONG.listOf()
                                    .fieldOf("spawned")
                                    .forGetter(s -> toList(s.spawnedRegions)),
                            Codec.LONG.listOf()
                                    .fieldOf("queued")
                                    .forGetter(s -> toList(s.queuedRegions))
                    ).apply(instance, (spawned, queued) -> {
                        KingdomsSpawnState s = new KingdomsSpawnState(ctx);
                        spawned.forEach(v -> s.spawnedRegions.add(v));
                        queued.forEach(v -> s.queuedRegions.add(v));
                        return s;
                    })),
                    DataFixTypes.LEVEL
            );

    public KingdomsSpawnState(SavedData.Context ctx) {
        // ctx.level / ctx.worldSeed available if needed
    }

    public static KingdomsSpawnState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // ---------------------------------------------------------------------
    // Spawned (completed)
    // ---------------------------------------------------------------------

    public boolean hasSpawned(long regionKey) {
        return spawnedRegions.contains(regionKey);
    }

    public void markSpawned(long regionKey) {
        if (spawnedRegions.add(regionKey)) setDirty();
    }

    // ---------------------------------------------------------------------
    // Queued (in-flight)
    // ---------------------------------------------------------------------

    public boolean isQueued(long regionKey) {
        return queuedRegions.contains(regionKey);
    }

    public void markQueued(long regionKey) {
        if (queuedRegions.add(regionKey)) setDirty();
    }

    public void clearQueued(long regionKey) {
        if (queuedRegions.remove(regionKey)) setDirty();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static List<Long> toList(LongSet set) {
        long[] arr = set.toLongArray();
        List<Long> out = new ArrayList<>(arr.length);
        for (long v : arr) out.add(v);
        return out;
    }
}
