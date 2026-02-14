package name.kingdoms.war;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import name.kingdoms.Kingdoms;
import name.kingdoms.aiKingdomState;
import name.kingdoms.aiKingdomState.AiKingdom;
import name.kingdoms.kingdomState;
import name.kingdoms.diplomacy.AiDiplomacyEvent;
import name.kingdoms.diplomacy.AiDiplomacyEventState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import name.kingdoms.news.KingdomNewsState;
import java.util.*;

public final class WarState extends SavedData {

    // store unordered pairs as "minUUID|maxUUID"
    private final Set<String> wars = new HashSet<>();

    // battle zone per war pair (key is same min|max)
    private final Map<String, BattleZone> zones = new HashMap<>();
    private final Map<String, BattleZone> siegeZones = new HashMap<>();
    private final Map<String, String> pairToRoot = new HashMap<>();

    public enum WarEndReason { SURRENDER, WHITE_PEACE, NEGOTIATED, TIMEOUT }
    public enum WarEndSource { AI_SIM, PLAYER_BATTLE, LETTER }

    public record WarEndResult(
            UUID winnerRoot,
            UUID loserRoot,
            WarEndReason reason,
            boolean puppetCreated,
            Map<String, Integer> transferred // resource -> amount
    ) {}

    public WarEndResult endWar(MinecraftServer server, UUID winnerRoot, UUID loserRoot,
                                WarEndReason reason, WarEndSource source) {

            if (server == null || winnerRoot == null || loserRoot == null) {
                return new WarEndResult(winnerRoot, loserRoot, reason, false, Map.of());
            }

            // Map WarEndReason -> PeaceType
            WarPeaceEffects.PeaceType pt = switch (reason) {
                case WHITE_PEACE -> WarPeaceEffects.PeaceType.WHITE_PEACE;
                case SURRENDER -> WarPeaceEffects.PeaceType.SURRENDER;
                default -> WarPeaceEffects.PeaceType.WHITE_PEACE; // negotiated/timeout treated as soft
            };

            WarPeaceEffects.SettlementResult settlement = WarPeaceEffects.apply(server, winnerRoot, loserRoot, pt);

            // If you still want the "transferred" map for UI/logs:
            Map<String, Integer> transferred = new HashMap<>();
            if (settlement.goldTaken()  > 0) transferred.put("gold",  settlement.goldTaken());
            if (settlement.woodTaken()  > 0) transferred.put("wood",  settlement.woodTaken());
            if (settlement.metalTaken() > 0) transferred.put("metal", settlement.metalTaken());

            try {
                emitWarEndDiplomacy(server, winnerRoot, loserRoot, reason, source, transferred);
            } catch (Throwable t) {
                Kingdoms.LOGGER.warn("[War] endWar: diplomacy emit failed: {}", t.toString());
            }

            // Actually end the war
            makePeace(winnerRoot, loserRoot);

            return new WarEndResult(
                    winnerRoot, loserRoot, reason,
                    settlement.puppetCreated(),
                    transferred
            );
        }

            // -----------------------------
            // Diplomacy / news emission
            // -----------------------------

            private void emitWarEndDiplomacy(
                    MinecraftServer server,
                    UUID winnerRoot,
                    UUID loserRoot,
                    WarEndReason reason,
                    WarEndSource source,
                    Map<String, Integer> transferred
            ) {
                if (server == null || winnerRoot == null || loserRoot == null) return;

                String winnerName = nameOf(server, winnerRoot);
                String loserName  = nameOf(server, loserRoot);

                String reasonText = switch (reason) {
                    case SURRENDER   -> "surrendered";
                    case WHITE_PEACE -> "agreed to white peace";
                    case NEGOTIATED  -> "negotiated peace";
                    case TIMEOUT     -> "ended the war (timeout)";
                };

                // GLOBAL news: surrenders + white peace + peace always show
                if (server.overworld() != null) {
                    String w = winnerName;
                    String l = loserName;


                    String prefix = switch (reason) {
                        case SURRENDER   -> "[SURRENDER] ";
                        case WHITE_PEACE -> "[PEACE] ";
                        default          -> "[PEACE] ";
                    };

                    String newsMsg = switch (reason) {
                        case SURRENDER   -> prefix + l + " surrendered to " + w + ".";
                        case WHITE_PEACE -> prefix + w + " and " + l + " agreed to white peace.";
                        default          -> prefix + w + " and " + l + " made peace.";
                    };

                    // Use the old overload: name-only + no UUIDs => cannot be range-gated.
                    KingdomNewsState.get(server.overworld()).add(server.getTickCount(), newsMsg);
                }



                String lootText = "";
                if (transferred != null && !transferred.isEmpty()) {
                    // Keep it short for UI/snippets
                    StringBuilder sb = new StringBuilder(" Reparations: ");
                    boolean first = true;
                    for (var e : transferred.entrySet()) {
                        int amt = (e.getValue() == null) ? 0 : e.getValue();
                        if (amt <= 0) continue;
                        if (!first) sb.append(", ");
                        first = false;
                        sb.append(amt).append(" ").append(e.getKey());
                    }
                    if (!first) lootText = sb.toString();
                }

                String msg = switch (reason) {
                    case SURRENDER ->
                            loserName + " surrendered to " + winnerName + "." + lootText;
                    case WHITE_PEACE ->
                            winnerName + " and " + loserName + " agreed to white peace." + lootText;
                    default ->
                            winnerName + " and " + loserName + " made peace." + lootText;
                };


                try {
                    var events = AiDiplomacyEventState.get(server.overworld());
                    events.add(new AiDiplomacyEvent(
                            winnerRoot,
                            loserRoot,
                            AiDiplomacyEvent.Type.PEACE_SIGNED, // reuse existing type
                            server.getTickCount(),
                            msg
                    ));
                } catch (Throwable t) {
                    Kingdoms.LOGGER.warn("[War] emitWarEndDiplomacy failed: {}", t.toString());
                }
            }




    // Simulated war state for AI vs AI (morale per side)
    private final Map<String, AiWarSim> aiSim = new HashMap<>();

    private record AiWarSim(double moraleA, double moraleB, int startA, int startB) {}

    private static final int AI_WAR_TICK_INTERVAL = 20 * 60 * 1; // 1 MINUTE DEBUG FOR DEV

    private static final double PUPPET_ON_WIN_CHANCE = 0.15; // 15% chance winner puppets loser DEBUG FOR DEV


    // -----------------------------
    // Pending war zone computation (no hitch)
    // -----------------------------

    // ROOT war key -> pending root info (root wars only)
    private final Map<String, PendingRootWar> pendingRoots = new HashMap<>();

    // Runtime-only job queue (not serialized)
    private transient ArrayDeque<String> pendingQueue = new ArrayDeque<>();
    private transient Map<String, ZoneJob> pendingJobs = new HashMap<>();

    // Tune: how much CPU we allow per tick for pending war zone search (2–4ms is safe)
    private static final long PENDING_BUDGET_NANOS_PER_TICK = 3_000_000L;

    // optional: cap candidate evaluations per tick even if nanos budget remains
    private static final int PENDING_MAX_CANDIDATES_PER_TICK = 40;

    private record PendingRootWar(UUID rootA, UUID rootB, long requestedAtTick) {}


    // -----------------------------
    // Zone quality tuning
    // -----------------------------
    private static final double MAX_WATER_FRAC = 0.05; 
    private static final int MAX_HEIGHT_RANGE = 14;    // blocks (tune)
    private static final double MAX_STDDEV = 4.0;      // blocks (tune)

    /** Maximum allowed step between neighboring samples (ravine / cliff detector). */
    private static final int MAX_ADJACENT_STEP = 1;    // blocks (tune)

    // How hard we search around the computed frontline center
    private static final int ZONE_SEARCH_RADIUS = 128; // base radius (tune)
    private static final int ZONE_SEARCH_STEP   = 16;  // blocks (tune)
    private static final int SAMPLE_STEP = 6;         
    private static final int MAX_CELLS = 9000; 


   private record ZoneQuality(
            double waterFrac,
            int heightRange,
            double stddev,
            int maxStep,
            double score,
            int waterCount,
            int samples
    ) {}

    private static boolean warInvolvesOnlinePlayer(MinecraftServer server, UUID rootA, UUID rootB) {
        var ks = kingdomState.get(server);
        var alliance = name.kingdoms.diplomacy.AllianceState.get(server);

        for (var p : server.getPlayerList().getPlayers()) {
            UUID pk = ks.getKingdomIdFor(p.getUUID());
            if (pk == null) continue;

            boolean onA = pk.equals(rootA) || alliance.isAllied(pk, rootA);
            boolean onB = pk.equals(rootB) || alliance.isAllied(pk, rootB);

            if (onA ^ onB) return true; // involved on exactly one side
        }
        return false;
    }


    private static boolean overlapsAnyKingdom(ServerLevel level, BattleZone zone) {
        kingdomState ks = kingdomState.get(level.getServer());
        if (ks == null) return false;

        // Sample grid. Step=10 matches your claim cell size (if you use 10x10 claims).
        final int step = 10;

        for (int z = zone.minZ(); z <= zone.maxZ(); z += step) {
            for (int x = zone.minX(); x <= zone.maxX(); x += step) {
                if (ks.getKingdomAtFast(level, x, z) != null) return true;

            }
        }

        // Corners too (edge cases)
        if (ks.getKingdomAt(level, new BlockPos(zone.minX(), 0, zone.minZ())) != null) return true;
        if (ks.getKingdomAt(level, new BlockPos(zone.maxX(), 0, zone.minZ())) != null) return true;
        if (ks.getKingdomAt(level, new BlockPos(zone.minX(), 0, zone.maxZ())) != null) return true;
        if (ks.getKingdomAt(level, new BlockPos(zone.maxX(), 0, zone.maxZ())) != null) return true;

        return false;
    }

    private static double betweenPenalty(int cx, int cz, int ax, int az, int bx, int bz) {
        double abx = (double) (bx - ax);
        double abz = (double) (bz - az);
        double apx = (double) (cx - ax);
        double apz = (double) (cz - az);

        double abLen2 = abx * abx + abz * abz;
        if (abLen2 < 1.0) return 0.0;

        // Projection t onto segment A->B
        double t = (apx * abx + apz * abz) / abLen2;

        // Perpendicular distance to the infinite line
        double cross = apx * abz - apz * abx;
        double perpDist = Math.abs(cross) / Math.sqrt(abLen2);

        // Penalize being off the line corridor
        double pPerp = perpDist * 1.2; // tune weight

        // Penalize being away from the midpoint (encourages "between")
        double pMid = Math.abs(t - 0.5) * 600.0; // tune weight

        // Hard penalty if beyond either endpoint
        double pOutside = 0.0;
        if (t < 0.0) pOutside += (-t) * 2000.0;
        if (t > 1.0) pOutside += (t - 1.0) * 2000.0;

        // Soft penalty if too close to an endpoint (prevents zones right next to a kingdom)
        double pNearEnds = 0.0;
        if (t < 0.20) pNearEnds += (0.20 - t) * 1200.0;
        if (t > 0.80) pNearEnds += (t - 0.80) * 1200.0;

        return pPerp + pMid + pOutside + pNearEnds;
    }



    private static boolean isSurfaceWater(ServerLevel level, int x, int z) {
        if (!isChunkLoaded(level, x, z)) return false; // NEW safety

        int yTop = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        for (int dy = 0; dy <= 3; dy++) {
            if (level.getFluidState(new BlockPos(x, yTop - dy, z)).is(Fluids.WATER)) return true;
        }

        int yFloor = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
        for (int dy = 0; dy <= 2; dy++) {
            if (level.getFluidState(new BlockPos(x, yFloor + dy, z)).is(Fluids.WATER)) return true;
        }
        return false;
    }

    private static int groundY(ServerLevel level, int x, int z) {
        if (!isChunkLoaded(level, x, z)) return 0; // NEW safety (or some sentinel)
        return level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
    }


    private static ZoneQuality evaluateZone(ServerLevel level, BattleZone zone) {
        int minX = zone.minX();
        int maxX = zone.maxX();
        int minZ = zone.minZ();
        int maxZ = zone.maxZ();

        int spanX = Math.max(1, maxX - minX);
        int spanZ = Math.max(1, maxZ - minZ);

        int step = SAMPLE_STEP;

        // keep the cap low so we don't miss ravines too easily
        while (((spanX / step) + 1) * ((spanZ / step) + 1) > MAX_CELLS) {
            step += 2;
            if (step > 12) break;
        }

        int nx = (spanX / step) + 1;
        int nz = (spanZ / step) + 1;
        int samples = nx * nz;

        int water = 0;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        double sum = 0.0;
        double sumSq = 0.0;

        int[][] h = new int[nz][nx];

        // sample heights + water
        for (int iz = 0; iz < nz; iz++) {
            int z = minZ + iz * step;
            for (int ix = 0; ix < nx; ix++) {
                int x = minX + ix * step;

                if (isSurfaceWater(level, x, z)) water++;

                int y = groundY(level, x, z);
                h[iz][ix] = y;

                if (y < minY) minY = y;
                if (y > maxY) maxY = y;

                sum += y;
                sumSq += (double) y * (double) y;
            }
        }

        // max adjacent step (cliff/ravine detector at sampling scale)
        int maxStep = 0;

        // Optional: average adjacent step as an extra “roughness” signal (still cheap)
        long adjCount = 0;
        long adjSum = 0;

        for (int iz = 0; iz < nz; iz++) {
            for (int ix = 0; ix < nx; ix++) {
                int y = h[iz][ix];

                if (ix + 1 < nx) {
                    int d = Math.abs(y - h[iz][ix + 1]);
                    maxStep = Math.max(maxStep, d);
                    adjSum += d; adjCount++;
                }
                if (iz + 1 < nz) {
                    int d = Math.abs(y - h[iz + 1][ix]);
                    maxStep = Math.max(maxStep, d);
                    adjSum += d; adjCount++;
                }
            }
        }

        double mean = sum / samples;
        double var = Math.max(0.0, (sumSq / samples) - (mean * mean));
        double std = Math.sqrt(var);

        int range = Math.max(0, maxY - minY);
        double waterFrac = (double) water / (double) samples;

        double avgAdj = (adjCount == 0) ? 0.0 : ((double) adjSum / (double) adjCount);

        // Score: hard-penalize water and roughness/cliffs
        double score =
                (waterFrac * 200000.0) +   // huge penalty; still gated by passes()
                (range * 10.0) +
                (std * 45.0) +
                (maxStep * 300.0) +
                (avgAdj * 120.0);

        return new ZoneQuality(waterFrac, range, std, maxStep, score, water, samples);
    }


    private static boolean isTraversableAcross(int[][] h, int maxStep) {
        int nz = h.length;
        if (nz == 0) return false;
        int nx = h[0].length;
        if (nx == 0) return false;

        // We consider success if we can reach ANY cell on the opposite edge.
        // We'll try both directions (left->right OR top->bottom). If either works, it's traversable.
        return bfsEdgeToEdge(h, maxStep, true) || bfsEdgeToEdge(h, maxStep, false);
    }

    private static boolean bfsEdgeToEdge(int[][] h, int maxStep, boolean leftToRight) {
        int nz = h.length;
        int nx = h[0].length;

        boolean[][] vis = new boolean[nz][nx];
        ArrayDeque<int[]> q = new ArrayDeque<>();

        if (leftToRight) {
            // start at left edge (x=0)
            for (int z = 0; z < nz; z++) {
                vis[z][0] = true;
                q.add(new int[]{0, z});
            }
        } else {
            // start at top edge (z=0)
            for (int x = 0; x < nx; x++) {
                vis[0][x] = true;
                q.add(new int[]{x, 0});
            }
        }

        while (!q.isEmpty()) {
            int[] cur = q.removeFirst();
            int x = cur[0], z = cur[1];

            // reached opposite edge?
            if (leftToRight) {
                if (x == nx - 1) return true;
            } else {
                if (z == nz - 1) return true;
            }

            int y = h[z][x];

            // 4-neighbors
            // (x+1, z)
            if (x + 1 < nx && !vis[z][x + 1] && Math.abs(h[z][x + 1] - y) <= maxStep) {
                vis[z][x + 1] = true;
                q.add(new int[]{x + 1, z});
            }
            // (x-1, z)
            if (x - 1 >= 0 && !vis[z][x - 1] && Math.abs(h[z][x - 1] - y) <= maxStep) {
                vis[z][x - 1] = true;
                q.add(new int[]{x - 1, z});
            }
            // (x, z+1)
            if (z + 1 < nz && !vis[z + 1][x] && Math.abs(h[z + 1][x] - y) <= maxStep) {
                vis[z + 1][x] = true;
                q.add(new int[]{x, z + 1});
            }
            // (x, z-1)
            if (z - 1 >= 0 && !vis[z - 1][x] && Math.abs(h[z - 1][x] - y) <= maxStep) {
                vis[z - 1][x] = true;
                q.add(new int[]{x, z - 1});
            }
        }

        return false;
    }



    private static boolean passes(ZoneQuality q) {
        return q.waterFrac <= MAX_WATER_FRAC
                && q.maxStep <= MAX_ADJACENT_STEP
                && q.heightRange <= MAX_HEIGHT_RANGE
                && q.stddev <= MAX_STDDEV;
    }


    // -----------------------------
    // Queries
    // -----------------------------

    public boolean isAtWar(UUID a, UUID b) {
        return wars.contains(key(a, b));
    }

    public boolean isAtWarWithAny(UUID kingdomId) {
        String s = kingdomId.toString();
        String prefix = s + "|";
        String suffix = "|" + s;

        for (String k : wars) {
            if (k.startsWith(prefix) || k.endsWith(suffix)) return true;
        }
        return false;
    }

    public Set<String> wars() {
        return Collections.unmodifiableSet(wars);
    }

    // -----------------------------
    // War lifecycle
    // -----------------------------

   public void declareWar(MinecraftServer server, UUID a, UUID b) {
        if (a == null || b == null) return;
        if (a.equals(b)) return;

        // Root belligerents are the initial declaration pair.
        requestPendingRootWar(server, a, b);
    }

    /**
     * Internal declaration that preserves a single ROOT war for the whole coalition.
     * Every allied pair-link maps back to the same rootKey, and zones are stored once per rootKey.
     */
    private void declareWarInternal(MinecraftServer server, UUID a, UUID b, UUID rootA, UUID rootB, boolean computeZoneIfMissing) {
        if (a == null || b == null) return;
        if (a.equals(b)) return;

        var alliance = name.kingdoms.diplomacy.AllianceState.get(server);

        // RULE 1: Allies cannot declare war on each other
        if (alliance.isAllied(a, b)) {
            Kingdoms.LOGGER.warn("[War] Blocked war declaration between allies: {} <-> {}", a, b);
            return;
        }

        // Normalize keys
        String pairKey = key(a, b);
        String rootKey = key(rootA, rootB);

        // Prevent duplicate pair-links
        if (!wars.add(pairKey)) {
            pairToRoot.putIfAbsent(pairKey, rootKey);
            return;
        }

        pairToRoot.put(pairKey, rootKey);

        Kingdoms.LOGGER.info("[War] declareWar link a={} b={} pairKey={} rootKey={}", a, b, pairKey, rootKey);

        // Only compute if allowed (we will pass false for pending-war commit)
        if (computeZoneIfMissing) {
            zones.computeIfAbsent(rootKey, kk -> computeZone(server, rootA, rootB));
        }

        setDirty();

        // RULE 2: Allies auto-join wars
        for (UUID allyA : alliance.alliesOf(a)) {
            if (!allyA.equals(b) && !alliance.isAllied(allyA, b)) {
                declareWarInternal(server, allyA, b, rootA, rootB, computeZoneIfMissing);
            }
        }

        for (UUID allyB : alliance.alliesOf(b)) {
            if (!allyB.equals(a) && !alliance.isAllied(allyB, a)) {
                declareWarInternal(server, allyB, a, rootA, rootB, computeZoneIfMissing);
            }
        }
    }


    private void commitRootWar(MinecraftServer server, UUID rootA, UUID rootB) {
        String rootKey = key(rootA, rootB);

        // IMPORTANT: do NOT auto-create a zone here.
        // Zones are created only by the pending zone search (player-involved wars).


        // Create all pair-links and ally joins, WITHOUT computing zone
        declareWarInternal(server, rootA, rootB, rootA, rootB, false);

        // GLOBAL news: war declarations always show
        var level = server.overworld();
        if (level != null) {
            String aName = nameOf(server, rootA);
            String bName = nameOf(server, rootB);
            KingdomNewsState.get(level).add(
                    server.getTickCount(),
                    "[WAR] " + aName + " declared war on " + bName + ".",
                    level,
                    0, 0
            );
        }

    }

    private static boolean isChunkLoaded(ServerLevel level, int x, int z) {
            // getChunkNow returns null if not loaded (won't load it)
            return level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null;
        }

        private static boolean candidateAreaLoaded(ServerLevel level, BattleZone zone) {
        // sample corners + center + edge mids (9 points)
        int cx = (zone.minX() + zone.maxX()) / 2;
        int cz = (zone.minZ() + zone.maxZ()) / 2;

        int[] xs = new int[]{ zone.minX(), cx, zone.maxX() };
        int[] zs = new int[]{ zone.minZ(), cz, zone.maxZ() };

        for (int x : xs) for (int z : zs) {
            if (!isChunkLoaded(level, x, z)) return false;
        }
        return true;
    }



    private void requestPendingRootWar(MinecraftServer server, UUID rootA, UUID rootB) {
        var alliance = name.kingdoms.diplomacy.AllianceState.get(server);

        // RULE 1: Allies cannot declare war on each other
        if (alliance.isAllied(rootA, rootB)) {
            Kingdoms.LOGGER.warn("[War] Blocked war declaration between allies: {} <-> {}", rootA, rootB);
            return;
        }

        String rootKey = key(rootA, rootB);

        // If zone already exists, nothing to do.
        if (zones.containsKey(rootKey)) return;

        // Already pending?
        if (pendingRoots.containsKey(rootKey)) return;

        // If this war doesn't involve any ONLINE player, do NOT compute a zone.
        // Still commit the war links immediately (cheap), so AI sim can run.
        if (!warInvolvesOnlinePlayer(server, rootA, rootB)) {
            commitRootWar(server, rootA, rootB); // creates wars + pair-links, but no zone
            Kingdoms.LOGGER.info("[War] War committed AI-only (no zone) rootKey={}", rootKey);
            return;
        }

        // Player-involved: schedule zone search
        pendingRoots.put(rootKey, new PendingRootWar(rootA, rootB, server.getTickCount()));
        setDirty();

        ensurePendingRuntimeInit();
        pendingQueue.addLast(rootKey);

        Kingdoms.LOGGER.info("[War] War pending (zone search scheduled) rootKey={}", rootKey);

    }

    
    public void tickPendingWars(MinecraftServer server) {

        // Only run pending zone CPU work at 2 Hz (every 10 ticks) or 1 Hz (20 ticks)
        if ((server.getTickCount() % 10) != 0) return;

        if (pendingRoots.isEmpty()) return;

        ensurePendingRuntimeInit();

        // Every 5 seconds, scan for player-involved wars missing zones and schedule them.
        if ((server.getTickCount() % (20 * 5)) == 0) {
            scheduleZonesForPlayerWars(server);
        }

        // If something was loaded from disk, make sure it's enqueued
        if (pendingQueue.isEmpty() && !pendingRoots.isEmpty()) {
            // repopulate queue (round-robin)
            for (String rk : pendingRoots.keySet()) pendingQueue.addLast(rk);
        }

        long deadline = System.nanoTime() + PENDING_BUDGET_NANOS_PER_TICK;
        int candidatesLeft = PENDING_MAX_CANDIDATES_PER_TICK;

        while (!pendingQueue.isEmpty() && System.nanoTime() < deadline && candidatesLeft > 0) {
            String rootKey = pendingQueue.removeFirst();

            PendingRootWar pr = pendingRoots.get(rootKey);
            if (pr == null) continue; // removed/committed

            // If a zone already exists, cancel pending work
            if (zones.containsKey(rootKey)) {
                pendingRoots.remove(rootKey);
                pendingJobs.remove(rootKey);
                pendingQueue.removeIf(k -> k.equals(rootKey));
                setDirty();
                continue;
            }



            ZoneJob job = pendingJobs.get(rootKey);
            if (job == null) {
                job = new ZoneJob(server, pr.rootA(), pr.rootB());
                pendingJobs.put(rootKey, job);
            }

            // Step job: evaluate some candidates, yield often
            ZoneJob.StepResult r = job.step(server, deadline, candidatesLeft);
            candidatesLeft = r.candidatesLeft();

            if (r.done()) {
                // Pick best passing if found; else fallback best-any (or computed default)
                BattleZone finalZone = (r.bestPass() != null) ? r.bestPass()
                        : (r.bestAny() != null) ? r.bestAny()
                        : job.defaultFallback;

                // store zone under ROOT KEY
                zones.put(rootKey, finalZone);

                // Now commit the war links + ally auto-join (cheap)
                commitRootWar(server, pr.rootA(), pr.rootB());

                // cleanup
                pendingRoots.remove(rootKey);
                pendingJobs.remove(rootKey);
                setDirty();

                Kingdoms.LOGGER.info("[War] War committed rootKey={} zone={}", rootKey, finalZone);
                continue;
            }

            // Not done yet → requeue for round-robin
            pendingQueue.addLast(rootKey);
        }
    }

    private void ensurePendingRuntimeInit() {
        if (pendingQueue == null) pendingQueue = new ArrayDeque<>();
        if (pendingJobs == null) pendingJobs = new HashMap<>();
    }



   public void makePeace(UUID a, UUID b) {
        String pairKey = key(a, b);
        String rootKey = pairToRoot.getOrDefault(pairKey, pairKey);

        boolean changed = false;

        // --- cancel pending root war (if it exists) ---
        PendingRootWar pr = pendingRoots.remove(rootKey);
        if (pr != null) {
            ensurePendingRuntimeInit();
            pendingJobs.remove(rootKey);

            // remove all occurrences from the queue (it can appear multiple times due to round-robin)
            pendingQueue.removeIf(k -> k.equals(rootKey));

            changed = true;
        }

        // --- remove all pair-links belonging to this root war ---
        Iterator<String> it = wars.iterator();
        while (it.hasNext()) {
            String wk = it.next();
            String rk = pairToRoot.getOrDefault(wk, wk);
            if (rk.equals(rootKey)) {
                it.remove();
                pairToRoot.remove(wk);
                changed = true;

                // clear AI sim for this pair-link
                changed |= (aiSim.remove(wk) != null);
            }
        }

        // --- remove the root zone ---
        changed |= (zones.remove(rootKey) != null);

        if (changed) setDirty();
    }



    // -----------------------------
    // Zone API
    // -----------------------------

    public Optional<BattleZone> getZone(UUID a, UUID b) {
        String pairKey = key(a, b);
        String rootKey = pairToRoot.getOrDefault(pairKey, pairKey);
        return Optional.ofNullable(zones.get(rootKey));
    }


    public void setZone(UUID a, UUID b, BattleZone zone) {
        String rootKey = pairToRoot.getOrDefault(key(a, b), key(a, b));
        zones.put(rootKey, zone);
        setDirty();
    }


    public void clearZone(UUID a, UUID b) {
        String rootKey = pairToRoot.getOrDefault(key(a, b), key(a, b));
        zones.remove(rootKey);
        setDirty();
    }


    //AI TICKER

    private void logAiPeace(MinecraftServer server, AiKingdom winner, AiKingdom loser) {
        var events = AiDiplomacyEventState.get(server.overworld());
        events.add(new AiDiplomacyEvent(
            winner.id, loser.id,
            AiDiplomacyEvent.Type.PEACE_SIGNED,
            server.getTickCount(),
            loser.name + " has sued for peace with " + winner.name
        ));
    }

    public static record AiWarSimSnap(double moraleA, double moraleB, int startA, int startB) {}



    public record WarSnapshot(
        Set<String> wars,
        Map<String, BattleZone> zones,
        Map<String, AiWarSimSnap> aiSim
    ) {}

    public WarSnapshot exportState() {
        // deep copy
        Set<String> warsCopy = new HashSet<>(this.wars);
        Map<String, BattleZone> zonesCopy = new HashMap<>(this.zones); // BattleZone is immutable record-like
        Map<String, AiWarSimSnap> simCopy = new HashMap<>();
        for (var e : this.aiSim.entrySet()) {
            var v = e.getValue();
            simCopy.put(e.getKey(), new AiWarSimSnap(v.moraleA(), v.moraleB(), v.startA(), v.startB()));
        }
        return new WarSnapshot(warsCopy, zonesCopy, simCopy);
    }

    @SuppressWarnings("unchecked")
    public void importState(WarSnapshot snap) {
        this.wars.clear();
        this.zones.clear();
        this.aiSim.clear();

        if (snap != null) {
            if (snap.wars() != null) this.wars.addAll(snap.wars());
            if (snap.zones() != null) this.zones.putAll(snap.zones());

            if (snap.aiSim() != null) {
                for (var e : snap.aiSim().entrySet()) {
                    var v = e.getValue();
                    this.aiSim.put(e.getKey(), new AiWarSim(v.moraleA(), v.moraleB(), v.startA(), v.startB()));
                }
            }
        }

        setDirty();
    }

    private void scheduleZonesForPlayerWars(MinecraftServer server) {
        
        ensurePendingRuntimeInit();

        // gather unique root wars from the wars set
        HashSet<String> seenRoots = new HashSet<>();

        for (String pairKey : wars) {
            String rootKey = pairToRoot.getOrDefault(pairKey, pairKey);
            if (!seenRoots.add(rootKey)) continue;

            if (zones.containsKey(rootKey)) continue;
            if (pendingRoots.containsKey(rootKey)) continue;

            int bar = rootKey.indexOf('|');
            if (bar < 0) continue;

            UUID rootA, rootB;
            try {
                rootA = UUID.fromString(rootKey.substring(0, bar));
                rootB = UUID.fromString(rootKey.substring(bar + 1));
            } catch (Exception ignored) { continue; }

            if (!warInvolvesOnlinePlayer(server, rootA, rootB)) continue;

            // schedule zone computation for this active war
            pendingRoots.put(rootKey, new PendingRootWar(rootA, rootB, server.getTickCount()));
            pendingQueue.addLast(rootKey);
            Kingdoms.LOGGER.info("[War] Scheduled zone search for active player war rootKey={}", rootKey);
            setDirty();
        }
    }


    // -----------------------------
    // War telemetry (read-only view)
    // -----------------------------

    public record AiSimView(double moraleA, double moraleB) {}

    /**
     * Read-only morale view for the AI war sim.
     * Returns (100,100) if no sim state exists yet for this war key.
     */
    public AiSimView getAiSimView(UUID a, UUID b) {
        String k = key(a, b);
        AiWarSim sim = aiSim.get(k);
        if (sim == null) return new AiSimView(100.0, 100.0);
        return new AiSimView(sim.moraleA(), sim.moraleB());
    }

    public String warKey(UUID a, UUID b) {
        return key(a, b);
    }


    // Keep existing signature for live gameplay
    public void tickAiWars(MinecraftServer server) {
        tickAiWars(server, server.getTickCount());
    }

    private static String nameOf(MinecraftServer server, UUID kid) {
        var ks = kingdomState.get(server);
        var k = ks.getKingdom(kid);
        if (k != null && k.name != null && !k.name.isBlank()) return k.name;

        var ai = aiKingdomState.get(server);
        String n = ai.getNameById(kid);
        return (n == null || n.isBlank()) ? "Unknown" : n;
    }


    // New overload for sim (clockable)
    public void tickAiWars(MinecraftServer server, long nowTick) {
        if (nowTick % AI_WAR_TICK_INTERVAL != 0) return;

        var aiState = aiKingdomState.get(server);
        var rng = server.overworld().getRandom();

        boolean changed = false;

        // Snapshot to avoid ConcurrentModificationException when makePeace() removes from wars.
        for (String key : new ArrayList<>(wars)) {
            var parts = key.split("\\|");
            if (parts.length != 2) continue;

            UUID a, b;
            try {
                a = UUID.fromString(parts[0]);
                b = UUID.fromString(parts[1]);
            } catch (IllegalArgumentException ex) {
                continue;
            }

            var aiA = aiState.getById(a);
            var aiB = aiState.getById(b);
            if (aiA == null || aiB == null) continue;

            // -------------------------
            // simulate battle (tuned)
            // Goal: morale collapses faster, casualties slower,
            // ~40% of STARTING soldiers dead on average by morale=0
            // -------------------------

            int beforeA = aiA.aliveSoldiers;
            int beforeB = aiB.aliveSoldiers;

            // Load sim state (or initialize)
            AiWarSim sim0 = aiSim.get(key);
            if (sim0 == null) {
                sim0 = new AiWarSim(100.0, 100.0, aiA.aliveSoldiers, aiB.aliveSoldiers);
            }

            // If older save loaded with start=0, fix it once
            int startA = (sim0.startA() > 0) ? sim0.startA() : aiA.aliveSoldiers;
            int startB = (sim0.startB() > 0) ? sim0.startB() : aiB.aliveSoldiers;

            // Faster morale drop (was 2..10, now 8..16)
            double dA = Mth.nextDouble(rng, 8.0, 16.0);
            double dB = Mth.nextDouble(rng, 8.0, 16.0);

            // Casualties proportional to morale drop so totals ≈ 40% when morale hits 0
            // expectedLossThisTick ~= start * (0.40 * (d/100))
            double baseLossA = startA * (0.40 * (dA / 100.0));
            double baseLossB = startB * (0.40 * (dB / 100.0));

            // add variability (±35%) + small integer jitter
            double noiseA = Mth.nextDouble(rng, 0.65, 1.35);
            double noiseB = Mth.nextDouble(rng, 0.65, 1.35);

            int lossA = (int) Math.round(baseLossA * noiseA) + rng.nextInt(-1, 2);
            int lossB = (int) Math.round(baseLossB * noiseB) + rng.nextInt(-1, 2);

            // clamp: prevent weird spikes (cap ~8% of start per minute)
            lossA = Mth.clamp(lossA, 0, Math.max(1, startA / 12));
            lossB = Mth.clamp(lossB, 0, Math.max(1, startB / 12));

            aiA.aliveSoldiers = Math.max(0, aiA.aliveSoldiers - lossA);
            aiB.aliveSoldiers = Math.max(0, aiB.aliveSoldiers - lossB);

            if (aiA.aliveSoldiers != beforeA || aiB.aliveSoldiers != beforeB) {
                changed = true;
            }

            // apply morale
            double moraleA = Math.max(0.0, sim0.moraleA() - dA);
            double moraleB = Math.max(0.0, sim0.moraleB() - dB);

            // store updated sim
            AiWarSim sim1 = new AiWarSim(moraleA, moraleB, startA, startB);
            if (!sim1.equals(sim0)) {
                aiSim.put(key, sim1);
                changed = true;
            }


            // surrender logic (make it exclusive; peace ends the war)
            boolean aSurrenders = (aiA.aliveSoldiers <= 0) || (moraleA <= 0.0);
            boolean bSurrenders = (aiB.aliveSoldiers <= 0) || (moraleB <= 0.0);

           if (aSurrenders && !bSurrenders) {
                endWar(server, b, a, WarEndReason.SURRENDER, WarEndSource.AI_SIM);
                logAiPeace(server, aiB, aiA);
            } else if (bSurrenders && !aSurrenders) {
                endWar(server, a, b, WarEndReason.SURRENDER, WarEndSource.AI_SIM);
                logAiPeace(server, aiA, aiB);
            } else if (aSurrenders && bSurrenders) {
                if (rng.nextBoolean()) {
                    endWar(server, b, a, WarEndReason.SURRENDER, WarEndSource.AI_SIM);
                    logAiPeace(server, aiB, aiA);
                } else {
                    endWar(server, a, b, WarEndReason.SURRENDER, WarEndSource.AI_SIM);
                    logAiPeace(server, aiA, aiB);
                }
            }

        }

         if (changed) {
            setDirty();
            aiState.setDirty();
        }
    }




    public record ZoneView(UUID enemyId, BattleZone zone) {}

    /**
     * Returns all war zones involving this kingdom.
     * If a war exists but no zone exists yet, one is generated and stored.
     */
    public List<ZoneView> getZonesFor(MinecraftServer server, UUID kingdomId) {
        String me = kingdomId.toString();
        var out = new ArrayList<ZoneView>();

        boolean changed = false;
        HashSet<String> seenRoot = new HashSet<>();

        for (String pairKey : wars) {
            int bar = pairKey.indexOf('|');
            if (bar <= 0) continue;

            String aStr = pairKey.substring(0, bar);
            String bStr = pairKey.substring(bar + 1);

            // Only include wars where this kingdom is involved (directly; pair-links include allies already)
            if (!aStr.equals(me) && !bStr.equals(me)) continue;

            String rootKey = pairToRoot.getOrDefault(pairKey, pairKey);
            if (!seenRoot.add(rootKey)) continue; // only one entry per real war

            int rbar = rootKey.indexOf('|');
            if (rbar <= 0) continue;

            UUID rootA, rootB;
            try {
                rootA = UUID.fromString(rootKey.substring(0, rbar));
                rootB = UUID.fromString(rootKey.substring(rbar + 1));
            } catch (Exception ex) {
                continue;
            }

            // enemyId for display: pick the root that isn't "us" if we are one,
            // otherwise pick one root arbitrarily (UI can show both roots if desired).
            UUID enemyId;
            if (kingdomId.equals(rootA)) enemyId = rootB;
            else if (kingdomId.equals(rootB)) enemyId = rootA;
            else enemyId = rootA; // player is an ally, not a root; UI can treat enemyId as "the war target"

            BattleZone z = zones.get(rootKey);
            if (z == null) {
                // If war is pending, don’t compute synchronously.
                // Just skip it for now (UI can show "pending").
                if (pendingRoots.containsKey(rootKey)) {
                    continue;
                }

                // If somehow we have a war without a zone (shouldn't happen), do a cheap fallback box:
                Rect ra = rectFor(server, rootA);
                Rect rb = rectFor(server, rootB);
                int frontX = (ra.cx() + rb.cx()) / 2;
                int frontZ = (ra.cz() + rb.cz()) / 2;
                int half = 120;
                z = BattleZone.of(frontX - half, frontZ - half, frontX + half, frontZ + half);
                zones.put(rootKey, z);
                changed = true;
            }


            out.add(new ZoneView(enemyId, z));
        }

        if (changed) setDirty();
        return out;
    }


    // -----------------------------
    // Zone generation
    // -----------------------------

    private record Rect(int minX, int minZ, int maxX, int maxZ) {
        static Rect point(int x, int z) { return new Rect(x, z, x, z); }
        int cx() { return (minX + maxX) / 2; }
        int cz() { return (minZ + maxZ) / 2; }
        int spanX() { return Math.max(0, maxX - minX); }
        int spanZ() { return Math.max(0, maxZ - minZ); }
    }

    private static Rect rectFor(MinecraftServer server, UUID kingdomId) {
        // Prefer kingdomState (you mirror AI borders into kingdomState already)
        kingdomState ks = kingdomState.get(server);
        kingdomState.Kingdom k = ks.getKingdom(kingdomId);
        if (k != null) {
            if (k.hasBorder) {
                return new Rect(k.borderMinX, k.borderMinZ, k.borderMaxX, k.borderMaxZ);
            }
            BlockPos p = (k.hasTerminal ? k.terminalPos : k.origin);
            return Rect.point(p.getX(), p.getZ());
        }

        // Fallback: aiKingdomState if something is missing
        aiKingdomState ai = aiKingdomState.get(server);
        aiKingdomState.AiKingdom ak = ai.getById(kingdomId);
        if (ak != null) {
            if (ak.hasBorder) {
                return new Rect(ak.borderMinX, ak.borderMinZ, ak.borderMaxX, ak.borderMaxZ);
            }
            return Rect.point(ak.origin.getX(), ak.origin.getZ());
        }

        return Rect.point(0, 0);
    }

    private static int clampInt(int v, int lo, int hi) {
        return (v < lo) ? lo : (v > hi) ? hi : v;
    }

    private static int[] closestPointOn(Rect r, int x, int z) {
        int px = clampInt(x, r.minX, r.maxX);
        int pz = clampInt(z, r.minZ, r.maxZ);
        return new int[]{px, pz};
    }

    private static BattleZone searchForPassingZone(ServerLevel level,
                                                int frontX, int frontZ,
                                                int half, int searchRadius,
                                                int ax, int az, int bx, int bz) {
        BattleZone bestPassZone = null;
        double bestScorePass = Double.POSITIVE_INFINITY;

        for (int oz = -searchRadius; oz <= searchRadius; oz += ZONE_SEARCH_STEP) {
            for (int ox = -searchRadius; ox <= searchRadius; ox += ZONE_SEARCH_STEP) {
                int cx = frontX + ox;
                int cz = frontZ + oz;

                BattleZone candidate = BattleZone.of(cx - half, cz - half, cx + half, cz + half);

                // ✅ Hard reject: inside ANY kingdom territory
                if (overlapsAnyKingdom(level, candidate)) continue;
                if (!candidateAreaLoaded(level, candidate)) continue;
                ZoneQuality q = evaluateZone(level, candidate);

                if (passes(q)) {
                    double score = q.score + betweenPenalty(cx, cz, ax, az, bx, bz);
                    if (score < bestScorePass) {
                        bestScorePass = score;
                        bestPassZone = candidate;
                    }
                }
            }
        }

        return bestPassZone;
    }


    private static BattleZone searchForBestAnyZone(ServerLevel level,
                                                int frontX, int frontZ,
                                                int half, int searchRadius,
                                                int ax, int az, int bx, int bz) {
        BattleZone bestAnyZone = null;
        double bestScoreAny = Double.POSITIVE_INFINITY;

        for (int oz = -searchRadius; oz <= searchRadius; oz += ZONE_SEARCH_STEP) {
            for (int ox = -searchRadius; ox <= searchRadius; ox += ZONE_SEARCH_STEP) {
                int cx = frontX + ox;
                int cz = frontZ + oz;

                BattleZone candidate = BattleZone.of(cx - half, cz - half, cx + half, cz + half);

                // ✅ Still reject zones inside kingdom territory even for fallback.
                if (overlapsAnyKingdom(level, candidate)) continue;
                if (!candidateAreaLoaded(level, candidate)) continue;
                ZoneQuality q = evaluateZone(level, candidate);

                double score = q.score + betweenPenalty(cx, cz, ax, az, bx, bz);
                if (score < bestScoreAny) {
                    bestScoreAny = score;
                    bestAnyZone = candidate;
                }
            }
        }

        return bestAnyZone;
    }


    private static BattleZone computeZone(MinecraftServer server, UUID a, UUID b) {
        Rect ra = rectFor(server, a);
        Rect rb = rectFor(server, b);

        int acx = ra.cx(), acz = ra.cz();
        int bcx = rb.cx(), bcz = rb.cz();

        // Approx “frontline” between closest parts of the two rectangles
        int[] pa = closestPointOn(ra, bcx, bcz);
        int[] pb = closestPointOn(rb, acx, acz);

        int frontX = (pa[0] + pb[0]) / 2;
        int frontZ = (pa[1] + pb[1]) / 2;

        int dx = pb[0] - pa[0];
        int dz = pb[1] - pa[1];
        int dist = (int) Math.sqrt((double) dx * dx + (double) dz * dz);

        int aRad = Math.max(ra.spanX(), ra.spanZ()) / 2;
        int bRad = Math.max(rb.spanX(), rb.spanZ()) / 2;

        // Zone half-size (tweak later)
        int half = 90 + (aRad + bRad) / 12 + dist / 20;
        half = Mth.clamp(half, 90, 220);

        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) {
            return BattleZone.of(frontX - half, frontZ - half, frontX + half, frontZ + half);
        }

        // Prefer a strictly passing zone. If none exist nearby:
        // 1) expand search radius
        // 2) then shrink the zone a bit
        final int[] radiusTries = new int[] {
                ZONE_SEARCH_RADIUS,
                ZONE_SEARCH_RADIUS * 2,
                ZONE_SEARCH_RADIUS * 3,
                ZONE_SEARCH_RADIUS * 4
        };

        final int minHalf = 70; // never shrink below this
        final int[] halfTries = new int[] {
                half,
                Math.max(minHalf, (int) Math.round(half * 0.85)),
                Math.max(minHalf, (int) Math.round(half * 0.70))
        };

        for (int hTry : halfTries) {
            for (int rTry : radiusTries) {
                BattleZone pass = searchForPassingZone(level, frontX, frontZ, hTry, rTry, pa[0], pa[1], pb[0], pb[1]);
                if (pass != null) {
                    if (hTry != half || rTry != ZONE_SEARCH_RADIUS) {
                        Kingdoms.LOGGER.info("[War] computeZone: expanded search r={} half={} (origHalf={}) chosen={}",
                                rTry, hTry, half, pass);
                    }
                    return pass;
                }
            }
        }

        // Last resort: pick the best-any in the widest search with the smallest half.
        int widest = radiusTries[radiusTries.length - 1];
        int smallestHalf = halfTries[halfTries.length - 1];
        BattleZone fallback = searchForBestAnyZone(level, frontX, frontZ, smallestHalf, widest, pa[0], pa[1], pb[0], pb[1]);

        if (fallback == null) {
            fallback = BattleZone.of(frontX - half, frontZ - half, frontX + half, frontZ + half);
        }

        Kingdoms.LOGGER.warn("[War] computeZone: NO passing zone found (water/hills/ravines too extreme). Using fallback={}", fallback);
        return fallback;
    }

    // -----------------------------
    // Persistence
    // -----------------------------

    private static final Codec<Set<String>> WARS_CODEC =
            Codec.STRING.listOf().xmap(HashSet::new, set -> List.copyOf(set));

    private static final Codec<Map<String, BattleZone>> ZONES_CODEC =
            Codec.unboundedMap(Codec.STRING, BattleZone.CODEC);

    private static final Codec<Map<String, BattleZone>> SIEGE_ZONES_CODEC =
            Codec.unboundedMap(Codec.STRING, BattleZone.CODEC);


    private static final Codec<PendingRootWar> PENDING_ROOT_CODEC =
        RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.fieldOf("rootA").xmap(UUID::fromString, UUID::toString).forGetter(PendingRootWar::rootA),
            Codec.STRING.fieldOf("rootB").xmap(UUID::fromString, UUID::toString).forGetter(PendingRootWar::rootB),
            Codec.LONG.optionalFieldOf("requestedAt", 0L).forGetter(PendingRootWar::requestedAtTick)
        ).apply(inst, PendingRootWar::new));

    private static final Codec<Map<String, PendingRootWar>> PENDING_ROOTS_CODEC =
        Codec.unboundedMap(Codec.STRING, PENDING_ROOT_CODEC);


    private static final Codec<AiWarSim> AI_SIM_CODEC =
    RecordCodecBuilder.create(inst -> inst.group(
            Codec.DOUBLE.optionalFieldOf("moraleA", 100.0).forGetter(AiWarSim::moraleA),
            Codec.DOUBLE.optionalFieldOf("moraleB", 100.0).forGetter(AiWarSim::moraleB),

            // NEW: starting soldier counts (optional for backwards compatibility)
            Codec.INT.optionalFieldOf("startA", 0).forGetter(AiWarSim::startA),
            Codec.INT.optionalFieldOf("startB", 0).forGetter(AiWarSim::startB)
            ).apply(inst, AiWarSim::new));

    private static final Codec<Map<String, AiWarSim>> AI_SIM_MAP_CODEC =
            Codec.unboundedMap(Codec.STRING, AI_SIM_CODEC);

    private static final Codec<Map<String, String>> PAIR_TO_ROOT_CODEC =
        Codec.unboundedMap(Codec.STRING, Codec.STRING);


    private static final Codec<WarState> CODEC =
        RecordCodecBuilder.create(inst -> inst.group(
                WARS_CODEC.optionalFieldOf("wars", Set.of()).forGetter(s -> s.wars),
                ZONES_CODEC.optionalFieldOf("zones", Map.of()).forGetter(s -> s.zones),
                PAIR_TO_ROOT_CODEC.optionalFieldOf("pairToRoot", Map.of()).forGetter(s -> s.pairToRoot),
                AI_SIM_MAP_CODEC.optionalFieldOf("aiSim", Map.of()).forGetter(s -> s.aiSim),
                PENDING_ROOTS_CODEC.optionalFieldOf("pendingRoots", Map.of()).forGetter(s -> s.pendingRoots) 
        ).apply(inst, (loadedWars, loadedZones, loadedPairToRoot, loadedAiSim, loadedPendingRoots) -> {
            WarState s = new WarState();
            s.wars.addAll(loadedWars);
            s.zones.putAll(loadedZones);
            s.pairToRoot.putAll(loadedPairToRoot);
            s.aiSim.putAll(loadedAiSim);
            s.pendingRoots.putAll(loadedPendingRoots); 
            return s;
        }));




    private static final SavedDataType<WarState> TYPE =
            new SavedDataType<>(
                    "kingdoms_wars",
                    WarState::new,
                    CODEC,
                    null
            );

    public static WarState get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return new WarState();
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }

    private static String key(UUID a, UUID b) {
        String sa = a.toString();
        String sb = b.toString();
        return (sa.compareTo(sb) <= 0) ? (sa + "|" + sb) : (sb + "|" + sa);
    }

    /** Returns the canonical ROOT war key for the pair (a,b). Defaults to the pair key itself. */
    public String getRootKey(UUID a, UUID b) {
        String k = key(a, b);
        return pairToRoot.getOrDefault(k, k);
    }

    /** Returns the canonical ROOT war key for an already-stored pair-link key. */
    public String getRootKeyFromPairKey(String pairKey) {
        if (pairKey == null) return "";
        return pairToRoot.getOrDefault(pairKey, pairKey);
    }



        public void makePeaceWithAllies(MinecraftServer server, UUID a, UUID b) {
            if (server == null || a == null || b == null) return;

            var alliance = name.kingdoms.diplomacy.AllianceState.get(server);

            // closure on each side
            HashSet<UUID> sideA = new HashSet<>();
            HashSet<UUID> sideB = new HashSet<>();
            ArrayDeque<UUID> qa = new ArrayDeque<>();
            ArrayDeque<UUID> qb = new ArrayDeque<>();

            sideA.add(a); qa.add(a);
            sideB.add(b); qb.add(b);

            while (!qa.isEmpty()) {
                UUID cur = qa.removeFirst();
                for (UUID ally : alliance.alliesOf(cur)) if (ally != null && sideA.add(ally)) qa.addLast(ally);
            }
            while (!qb.isEmpty()) {
                UUID cur = qb.removeFirst();
                for (UUID ally : alliance.alliesOf(cur)) if (ally != null && sideB.add(ally)) qb.addLast(ally);
            }

            boolean changed = false;


            // Remove zones by ROOT KEY (dedupe roots)
            HashSet<String> rootsToRemove = new HashSet<>();

            for (UUID x : sideA) {
                for (UUID y : sideB) {
                    String pairKey = key(x, y);

                    // capture root BEFORE removing mapping
                    String rootKey = pairToRoot.getOrDefault(pairKey, pairKey);
                    rootsToRemove.add(rootKey);

                    if (wars.remove(pairKey)) changed = true;
                    if (aiSim.remove(pairKey) != null) changed = true;
                    pairToRoot.remove(pairKey);
                }
            }

            for (String rootKey : rootsToRemove) {
                if (zones.remove(rootKey) != null) changed = true;

                // cancel pending work too
                PendingRootWar pr = pendingRoots.remove(rootKey);
                if (pr != null) {
                    ensurePendingRuntimeInit();
                    pendingJobs.remove(rootKey);
                    pendingQueue.removeIf(k -> k.equals(rootKey));
                    changed = true;
                }
            }


            if (changed) setDirty();
        }


        private final class ZoneJob {

    // search state
    final UUID a, b;

    final int frontX, frontZ;
    final int ax, az, bx, bz;

    final int[] radiusTries;
    final int[] halfTries;

    int halfIdx = 0;
    int radIdx = 0;

    int ox;
    int oz;

    int curHalf;
    int curRadius;

    BattleZone bestPass = null;
    double bestPassScore = Double.POSITIVE_INFINITY;

    BattleZone bestAny = null;
    double bestAnyScore = Double.POSITIVE_INFINITY;

    final BattleZone defaultFallback;

    ZoneJob(MinecraftServer server, UUID a, UUID b) {
        this.a = a;
        this.b = b;

        Rect ra = rectFor(server, a);
        Rect rb = rectFor(server, b);

        int acx = ra.cx(), acz = ra.cz();
        int bcx = rb.cx(), bcz = rb.cz();

        int[] pa = closestPointOn(ra, bcx, bcz);
        int[] pb = closestPointOn(rb, acx, acz);

        this.ax = pa[0];
        this.az = pa[1];
        this.bx = pb[0];
        this.bz = pb[1];

        this.frontX = (ax + bx) / 2;
        this.frontZ = (az + bz) / 2;

        int dx = bx - ax;
        int dz = bz - az;
        int dist = (int) Math.sqrt((double) dx * dx + (double) dz * dz);

        int aRad = Math.max(ra.spanX(), ra.spanZ()) / 2;
        int bRad = Math.max(rb.spanX(), rb.spanZ()) / 2;

        int half = 90 + (aRad + bRad) / 12 + dist / 20;
        half = Mth.clamp(half, 90, 220);

        final int minHalf = 70;

        this.radiusTries = new int[] {
                ZONE_SEARCH_RADIUS,
                ZONE_SEARCH_RADIUS * 2,
                ZONE_SEARCH_RADIUS * 3,
                ZONE_SEARCH_RADIUS * 4
        };

        this.halfTries = new int[] {
                half,
                Math.max(minHalf, (int) Math.round(half * 0.85)),
                Math.max(minHalf, (int) Math.round(half * 0.70))
        };

        this.defaultFallback = BattleZone.of(frontX - half, frontZ - half, frontX + half, frontZ + half);

        loadLoop();
    }

    private void loadLoop() {
        curHalf = halfTries[halfIdx];
        curRadius = radiusTries[radIdx];
        ox = -curRadius;
        oz = -curRadius;
    }

    record StepResult(boolean done, BattleZone bestPass, BattleZone bestAny, int candidatesLeft) {}

    StepResult step(MinecraftServer server, long deadlineNanos, int candidatesLeft) {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            if (level == null) {
                // no overworld; just finish immediately with fallback
                return new StepResult(true, null, defaultFallback, candidatesLeft);
            }

            while (System.nanoTime() < deadlineNanos && candidatesLeft > 0) {
                candidatesLeft--;

                int cx = frontX + ox;
                int cz = frontZ + oz;

                BattleZone candidate = BattleZone.of(cx - curHalf, cz - curHalf, cx + curHalf, cz + curHalf);

                // reject if overlaps any kingdom
                if (!overlapsAnyKingdom(level, candidate)) {

                    if (!candidateAreaLoaded(level, candidate)) {
                        // skip - never load chunks during zone search
                    } else {
                        ZoneQuality q = evaluateZone(level, candidate);
                        double score = q.score + betweenPenalty(cx, cz, ax, az, bx, bz);

                        if (score < bestAnyScore) { bestAnyScore = score; bestAny = candidate; }
                        if (passes(q) && score < bestPassScore) {
                            bestPassScore = score;
                            bestPass = candidate;
                            return new StepResult(true, bestPass, bestAny, candidatesLeft);
                        }
                    }
                }


                // advance grid
                ox += ZONE_SEARCH_STEP;
                if (ox > curRadius) {
                    ox = -curRadius;
                    oz += ZONE_SEARCH_STEP;
                }

                // finished this (half,radius) scan?
                if (oz > curRadius) {
                    // next radius, else next half
                    radIdx++;
                    if (radIdx >= radiusTries.length) {
                        radIdx = 0;
                        halfIdx++;
                        if (halfIdx >= halfTries.length) {
                            // exhausted all
                            return new StepResult(true, bestPass, bestAny, candidatesLeft);
                        }
                    }
                    loadLoop();
                }
            }

            return new StepResult(false, bestPass, bestAny, candidatesLeft);
        }
    }


    public WarState() {}
}
