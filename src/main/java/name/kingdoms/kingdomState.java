package name.kingdoms;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import name.kingdoms.aiKingdomState.BorderData;
import name.kingdoms.kingdomState.Kingdom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.*;

public class kingdomState extends SavedData {
    
    
    public Collection<Kingdom> allKingdoms() {
        return kingdoms.values();
    }

    

    public double getResource(UUID kingdomId, String resource) {
        Kingdom k = getKingdom(kingdomId); // you already have getKingdom(UUID)
        if (k == null) return 0.0;

        return switch (resource) {
            case "gold" -> k.gold;
            case "meat" -> k.meat;
            case "grain" -> k.grain;
            case "fish" -> k.fish;
            case "wood" -> k.wood;
            case "metal" -> k.metal;
            case "armor" -> k.armor;
            case "weapons" -> k.weapons;
            case "gems" -> k.gems;
            case "horses" -> k.horses;
            case "potions" -> k.potions;
            default -> 0.0;
        };
    }

    public void setResource(UUID kingdomId, String resource, double value) {
        Kingdom k = getKingdom(kingdomId);
        if (k == null) return;

        double v = Math.max(0.0, value);

        switch (resource) {
            case "gold" -> k.gold = v;
            case "meat" -> k.meat = v;
            case "grain" -> k.grain = v;
            case "fish" -> k.fish = v;
            case "wood" -> k.wood = v;
            case "metal" -> k.metal = v;
            case "armor" -> k.armor = v;
            case "weapons" -> k.weapons = v;
            case "gems" -> k.gems = v;
            case "horses" -> k.horses = v;
            case "potions" -> k.potions = v;
        }
    }

    public void claimRect(Level level, UUID kingdomId, int minX, int maxX, int minZ, int maxZ) {
        int aMinX = Math.min(minX, maxX);
        int aMaxX = Math.max(minX, maxX);
        int aMinZ = Math.min(minZ, maxZ);
        int aMaxZ = Math.max(minZ, maxZ);

        int minGX = Math.floorDiv(aMinX, CLAIM_CELL_SIZE);
        int maxGX = Math.floorDiv(aMaxX, CLAIM_CELL_SIZE);
        int minGZ = Math.floorDiv(aMinZ, CLAIM_CELL_SIZE);
        int maxGZ = Math.floorDiv(aMaxZ, CLAIM_CELL_SIZE);

        for (int gx = minGX; gx <= maxGX; gx++) {
            for (int gz = minGZ; gz <= maxGZ; gz++) {
                claims.put(new ClaimKey(level.dimension(), gx, gz), kingdomId);
            }
        }

        setDirty();
    }

    public Kingdom ensureAiKingdom(UUID id, UUID owner, String name, BlockPos origin) {
        Kingdom k = kingdoms.get(id);
        if (k != null) return k;

        Kingdom created = new Kingdom(id, owner, name, origin);

        // AI kingdoms should NOT have a terminal (terminal is player kingdom block logic)
        created.hasTerminal = false;
        created.terminalDim = Level.OVERWORLD;
        created.terminalPos = origin.immutable();

        kingdoms.put(id, created);
        setDirty();
        return created;
    }


    public boolean tryConsumeResource(UUID kingdomId, String resource, double amount) {
        if (amount <= 0.0) return true;

        double have = getResource(kingdomId, resource);
        if (have + 1e-9 < amount) return false;

        setResource(kingdomId, resource, have - amount);
        setDirty();
        return true;
    }

    public static boolean hasAnyGarrison(kingdomState.Kingdom k) {
        if (k == null) return false;
        // “Exists” = placed; “functional” = active. Accept either.
        return k.placed.getOrDefault("garrison", 0) > 0
            || k.active.getOrDefault("garrison", 0) > 0;
    }

    public static boolean playerWarEligible(MinecraftServer server, UUID playerId) {
        var ks = kingdomState.get(server);
        var pk = ks.getPlayerKingdom(playerId);
        return hasAnyGarrison(pk);
    }
        


    public void validateTerminals(MinecraftServer server) {
        boolean changed = false;

        for (Kingdom k : kingdoms.values()) {
            if (!k.hasTerminal) continue;

            ServerLevel lvl = server.getLevel(k.terminalDim);
            if (lvl == null) {
                k.hasTerminal = false;
                clearKingdomBorder(server.getLevel(Level.OVERWORLD), k); // safe fallback
                changed = true;
                continue;
            }

            BlockPos p = k.terminalPos;
            if (!lvl.getBlockState(p).is(modBlock.kingdom_block)) {
                k.hasTerminal = false;
                clearKingdomBorder(lvl, k);
                changed = true;
            }
        }

        if (changed) setDirty();
    }

    public void clearKingdomBorder(ServerLevel level, Kingdom k) {
        if (k == null) return;

        UUID kid = k.id;

        // clear ALL claimed cells for this kingdom
        claims.entrySet().removeIf(e -> Objects.equals(e.getValue(), kid));

        // border off
        k.hasBorder = false;
        k.borderMinX = k.borderMaxX = k.borderMinZ = k.borderMaxZ = 0;

        // NOTE: do NOT re-claim origin; kingdom is "dormant" until a new terminal is placed
        setDirty();
    }

    public kingdomState() {}
    public void markDirty() { setDirty(); }

    /* -----------------------------
       SETTINGS
     ----------------------------- */

    /** Each claim cell is 10x10 blocks */
    public static final int CLAIM_CELL_SIZE = 10;

    /* -----------------------------
       STORAGE
     ----------------------------- */

    /** kingdom id → kingdom */
    private final Map<UUID, Kingdom> kingdoms = new HashMap<>();

    /** player → kingdom id */
    private final Map<UUID, UUID> playerKingdom = new HashMap<>();

    /** claim → kingdom id */
    private final Map<ClaimKey, UUID> claims = new HashMap<>();

    public Collection<Kingdom> getAllKingdoms() {
        return kingdoms.values();
    }

    /* -----------------------------
       kingdom border tracking
     ----------------------------- */

    public boolean trySetKingdomBorder(ServerLevel level, Kingdom k, int minX, int maxX, int minZ, int maxZ) {
        if (k == null) return false;

        // normalize
        int aMinX = Math.min(minX, maxX);
        int aMaxX = Math.max(minX, maxX);
        int aMinZ = Math.min(minZ, maxZ);
        int aMaxZ = Math.max(minZ, maxZ);

        int minGX = Math.floorDiv(aMinX, CLAIM_CELL_SIZE);
        int maxGX = Math.floorDiv(aMaxX, CLAIM_CELL_SIZE);
        int minGZ = Math.floorDiv(aMinZ, CLAIM_CELL_SIZE);
        int maxGZ = Math.floorDiv(aMaxZ, CLAIM_CELL_SIZE);

        UUID kid = k.id;

        // 1) validate: no overlaps with other kingdoms
        for (int gx = minGX; gx <= maxGX; gx++) {
            for (int gz = minGZ; gz <= maxGZ; gz++) {
                UUID existing = claims.get(new ClaimKey(level.dimension(), gx, gz));
                if (existing != null && !existing.equals(kid)) {
                    return false; // overlaps someone else
                }
            }
        }

        // 2) clear old claims for THIS kingdom
        claims.entrySet().removeIf(e -> Objects.equals(e.getValue(), kid));

        // 3) store border + claim all cells
        k.hasBorder = true;
        k.borderMinX = aMinX;
        k.borderMaxX = aMaxX;
        k.borderMinZ = aMinZ;
        k.borderMaxZ = aMaxZ;

        for (int gx = minGX; gx <= maxGX; gx++) {
            for (int gz = minGZ; gz <= maxGZ; gz++) {
                claims.put(new ClaimKey(level.dimension(), gx, gz), kid);
            }
        }

        setDirty();
        return true;
    }

    /* -----------------------------
       TERMINAL (ACTIVE KINGDOM BLOCK)
     ----------------------------- */

    public boolean isTerminal(ServerLevel level, Kingdom k, BlockPos pos) {
        return k != null
                && k.hasTerminal
                && k.terminalDim.equals(level.dimension())
                && k.terminalPos.equals(pos);
    }

    public void bindTerminal(ServerLevel level, Kingdom k, BlockPos pos) {
        k.hasTerminal = true;
        k.terminalDim = level.dimension();
        k.terminalPos = pos.immutable();
        setDirty();
    }

    public void claimCellForKingdom(Level level, Kingdom k, BlockPos pos) {
        if (k == null) return;
        claims.put(claimFromPos(level, pos), k.id);
        setDirty();
    }

    public void clearTerminalIfMatches(ServerLevel level, Kingdom k, BlockPos pos) {
        if (isTerminal(level, k, pos)) {
            k.hasTerminal = false;
            setDirty();
        }
    }

    /* -----------------------------
       BORDER / CLAIMS
     ----------------------------- */

    public void setKingdomBorder(ServerLevel level, Kingdom k,
                                 int minX, int maxX, int minZ, int maxZ) {

        k.hasBorder = true;
        k.borderMinX = minX;
        k.borderMaxX = maxX;
        k.borderMinZ = minZ;
        k.borderMaxZ = maxZ;

        UUID kid = k.id;
        claims.entrySet().removeIf(e -> e.getValue().equals(kid));

        int minGX = Math.floorDiv(minX, CLAIM_CELL_SIZE);
        int maxGX = Math.floorDiv(maxX, CLAIM_CELL_SIZE);
        int minGZ = Math.floorDiv(minZ, CLAIM_CELL_SIZE);
        int maxGZ = Math.floorDiv(maxZ, CLAIM_CELL_SIZE);

        for (int gx = minGX; gx <= maxGX; gx++) {
            for (int gz = minGZ; gz <= maxGZ; gz++) {
                claims.put(new ClaimKey(level.dimension(), gx, gz), kid);
            }
        }

        setDirty();
    }

    /* -----------------------------
       CODECS (PRIMITIVES)
     ----------------------------- */
     private static final UUID NIL_UUID = new UUID(0L, 0L);

    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    private static final Codec<Map<String, Integer>> STRING_INT_MAP_CODEC =
            Codec.unboundedMap(Codec.STRING, Codec.INT);

    private static final Codec<BlockPos> BLOCKPOS_CODEC =
            RecordCodecBuilder.create(inst -> inst.group(
                    Codec.INT.fieldOf("x").forGetter(BlockPos::getX),
                    Codec.INT.fieldOf("y").forGetter(BlockPos::getY),
                    Codec.INT.fieldOf("z").forGetter(BlockPos::getZ)
            ).apply(inst, BlockPos::new));

    private static final Codec<ResourceKey<Level>> DIM_KEY_CODEC =
            ResourceLocation.CODEC.xmap(
                    rl -> ResourceKey.create(Registries.DIMENSION, rl),
                    ResourceKey::location
            );

    private static final Codec<Map<UUID, UUID>> UUID_MAP_CODEC =
            Codec.unboundedMap(UUID_CODEC, UUID_CODEC);

    /* -----------------------------
       CLAIM TYPES
     ----------------------------- */

    public record ClaimKey(ResourceKey<Level> dim, int gx, int gz) {}

    public static ClaimKey claimFromPos(Level level, BlockPos pos) {
        return new ClaimKey(
                level.dimension(),
                Math.floorDiv(pos.getX(), CLAIM_CELL_SIZE),
                Math.floorDiv(pos.getZ(), CLAIM_CELL_SIZE)
        );
    }

    private record ClaimEntry(ResourceKey<Level> dim, int gx, int gz, UUID kid) {}

    private static final Codec<ClaimEntry> CLAIM_ENTRY_CODEC =
            RecordCodecBuilder.create(inst -> inst.group(
                    DIM_KEY_CODEC.fieldOf("dim").forGetter(ClaimEntry::dim),
                    Codec.INT.fieldOf("gx").forGetter(ClaimEntry::gx),
                    Codec.INT.fieldOf("gz").forGetter(ClaimEntry::gz),
                    UUID_CODEC.fieldOf("kid").forGetter(ClaimEntry::kid)
            ).apply(inst, ClaimEntry::new));

    /* -----------------------------
       SUB-DATA (ECO + BORDER)
     ----------------------------- */

    private record EconomyData(
            double gold, double meat, double grain, double fish,
            double wood, double metal, double armor, double weapons,
            double gems, double horses, double potions
    ) {
        static final Codec<EconomyData> CODEC =
                RecordCodecBuilder.create(inst -> inst.group(
                        Codec.DOUBLE.optionalFieldOf("gold", 0.0).forGetter(EconomyData::gold),
                        Codec.DOUBLE.optionalFieldOf("meat", 0.0).forGetter(EconomyData::meat),
                        Codec.DOUBLE.optionalFieldOf("grain", 0.0).forGetter(EconomyData::grain),
                        Codec.DOUBLE.optionalFieldOf("fish", 0.0).forGetter(EconomyData::fish),
                        Codec.DOUBLE.optionalFieldOf("wood", 0.0).forGetter(EconomyData::wood),
                        Codec.DOUBLE.optionalFieldOf("metal", 0.0).forGetter(EconomyData::metal),
                        Codec.DOUBLE.optionalFieldOf("armor", 0.0).forGetter(EconomyData::armor),
                        Codec.DOUBLE.optionalFieldOf("weapons", 0.0).forGetter(EconomyData::weapons),
                        Codec.DOUBLE.optionalFieldOf("gems", 0.0).forGetter(EconomyData::gems),
                        Codec.DOUBLE.optionalFieldOf("horses", 0.0).forGetter(EconomyData::horses),
                        Codec.DOUBLE.optionalFieldOf("potions", 0.0).forGetter(EconomyData::potions)
                ).apply(inst, EconomyData::new));

        static EconomyData from(Kingdom k) {
            return new EconomyData(
                    k.gold, k.meat, k.grain, k.fish,
                    k.wood, k.metal, k.armor, k.weapons,
                    k.gems, k.horses, k.potions
            );
        }

        void apply(Kingdom k) {
            k.gold = gold; k.meat = meat; k.grain = grain; k.fish = fish;
            k.wood = wood; k.metal = metal; k.armor = armor; k.weapons = weapons;
            k.gems = gems; k.horses = horses; k.potions = potions;
        }
    }

    private record BorderData(boolean set, int minX, int maxX, int minZ, int maxZ) {
        static final Codec<BorderData> CODEC =
                RecordCodecBuilder.create(inst -> inst.group(
                        Codec.BOOL.optionalFieldOf("set", false).forGetter(BorderData::set),
                        Codec.INT.optionalFieldOf("minX", 0).forGetter(BorderData::minX),
                        Codec.INT.optionalFieldOf("maxX", 0).forGetter(BorderData::maxX),
                        Codec.INT.optionalFieldOf("minZ", 0).forGetter(BorderData::minZ),
                        Codec.INT.optionalFieldOf("maxZ", 0).forGetter(BorderData::maxZ)
                ).apply(inst, BorderData::new));
    }

    /* -----------------------------
       KINGDOM
     ----------------------------- */

    public static class Kingdom {
        public final UUID id;
        public final UUID owner;

        public UUID retinueScribe = null;
        public UUID retinueTreasurer = null;
        public UUID retinueGeneral = null;
        public ItemStack heraldry = ItemStack.EMPTY;

        // IMPORTANT: networking expects k.name
        public String name;

        /** Where the kingdom was created (kingdom block position). */
        public final BlockPos origin;

        /** Active placed job blocks per jobId (requirements met). */
        public final Map<String, Integer> active = new HashMap<>();

        /** Placed job blocks per jobId (population). */
        public final Map<String, Integer> placed = new HashMap<>();

        public double gold, meat, grain, fish,
                wood, metal, armor, weapons,
                gems, horses, potions;

        // border (per-kingdom)
        public boolean hasBorder;
        public int borderMinX, borderMaxX, borderMinZ, borderMaxZ;

        // terminal (single active kingdom block)
        public boolean hasTerminal;
        public ResourceKey<Level> terminalDim = Level.OVERWORLD;
        public BlockPos terminalPos = BlockPos.ZERO;

        public Kingdom(UUID id, UUID owner, String name, BlockPos origin) {
            this.id = id;
            this.owner = owner;
            this.name = name;
            this.origin = origin;
        }
    

        public int getActive(String jobId) {
            return active.getOrDefault(jobId, 0);
        }

        public void bumpActive(String jobId, int delta) {
            int next = Math.max(0, getActive(jobId) + delta);
            if (next == 0) active.remove(jobId);
            else active.put(jobId, next);
        }

        public int populationJobs() {
            return kingdomState.sumCounts(this.placed);
        }

       public double happiness() {
            double h = 7.0;

            int pop = populationJobs();
            int over = Math.max(0, pop - 5);
            h -= 0.25 * over;

            // bonuses use ACTIVE job blocks (your jobBlockEntity now gates active by reqs+inputs)
            h += 3.0 * getActive("chapel");
            h += 1.0 * getActive("tavern");

            if (isSecurityLow()) h -= 2.0;   

            if (h < 0) h = 0;
            if (h > 10) h = 10;
            return h;
        }

        public static final double REQUIRED_SECURITY = 0.30;
        public static final double BASE_SECURITY = 0.40;

       public double securityValue() {
            int pop = populationJobs();
            if (pop <= 5) return BASE_SECURITY;

            int secActive =
                    getActive("guard") +
                    getActive("training") +
                    getActive("garrison"); // NEW

            if (pop <= 0) return BASE_SECURITY;
            return (double) secActive / (double) pop;
        }


        public String securityBand() {
            double s = securityValue();
            if (s >= 0.40) return "High";     // you can tweak thresholds later
            if (s >= REQUIRED_SECURITY) return "Medium";
            return "Low";
        }

        public boolean isSecurityLow() {
            return populationJobs() > 5 && securityValue() < REQUIRED_SECURITY;
        }


        public double productionMultiplier() {
            double h = happiness();
            if (h >= 7.0) return 1.0;
            if (h >= 4.0) return 0.75;
            return 0.5;
        }

        public String happinessBand() {
            double h = happiness();
            if (h >= 7.0) return "High";
            if (h >= 4.0) return "Medium";
            return "Low";
        }

        public boolean isInsideBorder(BlockPos pos) {
            if (!hasBorder) return true;
            int x = pos.getX();
            int z = pos.getZ();
            return x >= borderMinX && x <= borderMaxX
                    && z >= borderMinZ && z <= borderMaxZ;
        }

        
        // Royal Guard toggle (persisted)
        public boolean royalGuardsEnabled = false;

        public static final Codec<Kingdom> CODEC =
                RecordCodecBuilder.create(inst -> inst.group(
                        UUID_CODEC.fieldOf("id").forGetter(k -> k.id),
                        UUID_CODEC.fieldOf("owner").forGetter(k -> k.owner),
                        Codec.STRING.fieldOf("name").forGetter(k -> k.name),
                        BLOCKPOS_CODEC.fieldOf("origin").forGetter(k -> k.origin),

                        STRING_INT_MAP_CODEC.optionalFieldOf("active", Map.of()).forGetter(k -> k.active),
                        STRING_INT_MAP_CODEC.optionalFieldOf("placed", Map.of()).forGetter(k -> k.placed),

                        ItemStack.OPTIONAL_CODEC.optionalFieldOf("heraldry", ItemStack.EMPTY)
                         .forGetter(k -> k.heraldry),


                        EconomyData.CODEC.optionalFieldOf("eco", new EconomyData(
                                0,0,0,0, 0,0,0,0, 0,0,0
                        )).forGetter(EconomyData::from),

                        BorderData.CODEC.optionalFieldOf("border", new BorderData(false,0,0,0,0))
                                .forGetter(k -> new BorderData(
                                        k.hasBorder,
                                        k.borderMinX,
                                        k.borderMaxX,
                                        k.borderMinZ,
                                        k.borderMaxZ
                                )),

                        Codec.BOOL.optionalFieldOf("hasTerminal", false).forGetter(k -> k.hasTerminal),
                        DIM_KEY_CODEC.optionalFieldOf("terminalDim", Level.OVERWORLD).forGetter(k -> k.terminalDim),
                        BLOCKPOS_CODEC.optionalFieldOf("terminalPos", BlockPos.ZERO).forGetter(k -> k.terminalPos),
                        Codec.BOOL.optionalFieldOf("royalGuardsEnabled", false)
                                .forGetter(k -> k.royalGuardsEnabled),
                        UUID_CODEC.optionalFieldOf("retinueScribe", NIL_UUID)
                                .forGetter(k -> k.retinueScribe == null ? NIL_UUID : k.retinueScribe),
                        UUID_CODEC.optionalFieldOf("retinueTreasurer", NIL_UUID)
                                .forGetter(k -> k.retinueTreasurer == null ? NIL_UUID : k.retinueTreasurer),
                        UUID_CODEC.optionalFieldOf("retinueGeneral", NIL_UUID)
                                .forGetter(k -> k.retinueGeneral == null ? NIL_UUID : k.retinueGeneral)

                ).apply(inst, (id, owner, name, origin,
                               activeMap, placedMap, heraldry,
                               eco, border,
                               hasTerminal, terminalDim, terminalPos, royalGuardsEnabled,
                               retinueScribe, retinueTreasurer, retinueGeneral) -> {

                    Kingdom k = new Kingdom(id, owner, name, origin);

                    k.active.clear();
                    k.active.putAll(activeMap);

                    k.placed.clear();
                    k.placed.putAll(placedMap);

                    eco.apply(k);

                    k.hasBorder = border.set();
                    k.borderMinX = border.minX();
                    k.borderMaxX = border.maxX();
                    k.borderMinZ = border.minZ();
                    k.borderMaxZ = border.maxZ();

                    k.hasTerminal = hasTerminal;
                    k.terminalDim = terminalDim;
                    k.terminalPos = terminalPos;

                    k.retinueScribe = NIL_UUID.equals(retinueScribe) ? null : retinueScribe;
                    k.retinueTreasurer = NIL_UUID.equals(retinueTreasurer) ? null : retinueTreasurer;
                    k.retinueGeneral = NIL_UUID.equals(retinueGeneral) ? null : retinueGeneral;
                    k.heraldry = (heraldry == null) ? ItemStack.EMPTY : heraldry;

                    return k;
                }));
    }

    /* -----------------------------
       CREATE / DELETE
     ----------------------------- */

    public Kingdom createKingdom(ServerLevel level, UUID player, String name, BlockPos origin) {
        return createKingdom((Level) level, player, name, origin);
    }

    public Kingdom createKingdom(Level level, UUID player, String name, BlockPos origin) {
        if (playerKingdom.containsKey(player)) {
            throw new IllegalStateException("You already own a kingdom.");
        }

        UUID id = UUID.randomUUID();
        Kingdom k = new Kingdom(id, player, name, origin);

        k.gold = 10; k.meat = 0; k.grain = 0; k.fish = 0;
        k.wood = 0; k.metal = 0; k.armor = 0; k.weapons = 0;
        k.gems = 0; k.horses = 0; k.potions = 0;

        // immediately bind the origin as their terminal
        k.hasTerminal = true;
        k.terminalDim = level.dimension();
        k.terminalPos = origin.immutable();

        kingdoms.put(id, k);
        playerKingdom.put(player, id);

        // initial claim: the cell containing the origin
        claims.put(claimFromPos(level, origin), id);

        setDirty();
        return k;
    }

    public boolean disbandAt(ServerLevel level, UUID player, BlockPos pos) {
        Kingdom k = getKingdomAt(level, pos);
        if (k == null) return false;

        if (!k.owner.equals(player)) {
            throw new IllegalStateException("You do not own this kingdom.");
        }

        UUID kid = k.id;

        kingdoms.remove(kid);
        playerKingdom.remove(player);
        claims.entrySet().removeIf(e -> Objects.equals(e.getValue(), kid));

        setDirty();
        return true;
    }

    /* -----------------------------
       LOOKUPS
     ----------------------------- */

    public Kingdom getKingdom(UUID id) { return kingdoms.get(id); }

    public Kingdom getPlayerKingdom(UUID player) {
        UUID id = playerKingdom.get(player);
        return id == null ? null : kingdoms.get(id);
    }

    public Kingdom getOrThrowPlayerKingdom(UUID player) {
        Kingdom k = getPlayerKingdom(player);
        if (k == null) throw new IllegalStateException("You don't have a kingdom yet.");
        return k;
    }

    public Kingdom getKingdomAt(Level level, BlockPos pos) {
        UUID id = claims.get(claimFromPos(level, pos));
        return id == null ? null : kingdoms.get(id);
    }

    /* -----------------------------
       ACTIVE / PLACED COUNT UTILITY
     ----------------------------- */

    public static void bumpActiveCount(kingdomState.Kingdom k, jobDefinition job, int delta) {
        if (k == null || job == null) return;
        k.bumpActive(job.getId(), delta);
    }

    public static int sumCounts(Map<String, Integer> map) {
        int s = 0;
        for (int v : map.values()) s += Math.max(0, v);
        return s;
    }

    public static void bumpPlacedCount(kingdomState.Kingdom k, jobDefinition job, int delta) {
        if (k == null || job == null) return;
        String id = job.getId();
        int next = k.placed.getOrDefault(id, 0) + delta;
        if (next <= 0) k.placed.remove(id);
        else k.placed.put(id, next);
    }

    /* -----------------------------
       FOOD (VARIETY BONUS)
     ----------------------------- */

    public static int foodTypesPresent(kingdomState.Kingdom k) {
        int t = 0;
        if (k.meat  > 0.0) t++;
        if (k.grain > 0.0) t++;
        if (k.fish  > 0.0) t++;
        return t;
    }

    // Treat tiny values as zero to avoid weird floating point edge cases
    private static final double FOOD_EPS = 1e-9;

    // Overload: count types present from raw values (keeps your existing foodTypesPresent(Kingdom) intact)
    private static int foodTypesPresent(double meat, double grain, double fish) {
        int t = 0;
        if (meat  > FOOD_EPS) t++;
        if (grain > FOOD_EPS) t++;
        if (fish  > FOOD_EPS) t++;
        return t;
    }

    private static double multForTypes(int types) {
        return switch (types) {
            case 3 -> 1.20;
            case 2 -> 1.10;
            default -> 1.00;
        };
    }

    // Minimum stock among the *present* types
    private static double minPositive(double meat, double grain, double fish) {
        double m = Double.POSITIVE_INFINITY;
        if (meat  > FOOD_EPS) m = Math.min(m, meat);
        if (grain > FOOD_EPS) m = Math.min(m, grain);
        if (fish  > FOOD_EPS) m = Math.min(m, fish);
        return (m == Double.POSITIVE_INFINITY) ? 0.0 : m;
    }


    public static double effectiveFood(kingdomState.Kingdom k) {
        double meat  = k.meat;
        double grain = k.grain;
        double fish  = k.fish;

        double eff = 0.0;

        // Phases: 3-types -> 2-types -> 1-type
        while (true) {
            int types = foodTypesPresent(meat, grain, fish);
            if (types == 0) break;

            double mult = multForTypes(types);

            // Consume equally from each present type until one hits zero
            double step = minPositive(meat, grain, fish);

            // Effective gained in this phase
            eff += step * types * mult;

            // Reduce each present pool by the same "step"
            if (meat  > FOOD_EPS) meat  -= step;
            if (grain > FOOD_EPS) grain -= step;
            if (fish  > FOOD_EPS) fish  -= step;

            // Clamp tiny negatives
            if (meat  < FOOD_EPS) meat  = 0.0;
            if (grain < FOOD_EPS) grain = 0.0;
            if (fish  < FOOD_EPS) fish  = 0.0;
        }

        return eff;
    }


    public static boolean consumeFood(kingdomState.Kingdom k, double effectiveAmount) {
        if (effectiveAmount <= FOOD_EPS) return true;

        // Must match the same model used by canWork()
        if (effectiveFood(k) + FOOD_EPS < effectiveAmount) return false;

        double remaining = effectiveAmount;

        while (remaining > FOOD_EPS) {
            int types = foodTypesPresent(k.meat, k.grain, k.fish);
            if (types == 0) return false;

            double mult = multForTypes(types);

            // If we take x base from EACH present type, effective delivered = x * types * mult
            double xNeeded = remaining / (types * mult);

            // Can't take more than the smallest present stock (equal draw constraint)
            double xMax = minPositive(k.meat, k.grain, k.fish);
            double x = Math.min(xNeeded, xMax);

            // Consume equally from each present pool
            if (k.meat  > FOOD_EPS) k.meat  -= x;
            if (k.grain > FOOD_EPS) k.grain -= x;
            if (k.fish  > FOOD_EPS) k.fish  -= x;

            // Clamp tiny negatives
            if (k.meat  < FOOD_EPS) k.meat  = 0.0;
            if (k.grain < FOOD_EPS) k.grain = 0.0;
            if (k.fish  < FOOD_EPS) k.fish  = 0.0;

            remaining -= x * types * mult;
        }

        return true;
    }


    /* -----------------------------
       PERSISTENCE (STATE CODEC)
     ----------------------------- */

    private static final Codec<kingdomState> CODEC =
            RecordCodecBuilder.create(inst -> inst.group(
                    Kingdom.CODEC.listOf().fieldOf("kingdoms")
                            .forGetter(s -> new ArrayList<>(s.kingdoms.values())),
                    UUID_MAP_CODEC.fieldOf("playerKingdom")
                            .forGetter(s -> s.playerKingdom),
                    CLAIM_ENTRY_CODEC.listOf().fieldOf("claims")
                            .forGetter(s -> {
                                List<ClaimEntry> out = new ArrayList<>();
                                for (var e : s.claims.entrySet()) {
                                    ClaimKey ck = e.getKey();
                                    out.add(new ClaimEntry(ck.dim(), ck.gx(), ck.gz(), e.getValue()));
                                }
                                return out;
                            })
            ).apply(inst, (kingdomList, playerMap, claimList) -> {
                kingdomState s = new kingdomState();

                for (Kingdom k : kingdomList) s.kingdoms.put(k.id, k);
                s.playerKingdom.putAll(playerMap);

                for (ClaimEntry ce : claimList) {
                    s.claims.put(new ClaimKey(ce.dim(), ce.gx(), ce.gz()), ce.kid());
                }

                return s;
            }));

    /* -----------------------------
       SAVED DATA ACCESS
     ----------------------------- */

    private static final SavedDataType<kingdomState> TYPE =
            new SavedDataType<>(
                    "kingdoms_state",
                    kingdomState::new,
                    CODEC,
                    null
            );

    public static kingdomState get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return new kingdomState();
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }



    public UUID getKingdomIdFor(UUID playerUuid) {
    return playerKingdom.get(playerUuid); // may return null if player has no kingdom
}

}
