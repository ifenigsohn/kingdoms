package name.kingdoms.client;

import name.kingdoms.WarCommandItem;
import name.kingdoms.payload.warCommandCycleGroupC2SPayload;
import name.kingdoms.payload.warCommandMoveOrderC2SPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;

import net.minecraft.world.phys.HitResult;

public final class WarCommandClientHooks {
    private WarCommandClientHooks() {}

    // Keep this <= server MAX_ORDER_DIST (your WarBattleManager uses 50.0 right now)
    private static final double LONG_RAY_DIST = 50.0;

    private static boolean prevAttackDown = false;
    private static boolean prevUseDown = false;

    public static void init() {
        // Left-click air detection (MISS) via key state
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            boolean holding = isHoldingCommandItem(client.player);
            boolean attackDown = client.options.keyAttack.isDown();
            boolean useDown = client.options.keyUse.isDown();

            // LEFT CLICK (attack) edge
            if (holding && attackDown && !prevAttackDown) {
                if (client.hitResult == null || client.hitResult.getType() == HitResult.Type.MISS) {
                    ClientPlayNetworking.send(new warCommandCycleGroupC2SPayload());
                    client.player.swing(InteractionHand.MAIN_HAND);
                }
            }

            // RIGHT CLICK edge (long-range move only when vanilla thinks you're clicking air)
            if (holding && useDown && !prevUseDown) {
                if (!client.player.isShiftKeyDown()
                        && (client.hitResult == null || client.hitResult.getType() == HitResult.Type.MISS)) {

                    HitResult hr = client.player.pick(LONG_RAY_DIST, 0.0F, false);
                    if (hr.getType() == HitResult.Type.BLOCK) {
                        BlockPos pos = ((BlockHitResult) hr).getBlockPos();
                        ClientPlayNetworking.send(new warCommandMoveOrderC2SPayload(pos));
                        client.player.swing(InteractionHand.MAIN_HAND);
                    }
                }
            }

            prevAttackDown = attackDown;
            prevUseDown = useDown;
        });

        // Left-click block → cycle + cancel breaking
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (!level.isClientSide()) return InteractionResult.PASS;
            if (!isHoldingCommandItem(player)) return InteractionResult.PASS;

            ClientPlayNetworking.send(new warCommandCycleGroupC2SPayload());
            player.swing(hand);
            return InteractionResult.FAIL;
        });

        // Left-click entity → cycle + cancel attacking
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (!level.isClientSide()) return InteractionResult.PASS;
            if (!isHoldingCommandItem(player)) return InteractionResult.PASS;

            ClientPlayNetworking.send(new warCommandCycleGroupC2SPayload());
            player.swing(hand);
            return InteractionResult.FAIL;
        });
    }

    private static boolean isHoldingCommandItem(Player p) {
        return p.getMainHandItem().getItem() instanceof WarCommandItem
            || p.getOffhandItem().getItem() instanceof WarCommandItem;
    }
}
