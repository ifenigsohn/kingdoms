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

        // economy (add food buckets!)
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
            double happy = k.happiness / 100.0;
            double sec = k.security / .3;

            boolean inWar = ws.isAtWarWithAny(k.id);
            tickSoldiers(k, level.random, inWar);


            // income-ish
            k.gold += 2 + 8 * happy + 4 * sec + range(r, -3, 6);

            // food drift (simple model; later you can base it on jobs/biomes)
            k.grain += 2 + 6 * happy + range(r, -3, 8);
            k.meat  += 1 + 4 * sec   + range(r, -3, 6);
            k.fish  += range(r, -2, 5);

            // mats drift
            k.wood  += range(r, -5, 12);
            k.metal += range(r, -3, 8);

            // military
            k.weapons += (sec > 0.60 ? range(r, 0, 2) : range(r, -2, 1));
            k.armor   += (sec > 0.70 ? range(r, 0, 1) : range(r, -1, 1));

            // misc
            k.horses  += range(r, -1, 2);
            k.potions += range(r, -1, 2);
            k.gems    += range(r, -1, 1);

            // clamp >= 0
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

            // slight drift
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
