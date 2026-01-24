package name.kingdoms.client;

import name.kingdoms.kingdomsClient;
import name.kingdoms.payload.calendarSyncPayload;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public final class calendarHudOverlay {
    private calendarHudOverlay() {}

    private static final String[] MONTHS = {
            "Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"
    };

    public static void init() {
        // Use a lambda so signature always matches
        HudRenderCallback.EVENT.register((guiGraphics, deltaTracker) -> render(guiGraphics, deltaTracker));
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        if (mc.player == null) return;

        calendarSyncPayload cal = kingdomsClient.CLIENT_CALENDAR;
        if (cal == null) return;

        int m = Math.max(1, Math.min(12, cal.month()));
        String text = MONTHS[m - 1] + " " + cal.day() + ", " + cal.year();

        var font = mc.font;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();

        int padding = 6;
        int x = w - padding - font.width(text);
        int y = h - padding - font.lineHeight;

        g.drawString(font, Component.literal(text), x, y, 0xFFFFFFFF, true);
    }
}
