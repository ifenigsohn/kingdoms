package name.kingdoms.ambient;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class AmbientProps {
    private AmbientProps() {}

    /** Returned by place(): lets ScriptedAmbientEvent know where NPCs should “orbit”. */
    public record PropPlacement(UUID sceneId, BlockPos anchor) {}

    public enum Kind {
        SMALL_CAMP("camp_small"),
        SHRINE("shrine"),

        // old generic
        CART("cart"),

        // new variants
        FARM_CART("farm_cart"),
        METAL_CART("metal_cart"),
        COAL_CART("coal_cart"),
        GOLD_CART("gold_cart"),
        PLAGUE_CART("plague_cart"),

        HUNTER_TENT("hunter_tent"),
        MILITARY_CAMP("military_camp");

        public final String id;
        Kind(String id) { this.id = id; }

        public static Kind fromId(String id) {
            if (id == null) return null;
            String s = id.trim().toLowerCase(Locale.ROOT);

            return switch (s) {
                case "camp_basic", "camp_small", "small_camp" -> SMALL_CAMP;
                case "shrine", "roadside_shrine" -> SHRINE;

                // old + new cart ids
                case "cart" -> CART;
                case "farm_cart", "cart_farm" -> FARM_CART;
                case "metal_cart", "cart_metal", "iron_cart" -> METAL_CART;
                case "coal_cart", "cart_coal" -> COAL_CART;
                case "gold_cart", "cart_gold" -> GOLD_CART;
                case "plague_cart", "cart_plague" -> PLAGUE_CART;

                case "hunter_tent", "tent_hunter" -> HUNTER_TENT;
                case "military_camp", "mil_camp" -> MILITARY_CAMP;
                default -> null;
            };
        }
    }


    public static Kind fromId(String propId) {
        if (propId == null) return null;
        String s = propId.trim().toUpperCase().replace('-', '_');
        // allow "camp_small" -> "SMALL_CAMP"
        if (s.equals("CAMP_SMALL") || s.equals("SMALLCAMP") || s.equals("SMALL_CAMP")) return Kind.SMALL_CAMP;
        if (s.equals("SHRINE")) return Kind.SHRINE;
        if (s.equals("CART")) return Kind.CART;
        if (s.equals("HUNTER_TENT") || s.equals("TENT_HUNTER")) return Kind.HUNTER_TENT;
        if (s.equals("MILITARY_CAMP") || s.equals("MIL_CAMP") || s.equals("MILITARYCAMP")) return Kind.MILITARY_CAMP;

        // also allow exact enum names
        try { return Kind.valueOf(s); } catch (Throwable ignored) {}
        return null;
    }


    /** Builds absolute blocks for a prop centered on centerGround (GROUND pos). */
    public static Map<BlockPos, BlockState> build(Kind kind, BlockPos centerGround) {
        return switch (kind) {
            case SMALL_CAMP -> smallCamp(centerGround);
            case SHRINE -> shrine(centerGround);

            case CART, FARM_CART, METAL_CART, COAL_CART, GOLD_CART, PLAGUE_CART -> cart(centerGround, kind);

            case HUNTER_TENT -> hunterTent(centerGround);
            case MILITARY_CAMP -> militaryCamp(centerGround);
        };
    }


    // ---- templates ----

    private static Map<BlockPos, BlockState> smallCamp(BlockPos g) {
        Map<BlockPos, BlockState> m = new HashMap<>();

        // campfire sits in air above ground
        m.put(g.above(1), Blocks.CAMPFIRE.defaultBlockState());

        // seating around it (1 block further out, facing inward)
        m.put(g.offset( 2,1, 0), Blocks.SPRUCE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.EAST)); // east seat faces center
        m.put(g.offset(-2,1, 0), Blocks.SPRUCE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.WEST)); // west seat faces center
        m.put(g.offset( 0,1, 2), Blocks.SPRUCE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.SOUTH)); // south seat faces center
        m.put(g.offset( 0,1,-2), Blocks.SPRUCE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.NORTH)); // north seat faces center


        // bedroll vibe
        m.put(g.offset(4,1,0), Blocks.WHITE_CARPET.defaultBlockState());
        m.put(g.offset(4,1,1), Blocks.WHITE_CARPET.defaultBlockState());

        // supplies
        m.put(g.offset(-4,1,0), Blocks.BARREL.defaultBlockState());
        m.put(g.offset(-4,1,1), Blocks.CHEST.defaultBlockState());

        return m;
    }

    private static Map<BlockPos, BlockState> shrine(BlockPos g) {
        Map<BlockPos, BlockState> m = new HashMap<>();

        // 2-high cobble wall + end rod “candle”
        m.put(g.above(1), Blocks.COBBLESTONE_WALL.defaultBlockState());
        m.put(g.above(2), Blocks.COBBLESTONE_WALL.defaultBlockState());
        m.put(g.above(3), Blocks.END_ROD.defaultBlockState());

        // little base stones (in air above ground)
        m.put(g.offset( 1,1, 0), Blocks.MOSSY_COBBLESTONE.defaultBlockState());
        m.put(g.offset(-1,1, 0), Blocks.MOSSY_COBBLESTONE.defaultBlockState());
        m.put(g.offset( 0,1, 1), Blocks.MOSSY_COBBLESTONE.defaultBlockState());
        m.put(g.offset( 0,1,-1), Blocks.MOSSY_COBBLESTONE.defaultBlockState());

        return m;
    }

    private static Map<BlockPos, BlockState> cart(BlockPos g, Kind kind) {
        Map<BlockPos, BlockState> m = new HashMap<>();

        // --- Dimensions ---
        // Bed footprint: width 3 (x=-1..1), length 4 (z=0..3)
        // Move slabs UP by one: bed is now at y=2 (instead of y=1)
        int y = 2;
        int minX = -1, maxX = 1;
        int minZ = 0,  maxZ = 3;

        // Floor (slabs)
        BlockState floor = Blocks.SPRUCE_SLAB.defaultBlockState();
        for (int dx = minX; dx <= maxX; dx++) {
            for (int dz = minZ; dz <= maxZ; dz++) {
                m.put(g.offset(dx, y, dz), floor);
            }
        }

        // Trapdoor side walls (open = vertical)
        // Rotate trapdoors 180° compared to earlier: swap N<->S and E<->W.
        BlockState tdN = Blocks.SPRUCE_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.OPEN, true)
                .setValue(TrapDoorBlock.HALF, Half.BOTTOM)
                .setValue(TrapDoorBlock.FACING, Direction.SOUTH); // was NORTH

        BlockState tdS = Blocks.SPRUCE_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.OPEN, true)
                .setValue(TrapDoorBlock.HALF, Half.BOTTOM)
                .setValue(TrapDoorBlock.FACING, Direction.NORTH); // was SOUTH

        BlockState tdW = Blocks.SPRUCE_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.OPEN, true)
                .setValue(TrapDoorBlock.HALF, Half.BOTTOM)
                .setValue(TrapDoorBlock.FACING, Direction.EAST); // was WEST

        BlockState tdE = Blocks.SPRUCE_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.OPEN, true)
                .setValue(TrapDoorBlock.HALF, Half.BOTTOM)
                .setValue(TrapDoorBlock.FACING, Direction.WEST); // was EAST

        // Back wall (z = maxZ+1)
        for (int dx = minX; dx <= maxX; dx++) {
            m.put(g.offset(dx, y, maxZ + 1), tdN);
        }

        // Left/right walls
        for (int dz = minZ; dz <= maxZ; dz++) {
            m.put(g.offset(minX - 1, y, dz), tdE); // left wall faces inward
            m.put(g.offset(maxX + 1, y, dz), tdW); // right wall faces inward
        }

        // Front wall (z = -1), leave a gap in the middle for shafts
        m.put(g.offset(minX, y, -1), tdS);
        m.put(g.offset(maxX, y, -1), tdS);
        // (no trapdoor at x=0,z=-1)

        // Replace cauldrons with stripped wood "wheels/axle"
        // Two logs per side, axis X, at ground-ish level (y=0)
        BlockState wheel = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState()
                .setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);

        // Lift logs up under the bed (bed y=2, so under-bed is y=1)
        m.put(g.offset(minX, 1, 0), wheel);
        m.put(g.offset(minX, 1, 1), wheel);
        m.put(g.offset(maxX, 1, 0), wheel);
        m.put(g.offset(maxX, 1, 1), wheel);




        // Shaft/handles: rotate fence gates 90° (NORTH -> EAST)
        BlockState shaft = Blocks.SPRUCE_FENCE_GATE.defaultBlockState()
                .setValue(FenceGateBlock.FACING, Direction.EAST) // was NORTH
                .setValue(FenceGateBlock.OPEN, false);

        // Extend out the front (toward -Z)
        int shaftY = y - 1; // tuck under bed
        for (int dz = -1; dz >= -2; dz--) { // closer to cart
            m.put(g.offset(-1, shaftY, dz), shaft);
            m.put(g.offset( 1, shaftY, dz), shaft);
        }

        m.put(g.offset(-1, shaftY, 0), shaft);
        m.put(g.offset( 1, shaftY, 0), shaft);

        // Load: varies by cart kind (these overwrite the two slabs at (0,y,1) and (0,y,2))
        BlockState load1;
        BlockState load2;

        switch (kind) {
            case FARM_CART -> {
                load1 = Blocks.HAY_BLOCK.defaultBlockState();
                load2 = Blocks.HAY_BLOCK.defaultBlockState();
            }
            case METAL_CART -> {
                // pick the look you like better; raw iron block reads great as "cargo"
                load1 = Blocks.IRON_ORE.defaultBlockState();
                load2 = Blocks.RAW_IRON_BLOCK.defaultBlockState();
            }
            case COAL_CART -> {
                load1 = Blocks.COAL_ORE.defaultBlockState();
                load2 = Blocks.COAL_BLOCK.defaultBlockState();
            }
            case GOLD_CART -> {
                load1 = Blocks.GOLD_BLOCK.defaultBlockState();
                load2 = Blocks.GOLD_BLOCK.defaultBlockState();
            }
            case PLAGUE_CART -> {
                // keep bed clear; skeleton will be spawned as an entity (not a block)
                load1 = Blocks.AIR.defaultBlockState();
                load2 = Blocks.AIR.defaultBlockState();
            }
            default -> {
                // fallback for generic CART
                load1 = Blocks.HAY_BLOCK.defaultBlockState();
                load2 = Blocks.HAY_BLOCK.defaultBlockState();
            }
        }

        // Place the load (AIR means "don't place anything")
        if (!load1.isAir()) m.put(g.offset(0, y, 1), load1);
        if (!load2.isAir()) m.put(g.offset(0, y, 2), load2);


        return m;
    }



    private static Map<BlockPos, BlockState> hunterTent(BlockPos g) {
        Map<BlockPos, BlockState> m = new HashMap<>();

        // Orientation: "front" faces -Z
        // Ground convention: g = ground center; we build in the air at y+1+

        // ---- Posts (dark poles) ----
        // footprint is 5 (x) by 3 (z): x=-2..2, z=0..2
        BlockState post = Blocks.DARK_OAK_FENCE.defaultBlockState();

        // 4 corner posts, 3-high
        for (int y = 1; y <= 3; y++) {
            m.put(g.offset(-2, y, 0), post);
            m.put(g.offset( 2, y, 0), post);
            m.put(g.offset(-2, y, 2), post);
            m.put(g.offset( 2, y, 2), post);
        }

        // ---- Tarp roof (white wool) ----
        BlockState tarp = Blocks.WHITE_WOOL.defaultBlockState();

        // main roof at y+3 (a flat tarp)
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = 0; dz <= 2; dz++) {
                m.put(g.offset(dx, 3, dz), tarp);
            }
        }

        // front overhang lip (sticks out 1 block toward -Z like the pic)
        for (int dx = -2; dx <= 2; dx++) {
            m.put(g.offset(dx, 3, -1), tarp);
        }

        // ---- Front flap/panel (the “hanging” sheet) ----
        // a 3-wide panel hanging down from the overhang
        for (int dx = -1; dx <= 1; dx++) {
            m.put(g.offset(dx, 2, -1), tarp);
        }

        // ---- Side “bulge” / extra sheet like the screenshot ----
        // right-side protrusion at y+2..3
        m.put(g.offset(3, 3, 1), tarp);
        m.put(g.offset(3, 2, 1), tarp);

        // ---- Interior props (barrel + lantern) ----
        m.put(g.offset(1, 1, 1), Blocks.BARREL.defaultBlockState());
        m.put(g.offset(1, 2, 1), Blocks.LANTERN.defaultBlockState());

        // ---- Bedroll (carpet) ----
        m.put(g.offset(-1, 1, 1), Blocks.WHITE_CARPET.defaultBlockState());
        m.put(g.offset( 0, 1, 1), Blocks.LIGHT_BLUE_CARPET.defaultBlockState());
        m.put(g.offset(-1, 1, 2), Blocks.WHITE_CARPET.defaultBlockState());
        m.put(g.offset( 0, 1, 2), Blocks.WHITE_CARPET.defaultBlockState());

        // Optional: a little "mat" outside front
        m.put(g.offset(0, 1, -2), Blocks.WHITE_CARPET.defaultBlockState());

        return m;
    }


    private static Map<BlockPos, BlockState> militaryCamp(BlockPos g) {
        Map<BlockPos, BlockState> m = new HashMap<>();

        // larger tent footprint
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                m.put(g.offset(dx, 1, dz), Blocks.WHITE_WOOL.defaultBlockState());
            }
        }

        // banner pole
        m.put(g.offset(3,1,0), Blocks.SPRUCE_FENCE.defaultBlockState());
        m.put(g.offset(3,2,0), Blocks.SPRUCE_FENCE.defaultBlockState());
        m.put(g.offset(3,3,0), Blocks.LANTERN.defaultBlockState());

        // campfire + supplies (fix: campfire at y+1, not floating)
        m.put(g.offset(-3,1,0), Blocks.CAMPFIRE.defaultBlockState());
        m.put(g.offset(-3,1,1), Blocks.BARREL.defaultBlockState());
        m.put(g.offset(-3,1,-1), Blocks.CHEST.defaultBlockState());

        return m;
    }

    /**
     * Main API ScriptedAmbientEvent calls.
     * baseAir is typically y = heightmap result (AIR ABOVE ground). We convert to ground center with baseAir.below().
     */
    public static PropPlacement place(String propId, ServerLevel level, BlockPos baseAir, int ttlTicks) {
        Kind kind = fromId(propId);
        if (kind == null) return null;

        // ScriptedAmbientEvent passes a heightmap Y (air position),
        // your prop templates assume "center" is ground-level (you place blocks at y+1 etc).
        BlockPos centerGround = baseAir.below();

        // Placement checks (tune freely)
        int halfX;
        int halfZ;
        int airHeight = 4;

        switch (kind) {
            case SMALL_CAMP -> { halfX = 4; halfZ = 4; }
            case SHRINE -> { halfX = 3; halfZ = 3; }
            case CART -> { halfX = 4; halfZ = 3; }
            case HUNTER_TENT -> { halfX = 4; halfZ = 4; }
            case MILITARY_CAMP -> { halfX = 8; halfZ = 6; }
            default -> { halfX = 4; halfZ = 4; }
        }

       // how flat do we require + how we choose the flat plane
        int maxVariation = 4; // your rule

        Map<BlockPos, BlockState> terrainPatch =
                AmbientPropUtil.buildTerrainGradePatch(level, centerGround, halfX, halfZ, maxVariation, airHeight);

        // null means "reject placement" (too uneven / fluids / block entities / etc.)
        if (terrainPatch == null) return null;

        if (!terrainPatch.isEmpty()) {
            System.out.println("[AmbientProps] grading edits=" + terrainPatch.size()
                    + " kind=" + kind + " center=" + centerGround);
        }

        // Now build the prop blocks as usual
        Map<BlockPos, BlockState> blocks = build(kind, centerGround);
        if (blocks == null || blocks.isEmpty()) return null;

        // Merge: terrain first, then props overwrite terrain where they overlap
        Map<BlockPos, BlockState> merged = new HashMap<>(terrainPatch.size() + blocks.size());
        merged.putAll(terrainPatch);
        merged.putAll(blocks);

        UUID sceneId = AmbientPropManager.place(level, merged, ttlTicks);

        // --- Plague cart corpse (entity) ---
        if (kind == Kind.PLAGUE_CART) {
            // Your cart bed is at y=2 relative to ground centerGround.
            // centerGround is ground level, so bed surface is roughly centerGround.getY() + 2.
            double sx = centerGround.getX() + 0.5;
            double sy = centerGround.getY() + 2.05 + 0.5; // slight lift so it rests on the bed
            double sz = centerGround.getZ() + 1.5;  // centered between your two load tiles (z=1 and z=2)
            double[] xOffsets = { 0.0, -0.6, 0.6 };

            for (double xo : xOffsets) {
                Skeleton sk = EntityType.SKELETON.create(level, null);
                if (sk == null) continue;

                sk.setPos(sx + xo, sy, sz);

                sk.setYRot(90f);
                sk.setXRot(90f);
                sk.yHeadRot = sk.getYRot();
                sk.yBodyRot = sk.getYRot();

                sk.setNoAi(true);
                sk.noPhysics = true;
                sk.setNoGravity(true);
                sk.setSilent(true);
                sk.setInvulnerable(true);
                sk.setPersistenceRequired();

                // absolutely stop burning
                sk.addEffect(new MobEffectInstance(
                        MobEffects.FIRE_RESISTANCE,
                        Integer.MAX_VALUE,
                        0,
                        true,
                        false
                ));
                sk.setRemainingFireTicks(0);

                // tag to despawn with prop
                sk.addTag("ambient_scene:" + sceneId);

                // clear equipment
                sk.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                sk.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                sk.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
                sk.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
                sk.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
                sk.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);

                sk.setPose(Pose.SLEEPING);

                level.addFreshEntity(sk);
                AmbientPropManager.registerEntity(sceneId, sk);

                // kill fire tick that happens right after spawn
                level.getServer().execute(() -> {
                    if (!sk.isRemoved()) sk.setRemainingFireTicks(0);
                });
            }


        }


        // NPC orbit anchor
        BlockPos anchor = centerGround.above(1);
        return new PropPlacement(sceneId, anchor);
    }

}
