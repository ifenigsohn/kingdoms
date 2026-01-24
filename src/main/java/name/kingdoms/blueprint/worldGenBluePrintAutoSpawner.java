package name.kingdoms.blueprint;

import com.mojang.logging.LogUtils;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Deterministic, persisted region-based kingdom spawner.
 *
 * Goals:
 *  - "One per region forever" (decision is persisted)
 *  - Pre-decide regions near players so there are no surprise kingdoms later
 *  - WIN regions remain WIN_PENDING until successfully placed
 *  - Optionally force-load target chunks to avoid "must rejoin/TP" behavior
 */
public final class worldGenBluePrintAutoSpawner {
    private static final Logger LOGGER = LogUtils.getLogger();
    private worldGenBluePrintAutoSpawner() {}

    // =========================================================
    // CONFIG
    // =========================================================

    private static final boolean LOG = true;

    private static final int REGION_SIZE_BLOCKS = 700;

    /** 1/RARITY regions are WIN. */
    private static final int RARITY = 2;

    private static final String MOD_ID = "kingdoms";
    private static final String[] CASTLE_POOL = { "castlenew1", "castlemed1","castlenew2","castlenew3","castlenew4","castlenew5" };
    private static final boolean INCLUDE_AIR = false;

    // Anti-surprise: decide all regions within viewDist + EXTRA around players
    private static final int DECIDE_EXTRA_CHUNKS = 8;

    // Placement ring: only place if planned chunk is within this ring of any player
    private static final int PLACE_MIN_DIST_CHUNKS = 20;
    private static final int PLACE_MAX_EXTRA_CHUNKS = 60;

    // Throughput knobs
    private static final int DECIDE_REGIONS_PER_TICK = 2; // how many region decisions we persist per tick
    private static final int PLACE_PER_TICK = 1;           // how many castles to enqueue per tick
    private static final int MAX_BP_QUEUE = 1;             // if BlueprintPlacerEngine queue is >= this, pause spawning

    // Avoid "stalls" (recommended ON)
    private static final boolean FORCE_LOAD_TARGET_CHUNK = false;

    // Biome policy
    private static final boolean BLOCK_COLD_BIOMES = true;

    // Spacing
    private static final int MIN_CASTLE_SPACING_BLOCKS = 650;
    private static final long MIN_CASTLE_SPACING_SQ = (long) MIN_CASTLE_SPACING_BLOCKS * (long) MIN_CASTLE_SPACING_BLOCKS;

    // Never spawn a kingdom within this many blocks of any player
    private static final int NO_SPAWN_RADIUS_BLOCKS = 150;
    private static final long NO_SPAWN_RADIUS_SQ = (long) NO_SPAWN_RADIUS_BLOCKS * (long) NO_SPAWN_RADIUS_BLOCKS;
    private static final int MAX_PENDING_CHECKS_PER_TICK = 10;

    private static UUID kingdomUuidForRegion(long worldSeed, long regionKey) {
        String s = "kingdoms:region:" + worldSeed + ":" + regionKey;
        return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8));
    }


    private static String pickDifferentCastle(String current, String fallback) {
        if (current == null || current.isBlank()) return fallback;
        if (!current.equals(fallback)) return fallback;
        for (String s : CASTLE_POOL) {
            if (!s.equals(current)) return s;
        }
        return fallback;
    }


    // =========================================================
    // Runtime state
    // =========================================================

    private static int tickAge = 0;

    // Regions that need decisions (rx,rz packed into regionKey)
    private static final Deque<Long> decideQueue = new ArrayDeque<>();

    // Regions that are WIN_PENDING (regionKey)
    private static final Deque<Long> pendingQueue = new ArrayDeque<>();

    // reservation while in-flight (regionKey -> packed xz) to prevent spacing conflicts
    private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap reservedOriginXZ =
            new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

    // =========================================================
    // Retry control (prevents retry storms)
    // =========================================================

    // regionKey -> next tick allowed to attempt placement
    private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap nextPlaceTick =
            new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

    // regionKey -> consecutive failures
    private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap failCount =
            new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

    // regionKey -> last tick we force-loaded its target chunk
    private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap lastForceLoadTick =
            new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

    private static final int FAIL_COOLDOWN_BASE_TICKS = 20 * 5;   // 5s
    private static final int FAIL_COOLDOWN_MAX_TICKS  = 20 * 60;  // 60s
    private static final int FORCELOAD_THROTTLE_TICKS = 20 * 3;   // 3s
    private static final int SWITCH_BP_AFTER_FAILS    = 3;

    private static boolean tooCloseToAnyPlayer(ServerLevel level, int x, int z) {
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - (x + 0.5);
            double dz = p.getZ() - (z + 0.5);
            double d2 = dx * dx + dz * dz;
            if (d2 < NO_SPAWN_RADIUS_SQ) return true;
        }
        return false;
    }

    private static long maxDistSqPointToRect(long px, long pz, int minX, int minZ, int maxX, int maxZ) {
        long dx1 = px - (long) minX;
        long dx2 = px - (long) maxX;
        long dz1 = pz - (long) minZ;
        long dz2 = pz - (long) maxZ;

        long ax = Math.max(dx1 * dx1, dx2 * dx2);
        long az = Math.max(dz1 * dz1, dz2 * dz2);
        return ax + az;
    }

    /**
     * True if for ANY player, the entire region rectangle is inside the no-spawn radius.
     * In that case, re-planning within the region can never succeed (for now).
     */
    private static boolean regionIsFullyInsideNoSpawn(ServerLevel level, int rx, int rz) {
        int minX = rx * REGION_SIZE_BLOCKS;
        int minZ = rz * REGION_SIZE_BLOCKS;
        int maxX = minX + REGION_SIZE_BLOCKS - 1;
        int maxZ = minZ + REGION_SIZE_BLOCKS - 1;

        for (ServerPlayer p : level.players()) {
            long px = (long) Math.floor(p.getX());
            long pz = (long) Math.floor(p.getZ());

            long maxD2 = maxDistSqPointToRect(px, pz, minX, minZ, maxX, maxZ);

            // If even the farthest corner is within the radius, the whole region is blocked.
            if (maxD2 < NO_SPAWN_RADIUS_SQ) return true;
        }
        return false;
    }


    // =========================================================
    // init
    // =========================================================

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickAge++;
            if (server.getPlayerList().getPlayerCount() == 0) return;

            ServerLevel overworld = server.overworld();
            if (overworld == null) return;

             // === WORLDGEN TOGGLE ===
            if (!WorldgenToggleState.get(overworld).isEnabled()) {
                return; // stop deciding + placing entirely
            }

            // Only overworld for now (matches your old logic)
            feedDecideQueue(server, overworld);

            decideSome(overworld);

            rebuildPendingQueueIfNeeded(overworld); // cheap guard
            placeSome(server, overworld);

            if (LOG && (tickAge % (20 * 10) == 0)) {
                LOGGER.info("[Kingdoms][SpawnV3] tick={} decideQ={} pendingQ={} bpQ={} reserved={}",
                        tickAge, decideQueue.size(), pendingQueue.size(), BlueprintPlacerEngine.getQueueSize(), reservedOriginXZ.size());
            }
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            tickAge = 0;
            decideQueue.clear();
            pendingQueue.clear();
            reservedOriginXZ.clear();
            nextPlaceTick.clear();
            failCount.clear();
            lastForceLoadTick.clear();


            ServerLevel overworld = server.overworld();
            if (overworld != null) {
                // rebuild pending from persisted state on startup
                rebuildPendingFromState(overworld);
            }
            if (LOG) LOGGER.info("[Kingdoms][SpawnV3] started");
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            tickAge = 0;
            decideQueue.clear();
            pendingQueue.clear();
            reservedOriginXZ.clear();
            nextPlaceTick.clear();
            failCount.clear();
            lastForceLoadTick.clear();
            if (LOG) LOGGER.info("[Kingdoms][SpawnV3] stopping");
        });
    }

    // =========================================================
    // Decide queue feeding (anti-surprise)
    // =========================================================

    private static void feedDecideQueue(MinecraftServer server, ServerLevel level) {
        int viewDist = server.getPlayerList().getViewDistance();
        int radiusChunks = Math.max(6, viewDist + DECIDE_EXTRA_CHUNKS);

        RegionDecisionStateV2 state = RegionDecisionStateV2.get(level);

        for (ServerPlayer p : level.players()) {
            int pcx = p.chunkPosition().x;
            int pcz = p.chunkPosition().z;

            int minCX = pcx - radiusChunks;
            int maxCX = pcx + radiusChunks;
            int minCZ = pcz - radiusChunks;
            int maxCZ = pcz + radiusChunks;

            // Convert chunk bounds into region bounds.
            int minX = (minCX << 4);
            int maxX = (maxCX << 4);
            int minZ = (minCZ << 4);
            int maxZ = (maxCZ << 4);

            int minRX = Math.floorDiv(minX, REGION_SIZE_BLOCKS);
            int maxRX = Math.floorDiv(maxX, REGION_SIZE_BLOCKS);
            int minRZ = Math.floorDiv(minZ, REGION_SIZE_BLOCKS);
            int maxRZ = Math.floorDiv(maxZ, REGION_SIZE_BLOCKS);

            // Enqueue every region in this rectangle (no sampling)
            for (int rx = minRX; rx <= maxRX; rx++) {
                for (int rz = minRZ; rz <= maxRZ; rz++) {
                    long regionKey = packRegion(rx, rz);

                    // If already decided, ignore
                    if (state.getStatus(regionKey) != RegionDecisionStateV2.Status.UNKNOWN) continue;

                    // Avoid O(n) contains on huge queues by allowing minor duplicates;
                    // decideSome() will skip if already decided.
                    decideQueue.addLast(regionKey);
                }
            }
        }
    }

    // =========================================================
    // Decisions
    // =========================================================

    private static void decideSome(ServerLevel level) {
        RegionDecisionStateV2 state = RegionDecisionStateV2.get(level);

        int did = 0;
        while (did < DECIDE_REGIONS_PER_TICK && !decideQueue.isEmpty()) {
            long regionKey = decideQueue.pollFirst();
            int rx = unpackRX(regionKey);
            int rz = unpackRZ(regionKey);

            if (state.getStatus(regionKey) != RegionDecisionStateV2.Status.UNKNOWN) {
                did++;
                continue;
            }

            // Deterministic decision
            boolean win = regionWins(level, regionKey);

            if (!win) {
                state.put(new RegionDecisionStateV2.Entry(regionKey, rx, rz,
                        RegionDecisionStateV2.Status.LOSE.id,
                        0, 0,
                        "")); // bp irrelevant
                did++;
                continue;
            }

            // Deterministic plan inside region + deterministic bp pick
            Planned plan = planForRegion(level, rx, rz, regionKey);
            if (BLOCK_COLD_BIOMES && isColdBiome(level, plan.x, plan.z)) {
                // If a region "wins" but the planned point is cold, re-roll the plan deterministically
                // by applying a fixed perturbation. Still stable.
                plan = planForRegion(level, rx, rz, regionKey ^ 0xA5A5A5A5A5A5A5A5L);
            }

            state.put(new RegionDecisionStateV2.Entry(regionKey, rx, rz,
                    RegionDecisionStateV2.Status.WIN_PENDING.id,
                    plan.x, plan.z,
                    plan.bpId));

            // Add to pending queue (runtime)
            pendingQueue.addLast(regionKey);

            if (LOG) {
                LOGGER.info("[Kingdoms][SpawnV3] DECIDE WIN_PENDING region=({}, {}) key={} plan=({}, {}) bp={}",
                        rx, rz, regionKey, plan.x, plan.z, plan.bpId);
            }

            did++;
        }
    }

    // =========================================================
    // Pending rebuild
    // =========================================================

    private static void rebuildPendingFromState(ServerLevel level) {
        pendingQueue.clear();
        reservedOriginXZ.clear();

        RegionDecisionStateV2 state = RegionDecisionStateV2.get(level);

        for (RegionDecisionStateV2.Entry e : state.entries()) {
            if (e.statusEnum() == RegionDecisionStateV2.Status.WIN_PENDING) {
                pendingQueue.addLast(e.regionKey());
            }
        }
    }

    private static void rebuildPendingQueueIfNeeded(ServerLevel level) {
        // If we ever get into a state where pending is empty but the world has WIN_PENDING entries (e.g. after /reload),
        // this rebuild recovers it. It's cheap enough to do occasionally; do it rarely.
        if ((tickAge % (20 * 30)) != 0) return; // every 30s
        if (!pendingQueue.isEmpty()) return;

        RegionDecisionStateV2 state = RegionDecisionStateV2.get(level);
        for (RegionDecisionStateV2.Entry e : state.entries()) {
            if (e.statusEnum() == RegionDecisionStateV2.Status.WIN_PENDING) {
                rebuildPendingFromState(level);
                return;
            }
        }
    }

    // =========================================================
    // Placement
    // =========================================================

    private static boolean isWorldgenJobInProgress(ServerLevel level, long jobKey) {
        // If the jobKey is in the persisted worldgen queue, it is in progress or pending resume.
        WorldgenBlueprintQueueState q = WorldgenBlueprintQueueState.get(level);
        for (WorldgenBlueprintQueueState.Entry e : q.snapshot()) {
            if (e.regionKey == jobKey) return true;
        }
        return false;
    }


    private static void placeSome(MinecraftServer server, ServerLevel level) {

        if (KingdomGenGate.hasActiveRegion()) return;

        if (BlueprintPlacerEngine.getQueueSize() >= MAX_BP_QUEUE) return;

        if (pendingQueue.isEmpty()) return;

        int did = 0;
        int checks = 0;
        
        while (did < PLACE_PER_TICK && !pendingQueue.isEmpty() && checks++ < MAX_PENDING_CHECKS_PER_TICK) {
            long regionKey = pendingQueue.pollFirst();
            RegionDecisionStateV2 state = RegionDecisionStateV2.get(level);
            RegionDecisionStateV2.Entry e = state.get(regionKey);

            if (e == null || (e.statusEnum() != RegionDecisionStateV2.Status.WIN_PENDING && e.statusEnum() != RegionDecisionStateV2.Status.WIN_PLACING)) {
                reservedOriginXZ.remove(regionKey);
                continue;
            }

            if (e.statusEnum() == RegionDecisionStateV2.Status.WIN_PLACING) {
                continue;
            }

            if (isWorldgenJobInProgress(level, regionKey)) {
                state.setStatus(regionKey, RegionDecisionStateV2.Status.WIN_PLACING);
                continue;
            }


            int next = nextPlaceTick.getOrDefault(regionKey, 0);
            if (tickAge < next) {
                pendingQueue.addLast(regionKey);
                continue;
            }

            // Already spawned?
            if (KingdomsSpawnState.get(level).hasSpawned(regionKey)) {
                state.setStatus(regionKey, RegionDecisionStateV2.Status.WIN_SPAWNED);
                reservedOriginXZ.remove(regionKey);
                continue;
            }

            if (KingdomsSpawnState.get(level).isQueued(regionKey)) {
                // If nothing is actually in-flight for this key, clear the stale queued flag.
                if (!BlueprintPlacerEngine.isInFlight(regionKey)) {
                    KingdomsSpawnState.get(level).clearQueued(regionKey);
                } else {
                    pendingQueue.addLast(regionKey);
                    continue;
                }
            }


            int x = e.plannedX();
            int z = e.plannedZ();

            /*  DEBUG: show why candidates are/aren't eligible (prints every 10s)
            if (LOG && (tickAge % 200 == 0)) {
                boolean inRing = plannedIsInPlacementRing(server, level, x, z);
                boolean tooClosePlayer = tooCloseToAnyPlayer(level, x, z);
                boolean tooCloseCastle = isTooCloseToAnyCastleOrReservation(level, regionKey, x, z);

                LOGGER.info("[Kingdoms][SpawnV3] CHECK key={} rx={} rz={} plan=({}, {}) bp={} inRing={} tooClosePlayer={} tooCloseCastle={}",
                        regionKey, e.rx(), e.rz(), x, z, e.bpId(),
                        inRing, tooClosePlayer, tooCloseCastle);
            } */

            if (tooCloseToAnyPlayer(level, x, z)) {

                // If region is fully blocked by the no-spawn radius, just wait longer.
                if (regionIsFullyInsideNoSpawn(level, e.rx(), e.rz())) {
                    if (LOG) {
                        LOGGER.info("[Kingdoms][SpawnV3] SKIP too-close (region fully inside no-spawn) key={} rx={} rz={} plan=({}, {})",
                                regionKey, e.rx(), e.rz(), x, z);
                    }
                    nextPlaceTick.put(regionKey, tickAge + 20 * 20); // 20s
                    pendingQueue.addLast(regionKey);
                    continue;
                }

                // Otherwise: wait with increasing backoff so we "move on" to other regions.
                int fc = failCount.getOrDefault(regionKey, 0) + 1;
                failCount.put(regionKey, fc);

                // 5s, 10s, 15s... capped at 60s
                int backoff = Math.min(FAIL_COOLDOWN_MAX_TICKS, FAIL_COOLDOWN_BASE_TICKS * fc);
                nextPlaceTick.put(regionKey, tickAge + backoff);

                if (LOG) {
                    LOGGER.info("[Kingdoms][SpawnV3] SKIP too-close (temporary) key={} plan=({}, {}) tries={} backoff={}t",
                            regionKey, x, z, fc, backoff);
                }

                pendingQueue.addLast(regionKey);
                continue;
            }


            if (BLOCK_COLD_BIOMES && isColdBiome(level, x, z)) {
                // Region stays WIN_PENDING, but we change the plan deterministically to avoid perma-block.
                Planned plan2 = planForRegion(level, e.rx(), e.rz(), regionKey ^ 0xCAFEBABECAFEL);
                state.put(new RegionDecisionStateV2.Entry(regionKey, e.rx(), e.rz(),
                        RegionDecisionStateV2.Status.WIN_PENDING.id,
                        plan2.x, plan2.z,
                        plan2.bpId));
                

                pendingQueue.addLast(regionKey);
                continue;
            }

            // Only place when within ring around any player (reduces pop-in surprise)
            if (!plannedIsInPlacementRing(server, level, x, z)) {
                pendingQueue.addLast(regionKey);
                continue;
            }

            // Spacing gate: persisted castles + reservations
            if (isTooCloseToAnyCastleOrReservation(level, regionKey, x, z)) {
                // Re-plan deterministically, keep WIN_PENDING
                Planned plan2 = planForRegion(level, e.rx(), e.rz(), regionKey ^ 0x12345678ABCDEFL);
                state.put(new RegionDecisionStateV2.Entry(regionKey, e.rx(), e.rz(),
                        RegionDecisionStateV2.Status.WIN_PENDING.id,
                        plan2.x, plan2.z,
                        plan2.bpId));
            

                pendingQueue.addLast(regionKey);
                continue;
            }

            // Force-load target chunk to prevent "must rejoin/TP"
            int tcx = x >> 4;
            int tcz = z >> 4;
            if (FORCE_LOAD_TARGET_CHUNK) {
                level.getChunk(tcx, tcz); // loads/generates
            }

            int y = surfaceY(level, x, z);
            BlockPos origin = new BlockPos(x, y, z);

            try {
                Blueprint bp = Blueprint.load(server, MOD_ID, e.bpId());

                
                if (!KingdomGenGate.tryBeginRegion(regionKey)) {
                    pendingQueue.addLast(regionKey);
                    return;
                }

                
                reserve(regionKey, x, z);
          
                if (LOG) {
                    LOGGER.info("[Kingdoms][SpawnV3] ENQUEUE region=({}, {}) key={} bp={} origin={}",
                            e.rx(), e.rz(), regionKey, e.bpId(), origin);
                }

                // Mark as "in progress" BEFORE enqueue so we cannot enqueue twice after pause/resume.
                RegionDecisionStateV2.get(level).setStatus(regionKey, RegionDecisionStateV2.Status.WIN_PLACING);


                BlueprintPlacerEngine.enqueueWorldgen(
                        level, bp, origin, MOD_ID, INCLUDE_AIR,
                        regionKey, // roadsRegionKey
                        regionKey, // jobKey (castle job is unique per region)
                        () -> {
                            // SUCCESS
                            

                            KingdomsSpawnState.get(level).markSpawned(regionKey);
                            KingdomsSpawnState.get(level).clearQueued(regionKey);

                            failCount.remove(regionKey);
                            nextPlaceTick.remove(regionKey);

                            CastleOriginState.get(level).put(regionKey, origin);

                            RegionDecisionStateV2.get(level).setStatus(regionKey, RegionDecisionStateV2.Status.WIN_SPAWNED);
                            reservedOriginXZ.remove(regionKey);

                            // anchors + satellites (keep your behavior)
                            List<BlockPos> anchors = RoadAnchors.consumeBarrierAnchors(level, origin);
                            if (anchors.isEmpty()) anchors = List.of(RoadAnchors.fallbackFromBlueprintOrigin(level, origin));

                            RoadAnchorState st = RoadAnchorState.get(level);
                            for (BlockPos a : anchors) st.add(regionKey, a);

                            KingdomSatelliteSpawner.KingdomSize kSize = KingdomSatelliteSpawner.KingdomSize.MEDIUM;
                            List<String> buildingPool = List.of(
                                    "struct1","struct2","struct3","struct4","struct5","struct6","struct7","struct8","struct9","struct10","struct11","struct12","struct13","struct14","struct15","struct16","struct17"
                                    ,"struct18","struct19","struct20","struct21","struct22","struct23"
                            );

                            UUID kingdomId = kingdomUuidForRegion(level.getSeed(), regionKey);


                           int half = KingdomSatelliteSpawner.maxRadiusForSize(kSize) + 32;
                            int minX = origin.getX() - half;
                            int maxX = origin.getX() + half;
                            int minZ = origin.getZ() - half;
                            int maxZ = origin.getZ() + half;

                            // Ensure kingdom exists (same as you already do)
                            name.kingdoms.kingdomState ks = name.kingdoms.kingdomState.get(level.getServer());
                            name.kingdoms.kingdomState.Kingdom kk = ks.ensureAiKingdom(
                                    kingdomId,
                                    kingdomId,
                                    "Kingdom " + e.rx() + "," + e.rz(),
                                    origin
                            );

                            // Set border ONCE if not set (use overlap-safe setter if you added it)
                            boolean haveValidBorder = kk.hasBorder;

                            if (!kk.hasBorder) {
                                // If you already added trySetKingdomBorder(), use it:
                                boolean ok = ks.trySetKingdomBorder(level, kk, minX, maxX, minZ, maxZ);
                                haveValidBorder = ok;

                                if (!ok) {
                                    LOGGER.warn("[SpawnV3] Border overlap prevented for kingdomId={} regionKey={} rect=({},{})->({},{})",
                                            kingdomId, regionKey, minX, minZ, maxX, maxZ);

                                    // IMPORTANT: keep kk.hasBorder=false so we know it's invalid
                                    kk.hasBorder = false;
                                    kk.borderMinX = kk.borderMaxX = kk.borderMinZ = kk.borderMaxZ = 0;
                                    ks.setDirty();
                                }
                            }

                            // Satellites: clamp to border if valid, otherwise clamp to the local box around the origin
                            if (haveValidBorder) {
                                KingdomSatelliteSpawner.enqueuePlanAfterDelay(
                                        level, origin, bp, MOD_ID, regionKey,
                                        kSize, buildingPool, level.getRandom(),
                                        20 * 10,
                                        kk.borderMinX, kk.borderMaxX,
                                        kk.borderMinZ, kk.borderMaxZ
                                );
                            } else {
                                KingdomSatelliteSpawner.enqueuePlanAfterDelay(
                                        level, origin, bp, MOD_ID, regionKey,
                                        kSize, buildingPool, level.getRandom(),
                                        20 * 10,
                                        minX, maxX, minZ, maxZ
                                );
                            }



                        },
                        () -> {
                            KingdomGenGate.endRegion(regionKey);
                            
                            RegionDecisionStateV2.get(level).setStatus(regionKey, RegionDecisionStateV2.Status.WIN_PENDING);

                            // FAIL: release queued flag so this region can be tried again
                            KingdomsSpawnState.get(level).clearQueued(regionKey);

                            reservedOriginXZ.remove(regionKey);

                            // increase failure count + apply backoff
                            int fc = failCount.getOrDefault(regionKey, 0) + 1;
                            failCount.put(regionKey, fc);

                            int backoff = Math.min(FAIL_COOLDOWN_MAX_TICKS, FAIL_COOLDOWN_BASE_TICKS * fc);
                            nextPlaceTick.put(regionKey, tickAge + backoff);

                            // optionally switch blueprint after a few failures
                            Planned plan2 = planForRegion(level, e.rx(), e.rz(),
                                    regionKey ^ 0xF00DF00DF00DF00DL ^ ((long)fc * 0x9E3779B97F4A7C15L));

                            // update plan + reservation (keeps region WIN_PENDING)
                            RegionDecisionStateV2.get(level).put(new RegionDecisionStateV2.Entry(
                                    regionKey, e.rx(), e.rz(),
                                    RegionDecisionStateV2.Status.WIN_PENDING.id,
                                    plan2.x, plan2.z,
                                    (fc >= SWITCH_BP_AFTER_FAILS ? pickDifferentCastle(e.bpId(), plan2.bpId) : plan2.bpId)
                            ));

                            // requeue
                            pendingQueue.addLast(regionKey);

                            if (LOG) {
                                LOGGER.info("[Kingdoms][SpawnV3] FAIL key={} reason=BP_FAIL tries={} backoff={}t newPlan=({}, {}) bp={}",
                                        regionKey, fc, backoff, plan2.x, plan2.z,
                                        (fc >= SWITCH_BP_AFTER_FAILS ? pickDifferentCastle(e.bpId(), plan2.bpId) : plan2.bpId));
                            }
                        }

                );

                did++;

            } catch (Exception ex) {
                KingdomsSpawnState.get(level).clearQueued(regionKey);
                reservedOriginXZ.remove(regionKey);
                pendingQueue.addLast(regionKey);
                LOGGER.error("[Kingdoms][SpawnV3] EXCEPTION enqueue key={} origin={} bp={}",
                        regionKey, origin, e.bpId(), ex);
            }
        }
    }

    // =========================================================
    // Planning + deterministic selection
    // =========================================================

    private record Planned(int x, int z, String bpId) {}

    private static Planned planForRegion(ServerLevel level, int rx, int rz, long salt) {
        // region min corner
        int minX = rx * REGION_SIZE_BLOCKS;
        int minZ = rz * REGION_SIZE_BLOCKS;

        // deterministic RNG
        long seed = level.getSeed();
        long mix = seed
                ^ (salt * 0x9E3779B97F4A7C15L)
                ^ ((long) rx * 341873128712L)
                ^ ((long) rz * 132897987541L)
                ^ 0xD1B54A32D192ED03L;

        RandomSource rng = RandomSource.create(mix);

        // choose bp deterministically
        String bpId = CASTLE_POOL[rng.nextInt(CASTLE_POOL.length)];

        // choose a spot inside region with margins
        int margin = 96;
        int span = REGION_SIZE_BLOCKS - margin * 2;
        int x = minX + margin + rng.nextInt(Math.max(1, span));
        int z = minZ + margin + rng.nextInt(Math.max(1, span));

        // tiny jitter (stable)
        x += rng.nextInt(9) - 4;
        z += rng.nextInt(9) - 4;

        return new Planned(x, z, bpId);
    }

    private static boolean regionWins(ServerLevel level, long regionKey) {
        if (RARITY <= 1) return true;
        long seed = level.getSeed();
        long mix = seed ^ (regionKey * 0xC2B2AE3D27D4EB4FL) ^ 0xDEADBEEF12345678L;
        RandomSource rng = RandomSource.create(mix);
        return rng.nextInt(RARITY) == 0;
    }

    // =========================================================
    // Ring + biome + spacing helpers
    // =========================================================

    private static boolean plannedIsInPlacementRing(MinecraftServer server, ServerLevel level, int x, int z) {
        int viewDist = server.getPlayerList().getViewDistance();
        int maxDist = Math.max(6, viewDist + PLACE_MAX_EXTRA_CHUNKS);
        int minDist = Math.max(0, PLACE_MIN_DIST_CHUNKS);

        int cx = x >> 4;
        int cz = z >> 4;

        for (ServerPlayer p : level.players()) {
            int pcx = p.chunkPosition().x;
            int pcz = p.chunkPosition().z;
            int dx = Math.abs(cx - pcx);
            int dz = Math.abs(cz - pcz);
            int d = Math.max(dx, dz);
            if (d >= minDist && d <= maxDist) return true;
        }
        return false;
    }

    private static boolean isColdBiome(ServerLevel level, int x, int z) {
        int sea = level.getSeaLevel();
        Biome biome = level.getBiome(new BlockPos(x, sea, z)).value();
        return biome.getBaseTemperature() < 0.15f;
    }

    private static boolean isTooCloseToAnyCastleOrReservation(ServerLevel level, long ignoreRegionKey, int candX, int candZ) {
        // persisted
        CastleOriginState st = CastleOriginState.get(level);
        for (BlockPos p : st.allOrigins()) {
            long dx = (long) candX - (long) p.getX();
            long dz = (long) candZ - (long) p.getZ();
            if (dx * dx + dz * dz < MIN_CASTLE_SPACING_SQ) return true;
        }

        // reserved
        for (var it = reservedOriginXZ.long2LongEntrySet().fastIterator(); it.hasNext();) {
            var en = it.next();
            long rk = en.getLongKey();
            if (rk == ignoreRegionKey) continue;

            long packed = en.getLongValue();
            int x = (int)(packed >> 32);
            int z = (int)packed;

            long dx = (long) candX - (long) x;
            long dz = (long) candZ - (long) z;
            if (dx * dx + dz * dz < MIN_CASTLE_SPACING_SQ) return true;
        }

        return false;
    }

    private static void reserve(long regionKey, int x, int z) {
        reservedOriginXZ.put(regionKey, packXZ(x, z));
    }

    private static int surfaceY(ServerLevel level, int x, int z) {
        int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int y = h - 1;
        return Math.max(level.getMinY() + 1, y);
    }

    // =========================================================
    // Region key packing (overworld only)
    // =========================================================

    private static long packRegion(int rx, int rz) {
        return (((long) rx) << 32) ^ (rz & 0xffffffffL);
    }

    private static int unpackRX(long k) { return (int)(k >> 32); }
    private static int unpackRZ(long k) { return (int)k; }

    private static long packXZ(int x, int z) {
        return ((long)x << 32) | (z & 0xffffffffL);
    }
}
