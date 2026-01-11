package name.kingdoms.client;

import name.kingdoms.Kingdoms;
import name.kingdoms.entity.ai.aiKingdomNPCEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.ResourceLocation;

public class aiKingdomNPCRenderer
        extends HumanoidMobRenderer<aiKingdomNPCEntity, aiKingdomNPCRenderer.NPCState, HumanoidModel<aiKingdomNPCRenderer.NPCState>> {

    public static class NPCState extends HumanoidRenderState {
        public String aiTypeId = "villager";
        public int skinId = 0;
    }

    public aiKingdomNPCRenderer(EntityRendererProvider.Context ctx) {
        // Using PLAYER layer like your worker renderer
        super(ctx, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER)), 0.5f);
    }

    @Override
    public NPCState createRenderState() {
        return new NPCState();
    }

    @Override
    public void extractRenderState(aiKingdomNPCEntity entity, NPCState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);

        String t = entity.getAiTypeId();
        state.aiTypeId = (t == null || t.isBlank()) ? "villager" : t;

        state.skinId = Math.max(0, entity.getSkinId());
    }

    @Override
    public ResourceLocation getTextureLocation(NPCState state) {
        // Recommended layout:
        // assets/kingdoms/textures/entity/npc/<type>/<skin>.png
        // examples:
        // textures/entity/npc/villager/0.png
        // textures/entity/npc/guard/0.png
        // textures/entity/npc/noble/0.png
        String type = (state.aiTypeId == null || state.aiTypeId.isBlank()) ? "villager" : state.aiTypeId;
        int skin = Math.max(0, state.skinId);

        return ResourceLocation.fromNamespaceAndPath(
                Kingdoms.MOD_ID,
                "textures/entity/npc/" + type + "/" + skin + ".png"
        );
    }
}
