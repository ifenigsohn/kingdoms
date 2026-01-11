package name.kingdoms.diplomacy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class AiRelationsState extends SavedData {
    // key "min|max" -> rel
    private final Map<String, Integer> rel = new HashMap<>();

    private static final Codec<Map<String, Integer>> REL_CODEC =
            Codec.unboundedMap(Codec.STRING, Codec.INT);

    private static final Codec<AiRelationsState> CODEC =
            RecordCodecBuilder.create(inst -> inst.group(
                    REL_CODEC.optionalFieldOf("rel", Map.of()).forGetter(s -> s.rel)
            ).apply(inst, (loaded) -> {
                AiRelationsState s = new AiRelationsState();
                s.rel.putAll(loaded);
                return s;
            }));

    private static final SavedDataType<AiRelationsState> TYPE =
            new SavedDataType<>("kingdoms_ai_relations", AiRelationsState::new, CODEC, null);

    public static AiRelationsState get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return new AiRelationsState();
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }

    private static String key(UUID a, UUID b) {
        String sa = a.toString();
        String sb = b.toString();
        return (sa.compareTo(sb) <= 0) ? (sa + "|" + sb) : (sb + "|" + sa);
    }

    public int get(UUID a, UUID b) {
        if (a == null || b == null) return 0;
        return rel.getOrDefault(key(a, b), 0);
    }

    public int add(UUID a, UUID b, int delta) {
        String k = key(a, b);
        int next = rel.getOrDefault(k, 0) + delta;
        rel.put(k, next);
        setDirty();
        return next;
    }

    public void set(UUID a, UUID b, int value) {
        rel.put(key(a, b), value);
        setDirty();
    }
}
