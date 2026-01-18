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

    /* -----------------------------
       PERSISTENCE (STATE CODEC)
     ----------------------------- */

    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    private static final Codec<Map<UUID, Map<UUID, Integer>>> REL_CODEC =
            Codec.unboundedMap(UUID_CODEC, Codec.unboundedMap(UUID_CODEC, Codec.INT));

    private static final Codec<DiplomacyRelationsState> CODEC =
            RecordCodecBuilder.create(inst -> inst.group(
                    REL_CODEC.fieldOf("rel").forGetter(s -> s.rel)
            ).apply(inst, (loadedRel) -> {
                DiplomacyRelationsState s = new DiplomacyRelationsState();

                // Deep copy so inner maps are always mutable
                for (var e : loadedRel.entrySet()) {
                    UUID playerId = e.getKey();
                    Map<UUID, Integer> inner = e.getValue();
                    s.rel.put(playerId, new HashMap<>(inner));
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

    
}
