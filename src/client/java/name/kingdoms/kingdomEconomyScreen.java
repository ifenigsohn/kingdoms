package name.kingdoms;

import name.kingdoms.payload.ecoRequestPayload;
import name.kingdoms.payload.ecoSyncPayload;
import name.kingdoms.payload.kingdomInfoRequestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class kingdomEconomyScreen extends Screen {

    public kingdomEconomyScreen() {
        super(Component.literal("Kingdom Economy"));
    }

    @Override
    protected void init() {
        super.init();
        ClientPlayNetworking.send(new kingdomInfoRequestPayload());
        ClientPlayNetworking.send(new ecoRequestPayload());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTicks) {
        this.renderTransparentBackground(gui);

        String kName = kingdomsClient.CLIENT_KINGDOM_INFO.hasKingdom()
                ? kingdomsClient.CLIENT_KINGDOM_INFO.name()
                : "No Kingdom";

        // read latest snapshot (totals + projected deltas) provided by the server
        ecoSyncPayload p = kingdomsClient.CLIENT_ECO;

        int x = this.width / 2 - 100;
        int y = this.height / 2 - 90;

        gui.drawString(this.font, Component.literal("Kingdom: " + kName + " Economy"), x, y, 0xFFFFFFFF);
        y += 15;

        y = drawLine(gui, x, y, "Gold",    p.gold(),    p.dGold());
        y = drawLine(gui, x, y, "Meat",    p.meat(),    p.dMeat());
        y = drawLine(gui, x, y, "Grain",   p.grain(),   p.dGrain());
        y = drawLine(gui, x, y, "Fish",    p.fish(),    p.dFish());
        y = drawLine(gui, x, y, "Wood",    p.wood(),    p.dWood());
        y = drawLine(gui, x, y, "Metal",   p.metal(),   p.dMetal());
        y = drawLine(gui, x, y, "Armor",   p.armor(),   p.dArmor());
        y = drawLine(gui, x, y, "Weapons", p.weapons(), p.dWeapons());
        y = drawLine(gui, x, y, "Gems",    p.gems(),    p.dGems());
        y = drawLine(gui, x, y, "Horses",  p.horses(),  p.dHorses());
        y = drawLine(gui, x, y, "Potions", p.potions(), p.dPotions());

        super.render(gui, mouseX, mouseY, partialTicks);
    }

    private int drawLine(GuiGraphics gui, int x, int y, String label, double value, double deltaPerSec) {
        String valStr = String.format("%.2f", value);

        double per10sec = deltaPerSec * 10.0;
        if (Math.abs(per10sec) < 0.001) per10sec = 0.0;

        String deltaStr = String.format("%+.2f /5 minutes", per10sec);
        String line = label + ": " + valStr + "  (" + deltaStr + ")";

        gui.drawString(this.font, Component.literal(line), x, y, 0xFFFFFFFF);
        return y + 10;
    }
}