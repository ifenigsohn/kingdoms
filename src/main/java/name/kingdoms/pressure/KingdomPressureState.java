package name.kingdoms.pressure;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import name.kingdoms.pressure.KingdomPressureState.PressureEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.*;

/**
 * Kingdom Pressure & Actions System:
 * - Stores time-limited "events" that apply modifiers to kingdoms
 * - Ticks to expire events
 * - Provides a "modifiers for kingdom" snapshot for use by economy/security/happiness/etc.
 */
public class KingdomPressureState extends SavedData {

    /* ---------------------------------
       Public API (what other systems call)
     --------------------------------- */

    public enum Stat {
        ECONOMY,     // multiplier; e.g. -0.15 means -15% econ output
        HAPPINESS,   // additive on your 0..10 happiness scale; e.g. -1.0
        SECURITY,    // additive on your 0..1 securityValue; e.g. -0.05
        RELATIONS    // additive offset for relation evaluations (we'll wire later)
    }

    public enum RelScope {
        /** Applies to relation evals vs ANY other kingdom (legitimacy crisis, unrest, etc.) */
        GLOBAL,
        /** Applies only when evaluating relation vs the causer kingdom (temporary hostility/feud) */
        CAUSER_ONLY
    }


    /** A compact snapshot of current modifiers affecting one kingdom. */
    public record Mods(
            double economyMult,   // final multiplier (>=0)
            double happinessDelta, // add to happiness() result (0..10 scale)
            double securityDelta,  // add to securityValue() result (0..1 scale)
            int relationsDelta     // add to relations eval (int)
    ) {
        public static final Mods NONE = new Mods(1.0, 0.0, 0.0, 0);

        public Mods combine(Mods o) {
            if (o == null) return this;
            return new Mods(
                    this.economyMult * o.economyMult,
                    this.happinessDelta + o.happinessDelta,
                    this.securityDelta + o.securityDelta,
                    this.relationsDelta + o.relationsDelta
            );
        }
    }

    /**
     * Add a new event. You can call this from:
     * - right-click entity actions
     * - retinue orders
     * - diplomacy outcomes
     * - wars/sieges aftermath
     */
    public UUID addEvent(UUID causer, UUID causee, String typeId, Map<Stat, Double> effects, long nowTick, long durationTicks) {
        UUID eid = UUID.randomUUID();
        long end = Math.max(nowTick + 1, nowTick + Math.max(1, durationTicks));

        EnumMap<Stat, Double> eff = new EnumMap<>(Stat.class);
        if (effects != null) {
            for (var e : effects.entrySet()) {
                if (e.getKey() == null) continue;
                double v = (e.getValue() == null) ? 0.0 : e.getValue();
                if (Math.abs(v) < 1e-9) continue;
                eff.put(e.getKey(), v);
            }
        }

        PressureEvent pe = new PressureEvent(
                eid,
                typeId == null ? "unknown" : typeId,
                causer,
                causee,
                nowTick,
                end,
                eff,
                RelScope.GLOBAL // default for now
        );

        eventsByKingdom.computeIfAbsent(causee, k -> new ArrayList<>()).add(pe);
        setDirty();
        return eid;
    }

    public UUID addEvent(UUID causer, UUID causee, String typeId,
                        Map<Stat, Double> effects,
                        RelScope relScope,
                        long nowTick, long durationTicks) {

        UUID eid = UUID.randomUUID();
        long end = Math.max(nowTick + 1, nowTick + Math.max(1, durationTicks));

        EnumMap<Stat, Double> eff = new EnumMap<>(Stat.class);
        if (effects != null) {
            for (var e : effects.entrySet()) {
                if (e.getKey() == null) continue;
                double v = (e.getValue() == null) ? 0.0 : e.getValue();
                if (Math.abs(v) < 1e-9) continue;
                eff.put(e.getKey(), v);
            }
        }

        RelScope rs = (relScope == null) ? RelScope.GLOBAL : relScope;

        PressureEvent pe = new PressureEvent(
                eid,
                typeId == null ? "unknown" : typeId,
                causer,
                causee,
                nowTick,
                end,
                eff,
                rs
        );

        eventsByKingdom.computeIfAbsent(causee, k -> new ArrayList<>()).add(pe);
        setDirty();
        return eid;
    }


    /** Convenience for common “economy pressure”: -15% econ for N ticks. */
    public UUID addEconomyEvent(UUID causer, UUID causee, String typeId, double economyPct, long nowTick, long durationTicks) {
        // economyPct is like -0.15 for -15%, +0.10 for +10%
        Map<Stat, Double> m = Map.of(Stat.ECONOMY, economyPct);
        return addEvent(causer, causee, typeId, m, nowTick, durationTicks);
    }

    /** Remove an event by id (mostly for debugging; design says "no direct removal"). */
    public boolean removeEvent(UUID causee, UUID eventId) {
        var list = eventsByKingdom.get(causee);
        if (list == null || list.isEmpty()) return false;
        boolean removed = list.removeIf(e -> e != null && e.id().equals(eventId));
        if (removed) {
            if (list.isEmpty()) eventsByKingdom.remove(causee);
            setDirty();
        }
        return removed;
    }

    /** Read-only: returns active events list (including expired if caller passes old tick — but normally tick() cleans them). */
    public List<PressureEvent> getEvents(UUID kingdomId) {
        var list = eventsByKingdom.get(kingdomId);
        if (list == null) return List.of();
        return Collections.unmodifiableList(list);
    }

    /** The main thing other systems want: "what modifiers apply right now?" */
    public Mods getMods(UUID kingdomId, long nowTick) {
        var list = eventsByKingdom.get(kingdomId);
        if (list == null || list.isEmpty()) return Mods.NONE;

        double econMult = 1.0;
        double hapDelta = 0.0;
        double secDelta = 0.0;
        int relDelta = 0;

        for (PressureEvent e : list) {
            if (e == null) continue;
            if (nowTick >= e.endTick()) continue; // expired (tick() should clean, but safe)

            // For step 1: effects are constant through duration.
            // Later we can add "decay curves" here.
            var eff = e.effects();
            if (eff == null || eff.isEmpty()) continue;

            // ECONOMY: stored as pct (-0.15 = -15%)
            Double econPct = eff.get(Stat.ECONOMY);
            if (econPct != null) {
                econMult *= Math.max(0.0, 1.0 + econPct);
            }

            // HAPPINESS: delta on 0..10 scale (your Kingdom.happiness() result)
            Double h = eff.get(Stat.HAPPINESS);
            if (h != null) hapDelta += h;

            // SECURITY: delta on 0..1 scale (your Kingdom.securityValue() result)
            Double s = eff.get(Stat.SECURITY);
            if (s != null) secDelta += s;

            // RELATIONS: stored as double but treated as int offset
            Double r = eff.get(Stat.RELATIONS);
            if (r != null) relDelta += (int) Math.round(r);
        }

        // Avoid econ going negative or 0 unless you REALLY want “collapsed economy”
        econMult = Math.max(0.10, econMult);

        return new Mods(econMult, hapDelta, secDelta, relDelta);
    }

    /**
     * Server tick hook: expire old events.
     * Run this at low cost (once per second).
     */
    public void tick(long nowTick) {
        boolean changed = false;

        if (eventsByKingdom.isEmpty()) return;

        var it = eventsByKingdom.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var list = entry.getValue();
            if (list == null || list.isEmpty()) {
                it.remove();
                changed = true;
                continue;
            }

            int before = list.size();
            list.removeIf(e -> e == null || nowTick >= e.endTick());
            if (list.isEmpty()) {
                it.remove();
                changed = true;
            } else if (list.size() != before) {
                changed = true;
            }
        }

        if (changed) setDirty();
    }

    /* ---------------------------------
       Data model + codecs
     --------------------------------- */

    public record PressureEvent(
            UUID id,
            String typeId,
            UUID causer,
            UUID causee,
            long startTick,
            long endTick,
            EnumMap<Stat, Double> effects,
            RelScope relScope
    ) {}

    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    private static final Codec<Stat> STAT_CODEC =
            Codec.STRING.xmap(s -> Stat.valueOf(s.toUpperCase(Locale.ROOT)), Stat::name);

    private static final Codec<RelScope> REL_SCOPE_CODEC =
        Codec.STRING.xmap(s -> RelScope.valueOf(s.toUpperCase(Locale.ROOT)), RelScope::name);


    private static final Codec<EnumMap<Stat, Double>> EFFECTS_CODEC =
            Codec.unboundedMap(STAT_CODEC, Codec.DOUBLE).xmap(
                    m -> {
                        EnumMap<Stat, Double> em = new EnumMap<>(Stat.class);
                        if (m != null) {
                            for (var e : m.entrySet()) {
                                if (e.getKey() == null) continue;
                                em.put(e.getKey(), e.getValue());
                            }
                        }
                        return em;
                    },
                    em -> {
                        Map<Stat, Double> out = new LinkedHashMap<>();
                        if (em != null) out.putAll(em);
                        return out;
                    }
            );

    private static final Codec<PressureEvent> EVENT_CODEC =
            RecordCodecBuilder.create(inst -> inst.group(
                    UUID_CODEC.fieldOf("id").forGetter(PressureEvent::id),
                    Codec.STRING.fieldOf("typeId").forGetter(PressureEvent::typeId),
                    UUID_CODEC.optionalFieldOf("causer", new UUID(0L, 0L)).forGetter(PressureEvent::causer),
                    UUID_CODEC.fieldOf("causee").forGetter(PressureEvent::causee),
                    Codec.LONG.fieldOf("startTick").forGetter(PressureEvent::startTick),
                    Codec.LONG.fieldOf("endTick").forGetter(PressureEvent::endTick),
                   EFFECTS_CODEC.optionalFieldOf("effects", new EnumMap<>(Stat.class)).forGetter(PressureEvent::effects),
                    REL_SCOPE_CODEC.optionalFieldOf("relScope", RelScope.GLOBAL).forGetter(PressureEvent::relScope)

            ).apply(inst, (id, typeId, causer, causee, startTick, endTick, effects, relScope) -> {
                EnumMap<Stat, Double> eff = (effects == null) ? new EnumMap<>(Stat.class) : effects;
                UUID cz = (causer == null) ? new UUID(0L, 0L) : causer;
                RelScope rs = (relScope == null) ? RelScope.GLOBAL : relScope;
                return new PressureEvent(id, typeId, cz, causee, startTick, endTick, eff, rs);
            }));


    private static final Codec<Map<UUID, List<PressureEvent>>> MAP_CODEC =
            Codec.unboundedMap(UUID_CODEC, EVENT_CODEC.listOf());

    private final Map<UUID, List<PressureEvent>> eventsByKingdom = new HashMap<>();

    private final Set<UUID> knownAiKingdoms = new HashSet<>();

    public void markKnownAi(UUID kingdomId) {
        if (kingdomId == null) return;
        if (knownAiKingdoms.add(kingdomId)) setDirty();
    }

    public boolean isKnownAi(UUID kingdomId) {
        return kingdomId != null && knownAiKingdoms.contains(kingdomId);
    }



    private static final Codec<KingdomPressureState> CODEC =
        RecordCodecBuilder.create(inst -> inst.group(
                MAP_CODEC.optionalFieldOf("eventsByKingdom", Map.of()).forGetter(s -> s.eventsByKingdom),
                UUID_CODEC.listOf().optionalFieldOf("knownAi", List.of()).forGetter(s -> new ArrayList<>(s.knownAiKingdoms))
        ).apply(inst, (m, knownList) -> {
            KingdomPressureState s = new KingdomPressureState();
            if (m != null) {
                // Deep-copy values into mutable lists so tick() can removeIf(...)
                for (var e : m.entrySet()) {
                    UUID kid = e.getKey();
                    List<PressureEvent> list = e.getValue();
                    if (kid == null || list == null) continue;
                    s.eventsByKingdom.put(kid, new ArrayList<>(list));
                }
            }

            if (knownList != null) s.knownAiKingdoms.addAll(knownList);
            return s;
        }));


    private static final SavedDataType<KingdomPressureState> TYPE =
            new SavedDataType<>(
                    "kingdoms_pressure_state",
                    KingdomPressureState::new,
                    CODEC,
                    null
            );

    public static KingdomPressureState get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return new KingdomPressureState();
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }
}
