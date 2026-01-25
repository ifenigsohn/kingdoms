package name.kingdoms.client;

import com.mojang.blaze3d.vertex.PoseStack;

import name.kingdoms.Kingdoms;
import name.kingdoms.entity.ai.aiKingdomNPCEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.network.chat.Component;
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

    // --- Variant A: entity-based name tag hook (common in many mappings)
    protected void renderNameTag(aiKingdomNPCEntity entity, Component text, PoseStack poseStack,
                                MultiBufferSource buffers, int packedLight) {
        renderNameTagEntityMode(entity, text, poseStack, buffers, packedLight, Font.DisplayMode.NORMAL);
    }

    // --- Variant B: state-based name tag hook (some 1.21+ pipelines)
    protected void renderNameTag(NPCState state, Component text, PoseStack poseStack,
                                MultiBufferSource buffers, int packedLight) {
        // No entity available here; use a constant above-head offset.
        renderNameTagStateMode(text, poseStack, buffers, packedLight, Font.DisplayMode.NORMAL, 2.1D);
    }

   private void renderNameTagEntityMode(net.minecraft.world.entity.Entity entity, Component text,
                                        PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                                        Font.DisplayMode mode) {

        // Optional show rule: use the method that actually exists in your mappings
        // (MobRenderer.shouldShowName(T entity, double distanceSq)).
        if (entity instanceof aiKingdomNPCEntity npc) {
            double distSq = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition()
                    .distanceToSqr(entity.getX(), entity.getY(), entity.getZ());
            if (!this.shouldShowName(npc, distSq)) return;
        }

        poseStack.pushPose();

        double y = entity.getBbHeight() + 0.5D;
        poseStack.translate(0.0D, y, 0.0D);

        // Face camera (use the camera rotation, not EntityRenderDispatcher.cameraOrientation())
        poseStack.mulPose(Minecraft.getInstance().gameRenderer.getMainCamera().rotation());

        float scale = 0.025F;
        poseStack.scale(-scale, -scale, scale);

        Font font = this.getFont();
        int w = font.width(text);
        float x = -w / 2.0F;

        float bgAlpha = Minecraft.getInstance().options.getBackgroundOpacity(0.25F);
        int bg = ((int) (bgAlpha * 255.0F) << 24);

        font.drawInBatch(
                text, x, 0.0F,
                0xFFFFFFFF, false,
                poseStack.last().pose(),
                buffers,
                mode, // NORMAL => depth-tested (won’t show through blocks)
                bg,
                packedLight
        );

        poseStack.popPose();
    }


    private void renderNameTagStateMode(Component text, PoseStack poseStack,
                                        MultiBufferSource buffers, int packedLight,
                                        Font.DisplayMode mode, double yOffset) {

        poseStack.pushPose();

        poseStack.translate(0.0D, yOffset, 0.0D);
        poseStack.mulPose(Minecraft.getInstance().gameRenderer.getMainCamera().rotation());

        float scale = 0.025F;
        poseStack.scale(-scale, -scale, scale);

        Font font = this.getFont();
        int w = font.width(text);
        float x = -w / 2.0F;

        float bgAlpha = Minecraft.getInstance().options.getBackgroundOpacity(0.25F);
        int bg = ((int) (bgAlpha * 255.0F) << 24);

        font.drawInBatch(
                text, x, 0.0F,
                0xFFFFFFFF, false,
                poseStack.last().pose(),
                buffers,
                mode,
                bg,
                packedLight
        );

        poseStack.popPose();
    }




    @Override
    public ResourceLocation getTextureLocation(NPCState state) {
        String type = (state.aiTypeId == null || state.aiTypeId.isBlank())
                ? "villager"
                : state.aiTypeId;

        // Route scouts/guards to soldier visuals if desired
        if ("scout".equals(type) || "guard".equals(type)) {
            type = "soldier";
        }

        int skin = Math.max(0, state.skinId);

        return ResourceLocation.fromNamespaceAndPath(
                Kingdoms.MOD_ID,
                "textures/entity/" + type + "/" + type + "_" + skin + ".png"
        );
    }


}
