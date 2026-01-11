package name.kingdoms.menu;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

public class diploMenu extends AbstractContainerMenu {

    public static final MenuType<diploMenu> TYPE =
            new MenuType<>(diploMenu::new, FeatureFlags.VANILLA_SET);

    // Context (filled in when opening from payload)
    private int targetEntityId = -1;        // server lookup
    private UUID targetEntityUuid = null;   // client lookup if you need it
    private String kingdomName = "Unknown Kingdom";
    private int relation = 0;


    private UUID targetKingdomId = null;
    public UUID getTargetKingdomId() { return targetKingdomId; }


    // Convenient alias for screens/buttons (same as targetEntityId)
    public int kingEntityId = -1;

    public diploMenu(int syncId, Inventory inv) {
        super(TYPE, syncId);
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    /** Called by the client when opening the menu from opendiplomacyS2CPayload. */
    public void setContext(int entityId, UUID entityUuid, UUID kingdomId, String name, int relation) {
        this.targetEntityId = entityId;
        this.kingEntityId = entityId;

        this.targetEntityUuid = entityUuid;
        this.targetKingdomId = kingdomId;

        this.kingdomName = (name == null || name.isBlank()) ? "Unknown Kingdom" : name;
        this.relation = relation;
    }


    public int getTargetEntityId() { return targetEntityId; }
    public UUID getTargetEntityUuid() { return targetEntityUuid; }
    public String getKingdomName() { return kingdomName; }
    public int getRelation() { return relation; }

    /** Optional safety helper */
    public boolean hasTarget() { return targetEntityId >= 0; }
}
