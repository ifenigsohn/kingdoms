package name.kingdoms.blueprint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashSet;
import java.util.Set;

/**
 * Tracks which regionKeys have already started road generation,
 * to prevent double-starting roads.
 *
 * Uses 1.21+ SavedDataType + Codec (no manual NBT).
 */
public final class RoadBuildState extends SavedData {

    private final Set<Long> started = new HashSet<>();

    public RoadBuildState() {}


    /** Returns false if already started for this regionKey. */
    public boolean markStarted(long regionKey) {
        if (started.contains(regionKey)) return false;
        started.add(regionKey);
        setDirty();
        return true;
    }

    public boolean hasStarted(long regionKey) {
        return started.contains(regionKey);
    }

    public void clear(long regionKey) {
        if (started.remove(regionKey)) setDirty();
    }

    // --------- CODEC + STORAGE (1.21+) ---------

    private static final Codec<RoadBuildState> CODEC =
            RecordCodecBuilder.create(inst -> inst.group(
                    Codec.LONG.listOf().fieldOf("started")
                            .forGetter(s -> s.started.stream().toList())
            ).apply(inst, (list) -> {
                RoadBuildState s = new RoadBuildState();
                for (Long k : list) s.started.add(k);
                return s;
            }));

    private static final SavedDataType<RoadBuildState> TYPE =
            new SavedDataType<RoadBuildState>(
                    "kingdoms_road_build_state",
                    RoadBuildState::new,
                    CODEC,
                    null
            );

    public static RoadBuildState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }
}
