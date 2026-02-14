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
import name.kingdoms.entity.SoldierSkins;
import java.util.*;

public class kingdomState extends SavedData {
    
    
    public Collection<Kingdom> allKingdoms() {
        return kingdoms.values();
    }

    private void removeAllClaimsFor(UUID kid) {
        if (kid == null) return;
        claims.entrySet().removeIf(e -> Objects.equals(e.getValue(), kid));
        claimsFast.entrySet().removeIf(e -> Objects.equals(e.getValue(), kid));
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
                ClaimKey ck = new ClaimKey(level.dimension(), gx, gz);
                claims.put(ck, kingdomId);
                claimsFast.put(packClaimKey(ck.dim(), ck.gx(), ck.gz()), kingdomId);

            }
        }

        setDirty();
    }

    public double productionMultiplierWithPressure(MinecraftServer server, Kingdom k) {
        long now = server.getTickCount();
        var mods = name.kingdoms.pressure.KingdomPressureState.get(server).getMods(k.id, now);

        double sEff = securityValueWithPressure(server, k);
        double hEff = happinessWithPressure(server, k);

        double pmFromH = 0.40 + (hEff / 10.0) * (1.20 - 0.40);
        double pmFromS = 0.85 + (1.15 - 0.85) * sEff;

        return Math.max(0.40, Math.min(1.20, pmFromH * pmFromS)) * mods.economyMult();
    }


    public Kingdom ensureAiKingdom(UUID id, UUID owner, String name, BlockPos origin) {
        Kingdom k = kingdoms.get(id);
        if (k != null) return k;

        Kingdom created = new Kingdom(id, owner, name, origin);
        

        // AI kingdoms should NOT have a terminal (terminal is player kingdom block logic)
        created.hasTerminal = false;
        created.terminalDim = Level.OVERWORLD;
        created.terminalPos = origin.immutable();
        created.hasTerminal = false;
        created.terminalDim = Level.OVERWORLD;
        created.terminalPos = origin.immutable();
        created.diplomacyRangeBlocks = 2000; 

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

    public double happinessWithPressure(MinecraftServer server, Kingdom k) {
        if (k == null) return 0.0;
        double base = k.happiness();
        long now = server.getTickCount();
        var mods = name.kingdoms.pressure.KingdomPressureState.get(server).getMods(k.id, now);
        double h = base + mods.happinessDelta();
        if (h < 0) h = 0;
        if (h > 10) h = 10;
        return h;
    }

    public double securityValueWithPressure(MinecraftServer server, Kingdom k) {
        if (k == null) return 0.0;
        double base = k.securityValue();
        long now = server.getTickCount();
        var mods = name.kingdoms.pressure.KingdomPressureState.get(server).getMods(k.id, now);
        double s = base + mods.securityDelta();
        if (s < 0) s = 0;
        if (s > 1) s = 1;
        return s;
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
        removeAllClaimsFor(kid);


        // border off
        k.hasBorder = false;
        k.borderMinX = k.borderMaxX = k.borderMinZ = k.borderMaxZ = 0;

        // NOTE: do NOT re-claim origin; kingdom is "dormant" until a new terminal is placed
        setDirty();
    }

        /* -----------------------------
        ENVOY ANCHORS HELPERS
        ----------------------------- */

        public void upsertEnvoyAnchor(UUID kid, ResourceKey<Level> dim, BlockPos pos, int radius) {
            if (kid == null || dim == null || pos == null) return;
            Kingdom k = getKingdom(kid);
            if (k == null) return;

            BlockPos ipos = pos.immutable();
            int rr = Math.max(0, radius);

            // Remove any existing anchor at this exact spot
            k.envoyAnchors.removeIf(a -> a != null && a.dim().equals(dim) && a.pos().equals(ipos));

            // Add
            k.envoyAnchors.add(new DiplomacyAnchor(dim, ipos, rr));
            setDirty();
        }

        public void removeEnvoyAnchor(UUID kid, ResourceKey<Level> dim, BlockPos pos) {
            if (kid == null || dim == null || pos == null) return;
            Kingdom k = getKingdom(kid);
            if (k == null) return;

            BlockPos ipos = pos.immutable();
            boolean changed = k.envoyAnchors.removeIf(a -> a != null && a.dim().equals(dim) && a.pos().equals(ipos));
            if (changed) setDirty();
        }

        /** Removes anchors whose block no longer exists. */
        public void validateEnvoyAnchors(MinecraftServer server) {
            boolean changed = false;

            for (Kingdom k : kingdoms.values()) {
                if (k == null || k.envoyAnchors.isEmpty()) continue;

                changed |= k.envoyAnchors.removeIf(a -> {
                    if (a == null) return true;

                    ServerLevel lvl = server.getLevel(a.dim());
                    if (lvl == null) return true;

                    // anchor is valid ONLY if block exists
                    return !lvl.getBlockState(a.pos()).is(modBlock.envoy_block);
                });
            }

            if (changed) setDirty();
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

    /** puppetKid -> masterKid */
    private final Map<UUID, UUID> puppetMaster = new HashMap<>();

    /** claim → kingdom id */
    private final Map<ClaimKey, UUID> claims = new HashMap<>();

    public Collection<Kingdom> getAllKingdoms() {
        return kingdoms.values();
    }

    private static long packClaimKey(ResourceKey<Level> dim, int gx, int gz) {
      
        int dimHash = dim.location().hashCode();
        long k = 0L;
        k |= (long) dimHash & 0xFFFFFFFFL;
        k <<= 32;

        int h = 31 * gx + gz;
        k |= (long) h & 0xFFFFFFFFL;
        return k;
    }

    private final Map<Long, UUID> claimsFast = new HashMap<>();


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
        removeAllClaimsFor(kid);


        // 3) store border + claim all cells
        k.hasBorder = true;
        k.borderMinX = aMinX;
        k.borderMaxX = aMaxX;
        k.borderMinZ = aMinZ;
        k.borderMaxZ = aMaxZ;

       for (int gx = minGX; gx <= maxGX; gx++) {
            for (int gz = minGZ; gz <= maxGZ; gz++) {
                ClaimKey ck = new ClaimKey(level.dimension(), gx, gz);
                claims.put(ck, kid);
                claimsFast.put(packClaimKey(ck.dim(), ck.gx(), ck.gz()), kid);
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
        ClaimKey ck = claimFromPos(level, pos);
        claims.put(ck, k.id);
        claimsFast.put(packClaimKey(ck.dim(), ck.gx(), ck.gz()), k.id);
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
        removeAllClaimsFor(kid);


        int minGX = Math.floorDiv(minX, CLAIM_CELL_SIZE);
        int maxGX = Math.floorDiv(maxX, CLAIM_CELL_SIZE);
        int minGZ = Math.floorDiv(minZ, CLAIM_CELL_SIZE);
        int maxGZ = Math.floorDiv(maxZ, CLAIM_CELL_SIZE);

        for (int gx = minGX; gx <= maxGX; gx++) {
            for (int gz = minGZ; gz <= maxGZ; gz++) {
                ClaimKey ck = new ClaimKey(level.dimension(), gx, gz);
                claims.put(ck, kid);
                claimsFast.put(packClaimKey(ck.dim(), ck.gx(), ck.gz()), kid);


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
        DIPLOMACY ANCHORS
        ----------------------------- */

        public record DiplomacyAnchor(ResourceKey<Level> dim, BlockPos pos, int radiusBlocks) {
            public static final Codec<DiplomacyAnchor> CODEC =
                    RecordCodecBuilder.create(inst -> inst.group(
                            DIM_KEY_CODEC.fieldOf("dim").forGetter(DiplomacyAnchor::dim),
                            BLOCKPOS_CODEC.fieldOf("pos").forGetter(DiplomacyAnchor::pos),
                            Codec.INT.optionalFieldOf("radius", 600).forGetter(DiplomacyAnchor::radiusBlocks)
                    ).apply(inst, (dim, pos, radius) ->
                            new DiplomacyAnchor(dim, pos.immutable(), Math.max(0, radius))
                    ));
        }




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

    private record RetinueData(UUID scribe, UUID treasurer, UUID general) {
        static final RetinueData NONE = new RetinueData(NIL_UUID, NIL_UUID, NIL_UUID);

        static final Codec<RetinueData> CODEC =
                    RecordCodecBuilder.create(inst -> inst.group(
                            UUID_CODEC.optionalFieldOf("scribe", NIL_UUID).forGetter(RetinueData::scribe),
                            UUID_CODEC.optionalFieldOf("treasurer", NIL_UUID).forGetter(RetinueData::treasurer),
                            UUID_CODEC.optionalFieldOf("general", NIL_UUID).forGetter(RetinueData::general)
                    ).apply(inst, RetinueData::new));
        }

    private record Extras(
            boolean royalGuardsEnabled,
            int ticketsAlive,
            double ticketsRegenBuf,
            RetinueData retinue
    ) {
        static final Extras DEFAULT = new Extras(false, -1, 0.0, RetinueData.NONE);

        static final Codec<Extras> CODEC =
                RecordCodecBuilder.create(inst -> inst.group(
                        Codec.BOOL.optionalFieldOf("royalGuardsEnabled", false).forGetter(Extras::royalGuardsEnabled),
                        Codec.INT.optionalFieldOf("ticketsAlive", -1).forGetter(Extras::ticketsAlive),
                        Codec.DOUBLE.optionalFieldOf("ticketsRegenBuf", 0.0).forGetter(Extras::ticketsRegenBuf),
                        RetinueData.CODEC.optionalFieldOf("retinue", RetinueData.NONE).forGetter(Extras::retinue)
                ).apply(inst, Extras::new));
    }



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
        public int soldierSkinId = 0;

        // IMPORTANT: networking expects k.name
        public String name;

        /** Where the kingdom was created (kingdom block position). */
        public final BlockPos origin;

        /** Active placed job blocks per jobId (requirements met). */
        public final Map<String, Integer> active = new HashMap<>();

        /** Placed job blocks per jobId (population). */
        public final Map<String, Integer> placed = new HashMap<>();

        // --- economy accessors (for WarPeaceEffects etc.) ---
        public int goldInt()  { return (int) Math.floor(this.gold); }
        public int woodInt()  { return (int) Math.floor(this.wood); }
        public int metalInt() { return (int) Math.floor(this.metal); }

        public void setGoldInt(int v)  { this.gold  = Math.max(0, v); }
        public void setWoodInt(int v)  { this.wood  = Math.max(0, v); }
        public void setMetalInt(int v) { this.metal = Math.max(0, v); }


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

        // diplomacy
        public int diplomacyRangeBlocks = 0;
        public final java.util.List<DiplomacyAnchor> envoyAnchors = new java.util.ArrayList<>();


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

            // SECURITY -> HAPPINESS (sliding)
            if (pop > 5) {
                double s = securityValue(); // base security (no pressure here)
                double req = REQUIRED_SECURITY; // 0.30
                double deficit01 = Math.max(0.0, Math.min(1.0, (req - s) / req));
                double MAX_PENALTY = 3.0; // match EconomyCalc for consistency
                h -= MAX_PENALTY * deficit01;
            }


            if (h < 0) h = 0;
            if (h > 10) h = 10;
            return h;
        }

        public double happinessEff(MinecraftServer server) {
            return kingdomState.get(server).happinessWithPressure(server, this);
        }

        public double securityEff(MinecraftServer server) {
            return kingdomState.get(server).securityValueWithPressure(server, this);
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
        public int ticketsAlive = -1;     
        public double ticketsRegenBuf = 0.0; 

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

                        Codec.INT.optionalFieldOf("soldierSkinId", 0)
                           .forGetter(k -> k.soldierSkinId),
 


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
                        Codec.INT.optionalFieldOf("diplomacyRangeBlocks", 0).forGetter(k -> k.diplomacyRangeBlocks),
                        DiplomacyAnchor.CODEC.listOf().optionalFieldOf("envoyAnchors", List.of())
                                .forGetter(k -> k.envoyAnchors),

                       Extras.CODEC.optionalFieldOf("extras", Extras.DEFAULT).forGetter(k -> new Extras(
                            k.royalGuardsEnabled,
                            k.ticketsAlive,
                            k.ticketsRegenBuf,
                            new RetinueData(
                                    k.retinueScribe == null ? NIL_UUID : k.retinueScribe,
                                    k.retinueTreasurer == null ? NIL_UUID : k.retinueTreasurer,
                                    k.retinueGeneral == null ? NIL_UUID : k.retinueGeneral
                            )
                    ))


                                               ).apply(inst, (id, owner, name, origin,
                               activeMap, placedMap,
                               heraldry,
                               soldierSkinId,
                               eco, border,
                               hasTerminal, terminalDim, terminalPos,
                               diplomacyRangeBlocks, envoyAnchors,
                               extras) -> {

                    Kingdom k = new Kingdom(id, owner, name, origin);

                    k.active.clear();
                    k.active.putAll(activeMap);

                    k.placed.clear();
                    k.placed.putAll(placedMap);

                    // economy
                    eco.apply(k);

                    // border
                    k.hasBorder = border.set();
                    k.borderMinX = border.minX();
                    k.borderMaxX = border.maxX();
                    k.borderMinZ = border.minZ();
                    k.borderMaxZ = border.maxZ();

                    // terminal
                    k.hasTerminal = hasTerminal;
                    k.terminalDim = terminalDim;
                    k.terminalPos = terminalPos;

                    // diplomacy
                    k.diplomacyRangeBlocks = Math.max(0, diplomacyRangeBlocks);
                    k.envoyAnchors.clear();
                    if (envoyAnchors != null) k.envoyAnchors.addAll(envoyAnchors);

                    // extras
                    k.royalGuardsEnabled = (extras != null && extras.royalGuardsEnabled());
                    k.ticketsAlive = (extras == null) ? -1 : extras.ticketsAlive();
                    k.ticketsRegenBuf = (extras == null) ? 0.0 : extras.ticketsRegenBuf();

                    RetinueData r = (extras == null) ? RetinueData.NONE : extras.retinue();
                    k.retinueScribe = (r == null || NIL_UUID.equals(r.scribe())) ? null : r.scribe();
                    k.retinueTreasurer = (r == null || NIL_UUID.equals(r.treasurer())) ? null : r.treasurer();
                    k.retinueGeneral = (r == null || NIL_UUID.equals(r.general())) ? null : r.general();

                    // cosmetics
                    k.heraldry = (heraldry == null) ? ItemStack.EMPTY : heraldry;
                    k.soldierSkinId = net.minecraft.util.Mth.clamp(
                            soldierSkinId,
                            0,
                            SoldierSkins.MAX_SKIN_ID
                    );

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
        k.soldierSkinId = SoldierSkins.random(level.getRandom());


        k.gold = 10; k.meat = 0; k.grain = 0; k.fish = 0;
        k.wood = 0; k.metal = 0; k.armor = 0; k.weapons = 0;
        k.gems = 0; k.horses = 0; k.potions = 0;

        // immediately bind the origin as their terminal
        k.hasTerminal = true;
        k.terminalDim = level.dimension();
        k.terminalPos = origin.immutable();

        k.diplomacyRangeBlocks = 1500; // default player diplo range (tune later)


        kingdoms.put(id, k);
        playerKingdom.put(player, id);

        // initial claim: the cell containing the origin
        ClaimKey ck = claimFromPos(level, origin);
        claims.put(ck, id);
        claimsFast.put(packClaimKey(ck.dim(), ck.gx(), ck.gz()), id);


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
        removeAllClaimsFor(kid);


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

    public Kingdom getKingdomAtFast(Level level, int x, int z) {
        int gx = Math.floorDiv(x, CLAIM_CELL_SIZE);
        int gz = Math.floorDiv(z, CLAIM_CELL_SIZE);

        UUID kid = claimsFast.get(packClaimKey(level.dimension(), gx, gz));
        return (kid == null) ? null : kingdoms.get(kid);
    }

    // -----------------------------
    // PUPPETS
    // -----------------------------

    public UUID getMasterOf(UUID puppetKid) {
        return puppetMaster.get(puppetKid);
    }

    public boolean isPuppet(UUID kid) {
        return puppetMaster.containsKey(kid);
    }

    public boolean isMaster(UUID kid) {
        return puppetMaster.containsValue(kid);
    }

    public java.util.List<UUID> getPuppetsOf(UUID masterKid) {
        var out = new java.util.ArrayList<UUID>();
        for (var e : puppetMaster.entrySet()) {
            if (masterKid.equals(e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    /** Follow chain, but never allow cycles. */
    public UUID getUltimateMaster(UUID kid) {
        if (kid == null) return null;
        UUID cur = kid;
        java.util.HashSet<UUID> seen = new java.util.HashSet<>();
        while (true) {
            UUID m = puppetMaster.get(cur);
            if (m == null) return cur;
            if (!seen.add(cur)) return cur; // cycle guard
            cur = m;
        }
    }

    /** Master/puppet are treated as allied automatically. */
    public boolean isPuppetAllied(UUID a, UUID b) {
        if (a == null || b == null) return false;
        UUID am = puppetMaster.get(a);
        UUID bm = puppetMaster.get(b);

        // a is puppet of b, or b is puppet of a
        if (b.equals(am)) return true;
        if (a.equals(bm)) return true;

        // share the same ultimate master (optional; keeps puppet blocks together)
        UUID ua = getUltimateMaster(a);
        UUID ub = getUltimateMaster(b);
        return ua != null && ua.equals(ub) && !ua.equals(a) && !ub.equals(b);
    }

    /** Set puppet -> master (prevents self + cycles). */
    public boolean setPuppet(UUID puppetKid, UUID masterKid) {
        if (puppetKid == null || masterKid == null) return false;
        if (puppetKid.equals(masterKid)) return false;

        // prevent cycles: master cannot (directly or indirectly) be a puppet of puppet
        UUID um = getUltimateMaster(masterKid);
        UUID up = getUltimateMaster(puppetKid);
        if (um != null && um.equals(puppetKid)) return false; // would loop

        puppetMaster.put(puppetKid, masterKid);
        setDirty();
        return true;
    }

    public void clearPuppet(UUID puppetKid) {
        if (puppetMaster.remove(puppetKid) != null) {
            setDirty();
        }
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
                            }),
                    UUID_MAP_CODEC.optionalFieldOf("puppetMaster", Map.of())
                    .forGetter(s -> s.puppetMaster)

            ).apply(inst, (kingdomList, playerMap, claimList, puppetMap) -> {
                kingdomState s = new kingdomState();

                for (Kingdom k : kingdomList) s.kingdoms.put(k.id, k);
                s.playerKingdom.putAll(playerMap);
                s.puppetMaster.putAll(puppetMap);

                for (ClaimEntry ce : claimList) {
                    s.claims.put(new ClaimKey(ce.dim(), ce.gx(), ce.gz()), ce.kid());
                }

                for (var e : s.claims.entrySet()) {
                    ClaimKey ck = e.getKey();
                    s.claimsFast.put(packClaimKey(ck.dim(), ck.gx(), ck.gz()), e.getValue());
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

        kingdomState s = overworld.getDataStorage().computeIfAbsent(TYPE);

        // ✅ Migration: ensure sane diplomacy range defaults for old saves
        boolean changed = false;
        for (Kingdom k : s.kingdoms.values()) {
            if (k == null) continue;

            if (k.diplomacyRangeBlocks <= 0) {
                // player kingdoms tend to be smaller / more local
                boolean isPlayer = (k.owner != null && s.playerKingdom.containsKey(k.owner));

                k.diplomacyRangeBlocks = isPlayer ? 1500 : 1500;
                changed = true;
            }
        }

        if (changed) s.setDirty();
        return s;
    }




    public UUID getKingdomIdFor(UUID playerUuid) {
    return playerKingdom.get(playerUuid); // may return null if player has no kingdom
}

        public static int computePlayerTicketsMax(kingdomState.Kingdom k) {
            if (k == null) return 0;
            int garrisons = k.getActive("garrison"); // matches your existing rule
            return Math.max(0, garrisons * 50);
        }


}
