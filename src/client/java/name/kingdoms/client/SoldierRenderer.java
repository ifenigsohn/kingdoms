package name.kingdoms.client;
import name.kingdoms.entity.SoldierSkins;
import name.kingdoms.Kingdoms;
import name.kingdoms.entity.SoldierEntity;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.ResourceLocation;

import net.minecraft.client.model.HumanoidModel.ArmPose;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemUseAnimation;

public final class SoldierRenderer extends HumanoidMobRenderer<
        SoldierEntity,
        SoldierRenderer.SoldierRenderState,
        HumanoidModel<SoldierRenderer.SoldierRenderState>
        > {


    public SoldierRenderer(EntityRendererProvider.Context ctx) {
        // Player model layer usually plays nicer with humanoid poses than Zombie
        super(ctx, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER)), 0.5f);
    }

    public static final class SoldierRenderState extends HumanoidRenderState {
        public boolean enemy;
        public boolean bannerman;
        public int skinId;
    }

    @Override
    public SoldierRenderState createRenderState() {
        return new SoldierRenderState();
    }

    @Override
    public void extractRenderState(SoldierEntity entity, SoldierRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);

        state.enemy = (entity.getSide() == SoldierEntity.Side.ENEMY);
        state.bannerman = entity.isBannerman();
        state.skinId = entity.getSkinId();

        // ------------------------------------------------------------
        // IMPORTANT: drive melee swing animation
        // HumanoidModel melee swing uses renderState.attackTime + attackArm
        // ------------------------------------------------------------
        state.attackTime = entity.getAttackAnim(partialTick);
        state.attackArm = HumanoidArm.RIGHT;   // your soldiers always swing mainhand; keep it simple
        state.mainArm = HumanoidArm.RIGHT;

        // ------------------------------------------------------------
        // Bow draw pose (uses ArmPose, not attackTime)
        // ------------------------------------------------------------
        if (entity.isUsingItem()
                && entity.getUseItem() != null
                && entity.getUseItem().getUseAnimation() == ItemUseAnimation.BOW) {
            state.rightArmPose = ArmPose.BOW_AND_ARROW;
            state.leftArmPose  = ArmPose.BOW_AND_ARROW;
        } else {
            // For swords/normal items, ITEM pose is fine.
            // The actual swing comes from attackTime above.
            state.rightArmPose = ArmPose.ITEM;
            state.rightArmPose = ArmPose.ITEM;
            state.leftArmPose  = entity.getOffhandItem().isEmpty() ? ArmPose.EMPTY : ArmPose.ITEM;

        }
    }

    @Override
    public ResourceLocation getTextureLocation(SoldierRenderState state) {
        return SoldierSkins.tex(state.skinId);
    }


}
