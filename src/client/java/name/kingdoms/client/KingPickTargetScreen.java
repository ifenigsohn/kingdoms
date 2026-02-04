package name.kingdoms.client;

import name.kingdoms.payload.KingSpeakActionC2SPayload;
import name.kingdoms.payload.OpenKingSpeakActionsS2CPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class KingPickTargetScreen extends Screen {

    private final Screen parent;
    private final OpenKingSpeakActionsS2CPayload data;
    private final List<String> actionIds; // each is like KING_ASK_ABOUT|...
    private final String titleVerb;

    private int left, top, w, h;
    private static final int PAD = 10;
    private static final int BTN_H = 20;
    private static final int GAP = 6;

    private int page = 0;
    private static final int PER_PAGE = 7;

    public KingPickTargetScreen(Screen parent,
                               OpenKingSpeakActionsS2CPayload data,
                               List<String> actionIds,
                               String titleVerb) {
        super(Component.literal(titleVerb + " — Choose a King"));
        this.parent = parent;
        this.data = data;
        this.actionIds = new ArrayList<>(actionIds);
        this.titleVerb = titleVerb;

        // Sort by display name so it’s not random
        this.actionIds.sort(Comparator.comparing(KingPickTargetScreen::displayNameFromActionId, String.CASE_INSENSITIVE_ORDER));
    }

    @Override
    protected void init() {
        super.init();

        w = 420;
        h = 260;
        left = (this.width - w) / 2;
        top = (this.height - h) / 2;

        int x = left + PAD;
        int yStart = top + 70;
        int bw = w - PAD * 2;

        int totalPages = Math.max(1, (int) Math.ceil(actionIds.size() / (double) PER_PAGE));
        if (page < 0) page = 0;
        if (page > totalPages - 1) page = totalPages - 1;

        int start = page * PER_PAGE;
        int end = Math.min(actionIds.size(), start + PER_PAGE);

        int y = yStart;
        for (int i = start; i < end; i++) {
            String actionId = actionIds.get(i);
            String disp = displayNameFromActionId(actionId);

            Button b = Button.builder(Component.literal(disp), btn -> {
                        ClientPlayNetworking.send(new KingSpeakActionC2SPayload(
                                data.entityId(),
                                data.entityUuid(),
                                data.targetKingdomId(),
                                data.npcType(),
                                actionId // IMPORTANT: send original string unchanged
                        ));
                        this.minecraft.setScreen(parent);
                    })
                    .bounds(x, y, bw, BTN_H)
                    .build();

            addRenderableWidget(b);
            y += BTN_H + GAP;
        }

        // Pagination controls
        int navY = top + h - 54;
        int navW = (bw - GAP * 2) / 3;

        addRenderableWidget(Button.builder(Component.literal("Prev"), btn -> {
                    page--;
                    this.clearWidgets();
                    this.init();
                })
                .bounds(x, navY, navW, 20)
                .build()).active = page > 0;

        addRenderableWidget(Button.builder(Component.literal("Page " + (page + 1) + "/" + totalPages), btn -> {})
                .bounds(x + navW + GAP, navY, navW, 20)
                .build()).active = false;

        addRenderableWidget(Button.builder(Component.literal("Next"), btn -> {
                    page++;
                    this.clearWidgets();
                    this.init();
                })
                .bounds(x + (navW + GAP) * 2, navY, navW, 20)
                .build()).active = page < totalPages - 1;

        // Back + Close
        addRenderableWidget(
                Button.builder(Component.literal("Back"), b -> this.minecraft.setScreen(parent))
                        .bounds(left + PAD, top + h - 28, (bw - GAP) / 2, 20)
                        .build()
        );

        addRenderableWidget(
                Button.builder(Component.literal("Close"), b -> onClose())
                        .bounds(left + PAD + (bw - GAP) / 2 + GAP, top + h - 28, (bw - GAP) / 2, 20)
                        .build()
        );
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, this.width, this.height, 0xAA000000);
        g.fill(left - 1, top - 1, left + w + 1, top + h + 1, 0xFF000000);
        g.fill(left, top, left + w, top + h, 0xFF202020);

        g.drawString(this.font,
                Component.literal(titleVerb + " — Choose a King").withStyle(ChatFormatting.BOLD),
                left + PAD, top + PAD, 0xFFFFFFFF);

        String kname = (data.targetKingdomName() == null || data.targetKingdomName().isBlank())
                ? "Unknown Kingdom" : data.targetKingdomName();

        g.drawString(this.font,
                Component.literal("You are speaking at: " + kname),
                left + PAD, top + 30, 0xFFCCCCCC);

        g.drawString(this.font,
                Component.literal("Select a target:"),
                left + PAD, top + 46, 0xFFAAAAAA);

        super.render(g, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    /**
     * Expected formats:
     * - KING_ASK_ABOUT|kingdomId
     * - KING_ASK_ABOUT|kingdomId|King Name
     * - KING_ASK_ABOUT|kingdomId|King Name|extra...
     *
     * If there is no name, we fall back to the id.
     */
    private static String displayNameFromActionId(String actionId) {
        try {
            String[] parts = actionId.split("\\|");
            if (parts.length >= 3 && parts[2] != null && !parts[2].isBlank()) {
                return parts[2].trim();
            }
            if (parts.length >= 2) {
                return parts[1].trim();
            }
        } catch (Exception ignored) {}
        return actionId;
    }
}
