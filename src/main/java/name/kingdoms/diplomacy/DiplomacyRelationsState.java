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

public final class DiplomacyRelationsState extends SavedData {

    // player -> (kingdom -> rel)
    private final Map<UUID, Map<UUID, Integer>> rel = new HashMap<>();

    // baseline relation target (attractor), stored per (player, kingdom)
    private final java.util.Map<java.util.UUID, java.util.Map<java.util.UUID, Integer>> relBaseline = new java.util.HashMap<>();


    /* -----------------------------
       PERSISTENCE (STATE CODEC)
     ----------------------------- */

    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    private static final Codec<Map<UUID, Map<UUID, Integer>>> REL_CODEC =
            Codec.unboundedMap(UUID_CODEC, Codec.unboundedMap(UUID_CODEC, Codec.INT));

    private static final Codec<Map<UUID, Map<UUID, Integer>>> BASELINE_CODEC =
        Codec.unboundedMap(UUID_CODEC, Codec.unboundedMap(UUID_CODEC, Codec.INT));


    private static final Codec<DiplomacyRelationsState> CODEC =
        RecordCodecBuilder.create(inst -> inst.group(
                REL_CODEC.fieldOf("rel").forGetter(s -> s.rel),
                BASELINE_CODEC.optionalFieldOf("relBaseline", java.util.Map.of()).forGetter(s -> s.relBaseline)
        ).apply(inst, (loadedRel, loadedBaseline) -> {
            DiplomacyRelationsState s = new DiplomacyRelationsState();

            // Deep copy REL so inner maps are mutable
            for (var e : loadedRel.entrySet()) {
                UUID playerId = e.getKey();
                Map<UUID, Integer> inner = e.getValue();
                s.rel.put(playerId, new HashMap<>(inner));
            }

            // Deep copy BASELINE so inner maps are mutable
            for (var e : loadedBaseline.entrySet()) {
                UUID playerId = e.getKey();
                Map<UUID, Integer> inner = e.getValue();
                s.relBaseline.put(playerId, new HashMap<>(inner));
            }

            return s;
        }));


    private static final SavedDataType<DiplomacyRelationsState> TYPE =
            new SavedDataType<>(
                    "kingdoms_diplomacy_relations",
                    DiplomacyRelationsState::new,
                    CODEC,
                    null
            );

            

    public static DiplomacyRelationsState get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return new DiplomacyRelationsState();
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }

    /** Initialize relation for first discovery: sets both current and baseline. */
    public void initRelation(java.util.UUID playerId, java.util.UUID kingdomId, int baseline) {
        // set current (reuse your existing setter if you have one)
        setRelation(playerId, kingdomId, baseline);

        // set baseline only if missing (so you don't overwrite later)
        if (!hasBaseline(playerId, kingdomId)) {
            setBaseline(playerId, kingdomId, baseline);
        }
    }


    public int getRelation(UUID player, UUID kingdom) {
        if (player == null || kingdom == null) return 0;
        Map<UUID, Integer> inner = rel.get(player);
        if (inner == null) return 0;
        Integer v = inner.get(kingdom);
        return v == null ? 0 : v;
    }


    public int addRelation(UUID player, UUID kingdom, int delta) {
        Map<UUID, Integer> m = rel.computeIfAbsent(player, k -> new HashMap<>());
        int next = m.getOrDefault(kingdom, 0) + delta;
        m.put(kingdom, next);
        setDirty();
        return next;
    }

    public void setRelation(UUID player, UUID kingdom, int value) {
        rel.computeIfAbsent(player, k -> new HashMap<>()).put(kingdom, value);
        setDirty();
    }

    public record Entry(UUID playerId, UUID kingdomId, int value) {}

    public Iterable<Entry> entries() {
        var out = new java.util.ArrayList<Entry>();
        for (var pe : rel.entrySet()) {
            UUID playerId = pe.getKey();
            for (var ke : pe.getValue().entrySet()) {
                out.add(new Entry(playerId, ke.getKey(), ke.getValue()));
            }
        }
        return out;
    }

    public void setRelationNoDirty(UUID player, UUID kingdom, int value) {
        rel.computeIfAbsent(player, k -> new HashMap<>()).put(kingdom, value);
    }

    public boolean hasRelation(UUID player, UUID kingdom) {
        if (player == null || kingdom == null) return false;
        Map<UUID, Integer> inner = rel.get(player);
        return inner != null && inner.containsKey(kingdom);
    }

    public boolean hasBaseline(java.util.UUID playerId, java.util.UUID kingdomId) {
        var m = relBaseline.get(playerId);
        return m != null && m.containsKey(kingdomId);
    }

    public int getBaseline(java.util.UUID playerId, java.util.UUID kingdomId) {
        var m = relBaseline.get(playerId);
        if (m == null || !m.containsKey(kingdomId)) {
            // Backfill baseline to current for old saves
            int cur = getRelation(playerId, kingdomId);
            setBaseline(playerId, kingdomId, cur);
            return cur;
        }
        return m.get(kingdomId);
    }


    public void setBaseline(java.util.UUID playerId, java.util.UUID kingdomId, int baseline) {
        relBaseline.computeIfAbsent(playerId, k -> new java.util.HashMap<>()).put(kingdomId, baseline);
        setDirty();
    }



    
}
