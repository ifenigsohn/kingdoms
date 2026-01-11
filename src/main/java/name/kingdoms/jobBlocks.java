package name.kingdoms;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public final class jobBlocks {
    private jobBlocks() {}

    public static ItemStack stackFor(String jobId, int qty) {
        Block block = switch (jobId) {

            // food / basic
            case "farm"     -> modBlock.grain_block;
            case "butcher"  -> modBlock.butcher_block;
            case "fishing"  -> modBlock.fish_block;

            // production
            case "wood"     -> modBlock.wood_block;
            case "metal"    -> modBlock.metal_block;
            case "gem"      -> modBlock.gem_block;

            // crafting
            case "alchemy"  -> modBlock.alchemy_block;
            case "weapon"   -> modBlock.weapon_block;
            case "armor"    -> modBlock.armor_block;

            // population / military
            case "stable"   -> modBlock.stable_block;
            case "guard"    -> modBlock.guard_block;
            case "training" -> modBlock.training_block;
            case "garrison" -> modBlock.garrison_block;

            // civic
            case "chapel"   -> modBlock.chapel_block;
            case "tavern"   -> modBlock.tavern_block;
            case "shop"     -> modBlock.shop_block;
            case "nobility" -> modBlock.nobility_block;

            default -> Blocks.AIR;
        };

        return new ItemStack(block.asItem(), Math.max(1, qty));
    }
}
