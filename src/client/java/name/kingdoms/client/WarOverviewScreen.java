package name.kingdoms.client;

import name.kingdoms.payload.royalGuardToggleC2SPayload;
import name.kingdoms.payload.warOverviewSyncS2CPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
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

        try {
                royalOn = (boolean) data.getClass().getMethod("royalGuardsEnabled").invoke(data);
            } catch (Throwable ignored) {
                royalOn = false;
            }

            int colW = Math.min(this.width - 20, 360);
            int x = (this.width - colW) / 2;
            int y = 20;


            // Put the toggle near the top
            int btnY = y + 140; // after your basic header lines
            int btnW = Math.min(180, colW);
            int btnH = 20;

            royalToggleBtn = Button.builder(toggleLabel(), (btn) -> {
            royalOn = !royalOn;

            // send to server
            ClientPlayNetworking.send(new royalGuardToggleC2SPayload(royalOn));

            // update label immediately
            btn.setMessage(toggleLabel());
        }).bounds(x, btnY, btnW, btnH).build();

            this.addRenderableWidget(royalToggleBtn);
        

    }

    private Button royalToggleBtn;
    private boolean royalOn;

    private Component toggleLabel() {
        return Component.literal("Royal Guards: " + (royalOn ? "ON" : "OFF"));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {

        this.renderTransparentBackground(g);
        super.render(g, mouseX, mouseY, partialTick);

        int colW = Math.min(this.width - 20, 360);
        int x = (this.width - colW) / 2;
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