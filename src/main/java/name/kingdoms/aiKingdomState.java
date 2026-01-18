package name.kingdoms;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import name.kingdoms.entity.aiKingdomEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import name.kingdoms.blueprint.KingdomSatelliteSpawner.KingdomSize;
import name.kingdoms.blueprint.KingdomSatelliteSpawner;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class aiKingdomState extends SavedData {
    public boolean isAiKingdom(UUID kingdomId) {
        return kingdoms.containsKey(kingdomId);
    }

    public final Map<UUID, AiKingdom> kingdoms = new HashMap<>();

    // ---- Inline codecs ----
    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    private static final Codec<BlockPos> BLOCKPOS_CODEC =
            Codec.LONG.xmap(BlockPos::of, BlockPos::asLong);

    /** Convenience wrapper like kingdomState has. */
    public void markDirty() { setDirty(); }

    // =========================
    // Lookups (optional helpers)
    // =========================

    /** Lookup by the UUID used as AiKingdom.id (currently king UUID). */
    public AiKingdom getById(UUID id) {
        return kingdoms.get(id);
    }

    /** Convenience for UI/debug/mail "From:" label. */
    public String getNameById(UUID id) {
        AiKingdom k = kingdoms.get(id);
        return (k != null) ? k.name : "Unknown Kingdom";
    }

    private static void tickSoldiers(AiKingdom k, RandomSource r, boolean inWar) {
    // safety for old data
    if (k.maxSoldiers <= 0) k.maxSoldiers = defaultMaxSoldiersForSize(k.size);
    k.maxSoldiers = Mth.clamp(k.maxSoldiers, 50, 300);
    k.aliveSoldiers = Mth.clamp(k.aliveSoldiers, 0, k.maxSoldiers);

    if (inWar) {
        // No regen while war is active (optional tiny attrition)
        if (r.nextInt(600) == 0 && k.aliveSoldiers > 0) { // very rare
            k.aliveSoldiers--;
        }
        return;
    }

        // Out of wars: slowly refill toward max
        if (k.aliveSoldiers < k.maxSoldiers) {
                // “random ticks with drift”
                if (r.nextInt(120) == 0) { // tune: bigger = slower regen
                int gain = 1 + (r.nextInt(10) == 0 ? 1 : 0); // sometimes +2
                k.aliveSoldiers = Math.min(k.maxSoldiers, k.aliveSoldiers + gain);
                }
        }

        // Slight negative drift when kingdom is unstable
        if ((k.happiness < 25 || k.security < 25) && r.nextInt(300) == 0) {
                k.aliveSoldiers = Math.max(0, k.aliveSoldiers - 1);
        }
        }


    // =========================
    // Creation / fetching
    // =========================

    public AiKingdom getOrCreateForKing(ServerLevel level, aiKingdomEntity king) {
        UUID id = king.getUUID();
        AiKingdom existing = kingdoms.get(id);
        if (existing != null) return existing;

        RandomSource r = level.random;

        String name = "King " + king.getKingName();
        BlockPos origin = king.getHomePos() != null ? king.getHomePos() : king.blockPosition();

        AiKingdom k = new AiKingdom(id, id, name, origin);
        k.personality = KingdomPersonality.random(r);


        // border: small/med/large square
        int roll = r.nextInt(100);
        KingdomSize size;
        if (roll < 45) size = KingdomSize.SMALL;
        else if (roll < 85) size = KingdomSize.MEDIUM;
        else size = KingdomSize.LARGE;

        k.size = size;


        int half = KingdomSatelliteSpawner.maxRadiusForSize(size) + 32;

        k.hasBorder = true;
        k.borderMinX = origin.getX() - half;
        k.borderMaxX = origin.getX() + half;
        k.borderMinZ = origin.getZ() - half;
        k.borderMaxZ = origin.getZ() + half;

        // soldiers: max based on size
        k.maxSoldiers = rollMaxSoldiers(r, size);

        // start partially filled so some kingdoms feel “weaker/stronger”
        int minAlive = Math.max(0, (int) Math.floor(k.maxSoldiers * 0.60));
        k.aliveSoldiers = rangeInt(r, minAlive, k.maxSoldiers);

        // Link AI -> kingdomState border/claim system
        kingdomState ks = kingdomState.get(level.getServer());
        kingdomState.Kingdom kk = ks.ensureAiKingdom(k.id, k.kingUuid, k.name, k.origin);
        kk.hasBorder = true;
        kk.borderMinX = k.borderMinX;
        kk.borderMaxX = k.borderMaxX;
        kk.borderMinZ = k.borderMinZ;
        kk.borderMaxZ = k.borderMaxZ;
        ks.claimRect(level, k.id, k.borderMinX, k.borderMaxX, k.borderMinZ, k.borderMaxZ);

        // economy (
        k.gold    = range(r, 200, 2000);
        k.meat    = range(r, 50, 800);
        k.grain   = range(r, 80, 1200);
        k.fish    = range(r, 30, 700);

        k.wood    = range(r, 100, 1500);
        k.metal   = range(r, 50, 800);
        k.gems    = range(r, 0, 120);
        k.potions = range(r, 0, 80);
        k.armor   = range(r, 10, 150);
        k.horses  = range(r, 0, 100);
        k.weapons = range(r, 20, 150);

        k.happiness = Mth.clamp(rangeInt(r, 25, 90), 0, 100);
        k.security  = Mth.clamp(rangeInt(r, 0, 1), 0, 100);

        k.skinId = king.getSkinId();
        kingdoms.put(id, k);
        setDirty();
        return k;

    }

    // =========================
    // Data models
    // =========================


    private static int rollMaxSoldiers(RandomSource r, KingdomSize size) {
        return switch (size) {
                case SMALL -> rangeInt(r, 50, 100);
                case MEDIUM -> rangeInt(r, 100, 200);
                case LARGE -> rangeInt(r, 200, 300);
        };
        }

        private static int defaultMaxSoldiersForSize(KingdomSize size) {
        return switch (size) {
                case SMALL -> 75;
                case MEDIUM -> 150;
                case LARGE -> 250;
        };
        }


    public record KingdomPersonality(
            double generosity,   // gives gifts / accepts asks more
            double greed,        // demands better trade ratios
            double trustBias,    // baseline friendliness to player
            double honor,        // dislikes unfair trades (more counteroffers)
            double aggression,   // more insults / harsher rejections (war later)
            double pragmatism    // need/surplus driven decisions
    ) {
        public static final KingdomPersonality DEFAULT =
                new KingdomPersonality(0.50, 0.50, 0.50, 0.50, 0.35, 0.60);

        private static double clamp01(double v) { return Mth.clamp(v, 0.0, 1.0); }

        public static KingdomPersonality random(RandomSource r) {
            // Mildly centered randomness so most kingdoms feel "reasonable"
            double gen = clamp01(0.50 + r.nextGaussian() * 0.18);
            double grd = clamp01(0.50 + r.nextGaussian() * 0.18);
            double tru = clamp01(0.50 + r.nextGaussian() * 0.18);
            double hon = clamp01(0.50 + r.nextGaussian() * 0.18);
            double agg = clamp01(0.35 + r.nextGaussian() * 0.20);
            double pra = clamp01(0.60 + r.nextGaussian() * 0.18);
            return new KingdomPersonality(gen, grd, tru, hon, agg, pra);
        }

        public static final Codec<KingdomPersonality> CODEC =
                RecordCodecBuilder.create(inst -> inst.group(
                        Codec.DOUBLE.optionalFieldOf("generosity", DEFAULT.generosity()).forGetter(KingdomPersonality::generosity),
                        Codec.DOUBLE.optionalFieldOf("greed",      DEFAULT.greed()).forGetter(KingdomPersonality::greed),
                        Codec.DOUBLE.optionalFieldOf("trustBias",  DEFAULT.trustBias()).forGetter(KingdomPersonality::trustBias),
                        Codec.DOUBLE.optionalFieldOf("honor",      DEFAULT.honor()).forGetter(KingdomPersonality::honor),
                        Codec.DOUBLE.optionalFieldOf("aggression", DEFAULT.aggression()).forGetter(KingdomPersonality::aggression),
                        Codec.DOUBLE.optionalFieldOf("pragmatism", DEFAULT.pragmatism()).forGetter(KingdomPersonality::pragmatism)
                ).apply(inst, (gen, grd, tru, hon, agg, pra) ->
                        new KingdomPersonality(
                                clamp01(gen), clamp01(grd), clamp01(tru),
                                clamp01(hon), clamp01(agg), clamp01(pra)
                        )
                ));
    }

        public record AiKingdomSnap(
                UUID id,

                // identity / flavor
                UUID kingUuid,
                String name,
                BlockPos origin,
                int skinId,
                KingdomSize size,
                KingdomPersonality personality,

                // army
                int maxSoldiers,
                int aliveSoldiers,

                // economy
                double gold, double meat, double grain, double fish,
                double wood, double metal, double gems,
                double potions, double armor, double horses, double weapons,

                // stability
                int happiness,
                int security,

                // border
                boolean hasBorder,
                int borderMinX, int borderMaxX, int borderMinZ, int borderMaxZ
        ) {}

        public java.util.Map<UUID, AiKingdomSnap> exportSnapshot() {
        java.util.Map<UUID, AiKingdomSnap> out = new java.util.HashMap<>();

                for (var e : kingdoms.entrySet()) {
                        UUID id = e.getKey();
                        AiKingdom k = e.getValue();
                        if (k == null) continue;

                        out.put(id, new AiKingdomSnap(
                                k.id,

                                k.kingUuid,
                                k.name,
                                k.origin,
                                k.skinId,
                                k.size,
                                k.personality,

                                k.maxSoldiers,
                                k.aliveSoldiers,

                                k.gold, k.meat, k.grain, k.fish,
                                k.wood, k.metal, k.gems,
                                k.potions, k.armor, k.horses, k.weapons,

                                k.happiness,
                                k.security,

                                k.hasBorder,
                                k.borderMinX, k.borderMaxX, k.borderMinZ, k.borderMaxZ
                        ));
                }

        return out;
        }

        public void importSnapshot(java.util.Map<UUID, AiKingdomSnap> snap) {
        if (snap == null) return;

                for (var e : snap.entrySet()) {
                        UUID id = e.getKey();
                        AiKingdomSnap s = e.getValue();
                        if (id == null || s == null) continue;

                        AiKingdom k = kingdoms.get(id);
                        if (k == null) continue; // if A/B uses same world, this should exist

                        // identity/flavor
                        k.name = s.name();
                        k.skinId = s.skinId();
                        k.size = s.size();
                        k.personality = s.personality();

                        // army
                        k.maxSoldiers = s.maxSoldiers();
                        k.aliveSoldiers = s.aliveSoldiers();

                        // economy
                        k.gold = s.gold();
                        k.meat = s.meat();
                        k.grain = s.grain();
                        k.fish = s.fish();
                        k.wood = s.wood();
                        k.metal = s.metal();
                        k.gems = s.gems();
                        k.potions = s.potions();
                        k.armor = s.armor();
                        k.horses = s.horses();
                        k.weapons = s.weapons();

                        // stability
                        k.happiness = s.happiness();
                        k.security = s.security();

                        // border
                        k.hasBorder = s.hasBorder();
                        k.borderMinX = s.borderMinX();
                        k.borderMaxX = s.borderMaxX();
                        k.borderMinZ = s.borderMinZ();
                        k.borderMaxZ = s.borderMaxZ();
                }

                setDirty();
        }




    public record EconomyData(
            double gold,
            double meat, double grain, double fish,
            double wood, double metal, double gems,
            double potions, double armor, double horses, double weapons
    ) {
        public static final EconomyData ZERO =
                new EconomyData(0, 0,0,0, 0,0,0, 0,0,0,0);

        public static final Codec<EconomyData> CODEC =
                RecordCodecBuilder.create(inst -> inst.group(
                        Codec.DOUBLE.optionalFieldOf("gold", 0.0).forGetter(EconomyData::gold),

                        Codec.DOUBLE.optionalFieldOf("meat", 0.0).forGetter(EconomyData::meat),
                        Codec.DOUBLE.optionalFieldOf("grain", 0.0).forGetter(EconomyData::grain),
                        Codec.DOUBLE.optionalFieldOf("fish", 0.0).forGetter(EconomyData::fish),

                        Codec.DOUBLE.optionalFieldOf("wood", 0.0).forGetter(EconomyData::wood),
                        Codec.DOUBLE.optionalFieldOf("metal", 0.0).forGetter(EconomyData::metal),
                        Codec.DOUBLE.optionalFieldOf("gems", 0.0).forGetter(EconomyData::gems),

                        Codec.DOUBLE.optionalFieldOf("potions", 0.0).forGetter(EconomyData::potions),
                        Codec.DOUBLE.optionalFieldOf("armor", 0.0).forGetter(EconomyData::armor),
                        Codec.DOUBLE.optionalFieldOf("horses", 0.0).forGetter(EconomyData::horses),
                        Codec.DOUBLE.optionalFieldOf("weapons", 0.0).forGetter(EconomyData::weapons)
                ).apply(inst, EconomyData::new));

        public static EconomyData from(AiKingdom k) {
            return new EconomyData(
                    k.gold,
                    k.meat, k.grain, k.fish,
                    k.wood, k.metal, k.gems,
                    k.potions, k.armor, k.horses, k.weapons
            );
        }

        public void apply(AiKingdom k) {
            k.gold = gold;

            k.meat = meat;
            k.grain = grain;
            k.fish = fish;

            k.wood = wood;
            k.metal = metal;
            k.gems = gems;

            k.potions = potions;
            k.armor = armor;
            k.horses = horses;
            k.weapons = weapons;
        }

    }

    public record BorderData(boolean hasBorder, int minX, int maxX, int minZ, int maxZ) {
        public static final BorderData NONE = new BorderData(false, 0,0,0,0);

        public static final Codec<BorderData> CODEC =
                RecordCodecBuilder.create(inst -> inst.group(
                        Codec.BOOL.optionalFieldOf("set", false).forGetter(BorderData::hasBorder),
                        Codec.INT.optionalFieldOf("minX", 0).forGetter(BorderData::minX),
                        Codec.INT.optionalFieldOf("maxX", 0).forGetter(BorderData::maxX),
                        Codec.INT.optionalFieldOf("minZ", 0).forGetter(BorderData::minZ),
                        Codec.INT.optionalFieldOf("maxZ", 0).forGetter(BorderData::maxZ)
                ).apply(inst, BorderData::new));
    }

    public static class AiKingdom {

        public KingdomSize size = KingdomSize.MEDIUM;
        public final UUID id;       // we use king UUID
        public final UUID kingUuid;
        public String name;
        public final BlockPos origin;
        public KingdomPersonality personality = KingdomPersonality.DEFAULT;
        public int skinId = 0;

        // army pool
        public int maxSoldiers;   // 50..300 (by size)
        public int aliveSoldiers; // 0..maxSoldiers (regens when not at war)


        // economy (NOW includes food buckets)
        public double gold, meat, grain, fish,
                wood, metal, gems,
                potions, armor, horses, weapons;

        public int happiness; // 0..100
        public int security;  // 0..100

        public boolean hasBorder;
        public int borderMinX, borderMaxX, borderMinZ, borderMaxZ;

        public AiKingdom(UUID id, UUID kingUuid, String name, BlockPos origin) {
            this.id = id;
            this.kingUuid = kingUuid;
            this.name = name;
            this.origin = origin;
        }

        public static final Codec<AiKingdom> CODEC =
                RecordCodecBuilder.create(inst -> inst.group(
                        UUID_CODEC.fieldOf("id").forGetter(k -> k.id),
                        UUID_CODEC.fieldOf("kingUuid").forGetter(k -> k.kingUuid),
                        Codec.STRING.fieldOf("name").forGetter(k -> k.name),
                        BLOCKPOS_CODEC.fieldOf("origin").forGetter(k -> k.origin),
                        KingdomPersonality.CODEC.optionalFieldOf("personality", KingdomPersonality.DEFAULT)
                            .forGetter(k -> k.personality),
                        Codec.INT.optionalFieldOf("skinId", 0).forGetter(k -> k.skinId),


                        
                        EconomyData.CODEC.optionalFieldOf("eco", EconomyData.ZERO)
                                .forGetter(k -> EconomyData.from(k)),

                        Codec.INT.optionalFieldOf("happiness", 50).forGetter(k -> k.happiness),
                        Codec.INT.optionalFieldOf("security", 50).forGetter(k -> k.security),

                        Codec.STRING.optionalFieldOf("size", "MEDIUM")
                            .xmap(KingdomSize::valueOf, Enum::name)
                            .forGetter(k -> k.size),

                        Codec.INT.optionalFieldOf("maxSoldiers", -1).forGetter(k -> k.maxSoldiers),
                        Codec.INT.optionalFieldOf("aliveSoldiers", -1).forGetter(k -> k.aliveSoldiers),


                        BorderData.CODEC.optionalFieldOf("border", BorderData.NONE).forGetter(
                                k -> new BorderData(k.hasBorder, k.borderMinX, k.borderMaxX, k.borderMinZ, k.borderMaxZ)
                        )
                ).apply(inst, (id, kingUuid, name, origin, personality,
                        skinId,
                        eco, happiness, security, size, maxSoldiers, aliveSoldiers, border) -> {

                        AiKingdom k = new AiKingdom(id, kingUuid, name, origin);
                        k.personality = personality;
                        k.size = size;

                       k.skinId = Mth.clamp(skinId, 0, kingSkinPoolState.MAX_SKIN_ID);


                        int ms = (maxSoldiers <= 0) ? defaultMaxSoldiersForSize(size) : Mth.clamp(maxSoldiers, 50, 300);
                        int as = (aliveSoldiers < 0) ? ms : Mth.clamp(aliveSoldiers, 0, ms);

                        k.maxSoldiers = ms;
                        k.aliveSoldiers = as;

                        eco.apply(k);
                        k.happiness = Mth.clamp(happiness, 0, 100);
                        k.security = Mth.clamp(security, 0, 100);

                        k.hasBorder = border.hasBorder();
                        k.borderMinX = border.minX();
                        k.borderMaxX = border.maxX();
                        k.borderMinZ = border.minZ();
                        k.borderMaxZ = border.maxZ();

                        return k;
                }));

        }

    // =========================
    // SavedData plumbing
    // =========================

    public static final Codec<aiKingdomState> CODEC =
            RecordCodecBuilder.create(inst -> inst.group(
                    Codec.unboundedMap(UUID_CODEC, AiKingdom.CODEC)
                            .optionalFieldOf("kingdoms", Map.of())
                            .forGetter(s -> s.kingdoms)
            ).apply(inst, (map) -> {
                aiKingdomState s = new aiKingdomState();
                s.kingdoms.clear();
                s.kingdoms.putAll(map);
                return s;
            }));

    private static final SavedDataType<aiKingdomState> TYPE =
            new SavedDataType<>(
                    "kingdoms_ai_state",
                    aiKingdomState::new,
                    CODEC,
                    null
            );

    public static aiKingdomState get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return new aiKingdomState();
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }


    // =========================
    // Economy ticking
    // =========================

    public void tickEconomies(ServerLevel level) {
        if (kingdoms.isEmpty()) return;
        RandomSource r = level.random;
        var ws = name.kingdoms.war.WarState.get(level.getServer());

        for (AiKingdom k : kingdoms.values()) {

            boolean inWar = ws.isAtWarWithAny(k.id);
            tickSoldiers(k, level.random, inWar);


                // normalized (FIXED)
                double happy = k.happiness / 100.0;
                double sec   = k.security  / 100.0;

                var p = k.personality;
                double greed = (p == null ? 0.50 : p.greed());
                double gen   = (p == null ? 0.50 : p.generosity());
                double prag  = (p == null ? 0.60 : p.pragmatism());
                double honor = (p == null ? 0.50 : p.honor());

                // size proxy (later replace with population/settlements)
                int size = Math.max(1, k.maxSoldiers);
                double sizeScale = Math.sqrt(size); // avoids insane scaling with big kingdoms

                // "hoarding appetite" (greedy kingdoms aim for bigger stockpiles)
                double hoard = 0.85 + 0.75 * greed - 0.20 * (gen - 0.5);    // ~0.75..1.55
                double invest = 0.95 + 0.35 * (prag - 0.60) + 0.15 * sec;   // small boost from prag/security
                invest = Mth.clamp(invest, 0.80, 1.30);

                // ---- TARGETS (tune these numbers later) ----
                // security/happiness affect productive capacity a bit
                double cap = (0.75 + 0.35 * happy + 0.25 * sec); // ~0.75..1.35

                double tGold   = (220 + 18 * sizeScale) * hoard * invest * cap;
                double tFood   = (320 + 24 * sizeScale) * (0.90 + 0.25 * prag) * cap;
                double tWood   = (240 + 16 * sizeScale) * (0.90 + 0.20 * greed) * cap;
                double tMetal  = (120 + 10 * sizeScale) * (0.95 + 0.30 * prag) * cap;

                double tWeaps  = ( 85 +  7 * sizeScale) * (0.85 + 0.55 * honor) * (0.80 + 0.40 * sec);
                double tArmor  = ( 75 +  6 * sizeScale) * (0.85 + 0.55 * honor) * (0.80 + 0.40 * sec);

                double tGems   = ( 35 +  3 * sizeScale) * (0.70 + 0.90 * greed) * cap;
                double tHorses = ( 25 +  2 * sizeScale) * (0.80 + 0.40 * honor) * cap;
                double tPot    = ( 18 +  2 * sizeScale) * (0.80 + 0.40 * prag) * cap;

                // ---- MEAN REVERSION ----
                // 1%–3% per economy tick; start at 2%
                double kappa = 0.02;

                // pull toward targets (this allows growth OR shrink)
                k.gold   += kappa * (tGold - k.gold);

                k.grain  += kappa * (tFood - k.grain);
                k.meat   += kappa * ((tFood * 0.55) - k.meat);
                k.fish   += kappa * ((tFood * 0.45) - k.fish);

                k.wood   += kappa * (tWood - k.wood);
                k.metal  += kappa * (tMetal - k.metal);

                k.weapons += kappa * (tWeaps - k.weapons);
                k.armor   += kappa * (tArmor - k.armor);

                k.gems    += kappa * (tGems - k.gems);
                k.horses  += kappa * (tHorses - k.horses);
                k.potions += kappa * (tPot - k.potions);

                // ---- NOISE (keeps it alive) ----
                // noise scales gently with size; security smooths volatility a bit
                double n = (1.0 + 0.12 * sizeScale) * (1.15 - 0.30 * sec);

                k.gold  += (r.nextDouble() - 0.5) * 6.0 * n;
                k.grain += (r.nextDouble() - 0.5) * 7.0 * n;
                k.meat  += (r.nextDouble() - 0.5) * 5.0 * n;
                k.fish  += (r.nextDouble() - 0.5) * 5.0 * n;

                k.wood  += (r.nextDouble() - 0.5) * 8.0 * n;
                k.metal += (r.nextDouble() - 0.5) * 6.0 * n;

                k.weapons += (r.nextDouble() - 0.5) * 2.0 * n;
                k.armor   += (r.nextDouble() - 0.5) * 1.5 * n;

                k.horses  += (r.nextDouble() - 0.5) * 2.0 * n;
                k.potions += (r.nextDouble() - 0.5) * 2.0 * n;
                k.gems    += (r.nextDouble() - 0.5) * 1.0 * n;

                // -------------------------
                // SINKS (wins/losses become real)
                // -------------------------

                // Food spoilage (prevents hoarding forever)
                k.grain *= 0.9992;
                k.meat  *= 0.9988;
                k.fish  *= 0.9988;

                // Maintenance / corruption (rich bleed; greed makes it worse)
                double wealth =
                        k.gold
                        + 0.10 * (k.meat + k.grain + k.fish)
                        + 0.15 * k.wood
                        + 0.55 * k.metal
                        + 1.20 * k.weapons
                        + 1.40 * k.armor
                        + 2.50 * k.gems
                        + 2.00 * k.potions
                        + 1.60 * k.horses;

                // tune: start small; greedy kingdoms pay more to maintain big stockpiles
                double decayRate = 0.0008 + 0.0010 * greed; // ~0.08%..0.18% per econ tick
                k.gold -= wealth * decayRate;

                // clamp >= 0 (do this AFTER sinks)
                k.gold = Math.max(0, k.gold);

                k.meat = Math.max(0, k.meat);
                k.grain = Math.max(0, k.grain);
                k.fish = Math.max(0, k.fish);

                k.wood = Math.max(0, k.wood);
                k.metal = Math.max(0, k.metal);
                k.gems = Math.max(0, k.gems);
                k.potions = Math.max(0, k.potions);
                k.armor = Math.max(0, k.armor);
                k.horses = Math.max(0, k.horses);
                k.weapons = Math.max(0, k.weapons);

                // slight drift (keep yours)
                k.happiness = Mth.clamp(k.happiness + rangeInt(r, -1, 1), 0, 100);
                k.security  = Mth.clamp(k.security + rangeInt(r, -1, 1), 0, 100);

        }

        setDirty();
    }

    private static double range(RandomSource r, int min, int max) {
        return min + r.nextInt(max - min + 1);
    }

    private static int rangeInt(RandomSource r, int min, int max) {
        return min + r.nextInt(max - min + 1);
    }
}
