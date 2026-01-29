package name.kingdoms.client;

import name.kingdoms.Kingdoms;
import name.kingdoms.entity.ai.RoadAmbientNPCEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.ResourceLocation;

public class RoadAmbientNPCRenderer
        extends HumanoidMobRenderer<RoadAmbientNPCEntity, RoadAmbientNPCRenderer.NPCState, HumanoidModel<RoadAmbientNPCRenderer.NPCState>> {

    public static class NPCState extends HumanoidRenderState {
        public String aiTypeId = "soldier";
        public int skinId = 0;
    }

    public RoadAmbientNPCRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER)), 0.5f);
    }

    @Override public NPCState createRenderState() { return new NPCState(); }

    @Override
    public void extractRenderState(RoadAmbientNPCEntity entity, NPCState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        String t = entity.getAiTypeId();
        state.aiTypeId = (t == null || t.isBlank()) ? "soldier" : t;
        state.skinId = Math.max(0, entity.getSkinId());
    }

    @Override
    public ResourceLocation getTextureLocation(NPCState state) {
        String type = (state.aiTypeId == null || state.aiTypeId.isBlank()) ? "soldier" : state.aiTypeId;

        // match your existing convention
        if ("scout".equals(type) || "guard".equals(type)) type = "soldier";

        int skin = Math.max(0, state.skinId);

        return ResourceLocation.fromNamespaceAndPath(
                Kingdoms.MOD_ID,
                "textures/entity/" + type + "/" + type + "_" + skin + ".png"
        );
    }
}
