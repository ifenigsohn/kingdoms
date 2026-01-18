package name.kingdoms;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

public class jobDefinition {

    /* -----------------------------
       REGISTRY
     ----------------------------- */

    private static final Map<String, jobDefinition> BY_ID = new HashMap<>();

    private static jobDefinition reg(jobDefinition j) {
        BY_ID.put(j.getId(), j);
        return j;
    }

    public static jobDefinition byId(String id) {
        return BY_ID.get(id);
    }

    public static Iterable<jobDefinition> all() {
        return BY_ID.values();
    }

    /* -----------------------------
       BASICS
     ----------------------------- */

    private final String id;
    private final int workIntervalTicks;

    public jobDefinition(String id, int intervalTicks) {
        this.id = id;
        this.workIntervalTicks = intervalTicks;
    }

    public String getId() { return id; }
    public int getWorkInterval() { return workIntervalTicks; }

    /* -----------------------------
       INPUTS per cycle
     ----------------------------- */

    private double inGold;
    private double inMeat, inGrain, inFish;
    private double inWood, inMetal, inArmor, inWeapons, inGems, inHorses, inPotions;

    /* -----------------------------
       OUTPUTS per cycle
     ----------------------------- */

    private double outGold;
    private double outMeat, outGrain, outFish;
    private double outWood, outMetal, outArmor, outWeapons, outGems, outHorses, outPotions;

    /* -----------------------------
       COSTS
     ----------------------------- */

    private double costGold;
    private double costMeat, costGrain, costFish;
    private double costWood, costMetal, costArmor, costWeapons, costGems, costHorses, costPotions;

    /* -----------------------------
       INPUT GETTERS
     ----------------------------- */

    public double inGold()    { return inGold; }
    public double inMeat()    { return inMeat; }
    public double inGrain()   { return inGrain; }
    public double inFish()    { return inFish; }
    public double inWood()    { return inWood; }
    public double inMetal()   { return inMetal; }
    public double inArmor()   { return inArmor; }
    public double inWeapons() { return inWeapons; }
    public double inGems()    { return inGems; }
    public double inHorses()  { return inHorses; }
    public double inPotions() { return inPotions; }

    /** Generic food input (meat+grain+fish). Use this for UI. */
    public double inFood() { return inMeat + inGrain + inFish; }

    /* -----------------------------
       OUTPUT GETTERS
     ----------------------------- */

    public double outGold()    { return outGold; }
    public double outMeat()    { return outMeat; }
    public double outGrain()   { return outGrain; }
    public double outFish()    { return outFish; }
    public double outWood()    { return outWood; }
    public double outMetal()   { return outMetal; }
    public double outArmor()   { return outArmor; }
    public double outWeapons() { return outWeapons; }
    public double outGems()    { return outGems; }
    public double outHorses()  { return outHorses; }
    public double outPotions() { return outPotions; }

    /** Generic food output (meat+grain+fish). Handy for tooltips. */
    public double outFood() { return outMeat + outGrain + outFish; }

    /* -----------------------------
       REQUIREMENTS
     ----------------------------- */

    private final Map<ResourceLocation, Integer> requiredBlocks = new HashMap<>();
    private final Map<TagKey<Block>, Integer> requiredBlockTags = new HashMap<>();

    public Map<ResourceLocation, Integer> getRequiredBlocks() {
        return java.util.Collections.unmodifiableMap(requiredBlocks);
    }

    public Map<TagKey<Block>, Integer> getRequiredBlockTags() {
        return java.util.Collections.unmodifiableMap(requiredBlockTags);
    }

    /**
     * s can be:
     *  - "minecraft:chest"
     *  - "#minecraft:logs"
     */
    public jobDefinition requiresBlock(String s, int count) {
        String fixed = s.trim().toLowerCase(java.util.Locale.ROOT);
        if (count <= 0) return this;

        if (fixed.startsWith("#")) {
            String tagStr = fixed.substring(1).trim(); // strip leading '#'

            if (tagStr.isEmpty() || tagStr.contains("#")) {
                throw new IllegalArgumentException("Bad tag key: " + s + "  (use \"#namespace:path\")");
            }

            ResourceLocation id = ResourceLocation.parse(tagStr);
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, id);
            this.requiredBlockTags.merge(tag, count, Integer::sum);
        } else {
            ResourceLocation id = ResourceLocation.parse(fixed);
            this.requiredBlocks.merge(id, count, Integer::sum);
        }
        return this;
    }

    public jobDefinition requiresBlock(String s) {
        return requiresBlock(s, 1);
    }

    public boolean meetsBlockRequirements(ServerLevel level, BlockPos origin, int radius) {
        if (requiredBlocks.isEmpty() && requiredBlockTags.isEmpty()) return true;

        Map<ResourceLocation, Integer> haveBlocks = new HashMap<>();
        Map<TagKey<Block>, Integer> haveTags = new HashMap<>();

        for (var e : requiredBlocks.entrySet()) haveBlocks.put(e.getKey(), 0);
        for (var e : requiredBlockTags.entrySet()) haveTags.put(e.getKey(), 0);

        BlockPos min = origin.offset(-radius, -radius, -radius);
        BlockPos max = origin.offset( radius,  radius,  radius);

        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            BlockState st = level.getBlockState(p);
            Block b = st.getBlock();

            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(b);
            if (requiredBlocks.containsKey(id)) {
                haveBlocks.merge(id, 1, Integer::sum);
            }

            for (TagKey<Block> tag : requiredBlockTags.keySet()) {
                if (st.is(tag)) {
                    haveTags.merge(tag, 1, Integer::sum);
                }
            }
        }

        for (var e : requiredBlocks.entrySet()) {
            if (haveBlocks.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        }
        for (var e : requiredBlockTags.entrySet()) {
            if (haveTags.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        }

        return true;
    }

    /* -----------------------------
       BUILDERS
     ----------------------------- */

    public jobDefinition inputs(
            double gold,
            double meat, double grain, double fish,
            double wood, double metal, double armor, double weapons,
            double gems, double horses, double potions
    ) {
        this.inGold = gold;
        this.inMeat = meat;
        this.inGrain = grain;
        this.inFish = fish;
        this.inWood = wood;
        this.inMetal = metal;
        this.inArmor = armor;
        this.inWeapons = weapons;
        this.inGems = gems;
        this.inHorses = horses;
        this.inPotions = potions;
        return this;
    }

    /** Convenience: set food input as a single generic amount (stored evenly across meat/grain/fish). */
        public jobDefinition inputsFood(
                double gold,
                double food,
                double wood, double metal, double armor, double weapons,
                double gems, double horses, double potions
        ) {
        double each = food / 3.0;
        return inputs(gold, each, each, each, wood, metal, armor, weapons, gems, horses, potions);
        }


    public jobDefinition outputs(
            double gold,
            double meat, double grain, double fish,
            double wood, double metal, double armor, double weapons,
            double gems, double horses, double potions
    ) {
        this.outGold = gold;
        this.outMeat = meat;
        this.outGrain = grain;
        this.outFish = fish;
        this.outWood = wood;
        this.outMetal = metal;
        this.outArmor = armor;
        this.outWeapons = weapons;
        this.outGems = gems;
        this.outHorses = horses;
        this.outPotions = potions;
        return this;
    }

    public jobDefinition cost(
            double gold,
            double meat, double grain, double fish,
            double wood, double metal, double armor, double weapons,
            double gems, double horses, double potions
    ) {
        this.costGold = gold;
        this.costMeat = meat;
        this.costGrain = grain;
        this.costFish = fish;
        this.costWood = wood;
        this.costMetal = metal;
        this.costArmor = armor;
        this.costWeapons = weapons;
        this.costGems = gems;
        this.costHorses = horses;
        this.costPotions = potions;
        return this;
    }

        /** Convenience: set purchase cost food as a single generic amount (split evenly across meat/grain/fish). */
        public jobDefinition costFood(
                double gold,
                double food,
                double wood, double metal, double armor, double weapons,
                double gems, double horses, double potions
        ) {
        double each = food / 3.0;
        return cost(gold, each, each, each, wood, metal, armor, weapons, gems, horses, potions);
        }


    /* -----------------------------
       BUYING
     ----------------------------- */

    public boolean canAfford(kingdomState.Kingdom k, int qty) {
        return  k.gold    >= costGold    * qty &&
                k.meat    >= costMeat    * qty &&
                k.grain   >= costGrain   * qty &&
                k.fish    >= costFish    * qty &&
                k.wood    >= costWood    * qty &&
                k.metal   >= costMetal   * qty &&
                k.armor   >= costArmor   * qty &&
                k.weapons >= costWeapons * qty &&
                k.gems    >= costGems    * qty &&
                k.horses  >= costHorses  * qty &&
                k.potions >= costPotions * qty;
    }

    public void spend(kingdomState.Kingdom k, int qty) {
        k.gold    -= costGold    * qty;
        k.meat    -= costMeat    * qty;
        k.grain   -= costGrain   * qty;
        k.fish    -= costFish    * qty;
        k.wood    -= costWood    * qty;
        k.metal   -= costMetal   * qty;
        k.armor   -= costArmor   * qty;
        k.weapons -= costWeapons * qty;
        k.gems    -= costGems    * qty;
        k.horses  -= costHorses  * qty;
        k.potions -= costPotions * qty;
    }

    /* -----------------------------
       NET + COST HELPERS
     ----------------------------- */

    public double netGold()    { return outGold    - inGold; }
    public double netMeat()    { return outMeat    - inMeat; }
    public double netGrain()   { return outGrain   - inGrain; }
    public double netFish()    { return outFish    - inFish; }
    public double netWood()    { return outWood    - inWood; }
    public double netMetal()   { return outMetal   - inMetal; }
    public double netArmor()   { return outArmor   - inArmor; }
    public double netWeapons() { return outWeapons - inWeapons; }
    public double netGems()    { return outGems    - inGems; }
    public double netHorses()  { return outHorses  - inHorses; }
    public double netPotions() { return outPotions - inPotions; }

    /** Generic net food (meat+grain+fish). */
    public double netFood() { return (outMeat + outGrain + outFish) - (inMeat + inGrain + inFish); }

    public double costGold()    { return costGold; }
    public double costMeat()    { return costMeat; }
    public double costGrain()   { return costGrain; }
    public double costFish()    { return costFish; }
    public double costWood()    { return costWood; }
    public double costMetal()   { return costMetal; }
    public double costArmor()   { return costArmor; }
    public double costWeapons() { return costWeapons; }
    public double costGems()    { return costGems; }
    public double costHorses()  { return costHorses; }
    public double costPotions() { return costPotions; }

    /** Generic cost food (meat+grain+fish). */
    public double costFood() { return costMeat + costGrain + costFish; }

    /* -----------------------------
       EXECUTION
     ----------------------------- */

    public boolean canWork(ServerLevel level, kingdomState.Kingdom k) {
        double foodNeeded = inFood();

        return  k.gold    >= inGold &&
                kingdomState.effectiveFood(k) >= foodNeeded &&
                k.wood    >= inWood &&
                k.metal   >= inMetal &&
                k.armor   >= inArmor &&
                k.weapons >= inWeapons &&
                k.gems    >= inGems &&
                k.horses  >= inHorses &&
                k.potions >= inPotions;
    }

    public boolean consumeInputs(ServerLevel level, kingdomState.Kingdom k) {
        if (!canWork(level, k)) return false;

        double foodNeeded = inFood();

        if (!kingdomState.consumeFood(k, foodNeeded)) return false;

        k.gold -= inGold;
        k.wood    -= inWood;
        k.metal   -= inMetal;
        k.armor   -= inArmor;
        k.weapons -= inWeapons;
        k.gems    -= inGems;
        k.horses  -= inHorses;
        k.potions -= inPotions;

        return true;
    }

    public void applyOutputs(ServerLevel level, kingdomState.Kingdom k) {
        double mult = k.productionMultiplier();

        k.gold    += outGold    * mult;
        k.meat    += outMeat    * mult;
        k.grain   += outGrain   * mult;
        k.fish    += outFish    * mult;
        k.wood    += outWood    * mult;
        k.metal   += outMetal   * mult;
        k.armor   += outArmor   * mult;
        k.weapons += outWeapons * mult;
        k.gems    += outGems    * mult;
        k.horses  += outHorses  * mult;
        k.potions += outPotions * mult;

        kingdomState.get(level.getServer()).markDirty();
        }


    /* -----------------------------
       JOB DEFINITIONS
     ----------------------------- */

    public static final jobDefinition FARM_JOB =
            reg(new jobDefinition("farm", 6000))
                    .requiresBlock("minecraft:farmland", 8)
                    .requiresBlock("minecraft:water", 1)
                    .outputs(0.5, 0,3,0, 0,
                            0,0,0, 0,0,0)
                    .cost(5, 0,0,0, 0,0,0,0, 0,0,0);

    public static final jobDefinition BUTCHER_JOB =
            reg(new jobDefinition("butcher", 6000))
                  .requiresBlock("minecraft:smoker", 2)
                  .requiresBlock("#minecraft:beds", 2)
                  .requiresBlock("minecraft:chest", 2)
                    .outputs(0.5, 3,0,0, 0,
                            0,0,0, 0,0,0)
                    .cost(5, 0,5,0, 0,0,0,0, 0,0,0);

    public static final jobDefinition FISHING_JOB =
            reg(new jobDefinition("fishing", 6000))
                .requiresBlock("minecraft:barrel", 1)
                .requiresBlock("minecraft:water", 10)
                    .outputs(0.5, 0,0,3, 0,
                            0,0,0, 0,0,0)
                    .cost(5, 0,0,0, 0,0,0,0, 0,0,0);

    public static final jobDefinition WOOD_JOB =
            reg(new jobDefinition("wood", 6000))
                    .requiresBlock("minecraft:chest", 1)
                    .requiresBlock("#minecraft:logs", 10)
                    .inputsFood(0, 2, 0, 0, 0, 0, 0, 0, 0)
                    .outputs(0.5, 0,0,0, 3,
                            0,0,0, 0,0,0)
                    .cost(5, 0,0,0, 0,0,0,0, 0,0,0);

    public static final jobDefinition METAL_JOB =
            reg(new jobDefinition("metal", 6000))
                    .requiresBlock("minecraft:chest", 3)
                    .requiresBlock("minecraft:iron_ore", 2)
                    .requiresBlock("#minecraft:beds", 1)
                    .inputsFood(0, 2, 2, 0, 0, 0, 0, 0, 0)
                    .outputs(1.5, 0,0,0, 0,
                            2,0,0, 0,0,0)
                    .cost(5, 0,0,0, 10,0,0,0, 0,0,0);

    public static final jobDefinition GEM_JOB =
            reg(new jobDefinition("gem", 6000))
                    .requiresBlock("minecraft:chest", 3)
                    .requiresBlock("minecraft:diamond_ore", 2)
                    .requiresBlock("#minecraft:beds", 1)
                    .inputsFood(0, 5, 5, 2, 0, 0, 0, 0, 0)
                    .outputs(2, 0,0,0, 0,
                            0,0,0, 1,0,0)
                    .cost(15, 0,0,0, 0,10,0,0, 0,0,0);

    public static final jobDefinition ALCHEMY_JOB =
            reg(new jobDefinition("alchemy", 6000))
                    .requiresBlock("minecraft:brewing_stand", 1)
                    .requiresBlock("minecraft:bookshelf", 5)
                    .inputsFood(0, 1, 0, 2, 0, 0, 1, 0, 0)
                    .outputs(3, 0,0,0, 0,
                            0,0,0, 0,0,1)
                    .cost(50, 0,0,0, 0,10,0,0, 10,0,0);

    public static final jobDefinition ARMOR_JOB =
            reg(new jobDefinition("armor", 6000))
                    .requiresBlock("minecraft:smithing_table", 1)
                    .requiresBlock("#minecraft:beds", 1)
                    .requiresBlock("minecraft:lava_cauldron", 1)
                    .inputsFood(0, 1, 0, 5, 0, 0, 0, 0, 0)
                    .outputs(1, 0,0,0, 0,
                            0,0.5,0, 0,0,0)
                    .cost(10, 0,0,0, 5,5,0,0, 0,0,0);

    public static final jobDefinition WEAPON_JOB =
            reg(new jobDefinition("weapon", 6000))
                    .requiresBlock("#minecraft:anvil", 1)
                    .requiresBlock("minecraft:grindstone", 1)
                    .requiresBlock("#minecraft:beds", 1)
                    .inputsFood(0, 2, 0, 5, 0, 0, 0, 0, 0)
                    .outputs(1, 0,0,0, 0,
                            0,0,0.5, 0,0,0)
                    .cost(10, 0,0,0, 5,5,0,0, 0,0,0);

    public static final jobDefinition STABLE_JOB =
            reg(new jobDefinition("stable", 200))
                    .requiresBlock("minecraft:hay_block", 5)
                    .requiresBlock("#minecraft:fence_gates", 1)
                    .requiresBlock("#minecraft:fences", 1)
                    .inputsFood(0, 1, 0, 1, 0, 0, 0, 0, 0)
                    .outputs(1, 0,0,0, 0,
                            0,0,0, 0,0.1,0)
                    .cost(15, 0,10,0, 0,5,0,0, 0,0,0);

    public static final jobDefinition GUARD_JOB =
            reg(new jobDefinition("guard", 6000))
                    .requiresBlock("#minecraft:beds", 3)
                    .requiresBlock("minecraft:chest", 3)
                    .requiresBlock("minecraft:barrel", 1)
                    .requiresBlock("minecraft:grindstone", 1)
                    .inputsFood(0.5, 3, 0, 0, 0.1, 0.1, 0, 0, 0)
                    .outputs(0, 0,0,0, 0,
                            0,0,0, 0,0,0)
                    .costFood(10, 10, 0, 0, 1, 1, 0, 0, 0);


    public static final jobDefinition TRAINING_JOB =
            reg(new jobDefinition("training", 6000))
                    .requiresBlock("#minecraft:fences", 5)
                    .requiresBlock("#minecraft:beds", 3)
                    .requiresBlock("minecraft:target", 2)
                   .inputsFood(2, 5, 5, 0, 0, 1, 0, 0, 0)
                    .outputs(0, 0,0,0, 0,
                            0,0,0, 0,0,0)
                    .costFood(20, 20, 0, 0, 2, 2, 0, 0, 0);


    public static final jobDefinition GARRISON_JOB =
            reg(new jobDefinition("garrison", 200))
                    .requiresBlock("#minecraft:beds", 3)
                    .requiresBlock("minecraft:barrel", 1)
                    .requiresBlock("#minecraft:anvil", 1)
                   .inputsFood(5, 15, 3, 5, 0, 0, 0, 0, 0)
                    .outputs(0, 0,0,0, 0,
                            0,0,0, 0,0,0)
                    .costFood(20, 20, 0, 0, 10, 10, 0, 0, 0);

    public static final jobDefinition CHAPEL_JOB =
            reg(new jobDefinition("chapel", 6000))
                    .requiresBlock("#minecraft:beds", 2)
                    .requiresBlock("minecraft:lectern", 1)
                    .requiresBlock("minecraft:glass", 1)
                   .inputsFood(0, 5, 2, 0, 0, 0, 1, 0, 0)
                    .outputs(0, 0,0,0, 0,
                            0,0,0, 0,0,0)
                    .cost(30, 0,0,0, 0,2,0,0, 1,0,0);

    public static final jobDefinition TAVERN_JOB =
            reg(new jobDefinition("tavern", 6000))
                    .requiresBlock("minecraft:dispenser", 2)
                    .requiresBlock("minecraft:flower_pot", 3)
                    .requiresBlock("#minecraft:beds", 3)
                    .inputsFood(0, 5, 2, 0, 0, 0, 1, 0, 0)
                    .outputs(2, 0,0,0, 0,
                            0,0,0, 0,0,0)
                    .costFood(0, 30, 5, 0, 0, 0, 0, 0, 0);


    public static final jobDefinition SHOP_JOB =
            reg(new jobDefinition("shop", 6000))
                    .requiresBlock("minecraft:item_frame", 2)
                    .requiresBlock("minecraft:chest", 2)
                    .inputsFood(0, 5, 0, 5, 0, 0, 1, 0, 0)
                    .outputs(5, 0,0,0, 0,
                            0,0,0, 0,0,0)
                    .costFood(0, 5, 0, 5, 0, 0, 1, 0, 0);


    public static final jobDefinition NOBILITY_JOB =
            reg(new jobDefinition("nobility", 6000))
                    .requiresBlock("#minecraft:beds", 4)
                    .requiresBlock("minecraft:note_block", 2)
                    .inputsFood(0, 10, 0, 3, 0, 0, 0, 0, 0)
                    .outputs(3, 0,0,0, 0,
                            0,0,0, 0,0,0)
                    .cost(10, 0,0,0, 0,0,0,0, 5,0,0);


        // -----------------------------
        // RETINUE JOBS (no production; purely follower roles)
        // -----------------------------

        public static final jobDefinition SCRIBE_JOB =
                reg(new jobDefinition("scribe", 20 * 60 * 60)); 
                // no requirements, no inputs/outputs/cost

        public static final jobDefinition TREASURER_JOB =
                reg(new jobDefinition("treasurer", 20 * 60 * 60));

        public static final jobDefinition GENERAL_JOB =
                reg(new jobDefinition("general", 20 * 60 * 60));

                }
