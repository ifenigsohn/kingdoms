package name.kingdoms;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

import java.util.function.Consumer;

public class styledBlockItem extends BlockItem {

    private final Component desc;

    public styledBlockItem(Block block, Properties props, Component desc) {
        super(block, props);
        this.desc = desc;
    }

    @Override
    public Component getName(ItemStack stack) {
        return super.getName(stack).copy()
                .withStyle(ChatFormatting.DARK_BLUE, ChatFormatting.BOLD);
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            Item.TooltipContext ctx,
            TooltipDisplay display,
            Consumer<Component> consumer,
            TooltipFlag flag
    ) {
        super.appendHoverText(stack, ctx, display, consumer, flag);

        if (desc != null) {
            consumer.accept(desc.copy().withStyle(ChatFormatting.GRAY));
        }
    }
}
