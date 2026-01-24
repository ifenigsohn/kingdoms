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

import java.util.*;

public final class WarState extends SavedData {

    // store unordered pairs as "minUUID|maxUUID"
    private final Set<String> wars = new HashSet<>();

    // battle zone per war pair (key is same min|max)
    private final Map<String, BattleZone> zones = new HashMap<>();
    
    private final Map<String, String> pairToRoot = new HashMap<>();

    // Simulated war state for AI vs AI (morale per side)
    private final Map<String, AiWarSim> aiSim = new HashMap<>();

    private record AiWarSim(double moraleA, double moraleB) {}

    private static final int AI_WAR_TICK_INTERVAL = 20 * 60 * 1; // 1 MINUTE DEBUG FOR DEV

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
        // Use WORLD_SURFACE for "top" (often above water), then check multiple blocks
        int yTop = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);

        // Check at yTop and slightly below. Oceans/rivers often need this.
        for (int dy = 0; dy <= 3; dy++) {
            var pos = new BlockPos(x, yTop - dy, z);
            if (level.getFluidState(pos).is(Fluids.WATER)) return true;
        }

        // Also check the top of OCEAN_FLOOR column (shorelines)
        int yFloor = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
        for (int dy = 0; dy <= 2; dy++) {
            var pos = new BlockPos(x, yFloor + dy, z);
            if (level.getFluidState(pos).is(Fluids.WATER)) return true;
        }

        return false;
    }


    private static int groundY(ServerLevel level, int x, int z) {
        // OCEAN_FLOOR gives solid ground even under water (good for hilliness metric)
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
        declareWarInternal(server, a, b, a, b);
    }

    /**
     * Internal declaration that preserves a single ROOT war for the whole coalition.
     * Every allied pair-link maps back to the same rootKey, and zones are stored once per rootKey.
     */
    private void declareWarInternal(MinecraftServer server, UUID a, UUID b, UUID rootA, UUID rootB) {
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
            // Still ensure the mapping exists (migration / weird edges)
            pairToRoot.putIfAbsent(pairKey, rootKey);
            return;
        }

        pairToRoot.put(pairKey, rootKey);

        Kingdoms.LOGGER.info("[War] declareWar link a={} b={} pairKey={} rootKey={}", a, b, pairKey, rootKey);

        // Ensure a single battle zone exists for the ROOT war only
        zones.computeIfAbsent(rootKey, kk -> computeZone(server, rootA, rootB));

        setDirty();

        // RULE 2: Allies auto-join wars (but they map back to the same root war)
        for (UUID allyA : alliance.alliesOf(a)) {
            if (!allyA.equals(b) && !alliance.isAllied(allyA, b)) {
                declareWarInternal(server, allyA, b, rootA, rootB);
            }
        }

        for (UUID allyB : alliance.alliesOf(b)) {
            if (!allyB.equals(a) && !alliance.isAllied(allyB, a)) {
                declareWarInternal(server, allyB, a, rootA, rootB);
            }
        }
    }




    public void makePeace(UUID a, UUID b) {
        String pairKey = key(a, b);
        String rootKey = pairToRoot.getOrDefault(pairKey, pairKey);

        boolean changed = false;

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

    public static record AiWarSimSnap(double moraleA, double moraleB) {}


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
            simCopy.put(e.getKey(), new AiWarSimSnap(v.moraleA(), v.moraleB()));
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
                    this.aiSim.put(e.getKey(), new AiWarSim(v.moraleA(), v.moraleB()));
                }
            }
        }

        setDirty();
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

            // --- simulate battle ---
            int lossA = rng.nextInt(3, 15);
            int lossB = rng.nextInt(3, 15);

            int beforeA = aiA.aliveSoldiers;
            int beforeB = aiB.aliveSoldiers;

            aiA.aliveSoldiers = Math.max(0, aiA.aliveSoldiers - lossA);
            aiB.aliveSoldiers = Math.max(0, aiB.aliveSoldiers - lossB);

             if (aiA.aliveSoldiers != beforeA || aiB.aliveSoldiers != beforeB) {
                changed = true; // NEW
            }

            // morale
            AiWarSim sim = aiSim.get(key);
            if (sim == null) sim = new AiWarSim(100.0, 100.0);

            double dA = Mth.nextDouble(rng, 2, 10.0);
            double dB = Mth.nextDouble(rng, 2, 10.0);

            double moraleA = Math.max(0.0, sim.moraleA() - dA);
            double moraleB = Math.max(0.0, sim.moraleB() - dB);

            AiWarSim newSim = new AiWarSim(moraleA, moraleB);
            if (!newSim.equals(sim)) {
                aiSim.put(key, newSim);
                changed = true; // NEW
            }

            // surrender logic (make it exclusive; peace ends the war)
            boolean aSurrenders = (aiA.aliveSoldiers <= 0) || (moraleA <= 0.0);
            boolean bSurrenders = (aiB.aliveSoldiers <= 0) || (moraleB <= 0.0);

            if (aSurrenders && !bSurrenders) {
                makePeace(a, b);
                logAiPeace(server, aiB, aiA);
            } else if (bSurrenders && !aSurrenders) {
                makePeace(a, b);
                logAiPeace(server, aiA, aiB);
            } else if (aSurrenders && bSurrenders) {
                // tie case: pick a winner deterministically/randomly
                if (rng.nextBoolean()) {
                    makePeace(a, b);
                    logAiPeace(server, aiB, aiA);
                } else {
                    makePeace(a, b);
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
                z = computeZone(server, rootA, rootB);
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

    private static final Codec<AiWarSim> AI_SIM_CODEC =
        RecordCodecBuilder.create(inst -> inst.group(
                Codec.DOUBLE.optionalFieldOf("moraleA", 100.0).forGetter(AiWarSim::moraleA),
                Codec.DOUBLE.optionalFieldOf("moraleB", 100.0).forGetter(AiWarSim::moraleB)
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
                AI_SIM_MAP_CODEC.optionalFieldOf("aiSim", Map.of()).forGetter(s -> s.aiSim)
        ).apply(inst, (loadedWars, loadedZones, loadedPairToRoot, loadedAiSim) -> {
            WarState s = new WarState();
            s.wars.addAll(loadedWars);
            s.zones.putAll(loadedZones);
            s.pairToRoot.putAll(loadedPairToRoot);
            s.aiSim.putAll(loadedAiSim);
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
                for (UUID ally : alliance.alliesOf(cur)) if (sideA.add(ally)) qa.addLast(ally);
            }
            while (!qb.isEmpty()) {
                UUID cur = qb.removeFirst();
                for (UUID ally : alliance.alliesOf(cur)) if (sideB.add(ally)) qb.addLast(ally);
            }

            boolean changed = false;
            for (UUID x : sideA) {
                for (UUID y : sideB) {
                    String k = key(x, y);
                    changed |= wars.remove(k);
                    changed |= (zones.remove(k) != null);
                    changed |= (aiSim.remove(k) != null);
                }
            }

            if (changed) setDirty();
        }


    public WarState() {}
}
