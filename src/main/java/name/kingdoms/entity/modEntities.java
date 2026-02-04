package name.kingdoms.entity;

import name.kingdoms.Kingdoms;
import name.kingdoms.entity.ai.aiKingdomNPCEntity;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class modEntities {

    public static final ResourceLocation SOLDIER_ID =
            ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "soldier");

    public static final ResourceKey<EntityType<?>> SOLDIER_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE, SOLDIER_ID);

    public static final EntityType<SoldierEntity> SOLDIER = FabricEntityTypeBuilder
            .create(MobCategory.MISC, SoldierEntity::new)
            .dimensions(EntityDimensions.fixed(0.6f, 1.95f))
            .trackRangeBlocks(80)
            .trackedUpdateRate(3)
            .build(SOLDIER_KEY);

    public static final ResourceLocation AI_KINGDOM_NPC_ID =
            ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "ai_kingdom_npc");

    public static final ResourceKey<EntityType<?>> AI_KINGDOM_NPC_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE, AI_KINGDOM_NPC_ID);

    public static final EntityType<aiKingdomNPCEntity> AI_KINGDOM_NPC = FabricEntityTypeBuilder
            .create(MobCategory.CREATURE, aiKingdomNPCEntity::new)
            .dimensions(EntityDimensions.fixed(0.6f, 1.95f))
            .trackRangeBlocks(80)
            .trackedUpdateRate(3)
            .build(AI_KINGDOM_NPC_KEY);

    public static void register() {
        Registry.register(BuiltInRegistries.ENTITY_TYPE, SOLDIER_ID, SOLDIER);
        Registry.register(BuiltInRegistries.ENTITY_TYPE, AI_KINGDOM_NPC_ID, AI_KINGDOM_NPC);

        FabricDefaultAttributeRegistry.register(SOLDIER, SoldierEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(AI_KINGDOM_NPC, aiKingdomNPCEntity.createAttributes());
    }

    private modEntities() {}
}
