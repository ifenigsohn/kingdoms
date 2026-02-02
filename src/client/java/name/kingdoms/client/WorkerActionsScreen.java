package name.kingdoms.client;

import name.kingdoms.payload.OpenWorkerActionsS2CPayload;
import name.kingdoms.payload.WorkerActionC2SPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class WorkerActionsScreen extends Screen {

    private final OpenWorkerActionsS2CPayload data;

    private int left, top, w, h;

    private final List<Button> buttons = new ArrayList<>();
    private int scroll = 0;

    private static final int PAD = 10;
    private static final int BTN_H = 20;
    private static final int GAP = 6;

    public WorkerActionsScreen(OpenWorkerActionsS2CPayload data) {
        super(Component.literal("Worker Actions"));
        this.data = data;
    }

    @Override
    protected void init() {
        super.init();
        buttons.clear();

        w = 360;
        h = 220;
        left = (this.width - w) / 2;
        top = (this.height - h) / 2;

        int x = left + PAD;
        int y = top + 54;
        int bw = w - PAD * 2;

        // Title buttons always at top
        // Add TAX if allowed
        if (data.canTax()) {
            addActionButton(x, y, bw, "Request Tax", "TAX");
            y += BTN_H + GAP;
        }

        // Retinue utility buttons
        if (data.canOpenTreasury()) {
            addActionButton(x, y, bw, "Open Treasury", "OPEN_TREASURY");
            y += BTN_H + GAP;
        }
        if (data.canOpenMail()) {
            addActionButton(x, y, bw, "Open Mail", "OPEN_MAIL");
            y += BTN_H + GAP;
        }
        if (data.canOpenWarOverview()) {
            addActionButton(x, y, bw, "Open War Overview", "OPEN_WAR_OVERVIEW");
            y += BTN_H + GAP;
        }

        // Pressure actions
        if (data.actionIds() != null) {
            for (String id : data.actionIds()) {
                if (id == null || id.isBlank()) continue;

                String label = switch (id) {
                    case "DOUBLE_PACE" -> "Double pace (Economy ↑, Happiness ↓)";
                    case "LEISURELY_PACE" -> "Leisurely pace (Economy ↓, Happiness ↑)";

                    case "INCREASE_PATROLS" -> "Increase patrols (Security ↑, Economy ↓)";
                    case "DECREASE_PATROLS" -> "Decrease patrols (Security ↓, Economy ↑)";

                    case "DOUBLE_RATIONS" -> "Double rations (Regen ↑, Food cost ↑)";
                    case "HALVE_RATIONS" -> "Halve rations (Regen ↓, Food cost ↓)";

                    case "ALCOHOL_SUBSIDIES" -> "Alcohol subsidies (Happiness ↑, Gold cost ↓)";
                    case "DRUNK_CRACKDOWNS" -> "Drunk crackdowns (Happiness ↓, Economy ↑)";

                    case "FREQUENT_SERVICES" -> "Frequent services (Happiness ↑, Economy ↓)";
                    case "PAPAL_AUTHORITY" -> "Papal authority (Relations ↑ with all rulers)";

                    case "DIPLOMATIC_ENVOYS" -> "Diplomatic envoys (Relations ↑ with all rulers)";
                    case "VASSAL_CONTRIBUTIONS" -> "Vassal contributions (Economy ↑, Happiness ↓)";

                    case "MARKET_SUBSIDIES" -> "Market subsidies (Relations ↑, Shop profit ↓)";
                    case "CONTRABAND_CRACKDOWNS" -> "Contraband crackdowns (Security ↑, Relations ↓)";

                    default -> id;
                };


                addActionButton(x, y, bw, label, id);
                y += BTN_H + GAP;
            }
        }

        // Close
        this.addRenderableWidget(
                Button.builder(Component.literal("Close"), b -> onClose())
                        .bounds(left + PAD, top + h - 28, w - PAD * 2, 20)
                        .build()
        );
    }

    private void addActionButton(int x, int y, int w, String label, String actionId) {
        Button b = Button.builder(Component.literal(label), btn -> {
                    ClientPlayNetworking.send(new WorkerActionC2SPayload(
                            data.entityId(),
                            data.entityUuid(),
                            actionId
                    ));
                    // keep screen open for multi-actions, except for open screens actions
                    if (actionId.startsWith("OPEN_")) onClose();
                })
                .bounds(x, y, w, BTN_H)
                .build();

        buttons.add(b);
        addRenderableWidget(b);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, this.width, this.height, 0xAA000000);

        // panel
        g.fill(left - 1, top - 1, left + w + 1, top + h + 1, 0xFF000000);
        g.fill(left, top, left + w, top + h, 0xFF202020);

        g.drawString(this.font,
                Component.literal("ACTIONS").withStyle(ChatFormatting.BOLD),
                left + PAD,
                top + PAD,
                0xFFFFFFFF
        );

        String job = (data.jobId() == null || data.jobId().isBlank()) ? "unknown" : data.jobId();
        g.drawString(this.font,
                Component.literal("Role: " + job),
                left + PAD,
                top + 28,
                0xFFCCCCCC
        );

        super.render(g, mouseX, mouseY, delta);
    }
}
