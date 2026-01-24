package name.kingdoms.client;

import name.kingdoms.payload.royalGuardToggleC2SPayload;
import name.kingdoms.payload.warOverviewSyncS2CPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;

public final class WarOverviewScreen extends Screen {

    private final warOverviewSyncS2CPayload data;

    private Button royalToggleBtn;
    private boolean royalOn;

    private Button skinPrevBtn;
    private Button skinNextBtn;
    private Button skinApplyBtn;
    private int selectedSoldierSkinId;

    // 3D preview
    private name.kingdoms.entity.SoldierEntity previewSoldier;

    public WarOverviewScreen(warOverviewSyncS2CPayload data) {
        super(Component.literal("War Room"));
        this.data = data;
    }

    @Override
    protected void init() {
        super.init();

        selectedSoldierSkinId = data.soldierSkinId();

        try {
            royalOn = (boolean) data.getClass().getMethod("royalGuardsEnabled").invoke(data);
        } catch (Throwable ignored) {
            royalOn = false;
        }

        int colW = Math.min(this.width - 20, 360);
        int x = (this.width - colW) / 2;
        int y = 20;

        // Put the toggle near the top
        int btnY = y + 140;
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

        // ----------------------------
        // Loadout section positions
        // ----------------------------
        int loadoutTop = btnY + 30;

        // preview box dimensions (render-only, used for positioning)
        int previewW = 60;
        int previewH = 80;

        int previewX = x;                  // left side
        int previewY = loadoutTop;

        int controlsX = previewX + previewW + 10;
        int controlsY = loadoutTop + 18;

        int arrowW = 20;
        int arrowH = 20;
        int applyW = 140;
        int applyH = 20;

        // Create preview entity (client-side)
        buildPreviewSoldier();

        skinPrevBtn = Button.builder(Component.literal("<"), b -> {
            selectedSoldierSkinId--;
            if (selectedSoldierSkinId < 0) selectedSoldierSkinId = name.kingdoms.entity.SoldierSkins.MAX_SKIN_ID;
            if (previewSoldier != null) previewSoldier.setSkinId(selectedSoldierSkinId);
        }).bounds(controlsX, controlsY, arrowW, arrowH).build();

        skinApplyBtn = Button.builder(Component.literal("Apply Soldier Skin"), b -> {
            ClientPlayNetworking.send(
                    new name.kingdoms.payload.soldierSkinSelectC2SPayload(selectedSoldierSkinId)
            );

            // request refresh so UI will reflect server state (optional but nice)
            ClientPlayNetworking.send(
                    new name.kingdoms.payload.warOverviewRequestC2SPayload()
            );
        }).bounds(controlsX + arrowW + 6, controlsY, applyW, applyH).build();

        skinNextBtn = Button.builder(Component.literal(">"), b -> {
            selectedSoldierSkinId++;
            if (selectedSoldierSkinId > name.kingdoms.entity.SoldierSkins.MAX_SKIN_ID) selectedSoldierSkinId = 0;
            if (previewSoldier != null) previewSoldier.setSkinId(selectedSoldierSkinId);
        }).bounds(controlsX + arrowW + 6 + applyW + 6, controlsY, arrowW, arrowH).build();

        this.addRenderableWidget(skinPrevBtn);
        this.addRenderableWidget(skinApplyBtn);
        this.addRenderableWidget(skinNextBtn);
    }

    private void buildPreviewSoldier() {
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            previewSoldier = null;
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            var type = (net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.PathfinderMob>)
                    name.kingdoms.entity.modEntities.SOLDIER;

            var se = new name.kingdoms.entity.SoldierEntity(type, mc.level);
            se.setSide(name.kingdoms.entity.SoldierEntity.Side.FRIEND);
            se.setRole(name.kingdoms.entity.SoldierEntity.Role.FOOTMAN);
            se.setBannerman(false);
            se.setCaptain(false);
            se.setSkinId(selectedSoldierSkinId);

            previewSoldier = se;
        } catch (Throwable t) {
            // Don't silently fail while debugging
            System.out.println("[WarOverviewScreen] Failed to create previewSoldier: " + t);
            previewSoldier = null;
        }
    }


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

        // ----------------------------
        // Loadout section render
        // ----------------------------
        int btnY = 20 + 140;
        int loadoutTop = btnY + 30;

        int previewW = 60;

        int previewX = x;
        int previewCenterX = previewX + (previewW / 2);
        int previewBottomY = loadoutTop + 78;

        g.drawString(this.font, "Loadout", x, loadoutTop, 0xFFFFFFFF);
        g.drawString(this.font, "Soldier Skin: " + selectedSoldierSkinId, x + previewW + 10, loadoutTop, 0xFFFFFFFF);

        if (previewSoldier != null) {
            int scale = 35;
            InventoryScreen.renderEntityInInventoryFollowsMouse(
                    g,
                    previewCenterX,
                    previewBottomY,
                    scale,
                    scale, scale, (float) (previewCenterX - mouseX),
                    (float) (previewBottomY - mouseY),
                    scale, previewSoldier
            );
        } else {
            g.drawString(this.font, "(preview unavailable)", x, loadoutTop + 12, 0xFFFF7777);
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
