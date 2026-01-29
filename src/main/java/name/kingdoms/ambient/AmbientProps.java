package name.kingdoms.ambient;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
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
        CART("cart"),
        HUNTER_TENT("hunter_tent"),
        MILITARY_CAMP("military_camp");

        public final String id;
        Kind(String id) { this.id = id; }

        public static Kind fromId(String id) {
            if (id == null) return null;
            String s = id.trim().toLowerCase(Locale.ROOT);

            // aliases so you don’t have to rename old event defs
            return switch (s) {
                case "camp_basic", "camp_small", "small_camp" -> SMALL_CAMP;
                case "shrine", "roadside_shrine" -> SHRINE;
                case "cart" -> CART;
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
            case CART -> cart(centerGround);
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

    private static Map<BlockPos, BlockState> cart(BlockPos g) {
    Map<BlockPos, BlockState> m = new HashMap<>();

    // ------------------------------------------------------------------
    // Layout notes:
    // - Bed is a 3x2 rectangle at y+1 (thin floor)
    // - We'll treat +X as the "hitch" side for now (you can rotate later)
    // ------------------------------------------------------------------

    int y = 1;

    BlockState bed = Blocks.SPRUCE_SLAB.defaultBlockState();

    // Bed floor: x = -1..1, z = 0..1  (3x2)
    for (int dx = -1; dx <= 1; dx++) {
        for (int dz = 0; dz <= 1; dz++) {
            m.put(g.offset(dx, y, dz), bed);
        }
    }

    // Corner posts (fence posts up 2 high like the screenshot corners)
    BlockState post = Blocks.SPRUCE_FENCE.defaultBlockState();
        m.put(g.offset(-1, y, 0), post);
        m.put(g.offset(-1, y+1, 0), post);

        m.put(g.offset(-1, y, 1), post);
        m.put(g.offset(-1, y+1, 1), post);

        m.put(g.offset( 1, y, 0), post);
        m.put(g.offset( 1, y+1, 0), post);

        m.put(g.offset( 1, y, 1), post);
        m.put(g.offset( 1, y+1, 1), post);

        // Side boards: open trapdoors standing vertical around the bed
        // When OPEN=true, trapdoors stand up (vertical). Facing is fiddly; this is a good starting point.
        BlockState tdNorth = Blocks.SPRUCE_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.OPEN, true)
                .setValue(TrapDoorBlock.HALF, Half.BOTTOM)
                .setValue(TrapDoorBlock.FACING, Direction.NORTH);

        BlockState tdSouth = Blocks.SPRUCE_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.OPEN, true)
                .setValue(TrapDoorBlock.HALF, Half.BOTTOM)
                .setValue(TrapDoorBlock.FACING, Direction.SOUTH);

        BlockState tdWest = Blocks.SPRUCE_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.OPEN, true)
                .setValue(TrapDoorBlock.HALF, Half.BOTTOM)
                .setValue(TrapDoorBlock.FACING, Direction.WEST);

        BlockState tdEast = Blocks.SPRUCE_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.OPEN, true)
                .setValue(TrapDoorBlock.HALF, Half.BOTTOM)
                .setValue(TrapDoorBlock.FACING, Direction.EAST);

        // Long sides (north/south edges of the bed rectangle)
        // north edge is z = -1 line adjacent to bed z=0
        for (int dx = -1; dx <= 1; dx++) m.put(g.offset(dx, y, -1), tdSouth);
        // south edge is z = 2 line adjacent to bed z=1
        for (int dx = -1; dx <= 1; dx++) m.put(g.offset(dx, y,  2), tdNorth);

        // Short sides (west/east edges)
        // west edge is x = -2 line adjacent to bed x=-1
        for (int dz = 0; dz <= 1; dz++) m.put(g.offset(-2, y, dz), tdEast);
        // east edge is x = 2 line adjacent to bed x=1
        for (int dz = 0; dz <= 1; dz++) m.put(g.offset( 2, y, dz), tdWest);

        // Wheels: sideways logs at ground level just under the bed
        // Put them at y (same as bed) - 1 => ground+0
        BlockState wheelX = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState()
                .setValue(RotatedPillarBlock.AXIS, net.minecraft.core.Direction.Axis.X);

        // two wheels on each side, roughly centered
        m.put(g.offset(0, 0, 0), wheelX);
        m.put(g.offset(0, 0, 1), wheelX);

        // Undercarriage beam (optional, helps sell the cart)
        BlockState beam = Blocks.SPRUCE_SLAB.defaultBlockState();
        m.put(g.offset(-1, 0, 0), beam);
        m.put(g.offset( 0, 0, 0), beam);
        m.put(g.offset( 1, 0, 0), beam);

        // Hitch/handle: fences extending out on +X, with a little gate look
        m.put(g.offset(3, y, 0), post);
        m.put(g.offset(3, y, 1), post);

        // A "rail" between them (fence gate reads like the screenshot tail/side rail)
        BlockState gate = Blocks.SPRUCE_FENCE_GATE.defaultBlockState()
                .setValue(FenceGateBlock.FACING, Direction.EAST)
                .setValue(FenceGateBlock.OPEN, false);
        m.put(g.offset(2, y, 0), gate);

        // Load: hay bales on top of the bed (like the pic)
        m.put(g.offset(0, y+1, 0), Blocks.HAY_BLOCK.defaultBlockState());
        m.put(g.offset(0, y+1, 1), Blocks.HAY_BLOCK.defaultBlockState());

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
        int maxVariation = 4;            // your rule
        int flatY = centerGround.getY(); // flatten to the sampled center ground level

        Map<BlockPos, BlockState> terrainPatch =
           AmbientPropUtil.buildTerrainGradePatch(level, centerGround, halfX, halfZ, maxVariation, flatY);

        if (!terrainPatch.isEmpty()) {
            System.out.println("[AmbientProps] grading edits=" + terrainPatch.size()
                    + " kind=" + kind + " center=" + centerGround);
        }
    

        if (terrainPatch == null) {
            return null;
        }

        // Now build the prop blocks as usual
        Map<BlockPos, BlockState> blocks = build(kind, centerGround);
        if (blocks == null || blocks.isEmpty()) return null;

        // Merge: terrain first, then props overwrite terrain where they overlap
        Map<BlockPos, BlockState> merged = new HashMap<>(terrainPatch.size() + blocks.size());
        merged.putAll(terrainPatch);
        merged.putAll(blocks);

        UUID sceneId = AmbientPropManager.place(level, merged, ttlTicks);

        // NPC orbit anchor
        BlockPos anchor = centerGround.above(1);
        return new PropPlacement(sceneId, anchor);
    }

}
