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
import java.util.List;

public final class KingSpeakScreen extends Screen {

    private final OpenKingSpeakActionsS2CPayload data;
    private final List<Button> buttons = new ArrayList<>();

    private int left, top, w, h;
    private static final int PAD = 10;
    private static final int BTN_H = 20;
    private static final int GAP = 6;

    public KingSpeakScreen(OpenKingSpeakActionsS2CPayload data) {
        super(Component.literal("Court Actions"));
        this.data = data;
    }


    @Override
    protected void init() {
        super.init();
        buttons.clear();

        w = 420;
        h = 260;
        left = (this.width - w) / 2;
        top = (this.height - h) / 2;

        int x = left + PAD;
        int y = top + 70;
        int bw = w - PAD * 2;

        // Split dynamic king-targeted actions into buckets
        List<String> askAbout = new ArrayList<>();
        List<String> speakWell = new ArrayList<>();
        List<String> speakIll = new ArrayList<>();
        List<String> normal = new ArrayList<>();

        if (data.actionIds() != null) {
            for (String id : data.actionIds()) {
                // Always pin “what’s happening” to the top so it doesn’t get cut off by the height limit
                if ("KING_WHATS_HAPPENING".equals(id)) { 
                normal.add(0, id); 
                continue; 
                }

                if (id.startsWith("KING_ASK_ABOUT|")) { askAbout.add(id); continue; }
                if (id.startsWith("KING_SPEAK_WELL|")) { speakWell.add(id); continue; }
                if (id.startsWith("KING_SPEAK_ILL|")) { speakIll.add(id); continue; }

                normal.add(id);

            }
        }

        // 1) Add the 3 grouped "submenu" entries (only if present)
        if (!askAbout.isEmpty()) {
            addRenderableWidget(Button.builder(Component.literal("Ask About Another King…"), btn -> {
                this.minecraft.setScreen(new KingPickTargetScreen(this, data, askAbout, "Ask About"));
            }).bounds(x, y, bw, BTN_H).build());
            y += BTN_H + GAP;
        }

        if (!speakWell.isEmpty()) {
            addRenderableWidget(Button.builder(Component.literal("Speak Well of Another King…"), btn -> {
                this.minecraft.setScreen(new KingPickTargetScreen(this, data, speakWell, "Speak Well Of"));
            }).bounds(x, y, bw, BTN_H).build());
            y += BTN_H + GAP;
        }

        if (!speakIll.isEmpty()) {
            addRenderableWidget(Button.builder(Component.literal("Speak Ill of Another King…"), btn -> {
                this.minecraft.setScreen(new KingPickTargetScreen(this, data, speakIll, "Speak Ill Of"));
            }).bounds(x, y, bw, BTN_H).build());
            y += BTN_H + GAP;
        }

        // 2) Render all remaining normal actions as before
        for (String id : normal) {
            Component label = Component.literal(prettyActionLabel(id));

            Button b = Button.builder(label, btn -> {
                        ClientPlayNetworking.send(new KingSpeakActionC2SPayload(
                data.entityId(),
                data.entityUuid(),
                data.targetKingdomId(),
                data.npcType(),
                id
        ));

        // keep open for info-only action
        if (!"KING_WHATS_HAPPENING".equals(id)) {
        onClose();
        }
                    })
                    .bounds(x, y, bw, BTN_H)
                    .build();

            buttons.add(b);
            addRenderableWidget(b);

            y += BTN_H + GAP;
            if (y > top + h - 40) break;
        }

        // Close button
        addRenderableWidget(
                Button.builder(Component.literal("Close"), b -> onClose())
                        .bounds(left + PAD, top + h - 28, w - PAD * 2, 20)
                        .build()
        );
    }

        private static String prettyActionLabel(String id) {

            // -------------------------
            // Dynamic KING talk options
            // -------------------------
            if (id.equals("KING_WHATS_HAPPENING")) {
                return "Ask What’s Happening in the Realm";
            }
            if (id.startsWith("KING_ASK_ABOUT|")) {
                return "Ask About Another King";
            }
            if (id.startsWith("KING_SPEAK_WELL|")) {
                return "Speak Well of Another King";
            }
            if (id.startsWith("KING_SPEAK_ILL|")) {
                return "Speak Ill of Another King";
            }

            // -------------------------
            // Static pressure actions
            // -------------------------
            return switch (id) {

                // =========================
                // KING (OFFICIAL) — 10
                // =========================
                case "KING_TRIBUTE" ->
                        "Offer Tribute (Relations ↑, Gold ↓)";

                case "KING_FORMAL_APOLOGY" ->
                        "Issue Formal Apology (Relations ↑)";

                case "KING_TRADE_PACT" ->
                        "Propose Trade Pact (Economy ↑, Relations ↑)";

                case "KING_REQUEST_AID" ->
                        "Request Military Aid (Security ↑, Allies Only)";

                case "KING_OFFER_AID" ->
                        "Offer Aid (Relations ↑, Economy ↓)";

                case "KING_NONAGGRESSION" ->
                        "Propose Non-Aggression Pact (Relations ↑)";

                case "KING_THREATEN" ->
                        "Issue Threat (Relations ↓, Tension ↑)";

                case "KING_DEMAND_CONCESSIONS" ->
                        "Demand Concessions (Economy ↑, Relations ↓)";

                case "KING_FORMAL_COMPLAINT" ->
                        "File Formal Complaint (Relations ↓)";

                case "KING_DECLARE_RIVALRY" ->
                        "Declare Rivalry (Relations ↓↓, Hostility ↑)";

                // =========================
                // NOBLE / COURT — 6
                // =========================
                case "NOBLE_BRIBE" ->
                        "Bribe a Court Noble (Relations ↑, Gold ↓)";

                case "NOBLE_FLATTER" ->
                        "Flatter the Court (Relations ↑)";

                case "NOBLE_PRESS_CLAIM" ->
                        "Press a Legal Claim (Relations ↓)";

                case "NOBLE_SPREAD_SCANDAL" ->
                        "Spread Court Scandal (Relations ↓, Stability ↓)";

                case "NOBLE_BUY_FAVORS" ->
                        "Buy Political Favors (Influence ↑, Gold ↓)";

                case "NOBLE_UNDERMINE_COURT" ->
                        "Undermine the Court (Relations ↓↓, Intrigue ↑)";

                // =========================
                // GUARD / SECURITY — 5
                // =========================
                case "GUARD_PAY_OFF" ->
                        "Pay Off the Guard (Security ↓, Control ↑)";

                case "GUARD_INCITE_BRAWL" ->
                        "Incite a Brawl (Security ↓, Chaos ↑)";

                case "GUARD_REQUEST_ESCORT" ->
                        "Request Guard Escort (Security ↑)";

                case "GUARD_BORDER_HARASS" ->
                        "Harass the Border (Security ↓, War Tension ↑)";

                case "GUARD_SPREAD_WANTED" ->
                        "Circulate Wanted Notices (Fear ↑, Relations ↓)";

                // =========================
                // VILLAGERS / PEOPLE — 4
                // =========================
                case "VILLAGER_GIFTS" ->
                        "Distribute Gifts to Villagers (Happiness ↑, Gold ↓)";

                case "VILLAGER_STIR_PANIC" ->
                        "Stir Public Panic (Happiness ↓, Stability ↓)";

                case "VILLAGER_RECRUIT_INFORMANTS" ->
                        "Recruit Informants (Intelligence ↑, Trust ↓)";

                case "VILLAGER_FUND_BANDITS" ->
                        "Fund Bandit Activity (Security ↓↓, Chaos ↑)";

                // -------------------------
                default -> id;
            };
        }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, this.width, this.height, 0xAA000000);
        g.fill(left - 1, top - 1, left + w + 1, top + h + 1, 0xFF000000);
        g.fill(left, top, left + w, top + h, 0xFF202020);

        String kname = (data.targetKingdomName() == null || data.targetKingdomName().isBlank())
                ? "Unknown Kingdom" : data.targetKingdomName();

        g.drawString(this.font,
                Component.literal("Speak to: " + kname).withStyle(ChatFormatting.BOLD),
                left + PAD, top + PAD, 0xFFFFFFFF);

        g.drawString(this.font,
                Component.literal("Relation: " + data.relation()
                        + "   Allied: " + data.allied()
                        + "   At War: " + data.atWar()),
                left + PAD, top + 30, 0xFFCCCCCC);

        g.drawString(this.font,
                Component.literal("Channel: " + data.npcType()),
                left + PAD, top + 46, 0xFFAAAAAA);

        super.render(g, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
