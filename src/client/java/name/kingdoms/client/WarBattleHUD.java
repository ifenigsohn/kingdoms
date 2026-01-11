package name.kingdoms.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public final class WarBattleHUD {
    private WarBattleHUD() {}

    public static void register() {
        HudRenderCallback.EVENT.register(WarBattleHUD::onHudRender);
    }

    private static void onHudRender(GuiGraphics gg, DeltaTracker delta) {
        if (!ClientWarBattleStatus.active) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        int sw = mc.getWindow().getGuiScaledWidth();

        // Layout
        int top = 22;
        int barW = 160;
        int barH = 10;
        int gapY = 16; // vertical spacing between bars
        int midGap = 14;

        int leftX = (sw / 2) - barW - (midGap / 2);
        int rightX = (sw / 2) + (midGap / 2);

        // Percent helpers
        float ftPct = safePct(ClientWarBattleStatus.ticketsFriend, ClientWarBattleStatus.ticketsStartFriend);
        float etPct = safePct(ClientWarBattleStatus.ticketsEnemy, ClientWarBattleStatus.ticketsStartEnemy);
        float fmPct = safePct(ClientWarBattleStatus.moraleFriend, ClientWarBattleStatus.moraleStartFriend);
        float emPct = safePct(ClientWarBattleStatus.moraleEnemy, ClientWarBattleStatus.moraleStartEnemy);

        // Row 1: Tickets (left friend, right enemy)
        drawLabeledBar(
                gg, mc,
                leftX, top,
                barW, barH,
                ftPct,
                "Friendly Tickets: " + ClientWarBattleStatus.ticketsFriend + " / " + ClientWarBattleStatus.ticketsStartFriend,
                0xFF1E90FF // blue-ish
        );

        drawLabeledBar(
                gg, mc,
                rightX, top,
                barW, barH,
                etPct,
                "Enemy Tickets: " + ClientWarBattleStatus.ticketsEnemy + " / " + ClientWarBattleStatus.ticketsStartEnemy,
                0xFFE74C3C // red-ish
        );

        // Row 2: Morale
        int y2 = top + gapY;

        drawLabeledBar(
                gg, mc,
                leftX, y2,
                barW, barH,
                fmPct,
                "Friendly Morale: " + Math.round(ClientWarBattleStatus.moraleFriend) + " / " + Math.round(ClientWarBattleStatus.moraleStartFriend),
                0xFF2ECC71 // green-ish
        );

        drawLabeledBar(
                gg, mc,
                rightX, y2,
                barW, barH,
                emPct,
                "Enemy Morale: " + Math.round(ClientWarBattleStatus.moraleEnemy) + " / " + Math.round(ClientWarBattleStatus.moraleStartEnemy),
                0xFFF39C12 // orange-ish
        );
    }

    private static float safePct(float cur, float max) {
        if (max <= 0f) return 0f;
        float p = cur / max;
        if (p < 0f) p = 0f;
        if (p > 1f) p = 1f;
        return p;
    }

    private static void drawLabeledBar(
            GuiGraphics gg, Minecraft mc,
            int x, int y,
            int w, int h,
            float pct,
            String label,
            int fillColor
    ) {
        // Label above bar
        gg.drawString(mc.font, label, x, y - 10, 0xFFFFFFFF, true);

        // Background + border
        gg.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xAA000000);
        gg.fill(x, y, x + w, y + h, 0x66000000);

        // Fill
        int fw = Math.round(w * pct);
        if (fw > 0) {
            gg.fill(x, y, x + fw, y + h, fillColor);
        }
    }
}
