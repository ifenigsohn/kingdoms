package name.kingdoms.client;

import name.kingdoms.menu.diploMenu;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import name.kingdoms.payload.diplomacyFreezeC2SPayload;
import java.util.ArrayList;
import java.util.List;
import name.kingdoms.payload.mailSendC2SPayload;
import name.kingdoms.diplomacy.ResourceType;
import name.kingdoms.diplomacy.Letter;
import java.util.UUID;


public class diploScreen extends AbstractContainerScreen<diploMenu> {

    private final Minecraft mc = Minecraft.getInstance();

    private final int kingEntityId;
    private int freezePingTimer = 0;


    // Bigger overall screen
    private static final int GUI_W = 460;
    private static final int GUI_H = 250;

    // Bigger portraits
    private static final int PORTRAIT_W = 104;
    private static final int PORTRAIT_H = 128;

    // InventoryScreen-style render
    private static final int PORTRAIT_SCALE = 36;
    private static final float PORTRAIT_Y_OFFSET = 0.10F;

    // Layout (absolute screen coords)
    private int cx;
    private int portraitY;
    private int leftX, rightX;
    private int midX, midW, midY, midH;

    // Scrollable actions
    private static final int PAD = 10;
    private static final int BTN_H = 20;
    private static final int BTN_GAP = 6;
    private static final int SCROLL_SPEED = 14;

    private int listX, listY, listW, listH;
    private int scroll = 0;
    private int contentH = 0;

    private final List<Button> actionButtons = new ArrayList<>();
    private Button closeButton;

    public diploScreen(diploMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
           this.kingEntityId = menu.kingEntityId;
        this.imageWidth = GUI_W;
        this.imageHeight = GUI_H;
    }

    @Override
    protected void init() {
        super.init();
        actionButtons.clear();
        ClientPlayNetworking.send(new diplomacyFreezeC2SPayload(kingEntityId, true));


        cx = leftPos + imageWidth / 2;
        portraitY = topPos + 52;

        // portraits on left/right
        leftX = cx - 214;
        rightX = cx + 110;

        int gapLeft = leftX + PORTRAIT_W + 14;
        int gapRight = rightX - 14;
        midX = gapLeft;
        midW = Math.max(150, gapRight - gapLeft);

        midY = portraitY;
        midH = imageHeight - (midY - topPos) - 16;

        // list area (below relationship label + bar)
        listX = midX + PAD;
        listY = midY + 64;
        listW = midW - PAD * 2;
        listH = Math.max(10, (midY + midH - 38) - listY);

        addLocalAction("Write Letter", () -> mailScreen.open());
        addMailAction("Alliance Proposal", Letter.Kind.ALLIANCE_PROPOSAL, false);
        addMailAction("Declare War", Letter.Kind.WAR_DECLARATION, true);


        contentH = actionButtons.size() * BTN_H + Math.max(0, actionButtons.size() - 1) * BTN_GAP;

        closeButton = Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(listX, midY + midH - 28, listW, 20)
                .build();
        addRenderableWidget(closeButton);

        clampScroll();
        layoutActionButtons();
    }

    // IMPORTANT: draw our own labels here (GUI-local coords), and suppress vanilla "Inventory"
    @Override
    protected void renderLabels(GuiGraphics gg, int mouseX, int mouseY) {
        // GUI-local center
        int localCx = imageWidth / 2;

        gg.drawCenteredString(
                font,
                Component.literal("DIPLOMACY").withStyle(ChatFormatting.BOLD),
                localCx,
                12,
                0xFFFFFFFF
        );

        gg.drawCenteredString(
                font,
                menu.getKingdomName(),
                localCx,
                30,
                0xEAEAEA
        );

        // Relationship label in GUI-local coords
        int relLabelX = (midX - leftPos) + PAD;
        int relLabelY = (midY - topPos) + 10;

        gg.drawString(
                font,
                Component.literal("RELATIONSHIP").withStyle(ChatFormatting.BOLD),
                relLabelX,
                relLabelY,
                0xFFFFFFFF
        );
    }

    private void addLocalAction(String label, Runnable run) {
        Button b = Button.builder(Component.literal(label), btn -> run.run())
                .bounds(listX, listY, listW, BTN_H)
                .build();

        actionButtons.add(b);
        addRenderableWidget(b);
    }

    private void addMailAction(String label, Letter.Kind kind, boolean closeOnClick) {
        Button b = Button.builder(Component.literal(label), btn -> {

            UUID toKingdom = menu.getTargetKingdomId();
            if (toKingdom == null) {
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal("No target kingdom id."), false);
                }
                return;
            }

            UUID requestId = UUID.randomUUID();

            // Defaults for “non-economic” diplomacy letters
            ResourceType aType = ResourceType.GOLD;
            double aAmount = 0;

            ResourceType bType = null;
            double bAmount = 0;
            double maxAmount = 0;

            Letter.CasusBelli cb = null;
            String note = "";
            long overrideExpires = 0L;

            // Per-kind defaults
            switch (kind) {
                case ALLIANCE_PROPOSAL -> {
                    note = "We offer an alliance.";
                }

                case WAR_DECLARATION -> {
                    cb = Letter.CasusBelli.UNKNOWN; // or BORDER_VIOLATION / INSULT / etc.
                    note = "We declare war.";
                }

                case COMPLIMENT -> {
                    note = "Your court is impressive.";
                }

                case INSULT -> {
                    note = "Your rule is an embarrassment.";
                }

                case WARNING -> {
                    note = "Do not test our patience.";
                }

                case ULTIMATUM -> {
                    // Example ultimatum: demand 200 gold within 10 minutes
                    aType = ResourceType.GOLD;
                    aAmount = 200;

                    // Let server choose if you keep 0; if you want client-driven:
                    // overrideExpires = mc.level != null ? (mc.level.getGameTime() + 20L*60L*10L) : 0L;
                    note = "Pay tribute, or face war.";
                }

                // economic types could be wired later
                case REQUEST, OFFER, CONTRACT -> {
                    // If you ever add these buttons, you probably want a real UI instead.
                    note = "";
                }

                default -> {
                    // WHITE_PEACE / SURRENDER / ALLIANCE_BREAK etc.
                    note = "";
                }
            }

            ClientPlayNetworking.send(new mailSendC2SPayload(
                    requestId,
                    toKingdom,
                    kind,
                    aType,
                    aAmount,
                    bType,
                    bAmount,
                    maxAmount,
                    cb,
                    note,
                    overrideExpires
            ));

            if (closeOnClick) onClose();
        }).bounds(listX, listY, listW, BTN_H).build();

        actionButtons.add(b);
        addRenderableWidget(b);
    }




    private void clampScroll() {
        int max = Math.max(0, contentH - listH);
        scroll = Mth.clamp(scroll, 0, max);
    }

    private void layoutActionButtons() {
        int y = listY - scroll;
        for (Button b : actionButtons) {
            b.setX(listX);
            b.setY(y);
            b.setWidth(listW);
            y += BTN_H + BTN_GAP;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (mouseX >= listX && mouseX <= listX + listW && mouseY >= listY && mouseY <= listY + listH) {
            int max = Math.max(0, contentH - listH);
            if (max > 0) {
                scroll = Mth.clamp(scroll - (int)(dy * SCROLL_SPEED), 0, max);
                layoutActionButtons();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    @Override
    protected void renderBg(GuiGraphics gg, float pt, int mouseX, int mouseY) {
        gg.fillGradient(0, 0, width, height, 0xA0101010, 0xB0101010);

        // panels
        drawPanel(gg, leftPos, topPos, imageWidth, imageHeight);
        drawPanel(gg, leftX, portraitY, PORTRAIT_W, PORTRAIT_H);
        drawPanel(gg, rightX, portraitY, PORTRAIT_W, PORTRAIT_H);
        drawPanel(gg, midX, midY, midW, midH);

        // bar
        drawMinecraftyRelationBar(gg, midX + PAD, midY + 26, midW - PAD * 2, 14, menu.getRelation());

        // Clip button list area
        gg.enableScissor(listX, listY, listX + listW, listY + listH);
        for (Button b : actionButtons) {
            boolean visible = b.getY() + BTN_H > listY && b.getY() < listY + listH;
            b.visible = visible;
            b.active = visible;
            b.setFocused(false);

        }
        gg.disableScissor();

        drawScrollbar(gg);

        // entities
        if (mc.player != null) {
            renderEntityBox(gg, leftX, portraitY, PORTRAIT_W, PORTRAIT_H, mouseX, mouseY, mc.player);
        }

        LivingEntity king = resolveKingEntity();
        if (king != null) {
            renderEntityBox(gg, rightX, portraitY, PORTRAIT_W, PORTRAIT_H, mouseX, mouseY, king);
        }


    }

       @Override
        protected void containerTick() {
            super.containerTick();

            if (++freezePingTimer >= 20) { // once per second
                freezePingTimer = 0;
                ClientPlayNetworking.send(new diplomacyFreezeC2SPayload(kingEntityId, true));
            }
        }

    
        @Override
        public void removed() {
            super.removed();
            ClientPlayNetworking.send(new diplomacyFreezeC2SPayload(kingEntityId, false));
        }

    private void drawScrollbar(GuiGraphics gg) {
        int max = Math.max(0, contentH - listH);
        if (max <= 0) return;

        int trackX = midX + midW - 7;
        int trackY1 = listY;
        int trackY2 = listY + listH;

        gg.fill(trackX, trackY1, trackX + 3, trackY2, 0xFF1B1B1B);
        gg.fill(trackX, trackY1, trackX + 1, trackY2, 0xFF3A3A3A);

        float t = scroll / (float) max;
        int thumbH = Math.max(12, (int)(listH * (listH / (float)contentH)));
        int thumbY = (int) Mth.lerp(t, trackY1, trackY2 - thumbH);

        gg.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xFFB0B0B0);
        gg.fill(trackX, thumbY, trackX + 1, thumbY + thumbH, 0xFFFFFFFF);
    }

    private void renderEntityBox(GuiGraphics gg, int x, int y, int w, int h, int mouseX, int mouseY, LivingEntity entity) {
        int x1 = x;
        int y1 = y;
        int x2 = x + w;
        int y2 = y + h;

        InventoryScreen.renderEntityInInventoryFollowsMouse(
                gg,
                x1, y1,
                x2, y2,
                PORTRAIT_SCALE,
                PORTRAIT_Y_OFFSET,
                mouseX, mouseY,
                entity
        );
    }

    private LivingEntity resolveKingEntity() {
        if (mc.level == null) return mc.player;

        if (menu.getTargetEntityUuid() != null) {
            Entity byUuid = mc.level.getEntity(menu.getTargetEntityUuid());
            if (byUuid instanceof LivingEntity le && !le.isRemoved()) return le;
        }

        if (menu.getTargetEntityId() >= 0) {
            Entity byId = mc.level.getEntity(menu.getTargetEntityId());
            if (byId instanceof LivingEntity le && !le.isRemoved()) return le;
        }

        return mc.player;
    }

    private static void drawPanel(GuiGraphics gg, int x, int y, int w, int h) {
        gg.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);
        gg.fill(x, y, x + w, y + h, 0xFF202020);
        gg.fill(x, y, x + w, y + 1, 0xFF2E2E2E);
        gg.fill(x, y, x + 1, y + h, 0xFF2E2E2E);
    }

    private static void drawMinecraftyRelationBar(GuiGraphics gg, int x, int y, int w, int h, int rel) {
        rel = Mth.clamp(rel, -100, 100);
        int relSeg = Mth.clamp(Math.round(rel / 10.0f), -10, 10);


        gg.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);
        gg.fill(x, y, x + w, y + h, 0xFF2B2B2B);
        gg.fill(x, y, x + w, y + 1, 0xFF4A4A4A);
        gg.fill(x, y, x + 1, y + h, 0xFF4A4A4A);

        int segments = 21;
        int gap = 1;
        int segW = Math.max(2, (w - (segments - 1) * gap) / segments);
        int totalW = segW * segments + (segments - 1) * gap;

        int startX = x + (w - totalW) / 2;
        int segY1 = y + 2;
        int segY2 = y + h - 2;

        int zeroIndex = 10;

        for (int i = 0; i < segments; i++) {
            int sx1 = startX + i * (segW + gap);
            int sx2 = sx1 + segW;

            gg.fill(sx1, segY1, sx2, segY2, 0xFF1A1A1A);

            boolean shouldFill;
            if (relSeg >= 0) shouldFill = (i >= zeroIndex && i <= zeroIndex + relSeg);
            else shouldFill = (i <= zeroIndex && i >= zeroIndex + relSeg);


            if (shouldFill) {
                int color = (i >= zeroIndex) ? 0xFF3BD16F : 0xFFD14B3B;
                gg.fill(sx1, segY1, sx2, segY2, color);
                gg.fill(sx1, segY1, sx2, segY1 + 1, 0xFFFFFFFF);
            }

            if (i == zeroIndex) {
                gg.fill(sx1, y + 1, sx2, y + h - 1, 0xFF000000);
            }
        }
    }
}
