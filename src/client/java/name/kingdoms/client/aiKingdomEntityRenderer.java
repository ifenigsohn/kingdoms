package name.kingdoms.client;

import name.kingdoms.Kingdoms;
import name.kingdoms.entity.aiKingdomEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.ResourceLocation;

public class aiKingdomEntityRenderer
        extends HumanoidMobRenderer<aiKingdomEntity, aiKingdomEntityRenderer.KingState,
        HumanoidModel<aiKingdomEntityRenderer.KingState>> {




    public static class KingState extends HumanoidRenderState {
        public int skinId = 0;
    }

    public aiKingdomEntityRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER)), 0.5f);
    }

    @Override
    public KingState createRenderState() {
        return new KingState();
    }

    @Override
    public void extractRenderState(aiKingdomEntity entity, KingState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.skinId = entity.getSkinId();
    }
    
    

    @Override
    public ResourceLocation getTextureLocation(KingState state) {
        // textures/entity/king/king_<skinId>.png
        return ResourceLocation.fromNamespaceAndPath(
                Kingdoms.MOD_ID,
                "textures/entity/king/king_" + state.skinId + ".png"
        );
    }
}
