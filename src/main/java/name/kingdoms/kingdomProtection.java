package name.kingdoms;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class kingdomProtection {
    private kingdomProtection() {}

    // Simple anti-spam: player -> last server tick they were warned
    private static final Map<UUID, Long> lastWarnTick = new HashMap<>();
    private static final long WARN_COOLDOWN_TICKS = 10; // 0.5s

    public static void register() {

        // --- Block breaking (hard enforcement) ---
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!(world instanceof ServerLevel level)) return true;

            if (canEdit(level, player.getUUID(), pos, player.isCreative(), player.hasPermissions(2))) {
                return true;
            }

            warn(player.getUUID(), level.getServer().getTickCount(),
                    () -> player.displayClientMessage(Component.literal("You can't break blocks in another kingdom."), true));

            return false;
        });

        // --- Block breaking (prevents “crack animation” / start-damage) ---
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!(world instanceof ServerLevel level)) return InteractionResult.PASS;

            if (canEdit(level, player.getUUID(), pos, player.isCreative(), player.hasPermissions(2))) {
                return InteractionResult.PASS;
            }

            warn(player.getUUID(), level.getServer().getTickCount(),
                    () -> player.displayClientMessage(Component.literal("That land belongs to another kingdom."), true));

            return InteractionResult.FAIL;
        });

        // --- Block placing (covers normal right-click placement) ---
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(world instanceof ServerLevel level)) return InteractionResult.PASS;

            ItemStack held = player.getItemInHand(hand);
            if (!(held.getItem() instanceof BlockItem)) return InteractionResult.PASS;

            // Placement usually happens at the adjacent block in the clicked face direction
            BlockPos placePos = hit.getBlockPos().relative(hit.getDirection());

            if (canEdit(level, player.getUUID(), placePos, player.isCreative(), player.hasPermissions(2))) {
                return InteractionResult.PASS;
            }

            warn(player.getUUID(), level.getServer().getTickCount(),
                    () -> player.displayClientMessage(Component.literal("You can't build in another kingdom."), true));

            return InteractionResult.FAIL;
        });
    }

    private static void warn(UUID playerId, long nowTick, Runnable send) {
        long last = lastWarnTick.getOrDefault(playerId, -9999L);
        if (nowTick - last >= WARN_COOLDOWN_TICKS) {
            lastWarnTick.put(playerId, nowTick);
            send.run();
        }
    }

    /**
     * Rules:
     * - Unclaimed land: allowed
     * - Claimed land:
     *    - allowed if player belongs to that kingdom (same kingdom id)
     *    - otherwise denied (includes AI kingdoms)
     * - Optional bypass: creative + permission level 2
     */
    private static boolean canEdit(ServerLevel level, UUID playerId, BlockPos pos, boolean creative, boolean isOpLevel2) {
        // Optional bypass for ops in creative (tweak if you want)
        if (creative && isOpLevel2) return true;

        var ks = kingdomState.get(level.getServer());
        var at = ks.getKingdomAt(level, pos);

        // Unclaimed land: OK
        if (at == null) return true;

        // If player has no kingdom, they can't edit claimed land
        var pk = ks.getPlayerKingdom(playerId);
        if (pk == null) return false;

        // Same kingdom = OK
        return at.id.equals(pk.id);
    }
}