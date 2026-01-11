package name.kingdoms;

import com.google.common.base.Function;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

public class modItem {
    public static final String MOD_ID = "kingdoms";

    public static Item register(String name, Function<Item.Properties, Item> itemFactory, Item.Properties settings) {
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(MOD_ID, name));
        Item item = itemFactory.apply(settings.setId(itemKey));
        Registry.register(BuiltInRegistries.ITEM, itemKey, item);
        return item;
    }

    public static final int KING_HEAD_MAX = kingSkinPoolState.MAX_SKIN_ID;
    public static final Item[] KING_HEADS = new Item[KING_HEAD_MAX + 1];

    static {
        for (int i = 0; i <= KING_HEAD_MAX; i++) {
            final int idx = i;
            final String id = "king_head_" + i;
            KING_HEADS[idx] = register(id, Item::new, new Item.Properties());
        }
    }

    public static final Item BORDER_WAND =
            register("border_wand", Item::new, new Item.Properties().stacksTo(1));

    public static final Item KINGDOM_MAP =
            register("kingdom_map", kingdomBorderMapItem::new, new Item.Properties().stacksTo(1));

    public static final Item WAR_COMMAND =
            register("war_command", WarCommandItem::new, new Item.Properties().stacksTo(1));

    public static void initalize() {
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.INGREDIENTS)
                .register((itemGroup) -> {
                    itemGroup.accept(BORDER_WAND);
                    itemGroup.accept(KINGDOM_MAP);
                    itemGroup.accept(WAR_COMMAND);
                });
    }
}