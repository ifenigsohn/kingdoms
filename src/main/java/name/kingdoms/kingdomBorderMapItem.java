package name.kingdoms;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

public class kingdomBorderMapItem extends Item {

    public kingdomBorderMapItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            // call client-only code safely
            Kingdoms.PROXY.openKingdomBordersMap();
        }
        return InteractionResult.SUCCESS;
    }
}
