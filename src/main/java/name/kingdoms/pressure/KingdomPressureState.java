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

    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    /** Global cooldown check: does this causer currently have this typeId active anywhere (any causee)? */
    public boolean hasActiveByCauser(UUID causer, String typeId, long nowTick) {
        if (causer == null || typeId == null) return false;

        for (var entry : eventsByKingdom.entrySet()) {
            var list = entry.getValue();
            if (list == null || list.isEmpty()) continue;

            for (var e : list) {
                if (e == null) continue;
                if (nowTick >= e.endTick()) continue;
                if (!typeId.equals(e.typeId())) continue;
                if (!causer.equals(e.causer())) continue;

                return true;
            }
        }
        return false;
    }

    private record EntityLock(long endTick, UUID eventId, String typeId) {}

    private final Map<UUID, EntityLock> entityLocks = new HashMap<>();

    /** True if this entity currently has an active pressure order (any type) */
    public boolean isEntityLocked(UUID entityUuid, long nowTick) {
        if (entityUuid == null) return false;
        EntityLock lock = entityLocks.get(entityUuid);
        return lock != null && nowTick < lock.endTick();
    }

    /** Attach a lock to an entity until endTick. Returns false if already locked. */
    public boolean tryLockEntity(UUID entityUuid, long nowTick, long endTick, UUID eventId, String typeId) {
        if (entityUuid == null) return false;

        EntityLock cur = entityLocks.get(entityUuid);
        if (cur != null && nowTick < cur.endTick()) return false;

        entityLocks.put(entityUuid, new EntityLock(endTick, eventId, typeId == null ? "" : typeId));
        setDirty();
        return true;
    }

    /** Optional: remove lock early (debug/manual) */
    public void unlockEntity(UUID entityUuid) {
        if (entityUuid == null) return;
        if (entityLocks.remove(entityUuid) != null) setDirty();
    }




    /** A compact snapshot of current modifiers affecting one kingdom. */
    public record Mods(
            double economyMult,    // final multiplier (>=0)
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
     * Returns true if an event with this type is currently active on this kingdom.
     * If causerOrNull and/or scopeOrNull are provided, it matches those too.
     *
     * Use cases:
     * - GLOBAL cooldown: hasActiveEvent(causee, typeId, null, RelScope.GLOBAL, nowTick)
     * - Per-causer cooldown: hasActiveEvent(causee, typeId, causerId, RelScope.CAUSER_ONLY, nowTick)
     */
    public boolean hasActiveEvent(UUID causee, String typeId,
                                  UUID causerOrNull,
                                  RelScope scopeOrNull,
                                  long nowTick) {
        if (causee == null || typeId == null) return false;

        var list = eventsByKingdom.get(causee);
        if (list == null || list.isEmpty()) return false;

        for (var e : list) {
            if (e == null) continue;
            if (nowTick >= e.endTick()) continue;
            if (!typeId.equals(e.typeId())) continue;

            if (causerOrNull != null && !causerOrNull.equals(e.causer())) continue;
            if (scopeOrNull != null && scopeOrNull != e.relScope()) continue;

            return true;
        }
        return false;
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

        UUID cz = (causer == null) ? ZERO_UUID : causer;

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
                cz,
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

        UUID cz = (causer == null) ? ZERO_UUID : causer;

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
                cz,
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

    /** Adds an event only if it isn't already active (cooldown until expire). */
    public UUID tryAddEvent(UUID causer, UUID causee, String typeId,
                            Map<Stat, Double> effects,
                            long nowTick, long durationTicks) {
        // For GLOBAL-style self policies, "typeId already active" is the cooldown
        if (hasActiveEvent(causee, typeId, null, null, nowTick)) return null;
        return addEvent(causer, causee, typeId, effects, nowTick, durationTicks);
    }

    /**
     * Adds an event only if it isn't already active (cooldown until expire).
     * - GLOBAL: blocks by (causee, typeId)
     * - CAUSER_ONLY: blocks by (causee, typeId, causer, CAUSER_ONLY)
     */
    public UUID tryAddEvent(UUID causer, UUID causee, String typeId,
                            Map<Stat, Double> effects,
                            RelScope relScope,
                            long nowTick, long durationTicks) {

        RelScope rs = (relScope == null) ? RelScope.GLOBAL : relScope;
        UUID cz = (causer == null) ? ZERO_UUID : causer;

        if (rs == RelScope.GLOBAL) {
            if (hasActiveEvent(causee, typeId, null, RelScope.GLOBAL, nowTick)) return null;
        } else {
            if (hasActiveEvent(causee, typeId, cz, RelScope.CAUSER_ONLY, nowTick)) return null;
        }

        return addEvent(cz, causee, typeId, effects, rs, nowTick, durationTicks);
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

    /** INTERNAL: mark dirty explicitly when callers mutate lists directly. */
    public void markDirty() {
        setDirty();
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

        // expire entity locks
        if (!entityLocks.isEmpty()) {
            int before = entityLocks.size();
            entityLocks.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || nowTick >= e.getValue().endTick());
            if (entityLocks.size() != before) changed = true;
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
                    UUID_CODEC.optionalFieldOf("causer", ZERO_UUID).forGetter(PressureEvent::causer),
                    UUID_CODEC.fieldOf("causee").forGetter(PressureEvent::causee),
                    Codec.LONG.fieldOf("startTick").forGetter(PressureEvent::startTick),
                    Codec.LONG.fieldOf("endTick").forGetter(PressureEvent::endTick),
                    EFFECTS_CODEC.optionalFieldOf("effects", new EnumMap<>(Stat.class)).forGetter(PressureEvent::effects),
                    REL_SCOPE_CODEC.optionalFieldOf("relScope", RelScope.GLOBAL).forGetter(PressureEvent::relScope)

            ).apply(inst, (id, typeId, causer, causee, startTick, endTick, effects, relScope) -> {
                EnumMap<Stat, Double> eff = (effects == null) ? new EnumMap<>(Stat.class) : effects;
                UUID cz = (causer == null) ? ZERO_UUID : causer;
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

    /** INTERNAL: ensure a mutable list exists for this kingdom (used by server-side policy handlers). */
    public java.util.List<PressureEvent> getOrCreateEventsMutable(java.util.UUID kingdomId) {
        if (kingdomId == null) return null;
        return eventsByKingdom.computeIfAbsent(kingdomId, k -> new java.util.ArrayList<>());
    }

   
    // --- Entity lock codecs ---
    private static final Codec<EntityLock> ENTITY_LOCK_CODEC =
            RecordCodecBuilder.create(inst -> inst.group(
                    Codec.LONG.fieldOf("endTick").forGetter(EntityLock::endTick),
                    UUID_CODEC.optionalFieldOf("eventId", ZERO_UUID).forGetter(l -> l.eventId == null ? ZERO_UUID : l.eventId),
                    Codec.STRING.optionalFieldOf("typeId", "").forGetter(EntityLock::typeId)
            ).apply(inst, (endTick, eventId, typeId) -> new EntityLock(endTick, eventId, typeId)));

    private static final Codec<Map<UUID, EntityLock>> ENTITY_LOCKS_CODEC =
            Codec.unboundedMap(UUID_CODEC, ENTITY_LOCK_CODEC);

    // --- Full state codec (THIS is the only CODEC field you should have) ---
    private static final Codec<KingdomPressureState> CODEC =
            RecordCodecBuilder.create(inst -> inst.group(
                    MAP_CODEC.optionalFieldOf("eventsByKingdom", Map.of()).forGetter(s -> s.eventsByKingdom),
                    UUID_CODEC.listOf().optionalFieldOf("knownAi", List.of()).forGetter(s -> new ArrayList<>(s.knownAiKingdoms)),
                    ENTITY_LOCKS_CODEC.optionalFieldOf("entityLocks", Map.of()).forGetter(s -> s.entityLocks)
            ).apply(inst, (m, knownList, locks) -> {
                KingdomPressureState s = new KingdomPressureState();

                if (m != null) {
                    for (var e : m.entrySet()) {
                        UUID kid = e.getKey();
                        List<PressureEvent> list = e.getValue();
                        if (kid == null || list == null) continue;
                        s.eventsByKingdom.put(kid, new ArrayList<>(list));
                    }
                }

                if (knownList != null) s.knownAiKingdoms.addAll(knownList);
                if (locks != null) s.entityLocks.putAll(locks);

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
