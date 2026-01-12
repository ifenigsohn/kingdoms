package name.kingdoms.blueprint;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.tags.BlockTags;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.*;

import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;


/**
 * Places blueprints over multiple ticks WITH:
 * - site check (cheap sampling)
 * - terrain grading
 * - placement
 * - persistent worldgen queue + resume
 *
 * 3-lane scheduler:
 *  - Preflight lane (parallel): ACQUIRE_CHUNKS + CHECK_SITE for multiple tasks
 *  - Heavy lane (single): GRADE_TERRAIN + PLACE_BLUEPRINT for exactly one task at a time
 *
 * Guarantees grading occurs before placement within a task, and roads still only start
 * after blueprint placement success (and after persisted queue is empty for region).
 */
public final class BlueprintPlacerEngine {
    private static final Logger LOGGER = LogUtils.getLogger();

    // === 3 lane queues ===
    private static final Deque<Task> PREFLIGHT = new ArrayDeque<>();
    private static final Deque<Task> HEAVY = new ArrayDeque<>();

    // ======================================================================
    // In-flight de-dupe / claim (prevents double-enqueue of same jobKey)
    // ======================================================================
    private static final it.unimi.dsi.fastutil.longs.LongSet IN_FLIGHT =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    private static void releaseJob(long jobKey) {
        IN_FLIGHT.remove(jobKey);
    }

    public static boolean isInFlight(long jobKey) {
        return IN_FLIGHT.contains(jobKey);
    }

    private static void rebuildRegionActivityFromPersisted(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;

        WorldgenBlueprintQueueState state = WorldgenBlueprintQueueState.get(overworld);

        // roadsRegionKey -> count
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap counts = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

        for (WorldgenBlueprintQueueState.Entry e : state.snapshot()) {
            ServerLevel lvl = WorldgenBlueprintQueueState.getLevelById(server, e.dimId);
            if (lvl == null) continue;

            long jobKey = e.regionKey;
            long roadsRegionKey = JobToRoadRegionState.get(lvl).getRoadRegionOrSelf(jobKey);

            counts.addTo(roadsRegionKey, 1);
        }

        for (var it = counts.long2IntEntrySet().fastIterator(); it.hasNext();) {
            var en = it.next();
            long roadsRegionKey = en.getLongKey();
            int count = en.getIntValue();

            RegionActivityState act = RegionActivityState.get(overworld);
            for (int i = 0; i < count; i++) act.begin(roadsRegionKey);
        }

        LOGGER.info("[Kingdoms] Rebuilt RegionActivity from persisted queue: {} regions", counts.size());
    }

    private static void primeInFlightFromPersisted(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;

        WorldgenBlueprintQueueState state = WorldgenBlueprintQueueState.get(overworld);

        int primed = 0;
        for (WorldgenBlueprintQueueState.Entry e : state.snapshot()) {
            IN_FLIGHT.add(e.regionKey); // persisted queue key == jobKey
            primed++;
        }
        LOGGER.info("[Kingdoms] Primed IN_FLIGHT from persisted queue: {}", primed);
    }

    public static int getQueueSize() { return PREFLIGHT.size() + HEAVY.size(); }
    public static int getPreflightSize() { return PREFLIGHT.size(); }
    public static int getHeavySize() { return HEAVY.size(); }

    // === DEBUG ===
    public static boolean DEBUG = false;
    public static int DEBUG_EVERY_N_CHECK_SAMPLES = 64;
    public static int DEBUG_EVERY_N_GRADE_COLS = 256;

    private static void dbg(String msg) {
        if (DEBUG) LOGGER.info("[Kingdoms][BPDBG] " + msg);
    }

    // === PERFORMANCE ===
    // Heavy lane budgets (grading + placement)
    public static int BLOCKS_PER_TICK = 300;
    public static long MAX_NANOS_PER_TICK = 2_000_000; // ~15ms

    // Preflight lane budgets (acquire + site check)
    public static int PREFLIGHT_PARALLEL = 1;
    public static int PREFLIGHT_BUDGET_PER_TASK = 120;
    public static long PREFLIGHT_MAX_NANOS_PER_TICK = 3_000_000; // ~3ms
    private static int aliveTicks = 0;

    private BlueprintPlacerEngine() {}

    // ======================================================================
    // JobKey -> RoadsRegionKey mapping (persisted)
    // ======================================================================
    private static final class JobToRoadRegionState extends SavedData {

        private final Map<Long, Long> jobToRoad = new HashMap<>();

        public JobToRoadRegionState() {}

        public void put(long jobKey, long roadsRegionKey) {
            Long prev = jobToRoad.put(jobKey, roadsRegionKey);
            if (prev == null || prev.longValue() != roadsRegionKey) setDirty();
        }

        public long getRoadRegionOrSelf(long jobKey) {
            Long v = jobToRoad.get(jobKey);
            return (v == null) ? jobKey : v;
        }

        public void remove(long jobKey) {
            if (jobToRoad.remove(jobKey) != null) setDirty();
        }

        public record Pair(long jobKey, long roadsRegionKey) {
            public static final Codec<Pair> CODEC =
                    RecordCodecBuilder.create(inst -> inst.group(
                            Codec.LONG.fieldOf("jobKey").forGetter(Pair::jobKey),
                            Codec.LONG.fieldOf("roadsRegionKey").forGetter(Pair::roadsRegionKey)
                    ).apply(inst, Pair::new));
        }

        private List<Pair> snapshot() {
            ArrayList<Pair> out = new ArrayList<>(jobToRoad.size());
            for (var e : jobToRoad.entrySet()) out.add(new Pair(e.getKey(), e.getValue()));
            return out;
        }

        private static final Codec<JobToRoadRegionState> CODEC =
                RecordCodecBuilder.create(inst -> inst.group(
                        Pair.CODEC.listOf().fieldOf("pairs").forGetter(JobToRoadRegionState::snapshot)
                ).apply(inst, (pairs) -> {
                    JobToRoadRegionState s = new JobToRoadRegionState();
                    for (Pair p : pairs) s.jobToRoad.put(p.jobKey(), p.roadsRegionKey());
                    return s;
                }));

        private static final SavedDataType<JobToRoadRegionState> TYPE =
                new SavedDataType<>(
                        "kingdoms_job_to_road_region",
                        JobToRoadRegionState::new,
                        CODEC,
                        null
                );

        public static JobToRoadRegionState get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(TYPE);
        }
    }

    public static void init() {
        LOGGER.info("[Kingdoms] BlueprintPlacerEngine.init() CALLED");
        ServerTickEvents.END_SERVER_TICK.register(BlueprintPlacerEngine::tick);

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            clearAll(true);
            IN_FLIGHT.clear();
            LOGGER.info("[Kingdoms] Cleared blueprint task queues on server stop");
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            primeInFlightFromPersisted(server);
            rebuildRegionActivityFromPersisted(server);
            ServerLevel overworld = server.overworld();
            if (overworld == null) return;

            WorldgenBlueprintQueueState state = WorldgenBlueprintQueueState.get(overworld);

            for (WorldgenBlueprintQueueState.Entry e : state.snapshot()) {
                ServerLevel lvl = WorldgenBlueprintQueueState.getLevelById(server, e.dimId);
                if (lvl == null) continue;

                try {
                    Blueprint bp = Blueprint.load(server, e.modId, e.blueprintId);

                    int y = surfaceY(lvl, e.x, e.z);
                    BlockPos origin = new BlockPos(e.x, y, e.z);

                    long jobKey = e.regionKey;
                    long roadsRegionKey = JobToRoadRegionState.get(lvl).getRoadRegionOrSelf(jobKey);

                    enqueueWorldgenInternal(lvl, bp, origin, e.modId, e.includeAir,
                            roadsRegionKey, jobKey,
                            false, null, null);

                    LOGGER.info("[Kingdoms] Resumed pending worldgen blueprint '{}' at {}", e.blueprintId, origin);
                } catch (Exception ex) {
                    LOGGER.error("[Kingdoms] Failed to resume pending worldgen blueprint job; dropping it", ex);
                    ServerLevel lvl2 = WorldgenBlueprintQueueState.getLevelById(server, e.dimId);
                    if (lvl2 != null) WorldgenBlueprintQueueState.get(lvl2).remove(lvl2, e.regionKey);
                }
            }
        });
    }

    private static void clearAll(boolean tryReleaseForces) {
        if (tryReleaseForces) {
            for (Task t : PREFLIGHT) {
                try { t.releaseChunkForces(); } catch (Exception ignored) {}
                if (t.claimedJobKey != Long.MIN_VALUE) releaseJob(t.claimedJobKey);
            }
            for (Task t : HEAVY) {
                try { t.releaseChunkForces(); } catch (Exception ignored) {}
                if (t.claimedJobKey != Long.MIN_VALUE) releaseJob(t.claimedJobKey);
            }
        }
        PREFLIGHT.clear();
        HEAVY.clear();
    }

    public static boolean hasPendingTasks() {
        return !PREFLIGHT.isEmpty() || !HEAVY.isEmpty();
    }

    public static void enqueue(ServerLevel level, Blueprint bp, BlockPos origin, String modId, boolean includeAir) {
        enqueue(level, bp, origin, modId, includeAir, null, null);
    }

    public static void enqueue(ServerLevel level, Blueprint bp, BlockPos origin,
                               String modId, boolean includeAir,
                               Runnable onSuccess, Runnable onFail) {
        enqueue(level, bp, origin, modId, includeAir, onSuccess, onFail, Long.MIN_VALUE);
    }

    public static void enqueue(ServerLevel level, Blueprint bp, BlockPos origin,
                               String modId, boolean includeAir,
                               Runnable onSuccess, Runnable onFail,
                               long jobKey) {

        if (jobKey != Long.MIN_VALUE) {
            if (!IN_FLIGHT.add(jobKey)) {
                LOGGER.info("[Kingdoms] Skipping enqueue (already in-flight) jobKey={}", jobKey);
                return;
            }
        }

        Task t = new Task(level, bp, origin, modId, includeAir, onSuccess, onFail);
        t.claimedJobKey = jobKey;

        PREFLIGHT.add(t);

        LOGGER.info("[Kingdoms] Queued blueprint '{}' at {} jobKey={} (preflight={}, heavy={}, total={})",
                bp.id, origin, jobKey, PREFLIGHT.size(), HEAVY.size(), getQueueSize());
    }

    // ======================================================================
    // WORLDGEN ENQUEUE (PERSISTED)
    // ======================================================================
    public static void enqueueWorldgen(ServerLevel level, Blueprint bp, BlockPos origin, String modId, boolean includeAir, long regionKey) {
        enqueueWorldgenInternal(level, bp, origin, modId, includeAir, regionKey, regionKey, true, null, null);
    }

    public static void enqueueWorldgen(ServerLevel level, Blueprint bp, BlockPos origin, String modId, boolean includeAir, long regionKey,
                                       Runnable afterSuccess, Runnable afterFail) {
        enqueueWorldgenInternal(level, bp, origin, modId, includeAir, regionKey, regionKey, true, afterSuccess, afterFail);
    }

    public static void enqueueWorldgen(ServerLevel level, Blueprint bp, BlockPos origin, String modId, boolean includeAir,
                                       long roadsRegionKey, long jobKey) {
        enqueueWorldgenInternal(level, bp, origin, modId, includeAir, roadsRegionKey, jobKey, true, null, null);
    }

    public static void enqueueWorldgen(ServerLevel level, Blueprint bp, BlockPos origin, String modId, boolean includeAir,
                                       long roadsRegionKey, long jobKey,
                                       Runnable afterSuccess, Runnable afterFail) {
        enqueueWorldgenInternal(level, bp, origin, modId, includeAir, roadsRegionKey, jobKey, true, afterSuccess, afterFail);
    }

    private static void enqueueWorldgenInternal(ServerLevel level, Blueprint bp, BlockPos origin, String modId, boolean includeAir,
                                                long roadsRegionKey, long jobKey,
                                                boolean recordState,
                                                Runnable afterSuccess, Runnable afterFail) {
        if (recordState) {
            WorldgenBlueprintQueueState.get(level).upsert(
                    level, jobKey, modId, bp.id,
                    origin.getX(), origin.getY(), origin.getZ(),
                    includeAir
            );

            JobToRoadRegionState.get(level).put(jobKey, roadsRegionKey);

            KingdomsSpawnState.get(level).markQueued(jobKey);

            RegionActivityState.get(level).begin(roadsRegionKey);
        }

        // Reserve footprint
        final int sizeX = bp.sizeX;
        final int sizeZ = bp.sizeZ;

        final int minX = origin.getX();
        final int minZ = origin.getZ();
        final int maxX = minX + sizeX - 1;
        final int maxZ = minZ + sizeZ - 1;

        BlueprintFootprintState.get(level).addFootprint(roadsRegionKey, minX, minZ, maxX, maxZ);

        enqueue(level, bp, origin, modId, includeAir,
                () -> {
                    KingdomsSpawnState.get(level).markSpawned(jobKey);
                    KingdomsSpawnState.get(level).clearQueued(jobKey);

                    WorldgenBlueprintQueueState.get(level).remove(level, jobKey);
                    JobToRoadRegionState.get(level).remove(jobKey);

                    List<BlockPos> anchors = RoadAnchors.consumeBarrierAnchors(level, origin);
                    if (anchors.isEmpty()) {
                        BlockPos a1 = RoadAnchors.fallbackFromBlueprintOrigin(level, origin);

                        // second anchor: offset, same Y (NO world reads)
                        BlockPos a2 = new BlockPos(a1.getX() + 16, a1.getY(), a1.getZ());
                        if (a2.equals(a1)) {
                            a2 = new BlockPos(a1.getX(), a1.getY(), a1.getZ() + 16);
                        }

                        anchors = new java.util.ArrayList<>(2);
                        anchors.add(a1);
                        anchors.add(a2);
                    }


                    RoadAnchorState st = RoadAnchorState.get(level);
                    for (BlockPos anchorPos : anchors) {
                        st.add(roadsRegionKey, anchorPos);
                    }

                    if (afterSuccess != null) {
                        try { afterSuccess.run(); } catch (Exception ignored) {}
                    }

                    RegionActivityState.get(level).end(roadsRegionKey);

                    maybeStartRoads(level, roadsRegionKey);
                },
                () -> {
                    BlueprintFootprintState.get(level).removeFootprint(roadsRegionKey, minX, minZ, maxX, maxZ);

                    KingdomsSpawnState.get(level).clearQueued(jobKey);

                    WorldgenBlueprintQueueState.get(level).remove(level, jobKey);
                    JobToRoadRegionState.get(level).remove(jobKey);

                    RegionActivityState.get(level).end(roadsRegionKey);

                    if (afterFail != null) {
                        try { afterFail.run(); } catch (Exception ignored) {}
                    }
                },
                jobKey
        );
    }

    private static void tick(MinecraftServer server) {
        if ((aliveTicks++ % 200) == 0) {
            LOGGER.info("[BPQ] ALIVE preflight={} heavy={} total={}", PREFLIGHT.size(), HEAVY.size(), getQueueSize());
        }

        if (PREFLIGHT.isEmpty() && HEAVY.isEmpty()) return;

        // ----- Preflight lane -----
        final long preflightDeadline = System.nanoTime() + PREFLIGHT_MAX_NANOS_PER_TICK;

        int advanced = 0;
        int passes = Math.min(PREFLIGHT_PARALLEL, PREFLIGHT.size());

        while (advanced < passes && !PREFLIGHT.isEmpty()) {
            if (System.nanoTime() >= preflightDeadline) break;

            Task t = PREFLIGHT.poll();
            if (t == null) break;

            try {
                Task.PreflightResult r = t.stepPreflight(server, PREFLIGHT_BUDGET_PER_TASK, preflightDeadline);

                if (r == Task.PreflightResult.NEEDS_MORE) {
                    PREFLIGHT.add(t);
                } else if (r == Task.PreflightResult.READY_FOR_HEAVY) {
                    HEAVY.add(t);
                } else {
                    finishAndCleanupTask(t);
                }
            } catch (Exception e) {
                LOGGER.error("[Kingdoms] Blueprint placement failed (preflight tick)", e);
                t.failed = true;
                t.failReason = Task.FailReason.EXCEPTION;
                finishAndCleanupTask(t);
            }

            advanced++;
        }

        // ----- Heavy lane -----
        if (HEAVY.isEmpty()) return;

        Task task = HEAVY.peek();
        if (task == null) return;

        final long heavyDeadline = System.nanoTime() + MAX_NANOS_PER_TICK;

        try {
            boolean done = task.stepHeavy(server, BLOCKS_PER_TICK, heavyDeadline);
            if (done) {
                HEAVY.poll();
                finishAndCleanupTask(task);
            }
        } catch (Exception e) {
            LOGGER.error("[Kingdoms] Blueprint placement failed (heavy tick)", e);

            Task t = HEAVY.poll();
            if (t != null) {
                t.failed = true;
                t.failReason = Task.FailReason.EXCEPTION;
                finishAndCleanupTask(t);
            }
        }
    }

    private static void finishAndCleanupTask(Task task) {
        try { task.releaseChunkForces(); } catch (Exception ignored) {}

        if (task.claimedJobKey != Long.MIN_VALUE) {
            IN_FLIGHT.remove(task.claimedJobKey);
        }

        if (task.failed) {
            try { if (task.onFail != null) task.onFail.run(); } catch (Exception ignored) {}
            LOGGER.info("[Kingdoms] Blueprint '{}' failed/aborted (reason={})", task.bp.id, task.failReason);
        } else {
            try { if (task.onSuccess != null) task.onSuccess.run(); } catch (Exception ignored) {}
            LOGGER.info("[Kingdoms] Finished blueprint '{}'", task.bp.id);
        }
    }

    static int surfaceY(ServerLevel level, int x, int z) {
        int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int y = h - 1;
        return Math.max(level.getMinY() + 1, y);
    }

    private static void maybeStartRoads(ServerLevel level, long regionKey) {
        if (RegionActivityState.get(level).getActive(regionKey) > 0) {
            return;
        }

        WorldgenBlueprintQueueState q = WorldgenBlueprintQueueState.get(level);

        for (WorldgenBlueprintQueueState.Entry e : q.snapshot()) {
            long jobKey = e.regionKey;
            long rrk = JobToRoadRegionState.get(level).getRoadRegionOrSelf(jobKey);
            if (rrk == regionKey) {
                return;
            }
        }

        List<BlockPos> anchors = RoadAnchorState.get(level).getAnchors(regionKey);
        if (anchors.size() < 2) {
            LOGGER.info("[Kingdoms] Roads not started: region {} anchors={}", regionKey, anchors.size());
            return;
        }

        if (anchors.size() < 2) return;

        List<RoadEdge> edges = RoadNetworkPlanner.plan(regionKey, anchors);
        if (edges.isEmpty()) return;

        if (!RoadBuildState.get(level).markStarted(regionKey)) return;

        RoadBuilder.enqueue(level, regionKey, edges);

        LOGGER.info("[Kingdoms] Roads started for region {} anchors={} edges={}",
                regionKey, anchors.size(), edges.size());
    }

    /**
     * Ticket-based chunk holding that works across mappings/versions by reflecting
     * ServerChunkCache#addRegionTicket/removeRegionTicket.
     *
     * Uses built-in TicketType.FORCED (loads + simulates + persists) on 1.21.x.
     */
    private static final class ChunkForcer {
        private static boolean lookedUp = false;
        private static Method ADD = null;
        private static Method REMOVE = null;
        private static boolean warned = false;

        private static void lookup(ServerLevel level) {
            if (lookedUp) return;
            lookedUp = true;

            Object cs = level.getChunkSource();
            Class<?> cls = cs.getClass();

            // Search both public + declared
            List<Method> methods = new ArrayList<>();
            methods.addAll(Arrays.asList(cls.getMethods()));
            methods.addAll(Arrays.asList(cls.getDeclaredMethods()));

            for (Method m : methods) {

                Class<?>[] p = m.getParameterTypes();

                // Signature-only matching (works in dev + obfuscated jar)
                if (m.getReturnType() != void.class) continue;
                if (p.length != 3 && p.length != 4) continue;

                // Must contain TicketType, ChunkPos, int; optional long id
                boolean hasTicketType = false;
                boolean hasChunkPos = false;
                boolean hasInt = false;
                boolean hasLong = false;

                for (Class<?> c : p) {
                    if (c == TicketType.class) hasTicketType = true;
                    else if (c == ChunkPos.class) hasChunkPos = true;
                    else if (c == int.class || c == Integer.class) hasInt = true;
                    else if (c == long.class || c == Long.class) hasLong = true;
                }

                if (!hasTicketType || !hasChunkPos || !hasInt) continue;
                if (p.length == 4 && !hasLong) continue;

                m.setAccessible(true);

                // Pick first two distinct candidates as ADD/REMOVE
                if (ADD == null) ADD = m;
                else if (REMOVE == null && m != ADD) REMOVE = m;

            }

            if ((ADD == null || REMOVE == null) && !warned) {
                warned = true;
                LOGGER.warn("[Kingdoms] Could not find ticket methods on {} (ADD={}, REMOVE={})",
                        cls.getName(), (ADD != null), (REMOVE != null));

                // TEMP: dump all candidate ticket methods
                for (Method m : methods) {
                    String nl = m.getName().toLowerCase(Locale.ROOT);
                    if (nl.contains("ticket")) {
                        LOGGER.warn("[Kingdoms]  ticketMethod={} params={}",
                                m.getName(), Arrays.toString(m.getParameterTypes()));
                    }
                }
            } else if (!warned) {
                LOGGER.info("[Kingdoms] Ticket methods resolved on {} (ADD={}, REMOVE={})",
                        cls.getName(), ADD.getName(), REMOVE.getName());
            }

        }

        private static Object[] buildArgs(Method m, TicketType type, ChunkPos pos, int lvl, long id) {
            Class<?>[] p = m.getParameterTypes();
            Object[] a = new Object[p.length];

            // Fill by unique types
            for (int i = 0; i < p.length; i++) {
                Class<?> c = p[i];
                if (c == TicketType.class) a[i] = type;
                else if (c == ChunkPos.class) a[i] = pos;
                else if (c == int.class || c == Integer.class) a[i] = lvl;
            }

            // If there's a long param, treat it as id (only used in 4-arg versions)
            for (int i = 0; i < p.length; i++) {
                Class<?> c = p[i];
                if ((c == long.class || c == Long.class) && a[i] == null) {
                    a[i] = id;
                }
            }

            return a;
        }

        static boolean setForced(ServerLevel level, int chunkX, int chunkZ, boolean forced, long id) {
            try {
                lookup(level);
                if (ADD == null || REMOVE == null) return false;

                Object cs = level.getChunkSource();
                Method m = forced ? ADD : REMOVE;

                TicketType type = TicketType.FORCED;
                int ticketLevel = 2;
                ChunkPos pos = new ChunkPos(chunkX, chunkZ);

                Object[] args = buildArgs(m, type, pos, ticketLevel, id);
                m.invoke(cs, args);

                return true;
            } catch (Throwable t) {
                return false;
            }
        }

    }

    // ======================================================================
    // Task
    // ======================================================================
    private static final class Task {
        private enum Phase { ACQUIRE_CHUNKS, CHECK_SITE, GRADE_TERRAIN, PLACE_BLUEPRINT }
        private enum FailReason { NONE, SITE_REJECT, STALLED, EXCEPTION }
        private enum PreflightResult { NEEDS_MORE, READY_FOR_HEAVY, FINISHED }

        private Phase phase = Phase.ACQUIRE_CHUNKS;

        private final Runnable onSuccess;
        private final Runnable onFail;
        private boolean placeLogged = false;
        private long claimedJobKey = Long.MIN_VALUE;

        private final ServerLevel level;
        private final Blueprint bp;
        private final BlockPos origin;
        private final String modId;
        private final boolean includeAir;
        private boolean baseYInitialized = false;

        private double colEdgeFade = 1.0;
        private final Map<Integer, BlockState> paletteCache = new HashMap<>();
        private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

        private int secX = 0, secY = 0, secZ = 0;
        private DataInputStream secIn = null;
        private int dx, dy, dz;
        private int cx = 0, cy = 0, cz = 0;

        private static final int PREP_MARGIN = 10;
        private static final int CLEAR_ABOVE = 40;
        private static final int MAX_FILL_DEPTH = 64;
        private static final int MAX_CUT_HEIGHT = 120;
        private static final int FOOTPRINT_SAMPLE_STEP = 8;
        private static final int FOUNDATION_BURY = 1;
        private static final int CHECK_STEP = 10;
        private static final double ALLOWED_BAD_FRAC = 0.3;

        private static final int TREE_CLEAR_EXTRA_HEIGHT = 48;
        private static final int TREE_LEAF_CLEAR_RADIUS = 1;

        private static final int PROTECT_SCAN_ABOVE = 20;
        private static final int PROTECT_SCAN_BELOW = 6;

        private static final int STALL_TICKS_LIMIT = 20 * 60; // 60s
        private int stalledTicks = 0;

        private boolean failed = false;
        private FailReason failReason = FailReason.NONE;

        private boolean prepInitialized = false;
        private boolean prepRejected = false;

        private int baseY;
        private int originYShift;

        private int prepMinX, prepMaxX, prepMinZ, prepMaxZ;
        private int footMinX, footMaxX, footMinZ, footMaxZ;

        private boolean checkInitialized = false;
        private int checkX, checkZ;
        private int checkSamples = 0;
        private int checkBad = 0;
        private int checkBadLimit = 0;

        private int rejWater = 0;
        private int rejTooMuchCut = 0;
        private int rejTooMuchFill = 0;

        private int scanX, scanZ;

        private boolean colActive = false;
        private int colX, colZ;
        private int colSurfaceY;
        private int colDesiredY;
        private int colDist;
        private int colStage = 0;
        private int colY = 0;
        private boolean colSawLeaves = false;
        private boolean colDoTerrain = true;
        private boolean haloActive = false;
        private int haloDx = 0;
        private int haloDz = 0;
        private static final int HALO_CHECKS_PER_TICK = 12;
        private boolean colYInit = false;

        private BlockState colTopState;
        private BlockState colUnderState;
        private BlockState colDeepState;

        private int gradeColsDone = 0;
        private boolean placeBeginLogged = false;

        // === keep chunks loaded while we work ===
        private static final int FORCE_RADIUS_CHUNKS = 2;
        private static final int MAX_FORCE_PER_TICK = 2;
        private static final int SYNC_LOADS_PER_TICK = 0; // start at 1; safe for servers
        private boolean forceInit = false;
        private int forceMinCX, forceMaxCX, forceMinCZ, forceMaxCZ;
        private int forceCX, forceCZ;
        private final LongArray heldForcedChunks = new LongArray();
        private boolean baseInit = false;
        private int baseX, baseZ;
        private final IntArrayList baseSamples = new IntArrayList();


        // --- Preflight diagnostics (low spam) ---
        private int preflightTicks = 0;
        private Phase lastLoggedPhase = null;
        private int lastLoggedServerTick = -1;
        


        private static final class LongArray {
            private long[] a = new long[64];
            private int n = 0;
            void add(long v) {
                if (contains(v)) return;
                if (n >= a.length) a = Arrays.copyOf(a, a.length * 2);
                a[n++] = v;
            }
            boolean contains(long v) {
                for (int i = 0; i < n; i++) if (a[i] == v) return true;
                return false;
            }
            int size() { return n; }
            long get(int i) { return a[i]; }
            void clear() { n = 0; }
        }

        private static final int PLACE_SYNC_LOADS_PER_TICK = 1;
        private int placeSyncLoadsThisTick = 0;
        private int lastPlaceTick = -1;

        private boolean ensureChunkLoadedDuringPlace(int cX, int cZ, MinecraftServer server) {
            int tick = server.getTickCount();
            if (tick != lastPlaceTick) {
                lastPlaceTick = tick;
                placeSyncLoadsThisTick = 0;
            }

            if (level.getChunkSource().getChunkNow(cX, cZ) != null) return true;

            if (placeSyncLoadsThisTick < PLACE_SYNC_LOADS_PER_TICK) {
                level.getChunk(cX, cZ);
                placeSyncLoadsThisTick++;
                return true;
            }

            return false;
        }


        private static final int GRADE_SYNC_LOADS_PER_TICK = 1;
        private int gradeSyncLoadsThisTick = 0;
        private int lastGradeTick = -1;

        private boolean ensureChunkLoadedDuringGrade(int cX, int cZ, MinecraftServer server) {
            int tick = server.getTickCount();
            if (tick != lastGradeTick) {
                lastGradeTick = tick;
                gradeSyncLoadsThisTick = 0;
            }

            if (level.getChunkSource().getChunkNow(cX, cZ) != null) return true;

            if (gradeSyncLoadsThisTick < GRADE_SYNC_LOADS_PER_TICK) {
                level.getChunk(cX, cZ);
                gradeSyncLoadsThisTick++;
                return true;
            }

            return false;
        }


        private static final int PREFLIGHT_SYNC_LOADS_PER_TICK = 0;
        private int preflightSyncLoadsThisTick = 0;
        private int lastPreflightTick = -1;

        private boolean ensureChunkLoadedDuringPreflight(int cX, int cZ, MinecraftServer server) {
            // Tickets handle loading; we just wait until it is present.
            return level.getChunkSource().getChunk(cX, cZ, ChunkStatus.SURFACE, false) != null;

        }





        private Task(ServerLevel level, Blueprint bp, BlockPos origin, String modId, boolean includeAir,
                     Runnable onSuccess, Runnable onFail) {
            this.level = level;
            this.bp = bp;
            this.origin = origin;
            this.modId = modId;
            this.includeAir = includeAir;
            this.onSuccess = onSuccess;
            this.onFail = onFail;
        }

        PreflightResult stepPreflight(MinecraftServer server, int budget, long deadlineNanos) throws IOException {
            if (failed) return PreflightResult.FINISHED;

                // --- diagnostics ---
                preflightTicks++;

                int st = server.getTickCount();
                boolean phaseChanged = (phase != lastLoggedPhase);

                // log every 40 ticks (~2s) or when phase changes
                if (phaseChanged || (st != lastLoggedServerTick && (preflightTicks % 40 == 0))) {
                    lastLoggedServerTick = st;
                    lastLoggedPhase = phase;

                    int forcedCount = heldForcedChunks.size();
                    int forcedRect = forceInit ? ((forceMaxCX - forceMinCX + 1) * (forceMaxCZ - forceMinCZ + 1)) : -1;

                    LOGGER.info(
                        "[BPQ][PREFLIGHT] bp={} phase={} ticks={} forced={}/{} baseYInit={} checkInit={} cursor=({}, {})",
                        bp.id, phase, preflightTicks,
                        forcedCount, forcedRect,
                        baseYInitialized, checkInitialized,
                        forceCX, forceCZ
                    );
                }



            // bounds-only (no world reads)
            if (!prepInitialized) {
                initBoundsOnly();
            }

            // 1) Acquire / load chunks needed for preflight sampling
            if (phase == Phase.ACQUIRE_CHUNKS) {
                boolean done = acquireForces(deadlineNanos);
                if (!done) return PreflightResult.NEEDS_MORE;

                

                phase = Phase.CHECK_SITE;
            }

            // 2) Now safe to sample baseY
            if (!baseYInitialized) {
                if (!initBaseYAndCursors(server)) return PreflightResult.NEEDS_MORE;
                baseYInitialized = true;
            }

            // 3) Site check
            if (phase == Phase.CHECK_SITE) {
                boolean checkDone = checkSite(server, budget, deadlineNanos);
                if (!checkDone) return PreflightResult.NEEDS_MORE;

                if (prepRejected) {
                    failed = true;
                    failReason = FailReason.SITE_REJECT;
                    LOGGER.info("[Kingdoms] Terrain rejected for '{}' at {} (no grading performed)", bp.id, origin);
                    return PreflightResult.FINISHED;
                }

                phase = Phase.GRADE_TERRAIN;
                LOGGER.info("[Kingdoms] Site accepted for '{}' at {} (baseY={}, yShift={})", bp.id, origin, baseY, originYShift);
                return PreflightResult.READY_FOR_HEAVY;
            }

            return PreflightResult.READY_FOR_HEAVY;
        }


        boolean stepHeavy(MinecraftServer server, int budget, long deadlineNanos) throws IOException {
            if (failed) return true;

            try {
                if (phase == Phase.ACQUIRE_CHUNKS || phase == Phase.CHECK_SITE) {
                    PreflightResult r = stepPreflight(server, Math.min(budget, 800), deadlineNanos);
                    if (r == PreflightResult.NEEDS_MORE) return false;
                    if (r == PreflightResult.FINISHED) return true;
                }

                if (phase == Phase.GRADE_TERRAIN) {
                    boolean gradeDone = gradeTerrain(server, budget, deadlineNanos);
                    if (!gradeDone) return false;

                    phase = Phase.PLACE_BLUEPRINT;
                    LOGGER.info("[Kingdoms] Terrain graded for '{}' at {}", bp.id, origin);
                }

                return placeBlueprint(server, budget, deadlineNanos);

            } catch (Exception ex) {
                failed = true;
                failReason = FailReason.EXCEPTION;
                throw ex;
            }
        }

        private static long packChunk(int cx, int cz) {
            return (((long) cx) << 32) ^ (cz & 0xffffffffL);
        }
        private static int unpackChunkX(long k) { return (int)(k >> 32); }
        private static int unpackChunkZ(long k) { return (int)k; }

        private void initForceBounds() {
            forceMinCX = (prepMinX >> 4) - FORCE_RADIUS_CHUNKS;
            forceMaxCX = (prepMaxX >> 4) + FORCE_RADIUS_CHUNKS;
            forceMinCZ = (prepMinZ >> 4) - FORCE_RADIUS_CHUNKS;
            forceMaxCZ = (prepMaxZ >> 4) + FORCE_RADIUS_CHUNKS;

            forceCX = forceMinCX;
            forceCZ = forceMinCZ;
            forceInit = true;

            dbg("FORCE_BEGIN bp=" + bp.id
                    + " chunkBounds=(" + forceMinCX + "," + forceMinCZ + ")->(" + forceMaxCX + "," + forceMaxCZ + ")");
        }

        // NEW: non-loading “is loaded?” check
        private boolean isChunkLoadedNow(int cx, int cz) {
            try {
                return level.getChunkSource().getChunkNow(cx, cz) != null;
            } catch (Throwable ignored) {
                return false;
            }
        }


        // NEW: wait until forced chunks are actually present before we continue
        private boolean allForcedChunksLoaded() {
            // Check corners + center of the forced rectangle
            int midCX = (forceMinCX + forceMaxCX) >> 1;
            int midCZ = (forceMinCZ + forceMaxCZ) >> 1;

            int[] xs = { forceMinCX, midCX, forceMaxCX };
            int[] zs = { forceMinCZ, midCZ, forceMaxCZ };

            for (int cx : xs) {
                for (int cz : zs) {
                    if (!isChunkLoadedNow(cx, cz)) return false;
                }
            }
            return true;
        }



        private boolean acquireForces(long deadlineNanos) {
            if (!forceInit) initForceBounds();

            if (DEBUG && (System.nanoTime() >= deadlineNanos)) {
                dbg("ACQUIRE_SLICE bp=" + bp.id + " cursor=(" + forceCX + "," + forceCZ + ") loaded=" + allForcedChunksLoaded());
            }

            int did = 0;

            while (did < MAX_FORCE_PER_TICK) {
               if (System.nanoTime() >= deadlineNanos) {
                    if (DEBUG) dbg("ACQUIRE_YIELD_TIME bp=" + bp.id + " cursor=(" + forceCX + "," + forceCZ + ")");
                    return false;
                }


                if (forceCZ > forceMaxCZ) {
                    
                    dbg("FORCE_DONE bp=" + bp.id + " forcedChunks=" + heldForcedChunks.size());
                    return true;
                }

                int cx = forceCX;
                int cz = forceCZ;

                forceCX++;
                if (forceCX > forceMaxCX) {
                    forceCX = forceMinCX;
                    forceCZ++;
                }

                long key = packChunk(cx, cz);
                if (heldForcedChunks.contains(key)) continue;

                long ticketId = (claimedJobKey != Long.MIN_VALUE) ? claimedJobKey : origin.asLong();

                // Ticket it (this requests load/hold without sync generation)
                boolean ok = ChunkForcer.setForced(level, cx, cz, true, ticketId);
                if (ok) {
                    heldForcedChunks.add(key);
                }

                did++;
            }

            return false;
        }



        private void releaseChunkForces() {
            if (heldForcedChunks.size() == 0) return;

            long ticketId = (claimedJobKey != Long.MIN_VALUE) ? claimedJobKey : origin.asLong();

            for (int i = 0; i < heldForcedChunks.size(); i++) {
                long key = heldForcedChunks.get(i);
                int cx = unpackChunkX(key);
                int cz = unpackChunkZ(key);
                ChunkForcer.setForced(level, cx, cz, false, ticketId);
            }

            dbg("FORCE_RELEASE bp=" + bp.id + " released=" + heldForcedChunks.size());
            heldForcedChunks.clear();
        }


        // CHANGED: this is now truly a “stall if unloaded” (NO LOADING)
        private boolean stallIfUnloaded(int cX, int cZ) {
            if (isChunkLoadedNow(cX, cZ)) {
                stalledTicks = 0;
                return false;
            }

            stalledTicks++;
            if (stalledTicks >= STALL_TICKS_LIMIT) {
                failed = true;
                failReason = FailReason.STALLED;
                dbg("STALL_ABORT bp=" + bp.id + " origin=" + origin + " atChunk=(" + cX + "," + cZ + ")");
                return true;
            }
            return true;
        }

        // ---------------- Bounds + Site check ----------------

        // Step A: compute rectangle bounds only (NO world reads)
        private void initBoundsOnly() {
            int fx = bp.sizeX > 0 ? bp.sizeX : (bp.sectionsX * bp.sectionSize);
            int fz = bp.sizeZ > 0 ? bp.sizeZ : (bp.sectionsZ * bp.sectionSize);

            footMinX = origin.getX();
            footMaxX = origin.getX() + fx - 1;
            footMinZ = origin.getZ();
            footMaxZ = origin.getZ() + fz - 1;

            prepMinX = footMinX - PREP_MARGIN;
            prepMaxX = footMaxX + PREP_MARGIN;
            prepMinZ = footMinZ - PREP_MARGIN;
            prepMaxZ = footMaxZ + PREP_MARGIN;

            // IMPORTANT: the force bounds depend on prepMin/Max
            // (safe to call here; it does not touch world)
            if (!forceInit) initForceBounds();

            prepInitialized = true;
        }

        // Step B: now that chunks are loaded, sample terrain and init cursors
        private boolean initBaseYAndCursors(MinecraftServer server) {
            Integer chosen = chooseBaseYClamped(server, 32);
            if (chosen == null) return false;

            baseY = chosen - FOUNDATION_BURY;
            originYShift = baseY - origin.getY();

            checkX = footMinX;
            checkZ = footMinZ;
            checkSamples = 0;
            checkBad = 0;
            checkBadLimit = 0;

            scanX = prepMinX;
            scanZ = prepMinZ;

            checkInitialized = true;
            return true;
        }


       private boolean checkSite(MinecraftServer server, int budget, long deadlineNanos) {
            if (!checkInitialized) return true;

            int ops = 0;
            while (ops < budget) {
                if (System.nanoTime() >= deadlineNanos) return false;

                if (checkZ > footMaxZ) {
                    if (checkBadLimit <= 0) {
                        checkBadLimit = Math.max(2, (int) Math.ceil(checkSamples * ALLOWED_BAD_FRAC));
                    }
                    if (checkBad > checkBadLimit) prepRejected = true;
                    return true;
                }

                int x = checkX;
                int z = checkZ;

                checkX += CHECK_STEP;
                if (checkX > footMaxX) {
                    checkX = footMinX;
                    checkZ += CHECK_STEP;
                }

                int cX = x >> 4;
                int cZ = z >> 4;
                if (level.getChunkSource().getChunkNow(cX, cZ) == null) return false;

                int surfaceY = BlueprintPlacerEngine.surfaceY(level, x, z);
                int dist = distToFootprint(x, z);

                if (dist == 0 && isWaterNearSurface(x, surfaceY, z)) {
                    checkBad++;
                    rejWater++;
                }

                int cutNeeded = Math.max(0, surfaceY - baseY);
                int fillNeeded = Math.max(0, baseY - surfaceY);

                if (cutNeeded > MAX_CUT_HEIGHT) { checkBad++; rejTooMuchCut++; }
                if (fillNeeded > MAX_FILL_DEPTH) { checkBad++; rejTooMuchFill++; }

                checkSamples++;
                checkBadLimit = Math.max(2, (int) Math.ceil(checkSamples * ALLOWED_BAD_FRAC));

                ops++;
            }

            return false;
        }

        // ---------------- Terrain grading (SMOOTH / ORGANIC) ----------------
        // Drop-in grading block with:
        // 1) Tree purge across the whole graded area (logs only deleted if leaves nearby, so log houses survive)
        // 2) Smooth, low-frequency edge warping (no pixelly outline)
        // 3) Feathered boundary (no hard cutoff), so the edge fades out naturally
        // 4) Subtle, smooth slope noise (optional; kept small and faded at outer edge)
        //
        // NOTE: remove any duplicate helper methods (smoothstep/lerp/etc) if you already have them elsewhere in Task.

        // ---- Tuning knobs ----
        private static final int TREE_SCAN_BELOW_SURFACE = 32;           // how far down we scan to remove trunks
        private static final int LEAF_NEARBY_RADIUS = 4;                 // leaves radius to classify a log as natural tree
        private static final double EDGE_WARP_BLOCKS = 2.5;              // shape wobble amplitude (blocks)
        private static final int EDGE_MARGIN_JITTER = 1;                 // +- blocks added to PREP_MARGIN per 16-block cell
        private static final double EDGE_NOISE_SCALE = 1.0 / 192.0;      // smaller = smoother (1/96..1/192)
        private static final double EDGE_FEATHER = 6.0;                  // soft falloff band at boundary (blocks)
        private static final int SLOPE_NOISE_MAX = 1;                    // +- blocks of height noise on slopes (1–2)
        private static final double SLOPE_NOISE_SCALE = 1.0 / 48.0;      // slope noise frequency (1/32..1/64)


        // ---------------- Seed & noise ----------------
        private long gradeSeed() {
            long s = 0x9E3779B97F4A7C15L;
            s ^= (long) origin.getX() * 0xBF58476D1CE4E5B9L;
            s ^= (long) origin.getZ() * 0x94D049BB133111EBL;
            s ^= (long) baseY * 0xD6E8FEB86659FD93L;
            return s;
        }

        // Deterministic hash noise in [0,1)
        private double noise01(int x, int z, long seed) {
            long h = seed;
            h ^= (long) x * 0x9E3779B97F4A7C15L;
            h ^= (long) z * 0xC2B2AE3D27D4EB4FL;
            h ^= (h >>> 30);
            h *= 0xBF58476D1CE4E5B9L;
            h ^= (h >>> 27);
            h *= 0x94D049BB133111EBL;
            h ^= (h >>> 31);
            return (h >>> 11) * (1.0 / (1L << 53));
        }

        private double clamp01(double v) {
            return Math.max(0.0, Math.min(1.0, v));
        }

        // If you already have smoothstep(t) in Task, delete this duplicate.
        private double smoothstepLocal(double t) {
            t = clamp01(t);
            return t * t * (3.0 - 2.0 * t);
        }

        // If you already have lerp(a,b,t) in Task, delete this duplicate.
        private double lerpLocal(double a, double b, double t) {
            return a + (b - a) * t;
        }

        // Smooth value-noise centered in ~[-1, 1] at a given scale
        private double smoothNoiseCentered(int x, int z, double scale, long seed) {
            double fx = x * scale;
            double fz = z * scale;

            int ix = (int) Math.floor(fx);
            int iz = (int) Math.floor(fz);

            double tx = fx - ix;
            double tz = fz - iz;

            // smoothstep
            tx = tx * tx * (3.0 - 2.0 * tx);
            tz = tz * tz * (3.0 - 2.0 * tz);

            double n00 = noise01(ix,     iz,     seed);
            double n10 = noise01(ix + 1, iz,     seed);
            double n01 = noise01(ix,     iz + 1, seed);
            double n11 = noise01(ix + 1, iz + 1, seed);

            double nx0 = n00 + (n10 - n00) * tx;
            double nx1 = n01 + (n11 - n01) * tx;
            double nxy = nx0 + (nx1 - nx0) * tz;

            return (nxy * 2.0) - 1.0;
        }

        // Noisy distance as DOUBLE (prevents pixel edges)
        private double distToFootprintNoisyD(int x, int z) {
            int baseDist = distToFootprint(x, z); // your existing rect distance (0 inside)
            if (baseDist <= 0) return 0.0;

            long seed = gradeSeed() ^ 0xBEEFCAFE1234L;
            double warpN = smoothNoiseCentered(x, z, EDGE_NOISE_SCALE, seed); // [-1,1]
            double warped = baseDist - (warpN * EDGE_WARP_BLOCKS);
            return Math.max(0.0, warped);
        }

        // Per-column effective margin, but smooth in 16-block cells so it doesn't sparkle
        private int columnEffectiveMargin(int x, int z) {
            long seed = gradeSeed() ^ 0x1234ABCDL;

            int gx = x >> 4;  // 16-block cells (smoother)
            int gz = z >> 4;

            double n = noise01(gx, gz, seed); // [0,1)
            int jitter = (int) Math.round(lerpLocal(-EDGE_MARGIN_JITTER, EDGE_MARGIN_JITTER, n));
            return Math.max(1, PREP_MARGIN + jitter);
        }

        // ---------------- Your existing safe surface pickers ----------------
        private BlockState safeSurfaceTop(int x, int z) {
            int y = BlueprintPlacerEngine.surfaceY(level, x, z);
            scratch.set(x, y, z);
            BlockState top = level.getBlockState(scratch);

            if (top.isAir() || isLiquid(top) || isFoliageOrSoft(top)) {
                return Blocks.GRASS_BLOCK.defaultBlockState();
            }
            return top;
        }

        private BlockState safeSurfaceUnder(int x, int z) {
            int y = BlueprintPlacerEngine.surfaceY(level, x, z);
            scratch.set(x, y - 1, z);
            BlockState under = level.getBlockState(scratch);

            if (under.isAir() || isLiquid(under) || isFoliageOrSoft(under)) {
                return Blocks.DIRT.defaultBlockState();
            }
            return under;
        }

        private BlockState safeDeepFill(int x, int z) {
            int y = BlueprintPlacerEngine.surfaceY(level, x, z);
            int yy = Math.max(level.getMinY() + 1, y - 4);
            scratch.set(x, yy, z);
            BlockState deep = level.getBlockState(scratch);

            if (deep.isAir() || isLiquid(deep) || isFoliageOrSoft(deep)) {
                return Blocks.STONE.defaultBlockState();
            }
            return deep;
        }

        // ---------------- Protected-build scan (your existing logic) ----------------
        private boolean columnHasProtectedBuild(int x, int z, int surfaceY) {
            int y0 = Math.max(level.getMinY(), surfaceY - PROTECT_SCAN_BELOW);
            int y1 = Math.min(level.getMaxY() - 1, surfaceY + PROTECT_SCAN_ABOVE);

            for (int y = y0; y <= y1; y++) {
                scratch.set(x, y, z);
                BlockState bs = level.getBlockState(scratch);
                if (isProtectedBuildBlock(bs)) return true;
            }
            return false;
        }

        private boolean isProtectedBuildBlock(BlockState bs) {
            if (bs.isAir()) return false;
            if (isLiquid(bs)) return false;
            if (isFoliageOrSoft(bs)) return false;
            if (isLogOrWood(bs)) return false; // logs not protected; tree-logic prevents nuking log houses

            if (bs.is(Blocks.GRASS_BLOCK) || bs.is(Blocks.DIRT) || bs.is(Blocks.COARSE_DIRT) ||
                    bs.is(Blocks.ROOTED_DIRT) || bs.is(Blocks.PODZOL) || bs.is(Blocks.MYCELIUM) ||
                    bs.is(Blocks.SAND) || bs.is(Blocks.RED_SAND) || bs.is(Blocks.GRAVEL) ||
                    bs.is(Blocks.CLAY) || bs.is(Blocks.STONE) || bs.is(Blocks.DEEPSLATE) ||
                    bs.is(Blocks.ANDESITE) || bs.is(Blocks.DIORITE) || bs.is(Blocks.GRANITE) ||
                    bs.is(Blocks.TUFF) || bs.is(Blocks.CALCITE)) {
                return false;
            }

            Block b = bs.getBlock();

            if (bs.is(Blocks.COBBLESTONE) || bs.is(Blocks.MOSSY_COBBLESTONE) ||
                    bs.is(Blocks.STONE_BRICKS) || bs.is(Blocks.CRACKED_STONE_BRICKS) || bs.is(Blocks.MOSSY_STONE_BRICKS) ||
                    bs.is(Blocks.BRICKS) || bs.is(Blocks.NETHER_BRICKS) ||
                    bs.is(Blocks.GLASS) || bs.is(Blocks.GLASS_PANE) ||
                    bs.is(Blocks.WHITE_WOOL) || bs.is(Blocks.BLACK_WOOL) || bs.is(Blocks.GRAY_WOOL) ||
                    bs.is(Blocks.WHITE_CONCRETE) || bs.is(Blocks.BLACK_CONCRETE) ||
                    bs.is(Blocks.TERRACOTTA) || bs.is(Blocks.WHITE_TERRACOTTA) ||
                    bs.is(Blocks.CRAFTING_TABLE) || bs.is(Blocks.FURNACE) ||
                    bs.is(Blocks.CHEST) || bs.is(Blocks.BARREL) ||
                    bs.is(Blocks.TORCH) || bs.is(Blocks.LANTERN) ||
                    bs.is(Blocks.OAK_PLANKS) || bs.is(Blocks.SPRUCE_PLANKS) || bs.is(Blocks.BIRCH_PLANKS) ||
                    bs.is(Blocks.JUNGLE_PLANKS) || bs.is(Blocks.ACACIA_PLANKS) || bs.is(Blocks.DARK_OAK_PLANKS) ||
                    bs.is(Blocks.MANGROVE_PLANKS) || bs.is(Blocks.CHERRY_PLANKS) || bs.is(Blocks.BAMBOO_PLANKS)) {
                return true;
            }

            String key = BuiltInRegistries.BLOCK.getKey(b).toString();
            if (key.contains("planks") || key.contains("wood") ||
                    key.contains("stairs") || key.contains("slab") || key.contains("fence") ||
                    key.contains("door") || key.contains("trapdoor") || key.contains("wall") ||
                    key.contains("pane") || key.contains("glass") ||
                    key.contains("bricks") || key.contains("concrete") || key.contains("terracotta")) {
                return true;
            }

            return false;
        }

        // ---------------- Tree detection ----------------
        
        private boolean isLeaf(BlockState bs) {
            return bs.is(BlockTags.LEAVES) || (bs.getBlock() instanceof LeavesBlock);
        }

        private boolean isLog(BlockState bs) {
            return bs.is(BlockTags.LOGS) || bs.is(BlockTags.LOGS_THAT_BURN) || isLogOrWood(bs);
        }
                
        private boolean isSoilLike(BlockState bs) {
            return bs.is(Blocks.DIRT)
                    || bs.is(Blocks.GRASS_BLOCK)
                    || bs.is(Blocks.COARSE_DIRT)
                    || bs.is(Blocks.ROOTED_DIRT)
                    || bs.is(Blocks.PODZOL)
                    || bs.is(Blocks.MYCELIUM)
                    || bs.is(Blocks.MOSS_BLOCK);
        }
            
        
        private boolean isLogOrWood(BlockState bs) {
            return bs.is(Blocks.OAK_LOG) || bs.is(Blocks.SPRUCE_LOG) || bs.is(Blocks.BIRCH_LOG) ||
                    bs.is(Blocks.JUNGLE_LOG) || bs.is(Blocks.ACACIA_LOG) || bs.is(Blocks.DARK_OAK_LOG) ||
                    bs.is(Blocks.MANGROVE_LOG) || bs.is(Blocks.CHERRY_LOG) ||
                    bs.is(Blocks.OAK_WOOD) || bs.is(Blocks.SPRUCE_WOOD) || bs.is(Blocks.BIRCH_WOOD) ||
                    bs.is(Blocks.JUNGLE_WOOD) || bs.is(Blocks.ACACIA_WOOD) || bs.is(Blocks.DARK_OAK_WOOD) ||
                    bs.is(Blocks.MANGROVE_WOOD) || bs.is(Blocks.CHERRY_WOOD) ||
                    bs.is(Blocks.STRIPPED_OAK_LOG) || bs.is(Blocks.STRIPPED_SPRUCE_LOG) || bs.is(Blocks.STRIPPED_BIRCH_LOG) ||
                    bs.is(Blocks.STRIPPED_JUNGLE_LOG) || bs.is(Blocks.STRIPPED_ACACIA_LOG) || bs.is(Blocks.STRIPPED_DARK_OAK_LOG) ||
                    bs.is(Blocks.STRIPPED_MANGROVE_LOG) || bs.is(Blocks.STRIPPED_CHERRY_LOG) ||
                    bs.is(Blocks.STRIPPED_OAK_WOOD) || bs.is(Blocks.STRIPPED_SPRUCE_WOOD) || bs.is(Blocks.STRIPPED_BIRCH_WOOD) ||
                    bs.is(Blocks.STRIPPED_JUNGLE_WOOD) || bs.is(Blocks.STRIPPED_ACACIA_WOOD) || bs.is(Blocks.STRIPPED_DARK_OAK_WOOD) ||
                    bs.is(Blocks.STRIPPED_MANGROVE_WOOD) || bs.is(Blocks.STRIPPED_CHERRY_WOOD);
        }

        private boolean hasLeavesNearby(int x, int y, int z, int r) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        scratch.set(x + dx, y + dy, z + dz);
                        BlockState s = level.getBlockState(scratch);
                       if (isLeaf(s)) return true;
                    }
                }
            }
            return false;
        }

        private boolean isNaturalTreeJunkAt(int x, int y, int z, BlockState bs) {
            if (bs.isAir()) return false;
            if (isLeaf(bs)) return true;
            if (bs.is(Blocks.VINE)) return true;
            return false;
        }


        // ---------------- MAIN grading loop ----------------
        private long lastGradeLogNanos = 0;

        private boolean gradeTerrain(MinecraftServer server, int budget, long deadlineNanos) {
            if (gradeColsDone == 0) {
                dbg("GRADE_BEGIN bp=" + bp.id
                        + " origin=" + origin
                        + " baseY=" + baseY
                        + " margin=" + PREP_MARGIN
                        + " bounds=(" + prepMinX + "," + prepMinZ + ")->(" + prepMaxX + "," + prepMaxZ + ")");
            }

            int ops = 0;

            while (ops < budget) {

                long now = System.nanoTime();
                if (now - lastGradeLogNanos > 1_000_000_000L) { // 1s
                    dbg("GRADE_HEARTBEAT bp=" + bp.id
                        + " colsDone=" + gradeColsDone
                        + " colActive=" + colActive
                        + " colStage=" + colStage
                        + " scan=(" + scanX + "," + scanZ + ")"
                        + " col=(" + colX + "," + colZ + ")"
                        + " colY=" + colY);
                    lastGradeLogNanos = now;
                }


                if (System.nanoTime() >= deadlineNanos) return false;

                if (!colActive) {
                    if (scanZ > prepMaxZ) {
                        dbg("GRADE_DONE bp=" + bp.id + " colsDone=" + gradeColsDone);
                        return true;
                    }

                    int x = scanX;
                    int z = scanZ;

                    scanX++;
                    if (scanX > prepMaxX) {
                        scanX = prepMinX;
                        scanZ++;
                    }

                    int cX = x >> 4;
                    int cZ = z >> 4;
                    if (level.getChunkSource().getChunkNow(cX, cZ) == null) return false;

                    colActive = true;
                    colEdgeFade = 1.0;
                    colX = x;
                    colZ = z;
                    colSawLeaves = false;
                    haloActive = false;
                    haloDx = -TREE_LEAF_CLEAR_RADIUS;
                    haloDz = -TREE_LEAF_CLEAR_RADIUS;

                    colSurfaceY = BlueprintPlacerEngine.surfaceY(level, colX, colZ);

                    // Smooth, warped distance
                    double d = distToFootprintNoisyD(colX, colZ);
                    int effMargin = columnEffectiveMargin(colX, colZ);

                    // Feathered boundary: fade grading out instead of hard cutoff
                    double over = d - effMargin;                 // <=0 inside, >0 outside
                    colEdgeFade = 1.0 - clamp01(over / EDGE_FEATHER);
                    
                    colDoTerrain = (colEdgeFade > 0.0);

                    // Convert to int for internal stage logic where you used colDist previously
                    colDist = (int) Math.floor(d);

                    // Keep your "don't touch near protected builds" rule outside the footprint
                    if (colDist != 0 && columnHasProtectedBuild(colX, colZ, colSurfaceY)) {
                        colActive = false;
                        gradeColsDone++;
                        continue;
                    }

                    // Desired Y:
                    if (colDist == 0) {
                        colDesiredY = baseY;
                    } else {
                        double t = Math.min(1.0, d / (double) effMargin);

                        // Use your existing smoothstep if you have it; else smoothstepLocal.
                        double ts = smoothstepLocal(t);

                        // As we approach the outer boundary, push toward surface (natural fade)
                        ts = ts * colEdgeFade + (1.0 - colEdgeFade);

                        int blended = (int) Math.round(lerpLocal(baseY, colSurfaceY, ts));

                        // Subtle smooth slope noise, faded out at edge and near outer blend
                        long seed = gradeSeed() ^ 0xDEADBEEFCAFEL;
                        double n = smoothNoiseCentered(colX, colZ, SLOPE_NOISE_SCALE, seed); // [-1,1]
                        int amp = (int) Math.round(lerpLocal(SLOPE_NOISE_MAX, 0.0, ts));     // less noise farther out
                        amp = (int) Math.round(amp * colEdgeFade);                           // no noise at boundary
                        int jitter = (int) Math.round(n * amp);

                        colDesiredY = blended + jitter;
                    }

                    colTopState = safeSurfaceTop(colX, colZ);
                    colUnderState = safeSurfaceUnder(colX, colZ);
                    colDeepState = safeDeepFill(colX, colZ);

                    // Always do tree purge first (entire graded area)
                    colStage = -1;
                    colY = 0;
                    colYInit = false;
                }

                while (ops < budget) {
                    if (System.nanoTime() >= deadlineNanos) return false;

                    // --------------------
                    // Stage -1: tree purge
                    // --------------------
            
                    if (colStage == -1) {
                        final int yStart = Math.max(level.getMinY(), colSurfaceY - TREE_SCAN_BELOW_SURFACE);
                        final int yEnd   = Math.min(level.getMaxY() - 1, colSurfaceY + TREE_CLEAR_EXTRA_HEIGHT);

                        // Initialize starting Y (top-down) once
                        if (!colYInit) {
                            colYInit = true;
                            colY = yEnd;
                            haloActive = false;
                        }

                        // Finished tree purge
                        if (colY < yStart) {
                            haloActive = false;
                            colYInit = false; 
                            if (!colDoTerrain) {
                                colStage = 4;
                                continue;
                            }
                            colStage = 0;
                            colY = 0;
                            continue;
                        }

                        // --- 1) Clear center cell (colX,colY,colZ) ---
                        scratch.set(colX, colY, colZ);
                        BlockState bs = level.getBlockState(scratch);

                        if (isLeaf(bs) || bs.is(Blocks.VINE)) {
                            colSawLeaves = true;
                            level.setBlock(scratch, Blocks.AIR.defaultBlockState(), 2);
                            ops++;
                        } else if (isLog(bs)) {
                            // inside grading rectangle: treat logs as tree junk
                            level.setBlock(scratch, Blocks.AIR.defaultBlockState(), 2);
                            ops++;
                        } else if (isSoilLike(bs)) {
                            // Remove floating soil
                            scratch.set(colX, colY - 1, colZ);
                            BlockState below = level.getBlockState(scratch);

                            boolean unsupported = below.isAir() || !below.getFluidState().isEmpty()
                                    || isLeaf(below) || below.is(Blocks.VINE);

                            if (unsupported) {
                                scratch.set(colX, colY, colZ);
                                level.setBlock(scratch, Blocks.AIR.defaultBlockState(), 2);
                                ops++;
                            }
                        }

                        // --- 2) Halo scan around this Y (persistent across ticks) ---
                        if (!haloActive) {
                            haloActive = true;
                            haloDx = -TREE_LEAF_CLEAR_RADIUS;
                            haloDz = -TREE_LEAF_CLEAR_RADIUS;
                        }

                        int haloChecks = 0;
                        while (haloChecks < HALO_CHECKS_PER_TICK && ops < budget) {
                            // Finished halo for this Y
                            if (haloDx > TREE_LEAF_CLEAR_RADIUS) {
                                haloActive = false;
                                break;
                            }

                            // Skip center
                            if (!(haloDx == 0 && haloDz == 0)) {
                                scratch.set(colX + haloDx, colY, colZ + haloDz);
                                BlockState near = level.getBlockState(scratch);

                                if (isLeaf(near) || near.is(Blocks.VINE)) {
                                    colSawLeaves = true;
                                    level.setBlock(scratch, Blocks.AIR.defaultBlockState(), 2);
                                    ops++;
                                }
                            }

                            // advance halo cursor
                            haloDz++;
                            if (haloDz > TREE_LEAF_CLEAR_RADIUS) {
                                haloDz = -TREE_LEAF_CLEAR_RADIUS;
                                haloDx++;
                            }

                            haloChecks++;
                    }

                    // If halo not finished yet, yield WITHOUT changing colY
                    if (haloActive) {
                        return false;
                    }

                    // Halo finished => now step downward exactly once
                    colY--;
                    continue;
                }


                        // -----------------------
                        // Stage 0: clear foliage above
                        // -----------------------
                        if (colStage == 0) {
                            final int yStart = colDesiredY + 1;
                            final int yEnd   = colDesiredY + CLEAR_ABOVE;

                            // ✅ init once per stage/column (no sentinel "colY==0")
                            if (!colYInit) {
                                colYInit = true;
                                colY = yStart;
                            }

                            // ✅ done
                            if (colY > yEnd) {
                                colStage = 1;
                                colYInit = false; // prepare Stage 1 init
                                // (optional) colY = 0; not required, but fine either way
                                continue;
                            }

                            scratch.set(colX, colY, colZ);
                            BlockState bs = level.getBlockState(scratch);

                            if (!bs.isAir()) {
                                // Clear leaves/vines always
                                if (isLeaf(bs) || bs.is(Blocks.VINE)) {
                                    level.setBlock(scratch, Blocks.AIR.defaultBlockState(), 2);
                                    ops++;
                                }
                                // Clear logs if they still look like tree trunk (leaves nearby)
                                else if (isLog(bs) && hasLeavesNearby(colX, colY, colZ, LEAF_NEARBY_RADIUS)) {
                                    level.setBlock(scratch, Blocks.AIR.defaultBlockState(), 2);
                                    ops++;
                                }
                            }

                            colY++;
                            continue;
                        }


                    // -----------------------
                    // Stage 1: cut down to desiredY
                    // -----------------------
                    if (colStage == 1) {
                        int yStart = (colDist == 0) ? colDesiredY : (colDesiredY + 1);
                        int yEnd = colDesiredY + MAX_CUT_HEIGHT;

                        // ✅ init once per stage/column
                        if (!colYInit) {
                            colYInit = true;
                            colY = yStart;
                        }

                        // ✅ done
                        if (colY > yEnd) {
                            colStage = 2;
                            colYInit = false; // prepare for stage 2 init
                            continue;
                        }

                        scratch.set(colX, colY, colZ);
                        BlockState bs = level.getBlockState(scratch);

                        if (!bs.isAir()) {
                            if (colDist == 0) {
                                level.setBlock(scratch, Blocks.AIR.defaultBlockState(), 2);
                                ops++;
                            } else if (!isLiquid(bs)) {
                                if (!isProtectedBuildBlock(bs)) {
                                    level.setBlock(scratch, Blocks.AIR.defaultBlockState(), 2);
                                    ops++;
                                }
                            }
                        }

                        colY++;
                        continue;
                    }

                    // -----------------------
                    // Stage 2: fill up to desiredY
                    // -----------------------
                    if (colStage == 2) {
                        if (colSurfaceY >= colDesiredY) { colStage = 3; colY = 0; continue; }

                        int from = colSurfaceY + 1;
                        int to = colDesiredY;

                        if ((to - from) > MAX_FILL_DEPTH) { colStage = 3; colY = 0; continue; }

                        if (!colYInit) { colYInit = true; colY = from; }
                        if (colY > to) { colStage = 3; colYInit = false; continue; }
                        
                        scratch.set(colX, colY, colZ);

                        int depthBelowCap = colDesiredY - colY;
                        BlockState fill = (depthBelowCap <= 3) ? colUnderState : colDeepState;

                        if (fill == null || fill.isAir() || isLiquid(fill)) {
                            fill = (depthBelowCap <= 3)
                                    ? Blocks.DIRT.defaultBlockState()
                                    : Blocks.STONE.defaultBlockState();
                        }

                        BlockState cur = level.getBlockState(scratch);
                        if (colDist != 0 && isProtectedBuildBlock(cur)) {
                            colStage = 3; colY = 0;
                            continue;
                        }

                        level.setBlock(scratch, fill, 2);
                        ops++;
                        colY++;
                        continue;
                    }

                    // -----------------------
                    // Stage 3: cap the top (DON'T cap in trees / on foliage)
                    // -----------------------
                    if (colStage == 3) {
                        // Position at cap
                        scratch.set(colX, colDesiredY, colZ);
                        BlockState cur = level.getBlockState(scratch);

                        if (!isLiquid(cur)) {

                            // If the cap position is tree junk, clear it first
                            if (isNaturalTreeJunkAt(colX, colDesiredY, colZ, cur)) {
                                level.setBlock(scratch, Blocks.AIR.defaultBlockState(), 2);
                                ops++;
                                cur = level.getBlockState(scratch);
                            }

                            // After clearing, if the cap position is STILL foliage/log-like for any reason, abort capping
                            if (isLeaf(cur) || cur.is(Blocks.VINE) || isLog(cur)) {
                                colStage = 4;
                                continue;
                            }

                            // Check the block UNDER the cap *after* any clearing
                            scratch.set(colX, colDesiredY - 1, colZ);
                            BlockState below = level.getBlockState(scratch);

                            // If the support is not solid terrain, do not cap (prevents floating "dirt bricks")
                            if (below.isAir() || isLiquid(below) || isFoliageOrSoft(below) || isLeaf(below) || below.is(Blocks.VINE) || isLog(below)) {
                                // restore scratch isn't necessary since we continue, but keep it tidy if you like
                                colStage = 4;
                                continue;
                            }

                            // Restore scratch to cap position
                            scratch.set(colX, colDesiredY, colZ);

                            // Outside footprint: don't overwrite protected builds
                            if (colDist != 0 && isProtectedBuildBlock(cur)) {
                                colStage = 4;
                                continue;
                            }

                            BlockState cap = colTopState;
                            if (cap == null || cap.isAir() || isLiquid(cap) || isFoliageOrSoft(cap)) {
                                cap = Blocks.GRASS_BLOCK.defaultBlockState();
                            }

                            level.setBlock(scratch, cap, 2);
                            ops++;
                        }

                        colStage = 4;
                        continue;
                    }


                    // -----------------------
                    // Stage 4: finish column
                    // -----------------------
                    if (colStage == 4) {
                        colActive = false;
                        gradeColsDone++;

                        if (DEBUG && (gradeColsDone % DEBUG_EVERY_N_GRADE_COLS == 0)) {
                            dbg("GRADE_PROGRESS bp=" + bp.id
                                    + " colsDone=" + gradeColsDone
                                    + " atScan=(" + scanX + "," + scanZ + ")");
                        }
                        break;
                    }
                }
            }

            return false;
        }



        // ---------------- Placement ----------------

        private boolean placeBlueprint(MinecraftServer server, int budget, long deadlineNanos) throws IOException {
            if (!placeBeginLogged) {
                placeBeginLogged = true;
                dbg("PLACE_BEGIN bp=" + bp.id + " origin=" + origin + " baseY=" + baseY + " yShift=" + originYShift);
            }

            int placed = 0;
            while (placed < budget) {
                if (System.nanoTime() >= deadlineNanos) return false;

                if (secIn == null) {
                    if (secY >= bp.sectionsY) return true;
                    openCurrentSectionOrSkip(server);
                    continue;
                }

                if (cy >= dy) {
                    closeSection();
                    advanceSectionCoords();
                    continue;
                }

                int paletteIndex = readU16LE(secIn);

                int worldX = origin.getX() + secX * bp.sectionSize + cx;
                int worldY = (origin.getY() + originYShift) + secY * bp.sectionSize + cy;
                int worldZ = origin.getZ() + secZ * bp.sectionSize + cz;

                cx++;
                if (cx >= dx) {
                    cx = 0;
                    cz++;
                    if (cz >= dz) {
                        cz = 0;
                        cy++;
                    }
                }

                if (!includeAir && bp.airId != -1 && paletteIndex == bp.airId) continue;

                BlockState state = paletteState(paletteIndex);
                if (state == null) continue;
                if (!includeAir && state.isAir()) continue;

                int cX = worldX >> 4;
                int cZ = worldZ >> 4;
                if (!ensureChunkLoadedDuringPlace(cX, cZ, server)) {
                    return false; // wait this tick, try again next tick
                }

                scratch.set(worldX, worldY, worldZ);

                if (!placeLogged) {
                    placeLogged = true;
                    LOGGER.info("[Kingdoms] Placing '{}' starting at {} (baseY={}, yShift={})",
                            bp.id, origin, baseY, originYShift);
                }


                level.setBlock(scratch, state, 2);
                placed++;
            }

            return false;


        }

        private Integer chooseBaseYClamped(MinecraftServer server, int budget) {
            if (!baseInit) {
                baseInit = true;
                baseX = footMinX;
                baseZ = footMinZ;
                baseSamples.clear();
            }

            int ops = 0;
            while (ops < budget) {
                int cX = baseX >> 4;
                int cZ = baseZ >> 4;

                if (!ensureChunkLoadedDuringPreflight(cX, cZ, server)) {
                    if ((server.getTickCount() % 40) == 0) {
                        LOGGER.info("[BPQ][BASEY] waiting chunk=({}, {}) at sample=({}, {})", cX, cZ, baseX, baseZ);
                    }
                    return null; // yield, BUT keep baseX/baseZ where they are
                }

                baseSamples.add(BlueprintPlacerEngine.surfaceY(level, baseX, baseZ));

                // advance cursor
                baseZ += FOOTPRINT_SAMPLE_STEP;
                if (baseZ > footMaxZ) {
                    baseZ = footMinZ;
                    baseX += FOOTPRINT_SAMPLE_STEP;
                }

                // done?
                if (baseX > footMaxX) {
                    break;
                }

                ops++;
            }

            if (baseX <= footMaxX) return null; // not finished yet

            // compute median/clamp from baseSamples (same math as before)
            baseSamples.sort(Integer::compare);
            int median = baseSamples.getInt(baseSamples.size() / 2);
            int min = baseSamples.getInt(0);
            int max = baseSamples.getInt(baseSamples.size() - 1);

            int lo = max - MAX_CUT_HEIGHT;
            int hi = min + MAX_FILL_DEPTH;
            if (lo > hi) return median;
            return clamp(median, lo, hi);
        }

        private static int clamp(int v, int lo, int hi) {
            if (v < lo) return lo;
            if (v > hi) return hi;
            return v;
        }

        private boolean isWaterNearSurface(int x, int surfaceY, int z) {
            for (int y = surfaceY; y <= surfaceY + 2; y++) {
                scratch.set(x, y, z);
                BlockState bs = level.getBlockState(scratch);
                if (!bs.getFluidState().isEmpty()) return true;
                if (bs.is(Blocks.KELP) || bs.is(Blocks.SEAGRASS) || bs.is(Blocks.TALL_SEAGRASS)) return true;
            }
            return false;
        }

        private int distToFootprint(int x, int z) {
            int dx = 0;
            if (x < footMinX) dx = footMinX - x;
            else if (x > footMaxX) dx = x - footMaxX;

            int dz = 0;
            if (z < footMinZ) dz = footMinZ - z;
            else if (z > footMaxZ) dz = z - footMaxZ;

            return Math.max(dx, dz);
        }


        private boolean isLiquid(BlockState bs) {
            return !bs.getFluidState().isEmpty();
        }

        private boolean isFoliageOrSoft(BlockState bs) {
            if (bs.isAir()) return true;

            if (bs.is(Blocks.SHORT_GRASS) || bs.is(Blocks.TALL_GRASS)) return true;
            if (bs.is(Blocks.FERN) || bs.is(Blocks.LARGE_FERN)) return true;
            if (bs.is(Blocks.DEAD_BUSH)) return true;
            if (bs.is(Blocks.SNOW) || bs.is(Blocks.SNOW_BLOCK)) return true;
            if (bs.is(Blocks.VINE)) return true;
            if (bs.is(Blocks.SUGAR_CANE)) return true;

            if (bs.getBlock() instanceof LeavesBlock) return true;

            if (bs.is(Blocks.OAK_LOG) || bs.is(Blocks.SPRUCE_LOG) || bs.is(Blocks.BIRCH_LOG) ||
                    bs.is(Blocks.JUNGLE_LOG) || bs.is(Blocks.ACACIA_LOG) || bs.is(Blocks.DARK_OAK_LOG) ||
                    bs.is(Blocks.MANGROVE_LOG) || bs.is(Blocks.CHERRY_LOG) ||
                    bs.is(Blocks.OAK_WOOD) || bs.is(Blocks.SPRUCE_WOOD) || bs.is(Blocks.BIRCH_WOOD) ||
                    bs.is(Blocks.JUNGLE_WOOD) || bs.is(Blocks.ACACIA_WOOD) || bs.is(Blocks.DARK_OAK_WOOD) ||
                    bs.is(Blocks.MANGROVE_WOOD) || bs.is(Blocks.CHERRY_WOOD)) {
                return true;
            }

            return false;
        }

       private void openCurrentSectionOrSkip(MinecraftServer server) throws IOException {
            byte[] bytes;
            try (InputStream raw = bp.openSection(server, modId, secX, secY, secZ)) {
                if (raw == null) throw new IOException("openSection returned null");
                bytes = raw.readAllBytes();
            } catch (Exception e) {
                // IMPORTANT: catch Exception, not just IOException, so runtime failures don’t silently “finish”
                if (secX == 0 && secY == 0 && secZ == 0) {
                    throw new IOException("Missing section 0_0_0 for blueprint '" + bp.id + "'", e);
                }
                LOGGER.warn("[Kingdoms] Missing section {}_{}_{} for blueprint '{}' (skipping)",
                        secX, secY, secZ, bp.id);
                this.secIn = null;
                advanceSectionCoords();
                return;
            }

            // Decide if this file has a 3-byte header (dx,dy,dz)
            int off = 0;

            int hdx = (bytes.length >= 1) ? (bytes[0] & 0xFF) : 0;
            int hdy = (bytes.length >= 2) ? (bytes[1] & 0xFF) : 0;
            int hdz = (bytes.length >= 3) ? (bytes[2] & 0xFF) : 0;

            boolean headerLooksValid =
                    bytes.length >= 3 &&
                    hdx > 0 && hdy > 0 && hdz > 0 &&
                    hdx <= bp.sectionSize && hdy <= bp.sectionSize && hdz <= bp.sectionSize;

            if (headerLooksValid) {
                dx = hdx; dy = hdy; dz = hdz;
                off = 3;
            } else {
                // Assume “no header” format (full cube)
                dx = bp.sectionSize;
                dy = bp.sectionSize;
                dz = bp.sectionSize;
                off = 0;
            }

            dbg("OPEN_SECTION bp=" + bp.id
                    + " sec=(" + secX + "," + secY + "," + secZ + ")"
                    + " bytes=" + bytes.length
                    + " header=" + headerLooksValid
                    + " dims=(" + dx + "," + dy + "," + dz + ")"
                    + " off=" + off);

            this.secIn = new DataInputStream(new ByteArrayInputStream(bytes, off, bytes.length - off));

            this.cx = 0;
            this.cy = 0;
            this.cz = 0;
        }


        private void closeSection() {
            if (secIn != null) {
                try { secIn.close(); } catch (IOException ignored) {}
                secIn = null;
            }
        }

        private void advanceSectionCoords() {
            secX++;
            if (secX >= bp.sectionsX) {
                secX = 0;
                secZ++;
                if (secZ >= bp.sectionsZ) {
                    secZ = 0;
                    secY++;
                }
            }
        }

        private BlockState paletteState(int idx) {
            return paletteCache.computeIfAbsent(idx, this::parseBlockState);
        }

        private BlockState parseBlockState(int idx) {
            if (idx < 0 || idx >= bp.palette.size()) return null;

            String s = bp.palette.get(idx);

            try {
                return net.minecraft.commands.arguments.blocks.BlockStateParser
                        .parseForBlock(BuiltInRegistries.BLOCK, s, true)
                        .blockState();
            } catch (Exception ignored) {}

            try {
                String idStr = s;
                int props = idStr.indexOf('[');
                if (props >= 0) idStr = idStr.substring(0, props);

                return net.minecraft.commands.arguments.blocks.BlockStateParser
                        .parseForBlock(BuiltInRegistries.BLOCK, idStr, true)
                        .blockState();
            } catch (Exception ignored) {}

            return Blocks.AIR.defaultBlockState();
        }

        private static int readU16LE(DataInputStream in) throws IOException {
            int lo = in.readUnsignedByte();
            int hi = in.readUnsignedByte();
            return (hi << 8) | lo;
        }
    }
}