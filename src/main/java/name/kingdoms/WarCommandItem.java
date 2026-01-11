package name.kingdoms;

import name.kingdoms.war.WarBattleManager;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

public class WarCommandItem extends Item {

    /** Stored in CUSTOM_DATA as int. */
    public static final String TAG_GROUP = "KCmdGroup";
    public static final int GROUP_FOOTMEN = 0;
    public static final int GROUP_ARCHERS = 1;
    public static final int GROUP_BOTH = 2;

    public WarCommandItem(Properties props) {
        super(props);
    }

    // Right-click on a block = MOVE order
    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        Player player = ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;

        if (!level.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer sp) {
            // IMPORTANT: shift should FOLLOW even if aiming at a block
            if (player.isShiftKeyDown()) {
                WarBattleManager.issueFollowOrder(sp);
                return InteractionResult.SUCCESS;
            }

            boolean ok = WarBattleManager.issueMoveOrder(sp, ctx.getClickedPos());
            return ok ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        }

        return InteractionResult.SUCCESS;
    }


    // Right-click in air: Shift = FOLLOW/regroup
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer sp) {
            if (player.isShiftKeyDown()) {
                WarBattleManager.issueFollowOrder(sp);
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            Item.TooltipContext context,
            TooltipDisplay display,
            Consumer<Component> tooltip,
            TooltipFlag flag
    ) {
        int g = getGroup(stack);

        tooltip.accept(Component.literal("Selected: " + groupName(g)));
        tooltip.accept(Component.literal("Left-click: cycle FOOTMEN / ARCHERS / BOTH"));
        tooltip.accept(Component.literal("Right-click block: MOVE order"));
        tooltip.accept(Component.literal("Shift + Right-click: FOLLOW/regroup"));
    }

    @Override
    public Component getName(ItemStack stack) {
        int g = getGroup(stack);
        return super.getName(stack).copy()
                .append(Component.literal(" (" + groupName(g) + ")"));
    }

    public static int getGroup(ItemStack stack) {
        CustomData cd = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag tag = cd.copyTag();

        // Your mappings: getInt returns Optional<Integer> (your error showed that)
        int g = tag.getInt(TAG_GROUP).orElse(GROUP_FOOTMEN);

        if (g < 0 || g > 2) return GROUP_FOOTMEN;
        return g;
    }

    public static void setGroup(ItemStack stack, int group) {
        group = switch (group) {
            case GROUP_ARCHERS -> GROUP_ARCHERS;
            case GROUP_BOTH -> GROUP_BOTH;
            default -> GROUP_FOOTMEN;
        };

        CustomData cd = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag tag = cd.copyTag();
        tag.putInt(TAG_GROUP, group);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    public static String groupName(int g) {
        return switch (g) {
            case GROUP_ARCHERS -> "ARCHERS";
            case GROUP_BOTH -> "BOTH";
            default -> "FOOTMEN";
        };
    }
}
