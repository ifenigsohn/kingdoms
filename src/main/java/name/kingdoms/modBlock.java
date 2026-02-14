package name.kingdoms;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.function.Function;

public class modBlock {

    private static ResourceKey<Block> keyOfBlock(String name) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, name);
        return ResourceKey.create(Registries.BLOCK, id);
    }

    private static ResourceKey<Item> keyOfItem(String name) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, name);
        return ResourceKey.create(Registries.ITEM, id);
    }

    private static Block register(
            String name,
            Function<BlockBehaviour.Properties, Block> blockFactory,
            BlockBehaviour.Properties settings,
            boolean shouldRegisterItem
    ) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, name);

           settings = settings.noOcclusion();

        // ✅ CRITICAL: set block id BEFORE constructing the Block
        ResourceKey<Block> blockKey = keyOfBlock(name);
        Block block = blockFactory.apply(settings.setId(blockKey));

        // register block
        Registry.register(BuiltInRegistries.BLOCK, id, block);

        if (shouldRegisterItem) {
            Component desc = Component.translatable("tooltip." + Kingdoms.MOD_ID + "." + name);

            // ✅ also set item id on properties (same concept)
            ResourceKey<Item> itemKey = keyOfItem(name);
            Item.Properties itemProps = new Item.Properties().setId(itemKey);

            Item blockItem = new styledBlockItem(block, itemProps, desc);

            // register item (same id)
            Registry.register(BuiltInRegistries.ITEM, id, blockItem);
        }

        return block;
    }

    public static void initalize() {
        ItemGroupEvents
                .modifyEntriesEvent(CreativeModeTabs.BUILDING_BLOCKS)
                .register(entries -> {
                    entries.accept(kingdom_block);
                    entries.accept(ai_kingdom_kingspawner);
                    entries.accept(ai_kingdom_civilianspawner);
                    entries.accept(ai_kingdom_guardspawner);
                    entries.accept(ai_kingdom_noblespawner);
                });
    }

    public static final Block ai_kingdom_kingspawner = register(
            "ai_kingdom_kingspawner",
            props -> new kingdomKingSpawnerBlock(props, "king", 0),
            BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.STONE),
            true
    );

    public static final Block kingdom_block = register(
            "kingdom_block",
            kingdomBlock::new,
            BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.STONE),
            true
    );

    public static final Block grain_block = register(
            "grain_block",
            props -> new jobBlock(jobDefinition.FARM_JOB, props),
            BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.GRASS),
            true
    );

    public static final Block butcher_block = register(
            "butcher_block",
            props -> new jobBlock(jobDefinition.BUTCHER_JOB, props),
            BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.GRASS),
            true
    );

    public static final Block fish_block = register(
            "fish_block",
            props -> new jobBlock(jobDefinition.FISHING_JOB, props),
            BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.GRASS),
            true
    );

    public static final Block stable_block = register(
            "stable_block",
            props -> new jobBlock(jobDefinition.STABLE_JOB, props),
            BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.GRASS),
            true
    );

    public static final Block metal_block = register(
            "metal_block",
            props -> new jobBlock(jobDefinition.METAL_JOB, props),
            BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.GRASS),
            true
    );

    public static final Block gem_block = register(
            "gem_block",
            props -> new jobBlock(jobDefinition.GEM_JOB, props),
            BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.GRASS),
            true
    );

    public static final Block wood_block = register(
            "wood_block",
            props -> new jobBlock(jobDefinition.WOOD_JOB, props),
            BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.GRASS),
            true
    );

    public static final Block alchemy_block = register(
            "alchemy_block",
            props -> new jobBlock(jobDefinition.ALCHEMY_JOB, props),
            BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.GRASS),
            true
    );

    public static final Block weapon_block = register(
            "weapon_block",
            props -> new jobBlock(jobDefinition.WEAPON_JOB, props),
            BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.GRASS).noOcclusion(),
            true
    );

    public static final Block armor_block = register(
            "armor_block",
            props -> new jobBlock(jobDefinition.ARMOR_JOB, props),
            BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.GRASS),
            true
    );

    public static final Block guard_block = register(
            "guard_block",
            props -> new jobBlock(jobDefinition.GUARD_JOB, props),
            BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.GRASS),
            true
    );

    public static final Block training_block = register(
            "training_block",
            props -> new jobBlock(jobDefinition.TRAINING_JOB, props),
            BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.GRASS),
            true
    );

    public static final Block garrison_block = register(
            "garrison_block",
            props -> new jobBlock(jobDefinition.GARRISON_JOB, props),
            BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.GRASS),
            true
    );

    public static final Block chapel_block = register(
            "chapel_block",
            props -> new jobBlock(jobDefinition.CHAPEL_JOB, props),
            BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.GRASS),
            true
    );

    public static final Block tavern_block = register(
            "tavern_block",
            props -> new jobBlock(jobDefinition.TAVERN_JOB, props),
            BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.GRASS),
            true
    );

    public static final Block shop_block = register(
            "shop_block",
            props -> new jobBlock(jobDefinition.SHOP_JOB, props),
            BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.GRASS),
            true
    );

    public static final Block nobility_block = register(
            "nobility_block",
            props -> new jobBlock(jobDefinition.NOBILITY_JOB, props),
            BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.GRASS),
            true
    );

    public static final Block treasure_block = register(
            "treasure_block",
            treasuryBlock::new,
            BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.GRASS),
            true
    );

    public static final Block ai_kingdom_civilianspawner = register(
            "ai_kingdom_civilianspawner",
            props -> new AIkingdomNPCSpawnerBlock(props, "villager", -1, 10, 64, 20),
            BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.STONE),
            true
    );

    public static final Block ai_kingdom_guardspawner = register(
            "ai_kingdom_guardspawner",
            props -> new AIkingdomNPCSpawnerBlock(props, "guard", -1, 10, 64, 20),
            BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.STONE),
            true
    );

    public static final Block ai_kingdom_noblespawner = register(
            "ai_kingdom_noblespawner",
            props -> new AIkingdomNPCSpawnerBlock(props, "noble", -1, 10, 64, 20),
            BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.STONE),
            true
    );

        public static final Block envoy_block = register(
                "envoy_block",
                props -> new jobBlock(jobDefinition.ENVOY_JOB, props),
                BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.STONE),
                true
        );


}
