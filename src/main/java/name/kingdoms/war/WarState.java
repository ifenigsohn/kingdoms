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

    // Simulated war state for AI vs AI (morale per side)
    private final Map<String, AiWarSim> aiSim = new HashMap<>();

    private record AiWarSim(double moraleA, double moraleB) {}

    private static final int AI_WAR_TICK_INTERVAL = 20 * 60 * 10; // 10 min

    // -----------------------------
    // Zone quality tuning
    // -----------------------------
    private static final double MAX_WATER_FRAC = 0.10; // <= 10% water samples
    private static final int MAX_HEIGHT_RANGE = 16;    // blocks (tune)
    private static final double MAX_STDDEV = 6.0;      // blocks (tune)

    /** Maximum allowed step between neighboring samples (ravine / cliff detector). */
    private static final int MAX_ADJACENT_STEP = 3;    // blocks (tune)

    // How hard we search around the computed frontline center
    private static final int ZONE_SEARCH_RADIUS = 128; // base radius (tune)
    private static final int ZONE_SEARCH_STEP   = 16;  // blocks (tune)

    // Sampling grid resolution per candidate
    private static final int SAMPLE_GRID = 32;

    private record ZoneQuality(
            double waterFrac,
            int heightRange,
            double stddev,
            int maxStep,
            double score,
            int waterCount,
            int samples
    ) {}




    private static boolean isSurfaceWater(ServerLevel level, int x, int z) {
        int yTop = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        var pos = new BlockPos(x, yTop - 1, z);
        return level.getBlockState(pos).getFluidState().is(Fluids.WATER);
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

        int samples = SAMPLE_GRID * SAMPLE_GRID;

        int water = 0;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        double sum = 0.0;
        double sumSq = 0.0;

        // Store heights so we can detect cliffs/ravines via adjacent deltas
        int[][] h = new int[SAMPLE_GRID][SAMPLE_GRID];

        for (int iz = 0; iz < SAMPLE_GRID; iz++) {
            int z = minZ + (int) Math.round((double) iz * (maxZ - minZ) / (SAMPLE_GRID - 1));
            for (int ix = 0; ix < SAMPLE_GRID; ix++) {
                int x = minX + (int) Math.round((double) ix * (maxX - minX) / (SAMPLE_GRID - 1));

                if (isSurfaceWater(level, x, z)) water++;

                int y = groundY(level, x, z);
                h[iz][ix] = y;

                if (y < minY) minY = y;
                if (y > maxY) maxY = y;

                sum += y;
                sumSq += (double) y * (double) y;
            }
        }

        // Ravine / cliff detector: maximum neighbor step in the sample grid
        int maxStep = 0;
        for (int iz = 0; iz < SAMPLE_GRID; iz++) {
            for (int ix = 0; ix < SAMPLE_GRID; ix++) {
                int y = h[iz][ix];
                if (ix + 1 < SAMPLE_GRID) maxStep = Math.max(maxStep, Math.abs(y - h[iz][ix + 1]));
                if (iz + 1 < SAMPLE_GRID) maxStep = Math.max(maxStep, Math.abs(y - h[iz + 1][ix]));
            }
        }

        double mean = sum / samples;
        double var = Math.max(0.0, (sumSq / samples) - (mean * mean));
        double std = Math.sqrt(var);

        int range = Math.max(0, maxY - minY);
        double waterFrac = (double) water / (double) samples;

        // Score: hard-penalize water and hilliness; also penalize cliffs heavily
        double score =
                (waterFrac * 2000.0) +
                (range * 8.0) +
                (std * 40.0) +
                (maxStep * 120.0);

        return new ZoneQuality(waterFrac, range, std, maxStep, score, water, samples);
    }

    private static boolean passes(ZoneQuality q) {
        return q.waterFrac <= MAX_WATER_FRAC
                && q.heightRange <= MAX_HEIGHT_RANGE
                && q.stddev <= MAX_STDDEV
                && q.maxStep <= MAX_ADJACENT_STEP;
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

   /** Preferred: declares war AND ensures a zone exists immediately. */
    public void declareWar(MinecraftServer server, UUID a, UUID b) {
        if (a == null || b == null) return;
        if (a.equals(b)) return;

        var alliance = name.kingdoms.diplomacy.AllianceState.get(server);

        // -----------------------------
        // RULE 1: Allies cannot declare war on each other
        // -----------------------------
        if (alliance.isAllied(a, b)) {
            Kingdoms.LOGGER.warn(
                    "[War] Blocked war declaration between allies: {} <-> {}", a, b
            );
            return;
        }

        // Normalize key (unordered pair)
        String k = key(a, b);

        // -----------------------------
        // Prevent duplicate wars
        // -----------------------------
        if (!wars.add(k)) {
            return; // already at war
        }

        Kingdoms.LOGGER.info("[War] declareWar a={} b={} key={}", a, b, k);

        // -----------------------------
        // Ensure battle zone exists
        // -----------------------------
        zones.computeIfAbsent(k, kk -> computeZone(server, a, b));

        setDirty();

        // -----------------------------
        // RULE 2: Allies auto-join wars
        // -----------------------------
        // A's allies fight B (unless allied with B)
        for (UUID allyA : alliance.alliesOf(a)) {
            if (!allyA.equals(b) && !alliance.isAllied(allyA, b)) {
                declareWar(server, allyA, b);
            }
        }

        // B's allies fight A (unless allied with A)
        for (UUID allyB : alliance.alliesOf(b)) {
            if (!allyB.equals(a) && !alliance.isAllied(allyB, a)) {
                declareWar(server, allyB, a);
            }
        }
    }



    public void makePeace(UUID a, UUID b) {
        String k = key(a, b);
        wars.remove(k);
        zones.remove(k);
        aiSim.remove(k);
        setDirty();
    }

    // -----------------------------
    // Zone API
    // -----------------------------

    public Optional<BattleZone> getZone(UUID a, UUID b) {
        return Optional.ofNullable(zones.get(key(a, b)));
    }

    public void setZone(UUID a, UUID b, BattleZone zone) {
        zones.put(key(a, b), zone);
        setDirty();
    }

    public void clearZone(UUID a, UUID b) {
        zones.remove(key(a, b));
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


    public void tickAiWars(MinecraftServer server) {
        if (server.getTickCount() % AI_WAR_TICK_INTERVAL != 0) return;

        var aiState = aiKingdomState.get(server);
        var rng = server.overworld().getRandom();

        for (var key : wars) {
            var parts = key.split("\\|");
            UUID a = UUID.fromString(parts[0]);
            UUID b = UUID.fromString(parts[1]);

            var aiA = aiState.getById(a);
            var aiB = aiState.getById(b);
            if (aiA == null || aiB == null) continue;

            // --- simulate battle ---
            int lossA = rng.nextInt(5, 20);
            int lossB = rng.nextInt(5, 20);

            aiA.aliveSoldiers = Math.max(0, aiA.aliveSoldiers - lossA);
            aiB.aliveSoldiers = Math.max(0, aiB.aliveSoldiers - lossB);

            
            // Get sim morale for this war pair (initialize at 100/100)
            AiWarSim sim = aiSim.get(key);
            if (sim == null) sim = new AiWarSim(100.0, 100.0);

            // RandomSource doesn't have nextDouble(min,max); use Mth.nextDouble
            double dA = net.minecraft.util.Mth.nextDouble(rng, 0.5, 2.0);
            double dB = net.minecraft.util.Mth.nextDouble(rng, 0.5, 2.0);

            double moraleA = Math.max(0.0, sim.moraleA() - dA);
            double moraleB = Math.max(0.0, sim.moraleB() - dB);

            aiSim.put(key, new AiWarSim(moraleA, moraleB));


            // --- surrender logic ---
            if (aiA.aliveSoldiers <= 0 || moraleA <= 0.0) {
                makePeace(a, b);
                logAiPeace(server, aiB, aiA);
            }
            if (aiB.aliveSoldiers <= 0 || moraleB <= 0.0)  {
                makePeace(a, b);
                logAiPeace(server, aiA, aiB);
            }
        }

        aiState.setDirty();
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

        for (String warKey : wars) {
            int bar = warKey.indexOf('|');
            if (bar <= 0) continue;

            String a = warKey.substring(0, bar);
            String b = warKey.substring(bar + 1);

            if (!a.equals(me) && !b.equals(me)) continue;

            UUID enemyId;
            try {
                enemyId = UUID.fromString(a.equals(me) ? b : a);
            } catch (Exception ex) {
                continue;
            }

            BattleZone z = zones.get(warKey);
            if (z == null) {
                z = computeZone(server, kingdomId, enemyId);
                zones.put(warKey, z);
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

    private static BattleZone searchForPassingZone(ServerLevel level, int frontX, int frontZ, int half, int searchRadius) {
        BattleZone bestPassZone = null;
        double bestScorePass = Double.POSITIVE_INFINITY;

        for (int oz = -searchRadius; oz <= searchRadius; oz += ZONE_SEARCH_STEP) {
            for (int ox = -searchRadius; ox <= searchRadius; ox += ZONE_SEARCH_STEP) {
                int cx = frontX + ox;
                int cz = frontZ + oz;

                BattleZone candidate = BattleZone.of(cx - half, cz - half, cx + half, cz + half);
                ZoneQuality q = evaluateZone(level, candidate);

                if (passes(q) && q.score < bestScorePass) {
                    bestScorePass = q.score;
                    bestPassZone = candidate;
                }
            }
        }

        return bestPassZone;
    }

    private static BattleZone searchForBestAnyZone(ServerLevel level, int frontX, int frontZ, int half, int searchRadius) {
        BattleZone bestAnyZone = null;
        double bestScoreAny = Double.POSITIVE_INFINITY;

        for (int oz = -searchRadius; oz <= searchRadius; oz += ZONE_SEARCH_STEP) {
            for (int ox = -searchRadius; ox <= searchRadius; ox += ZONE_SEARCH_STEP) {
                int cx = frontX + ox;
                int cz = frontZ + oz;

                BattleZone candidate = BattleZone.of(cx - half, cz - half, cx + half, cz + half);
                ZoneQuality q = evaluateZone(level, candidate);

                if (q.score < bestScoreAny) {
                    bestScoreAny = q.score;
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
                BattleZone pass = searchForPassingZone(level, frontX, frontZ, hTry, rTry);
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
        BattleZone fallback = searchForBestAnyZone(level, frontX, frontZ, smallestHalf, widest);

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


    private static final Codec<WarState> CODEC =
        RecordCodecBuilder.create(inst -> inst.group(
                WARS_CODEC.optionalFieldOf("wars", Set.of()).forGetter(s -> s.wars),
                ZONES_CODEC.optionalFieldOf("zones", Map.of()).forGetter(s -> s.zones),
                AI_SIM_MAP_CODEC.optionalFieldOf("aiSim", Map.of()).forGetter(s -> s.aiSim)
        ).apply(inst, (loadedWars, loadedZones, loadedAiSim) -> {
            WarState s = new WarState();
            s.wars.addAll(loadedWars);
            s.zones.putAll(loadedZones);
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

    public WarState() {}
}
