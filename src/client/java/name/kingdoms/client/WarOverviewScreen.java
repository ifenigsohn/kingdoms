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

public final class WarOverviewScreen extends Screen {

    private enum Tab { WAR, EVENTS }
    private Tab tab = Tab.WAR;

    private Button warTabBtn;
    private Button eventsTabBtn;

    private java.util.List<name.kingdoms.payload.KingdomEventsSyncS2CPayload.Entry> activeEvents = java.util.List.of();


    private final warOverviewSyncS2CPayload data;

    private Button royalToggleBtn;
    private boolean royalOn;

    private Button skinPrevBtn;
    private Button skinNextBtn;
    private Button skinApplyBtn;
    private int selectedSoldierSkinId;

    public void onEventsSync(name.kingdoms.payload.KingdomEventsSyncS2CPayload payload) {
        this.activeEvents = (payload == null || payload.events() == null)
                ? java.util.List.of()
                : payload.events();
    }

    private static String prettyEventName(String typeId) {
        if (typeId == null) return "unknown";
        return switch (typeId) {
            // globals
            case "global_plague" -> "Plague";
            case "global_bountiful_harvest" -> "Bountiful Harvest";
            case "global_bandit_wave" -> "Bandit Wave";
            case "global_festival" -> "Festival Season";
            case "global_drought" -> "Drought";

            // locals / policies (optional)
            case "double_pace" -> "Double Pace";
            case "leisurely_pace" -> "Leisurely Pace";
            case "increase_patrols" -> "Increased Patrols";
            case "decrease_patrols" -> "Decreased Patrols";
            case "double_rations" -> "Double Rations";
            case "halve_rations" -> "Halved Rations";
            case "alcohol_subsidies" -> "Alcohol Subsidies";
            case "drunk_crackdowns" -> "Drink Crackdown";
            case "frequent_services" -> "Frequent Services";
            case "papal_authority" -> "Papal Authority";
            case "diplomatic_envoys" -> "Diplomatic Envoys";
            case "vassal_contributions" -> "Vassal Contributions";
            case "market_subsidies" -> "Market Subsidies";
            case "contraband_crackdowns" -> "Contraband Crackdowns";

            default -> typeId;
        };
    }


    // 3D preview
    private name.kingdoms.entity.SoldierEntity previewSoldier;

    public WarOverviewScreen(warOverviewSyncS2CPayload data) {
        super(Component.literal("War Room"));
        this.data = data;
    }

    @Override
    protected void init() {
        super.init();

        int x0 = 20;
        int y0 = 6;

        warTabBtn = Button.builder(Component.literal("War"), b -> {
            tab = Tab.WAR;
        }).bounds(x0, y0, 60, 18).build();

        eventsTabBtn = Button.builder(Component.literal("Events"), b -> {
            tab = Tab.EVENTS;
            ClientPlayNetworking.send(new name.kingdoms.payload.KingdomEventsRequestC2SPayload());
        }).bounds(x0 + 66, y0, 70, 18).build();

        addRenderableWidget(warTabBtn);
        addRenderableWidget(eventsTabBtn);


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

        ClientPlayNetworking.send(new name.kingdoms.payload.KingdomEventsRequestC2SPayload());

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
        
        if (tab == Tab.EVENTS) {
            renderEventsTab(g, mouseX, mouseY, partialTick);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }


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
        int loadoutTop = 120; 

        int previewW = 60;
        int previewH = 80;

        int colCenterX = x + (colW / 2);

        // +20 is the “nudge right” amount. Increase/decrease to taste.
        int previewX = colCenterX + 20 - (previewW / 2);

       int loadoutX = previewX;

        g.drawString(this.font, "Loadout", loadoutX, loadoutTop, 0xFFFFFFFF);
        g.drawString(
                this.font,
                "Soldier Skin: " + selectedSoldierSkinId,
                loadoutX + previewW + 10,
                loadoutTop,
                0xFFFFFFFF
        );


       if (previewSoldier != null) {
            int x1 = previewX;
            int y1 = loadoutTop;
            int x2 = previewX + previewW;
            int y2 = loadoutTop + previewH;

            InventoryScreen.renderEntityInInventoryFollowsMouse(
                    g,
                    x1, y1,
                    x2, y2,
                    36,          // scale (match diplo vibe)
                    0.10F,       // y offset (match diplo)
                    mouseX, mouseY,
                    previewSoldier
            );
        }
        super.render(g, mouseX, mouseY, partialTick);
        
    }

    private static String format1(double v) {
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void renderEventsTab(GuiGraphics g, int mouseX, int mouseY, float pt) {
        int x = 20;
        int y = 30;

        g.drawString(this.font, "Active Kingdom Events", x, y, 0xFFFFFFFF);
        y += 16;

        if (activeEvents == null || activeEvents.isEmpty()) {
            g.drawString(this.font, "None.", x, y, 0xFFCCCCCC);
            return;
        }

      

       int maxLines = 14;
        int total = 0;
        for (var e : activeEvents) if (e != null) total++;

        int shown = 0;
        for (var e : activeEvents) {
            if (e == null) continue;
            if (shown >= maxLines) break;

            String name = prettyEventName(e.typeId());
            var mc = Minecraft.getInstance();
            long nowGameTime = (mc.level == null) ? 0L : mc.level.getGameTime();

            long ticksLeft = e.endTick() - nowGameTime; // endTick is END GAME TIME
            if (ticksLeft <= 0) continue;               // hides expired without refresh

            int secs = (int) ((ticksLeft + 19) / 20);


            String time = (secs >= 60) ? ((secs / 60) + "m") : (secs + "s");

            // compact effect summary
            String eff =
                    "econ x" + String.format(java.util.Locale.US, "%.2f", e.econMult())
                            + " hap " + String.format(java.util.Locale.US, "%+.1f", e.happinessDelta())
                            + " sec " + String.format(java.util.Locale.US, "%+.2f", e.securityDelta())
                            + " rel " + (e.relationsDelta() >= 0 ? "+" : "") + e.relationsDelta();

            String by = (e.causerName() == null || e.causerName().isBlank()) ? "" : (" — by " + e.causerName());
            g.drawString(this.font, name + by + " (" + time + ")", x, y, 0xFFFFFFFF);

            y += 10;
            g.drawString(this.font, "  " + eff + " [" + e.scope() + "]", x, y, 0xFFAAAAAA);
            y += 12;
            shown++;
        }

        if (total > shown) {
            g.drawString(this.font, "...and " + (total - shown) + " more", x, y, 0xFF777777);
        }

        g.drawString(this.font, "(Refresh: click Events tab again)", x, this.height - 16, 0xFF777777);
    }

}
