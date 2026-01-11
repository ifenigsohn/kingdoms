package name.kingdoms.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

public final class KingdomTransitionHUD {

    private static Component message = null;
    private static long startTimeMs = 0;

    // how long it stays on screen
    private static final long DURATION_MS = 3_000;

    private KingdomTransitionHUD() {}

    public static void show(Component msg) {
        message = msg;
        startTimeMs = System.currentTimeMillis();
    }

    public static void register() {
        HudRenderCallback.EVENT.register(KingdomTransitionHUD::render);
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        if (message == null) return;

        long age = System.currentTimeMillis() - startTimeMs;
        if (age > DURATION_MS) {
            message = null;
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // fade out last 0.8s
        float alpha = 1.0f;
        if (age > DURATION_MS - 800) {
            alpha = 1.0f - (age - (DURATION_MS - 800)) / 800f;
        }
        int a = ((int)(alpha * 255f) & 0xFF) << 24;

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        // scale based on resolution, clamped
        float scale = Mth.clamp(screenW / 800f, 0.8f, 1.3f) * 3f;

        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(screenW / 2f, screenH / 2f - 30f);
        pose.scale(scale, scale);

        int textW = mc.font.width(message);
        g.drawString(mc.font, message, -textW / 2, 0, 0xFFFFFF | a, true);

        pose.popMatrix();
    }


}
