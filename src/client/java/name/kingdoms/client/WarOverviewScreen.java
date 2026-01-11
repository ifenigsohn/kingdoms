package name.kingdoms.client;

import name.kingdoms.payload.warOverviewSyncS2CPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class WarOverviewScreen extends Screen {

    private final warOverviewSyncS2CPayload data;

    public WarOverviewScreen(warOverviewSyncS2CPayload data) {
        super(Component.literal("War Room"));
        this.data = data;
    }

    @Override
    protected void init() {
        // later: add buttons (close, refresh, etc.)
        super.init();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {

        this.renderTransparentBackground(g);
        super.render(g, mouseX, mouseY, partialTick);

        int x = this.width / 2 - 160;
        int y = 20;

        g.drawString(this.font, "War Room (General)", x, y, 0xFFFFFFFF);
        y += 18;

        g.drawString(this.font,
                "Tickets: " + data.yourAlive() + " / " + data.yourMax(),
                x, y, 0xFFFFFFFF);
        y += 12;

        g.drawString(this.font,
                "Potions: " + format1(data.potions()),
                x, y, 0xFFFFFFFF);
        y += 16;

        // Allies
        g.drawString(this.font,
                "Alliances (" + data.allies().size() + ")",
                x, y, 0xFFA0FFA0);
        y += 12;

        for (var e : data.allies()) {
            g.drawString(this.font,
                    " - " + e.name() + ": " + e.alive() + " / " + e.max(),
                    x + 8, y, 0xFFFFFFFF);
            y += 10;
        }
        y += 10;

        // Enemies
        g.drawString(this.font,
                "Wars (" + data.enemies().size() + ")",
                x, y, 0xFFFF9090);
        y += 12;

        for (var e : data.enemies()) {
            g.drawString(this.font,
                    " - " + e.name() + ": " + e.alive() + " / " + e.max(),
                    x + 8, y, 0xFFFFFFFF);
            y += 10;
        }
    }



    private static String format1(double v) {
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}