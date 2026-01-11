package name.kingdoms;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class treasuryBlock extends Block {

    public treasuryBlock(Properties props) {
        super(props);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hit
    ) {
        // CLIENT: do nothing
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        if (!(level instanceof ServerLevel sl) || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.CONSUME;
        }

        kingdomState ks = kingdomState.get(sl.getServer());
        kingdomState.Kingdom atPos = ks.getKingdomAt(sl, pos);
        kingdomState.Kingdom owned = ks.getPlayerKingdom(sp.getUUID());

        // Case 1: outside all borders
        if (atPos == null) {
            sp.sendSystemMessage(Component.literal("Not connected to a kingdom."));
            return InteractionResult.CONSUME;
        }

        // Case 2: inside someone else's kingdom
        if (owned == null || !atPos.id.equals(owned.id)) {
            sp.sendSystemMessage(Component.literal("This is not your kingdom!"));
            return InteractionResult.CONSUME;
        }

        // Case 3: inside your kingdom → open
        kingdomsClientProxy.openTreasury(sp, pos);
        return InteractionResult.CONSUME;
    }
}
