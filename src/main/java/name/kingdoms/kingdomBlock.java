package name.kingdoms;

import name.kingdoms.payload.openKingdomMenuPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class kingdomBlock extends Block {

    public kingdomBlock(Properties props) {
        super(props);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        if (level.isClientSide()) return;
        if (!(level instanceof ServerLevel sl)) return;
        if (!(placer instanceof ServerPlayer sp)) return;

        var ks = kingdomState.get(sl.getServer());
        var k = ks.getPlayerKingdom(sp.getUUID());
        if (k == null) return; // placing block doesn't create a kingdom

        if (!k.hasTerminal) {
            ks.claimCellForKingdom(level, k, pos);
            ks.bindTerminal(sl, k, pos);
            sp.displayClientMessage(Component.literal("This is now your Kingdom Block."), false);

            // Spawn retinue when terminal is first bound
            RetinueSpawner.ensureRetinue(sl, sp, k);

            ks.markDirty();
        } else {
            sp.displayClientMessage(Component.literal("You already have a Kingdom Block placed."), false);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                              Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel sl)) return InteractionResult.CONSUME;

        var ks = kingdomState.get(sl.getServer());

        // Is this block already associated with some kingdom (by claim)?
        var at = ks.getKingdomAt(sl, pos);

        // If unclaimed: allow opening menu so player can CREATE
        if (at == null) {
            if (player instanceof ServerPlayer sp) {
                ServerPlayNetworking.send(sp, new openKingdomMenuPayload(pos));
            }
            return InteractionResult.CONSUME;
        }

        // Claimed by a kingdom: only the owner can use it (and only if it's their terminal)
        if (!at.owner.equals(player.getUUID())) {
            player.displayClientMessage(Component.literal("This Kingdom Block belongs to someone else."), false);
            return InteractionResult.CONSUME;
        }

        // Owner: must be their terminal block
        if (!ks.isTerminal(sl, at, pos)) {
            player.displayClientMessage(Component.literal("This is not your active Kingdom Block."), false);
            return InteractionResult.CONSUME;
        }

        // Ensure retinue exists whenever the real terminal is used
        if (player instanceof ServerPlayer sp) {
            RetinueSpawner.ensureRetinue(sl, sp, at);
            ks.markDirty();
            ServerPlayNetworking.send(sp, new openKingdomMenuPayload(pos));
        }

        return InteractionResult.CONSUME;
    }
}
