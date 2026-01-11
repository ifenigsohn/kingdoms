package name.kingdoms.client;

import name.kingdoms.Kingdoms;
import name.kingdoms.entity.kingdomWorkerEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.ResourceLocation;

public class kingdomWorkerRenderer
        extends HumanoidMobRenderer<kingdomWorkerEntity, kingdomWorkerRenderer.WorkerState, HumanoidModel<kingdomWorkerRenderer.WorkerState>> {

    public static class WorkerState extends HumanoidRenderState {
        public String jobId = "unknown";
    }

    public kingdomWorkerRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER)), 0.5f);
    }

    @Override
    public WorkerState createRenderState() {
        return new WorkerState();
    }

    @Override
    public void extractRenderState(kingdomWorkerEntity entity, WorkerState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.jobId = entity.getJobId();
    }

    @Override
    public ResourceLocation getTextureLocation(WorkerState state) {
        // textures/entity/worker/<jobId>.png
        // ex: textures/entity/worker/farm.png
        String id = (state.jobId == null || state.jobId.isBlank()) ? "unknown" : state.jobId;

        return ResourceLocation.fromNamespaceAndPath(
                Kingdoms.MOD_ID,
                "textures/entity/worker/" + id + ".png"
        );
    }
}
