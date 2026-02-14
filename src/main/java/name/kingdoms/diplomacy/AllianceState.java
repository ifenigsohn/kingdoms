package name.kingdoms.diplomacy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import name.kingdoms.aiKingdomState;
import name.kingdoms.kingdomState;
import name.kingdoms.news.KingdomNewsState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.*;

public final class AllianceState extends SavedData {
    private static final String DATA_NAME = "kingdoms_alliances";
    private static final int MAX_ALLIES = 3;

    // kingdomId -> set of allied kingdomIds
    private final Map<UUID, Set<UUID>> allies = new HashMap<>();

    public AllianceState() {}

    /** Snapshot: deep copy of allies map */
    public Map<UUID, List<UUID>> exportAllies() {
        Map<UUID, List<UUID>> out = new HashMap<>();
        for (var e : allies.entrySet()) {
            out.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return out;
    }

    /** Restore: replace internal state */
    public void importAllies(Map<UUID, List<UUID>> decoded) {
        allies.clear();
        if (decoded != null) {
            for (var e : decoded.entrySet()) {
                if (e.getKey() == null) continue;
                Set<UUID> set = new HashSet<>();
                if (e.getValue() != null) for (UUID id : e.getValue()) if (id != null) set.add(id);
                allies.put(e.getKey(), set);
            }
        }
        normalizeSymmetry();
        setDirty();
    }


    private AllianceState(Map<UUID, List<UUID>> decoded) {
        allies.clear();
        for (var e : decoded.entrySet()) {
            if (e.getKey() == null) continue;
            Set<UUID> set = new HashSet<>();
            if (e.getValue() != null) {
                for (UUID id : e.getValue()) if (id != null) set.add(id);
            }
            allies.put(e.getKey(), set);
        }
        // ensure symmetry
        normalizeSymmetry();
    }

    private void normalizeSymmetry() {
        for (var e : new HashMap<>(allies).entrySet()) {
            UUID a = e.getKey();
            for (UUID b : new HashSet<>(e.getValue())) {
                if (a.equals(b)) { e.getValue().remove(b); continue; }
                allies.computeIfAbsent(b, k -> new HashSet<>()).add(a);
            }
        }
    }

    // -------------------------
    // SavedData plumbing
    // -------------------------

    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    private static final Codec<Map<UUID, List<UUID>>> STATE_MAP_CODEC =
            Codec.unboundedMap(UUID_CODEC, UUID_CODEC.listOf());

    private static final Codec<AllianceState> STATE_CODEC =
            RecordCodecBuilder.create(inst -> inst.group(
                    STATE_MAP_CODEC.optionalFieldOf("allies", Map.of())
                            .forGetter(s -> {
                                // encode Set as List
                                Map<UUID, List<UUID>> out = new HashMap<>();
                                for (var e : s.allies.entrySet()) {
                                    out.put(e.getKey(), new ArrayList<>(e.getValue()));
                                }
                                return out;
                            })
            ).apply(inst, AllianceState::new));

    public static final SavedDataType<AllianceState> TYPE =
            new SavedDataType<>(DATA_NAME, AllianceState::new, STATE_CODEC, null);

    public static AllianceState get(MinecraftServer server) {
        ServerLevel level = server.overworld();
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // -------------------------
    // API
    // -------------------------

    public boolean isAllied(UUID a, UUID b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return false;
        Set<UUID> s = allies.get(a);
        return s != null && s.contains(b);
    }

    public Set<UUID> alliesOf(UUID a) {
        Set<UUID> s = allies.get(a);
        return (s == null) ? Set.of() : Collections.unmodifiableSet(s);
    }

    public boolean canAlly(UUID a, UUID b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return false;
        if (isAllied(a, b)) return true;

        int asz = allies.getOrDefault(a, Set.of()).size();
        int bsz = allies.getOrDefault(b, Set.of()).size();
        return asz < MAX_ALLIES && bsz < MAX_ALLIES;
    }

    /** Adds alliance symmetrically if both have capacity. */
    public boolean addAlliance(UUID a, UUID b) {
        if (!canAlly(a, b)) return false;

        allies.computeIfAbsent(a, k -> new HashSet<>()).add(b);
        allies.computeIfAbsent(b, k -> new HashSet<>()).add(a);
        setDirty();
        return true;
    }

    /** Breaks alliance symmetrically. */
    public boolean breakAlliance(UUID a, UUID b) {
        boolean changed = false;
        Set<UUID> sa = allies.get(a);
        if (sa != null) changed |= sa.remove(b);
        Set<UUID> sb = allies.get(b);
        if (sb != null) changed |= sb.remove(a);

        if (sa != null && sa.isEmpty()) allies.remove(a);
        if (sb != null && sb.isEmpty()) allies.remove(b);

        if (changed) setDirty();
        return changed;
    }

    public boolean addAlliance(MinecraftServer server, UUID a, UUID b) {
        boolean ok = addAlliance(a, b);
        if (ok && server != null) {
            try {
                var ks = kingdomState.get(server);
                var ai = aiKingdomState.get(server);

                String an = name(ks, ai, a);
                String bn = name(ks, ai, b);

                // Name-only: avoids UUID parsing so this is NEVER range-gated.
                KingdomNewsState.get(server.overworld()).add(server.getTickCount(),
                        "[ALLIANCE] " + an + " and " + bn + " have formed an alliance.");
            } catch (Throwable ignored) {}
        }
        return ok;
    }

    public boolean breakAlliance(MinecraftServer server, UUID a, UUID b) {
        boolean ok = breakAlliance(a, b);
        if (ok && server != null) {
            try {
                var ks = kingdomState.get(server);
                var ai = aiKingdomState.get(server);

                String an = name(ks, ai, a);
                String bn = name(ks, ai, b);

                // Name-only: avoids UUID parsing so this is NEVER range-gated.
                KingdomNewsState.get(server.overworld()).add(server.getTickCount(),
                        "[ALLIANCE] " + an + " and " + bn + " have broken their alliance.");
            } catch (Throwable ignored) {}
        }
        return ok;
    }

    /** Name resolution for both player and AI kingdoms. */
    /** Name resolution for both player and AI kingdoms. */
    private static String name(kingdomState ks, aiKingdomState ai, UUID kid) {
        if (kid == null) return "Unknown Kingdom";

        // Prefer the main kingdomState if present
        if (ks != null) {
            var k = ks.getKingdom(kid);
            if (k != null && k.name != null && !k.name.isBlank()) return k.name;
        }

        // Fallback: AI state lookup
        if (ai != null) {
            // If ai has full object access
            var ak = ai.getById(kid);
            if (ak != null && ak.name != null && !ak.name.isBlank()) return ak.name;

            // Or if you only have name mapping
            String n = ai.getNameById(kid);
            if (n != null && !n.isBlank()) return n;
        }

        return "Unknown Kingdom";
    }


}
