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
        int prev = rel.getOrDefault(k, 0);
        int next = net.minecraft.util.Mth.clamp(prev + delta, -100, 100);
        rel.put(k, next);
        setDirty();
        return next;
    }

    /** Scales deltas down near +/-100 so relations don't pin to extremes. */
    public int addScaled(UUID a, UUID b, int rawDelta) {
        if (a == null || b == null || rawDelta == 0) return get(a, b);

        String k = key(a, b);
        int prev = rel.getOrDefault(k, 0);

        // diminish near extremes: at 0 => 1.0, at +/-100 => 0.30
        double extremity = Math.min(1.0, Math.abs(prev) / 100.0);
        double scale = 1.0 - 0.70 * extremity;

        int d = (int) Math.round(rawDelta * scale);

        // keep small effects from disappearing
        if (d == 0) d = (rawDelta > 0) ? 1 : -1;

        int next = net.minecraft.util.Mth.clamp(prev + d, -100, 100);
        rel.put(k, next);
        setDirty();
        return next;
    }

    /**
     * Two-attractor drift: relations tend to settle around +/-40.
     * This creates "stable hostility" and "stable friendship" with occasional flips.
     *
     * @param strength typically 1 (maybe 2 if you want faster settling)
     * @param flipBand if |rel| < flipBand, we drift toward the sign we already have (or pick one if 0)
     */
    public int driftTowardBands(UUID a, UUID b, int strength, int band) {
        if (a == null || b == null) return 0;
        String k = key(a, b);
        int prev = rel.getOrDefault(k, 0);

        // choose target: +band if positive, -band if negative
        // if exactly 0, do nothing (you can handle flips elsewhere)
        if (prev == 0) return 0;

        int target = (prev > 0) ? band : -band;

        int step = Integer.compare(target, prev) * Math.max(1, strength);
        int next = prev + step;

        // don't overshoot
        if ((prev < target && next > target) || (prev > target && next < target)) next = target;

        rel.put(k, net.minecraft.util.Mth.clamp(next, -100, 100));
        setDirty();
        return next;
    }


    public void set(UUID a, UUID b, int value) {
        rel.put(key(a, b), value);
        setDirty();
    }

    public Map<String, Integer> exportRel() {
        return new HashMap<>(rel);
    }

    public void importRel(Map<String, Integer> snap) {
        rel.clear();
        if (snap != null) rel.putAll(snap);
        setDirty();
    }

    public void setKeyNoDirty(String key, int value) {
        rel.put(key, value);
    }

    public java.util.Map<String, Integer> entries() {
        return rel;
    }

}
