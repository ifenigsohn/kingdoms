package name.kingdoms.client;

import name.kingdoms.kingSkinPoolState;
import name.kingdoms.diplomacy.Letter;
import name.kingdoms.diplomacy.ResourceType;
import name.kingdoms.payload.mailActionC2SPayload;
import name.kingdoms.payload.mailInboxRequestC2SPayload;
import name.kingdoms.payload.mailRecipientsRequestC2SPayload;
import name.kingdoms.payload.mailRecipientsSyncS2CPayload;
import name.kingdoms.payload.mailSendC2SPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.ChatFormatting;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class mailScreen extends Screen {

    private enum Tab { INBOX, COMPOSE, NEWS }

    private Tab tab = Tab.INBOX;

    private int inboxSelected = 0;
    private int recipientSelected = 0;

    // scroll offsets
    private int inboxScroll = 0;
    private int recipientScroll = 0;

    private Letter.Kind composeKind = Letter.Kind.REQUEST;
    private ResourceType composeAType = ResourceType.GOLD;
    private ResourceType composeBType = ResourceType.WOOD;
    private Letter.CasusBelli composeCb = Letter.CasusBelli.UNKNOWN;

    //news
    private int newsSelected = 0;
    private int newsScroll = 0;

    private Button newsTabBtn;

    // inbox gating message (why Accept is disabled)
    private String inboxAcceptBlockMsg = "";

    // recipients refresh tick (server-authoritative relations)
    private int recipientsRefreshCooldown = 0;

    // how often to refresh (in ticks): 20*10 = every 10 seconds
    private static final int RECIPIENTS_REFRESH_PERIOD = 20 * 20;


    // gating message
    private String composeBlockMsg = "";

    // widgets
    private Button inboxTabBtn;
    private Button composeTabBtn;

    private Button acceptBtn;
    private Button refuseBtn;
    private Button ackBtn;

    private final List<Button> rowButtons = new ArrayList<>();

    private Button kindBtn;
    private Button aTypeBtn;
    private Button bTypeBtn;

    private EditBox aAmtBox;
    private EditBox bAmtBox;
    private EditBox maxAmtBox;

    // Note box exists, but will be hidden for compliment/insult (and other non-note kinds)
    private EditBox noteBox;

    private Button cbBtn;
    private Button sendBtn;

    // layout
    private int left, top;
    private int listRight, detailsLeft, rightEdge;
    private int listW, detailsW;
    private int rowW, detailsInnerW;

    // dynamic panel geometry
    private int panelTop;
    private int panelBottom;
    private int rowH = 20;
    private int visibleRows = 9;

    private int listX, listY, listH;

    // details panel inner bounds (for drawing text responsively)
    private int detailsTop;
    private int detailsBottom;

    public mailScreen() {
        super(Component.literal("Diplomacy Mail"));
    }

    public static void open() {
        ClientPlayNetworking.send(new mailInboxRequestC2SPayload());
        ClientPlayNetworking.send(new mailRecipientsRequestC2SPayload());
        ClientPlayNetworking.send(new name.kingdoms.payload.newsRequestC2SPayload(60));
        Minecraft.getInstance().setScreen(new mailScreen());
    }

    private void requestRecipientsRefresh() {
        ClientPlayNetworking.send(new mailRecipientsRequestC2SPayload());
    }

    private int drawWrapped(GuiGraphics g, String text, int x, int y, int maxW, int color, int maxLines) {
        if (text == null || text.isBlank()) return 0;

        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        int lines = 0;

        for (String w : words) {
            String trial = line.isEmpty() ? w : (line + " " + w);
            if (this.font.width(trial) <= maxW) {
                line.setLength(0);
                line.append(trial);
            } else {
                g.drawString(this.font, line.toString(), x, y, color);
                y += 12;
                lines++;
                if (lines >= maxLines) return lines;
                line.setLength(0);
                line.append(w);
            }
        }

        if (!line.isEmpty() && lines < maxLines) {
            g.drawString(this.font, line.toString(), x, y, color);
            lines++;
        }

        return lines;
    }


    // -------------------------
    // Compose kinds
    // -------------------------

    private static final Letter.Kind[] COMPOSE_KINDS = {
            Letter.Kind.REQUEST,
            Letter.Kind.OFFER,
            Letter.Kind.CONTRACT,
            Letter.Kind.ULTIMATUM,
            Letter.Kind.COMPLIMENT,
            Letter.Kind.INSULT,
            Letter.Kind.WARNING,
            Letter.Kind.WAR_DECLARATION,
            Letter.Kind.ALLIANCE_PROPOSAL,
            Letter.Kind.ALLIANCE_BREAK,
            Letter.Kind.WHITE_PEACE,
            Letter.Kind.SURRENDER
    };

    private static Letter.Kind nextKindInCompose(Letter.Kind cur) {
        for (int i = 0; i < COMPOSE_KINDS.length; i++) {
            if (COMPOSE_KINDS[i] == cur) return COMPOSE_KINDS[(i + 1) % COMPOSE_KINDS.length];
        }
        return COMPOSE_KINDS[0];
    }

    // -------------------------
    // Inbox buttons: accept/refuse vs ack
    // -------------------------

    private static boolean isActionable(Letter l) {
        if (l == null) return false;
        if (l.status() != Letter.Status.PENDING) return false;

        return switch (l.kind()) {
            case REQUEST, OFFER, CONTRACT, ULTIMATUM,
                 ALLIANCE_PROPOSAL, WHITE_PEACE -> true;
            default -> false;
        };
    }

    private static boolean isAcknowledgeOnly(Letter l) {
        if (l == null) return false;
        if (l.status() != Letter.Status.PENDING) return true;

        return switch (l.kind()) {
            case COMPLIMENT, INSULT, WARNING,
                 WAR_DECLARATION,
                 ALLIANCE_BREAK,
                 SURRENDER -> true; // surrender is ACK-only
            default -> false;
        };
    }

    // -------------------------
    // Compose visibility rules
    // -------------------------

    private boolean kindUsesEconomyA(Letter.Kind k) {
        return switch (k) {
            case REQUEST, OFFER, CONTRACT, ULTIMATUM -> true;
            default -> false;
        };
    }

    private boolean kindUsesContractB(Letter.Kind k) {
        return k == Letter.Kind.CONTRACT;
    }

    private boolean kindUsesCb(Letter.Kind k) {
        return k == Letter.Kind.WAR_DECLARATION;
    }

    private boolean kindUsesNote(Letter.Kind k) {
        // NOTE BOX REMOVED for COMPLIMENT/INSULT (canned text)
        return switch (k) {
            case ULTIMATUM,
                 WARNING,
                 WAR_DECLARATION,
                 ALLIANCE_PROPOSAL, ALLIANCE_BREAK,
                 WHITE_PEACE, SURRENDER -> true;
            default -> false;
        };
    }

    private void updateComposeBlockMsg() {
        var rec = getSelectedRecipient();
        if (rec == null) {
            composeBlockMsg = "";
            return;
        }

        var d = ClientMailPolicyCache.get(rec.kingdomId(), composeKind);

        // If we don't have policy yet, show "checking..." so Send is disabled.
        if (d == null) {
            composeBlockMsg = "Checking rules...";
            return;
        }

        if (!d.allowed()) {
            composeBlockMsg = (d.reason() == null || d.reason().isBlank()) ? "Blocked." : d.reason();
        } else {
            composeBlockMsg = "";
        }
    }


    private static String fmtSigned(int v) {
        return (v >= 0 ? "+" : "") + v;
    }

    

    private void requestNews() {
        ClientPlayNetworking.send(new name.kingdoms.payload.newsRequestC2SPayload(60));
    }


    // -------------------------
    // Init + responsive layout
    // -------------------------

    @Override
    protected void init() {

        // width: keep your existing behavior, but cap it so huge monitors don't stretch too far
       int usableW = (int)(this.width * 0.92);
        usableW = Math.min(usableW, this.width - 20);
        usableW = Math.max(360, usableW);

        left = (this.width - usableW) / 2;
        top  = 18;
        rightEdge = left + usableW; // IMPORTANT: rightEdge must match usableW now

        // dynamic panel height based on screen
        panelTop = top + 15;
        panelBottom = this.height - 24; // full height panel

        int tabY = Math.max(4, top - 22);
        int titleY = Math.max(4, tabY - 14);

        int availableH = Math.max(120, panelBottom - panelTop);

        // list fills the full panel height (not clamped down)
        visibleRows = Math.max(6, Math.min(18, availableH / rowH));
        listH = availableH;

        // list area uses full panel height
        detailsTop = panelTop + 6;
        detailsBottom = panelBottom - 6;


        // details inner bounds for text rendering
        this.detailsTop = this.panelTop + 6;
        this.detailsBottom = this.panelBottom - 6;



        // Ensure the details panel always has enough width for compose controls.
        int minDetailsW = 260;
        int gap = 10;

        detailsW = Math.max(minDetailsW, (int)(usableW * 0.36));
        listW = usableW - detailsW - gap;

        listW = Math.max(220, listW);
        detailsW = usableW - listW - gap;


        listRight = left + listW;
        detailsLeft = listRight + 10;

        rowW = listW - 16;
        detailsInnerW = detailsW - 20;

        listX = left + 8;
        listY = panelTop + 6;

        inboxTabBtn = Button.builder(Component.literal("Inbox"), b -> setTab(Tab.INBOX))
        .bounds(left, tabY, 80, 20).build();

        composeTabBtn = Button.builder(Component.literal("Compose"), b -> setTab(Tab.COMPOSE))
                .bounds(left + 85, tabY, 80, 20).build();

        newsTabBtn = Button.builder(Component.literal("News"), b -> {
                setTab(Tab.NEWS);
                requestNews();
            })
            .bounds(left + 170, tabY, 80, 20).build();



        addRenderableWidget(newsTabBtn);
        addRenderableWidget(inboxTabBtn);
        addRenderableWidget(composeTabBtn);

        int actionX = detailsLeft + 10;
        int actionY = panelBottom - 22;
        int actionW = Math.max(150, detailsInnerW);
        int halfW = Math.max(70, (actionW - 5) / 2);

        acceptBtn = Button.builder(Component.literal("Accept"), b -> onAccept())
                .bounds(actionX, actionY, halfW, 18).build();

        refuseBtn = Button.builder(Component.literal("Refuse"), b -> onRefuse())
                .bounds(actionX + halfW + 5, actionY, halfW, 18).build();

        ackBtn = Button.builder(Component.literal("Acknowledge"), b -> onAcknowledge())
                .bounds(actionX, actionY, actionW, 18).build();

        addRenderableWidget(acceptBtn);
        addRenderableWidget(refuseBtn);
        addRenderableWidget(ackBtn);

        buildRowButtons();

        int rx = detailsLeft + 10;
        int ry = panelTop + 28;


        int btnW = Math.max(140, Math.min(210, detailsInnerW - 90));
        int boxW = Math.max(70, Math.min(110, detailsInnerW - btnW - 10));

        kindBtn = Button.builder(Component.literal("Kind: " + composeKind.name()), b -> cycleKind())
                .bounds(rx, ry, Math.min(detailsInnerW, 260), 18).build();

        aTypeBtn = Button.builder(Component.literal(labelAType()), b -> cycleAType())
                .bounds(rx, ry + 22, btnW, 18).build();

        bTypeBtn = Button.builder(Component.literal(labelBType()), b -> cycleBType())
                .bounds(rx, ry + 44, btnW, 18).build();

        addRenderableWidget(kindBtn);
        addRenderableWidget(aTypeBtn);
        addRenderableWidget(bTypeBtn);

        aAmtBox = new EditBox(this.font, rx + btnW + 10, ry + 22, boxW, 18, Component.literal(""));
        bAmtBox = new EditBox(this.font, rx + btnW + 10, ry + 44, boxW, 18, Component.literal(""));
        maxAmtBox = new EditBox(this.font, rx + btnW + 10, ry + 66, boxW, 18, Component.literal(""));

        aAmtBox.setValue("10");
        bAmtBox.setValue("5");
        maxAmtBox.setValue("50");

        aAmtBox.setFilter(this::isNumber);
        bAmtBox.setFilter(this::isNumber);
        maxAmtBox.setFilter(this::isNumber);

        addRenderableWidget(aAmtBox);
        addRenderableWidget(bAmtBox);
        addRenderableWidget(maxAmtBox);

        noteBox = new EditBox(this.font, rx, ry + 88, Math.min(detailsInnerW, 270), 18, Component.literal(""));
        noteBox.setValue("");
        addRenderableWidget(noteBox);

        cbBtn = Button.builder(Component.literal("CB: " + composeCb.name()), b -> cycleCb())
                .bounds(rx, ry + 110, Math.min(detailsInnerW, 210), 18).build();
        addRenderableWidget(cbBtn);

        sendBtn = Button.builder(Component.literal("Send"), b -> onSend())
            .bounds(rx, panelBottom - 20, Math.min(detailsInnerW, 270), 18).build();

        addRenderableWidget(sendBtn);

        setTab(tab);
    }

    private String labelAType() {
        return switch (composeKind) {
            case OFFER -> "You give (A): " + composeAType.name();
            case REQUEST -> "You request (A): " + composeAType.name();
            case ULTIMATUM -> "They demand (A): " + composeAType.name();
            case CONTRACT -> "You give (A): " + composeAType.name();
            default -> "A: " + composeAType.name();
        };
    }


    private String labelBType() {
        return "They pay (B): " + composeBType.name(); // in CONTRACT, recipient pays B
    }

    private boolean isNumber(String s) {
        return s.isEmpty() || s.matches("^[0-9]*\\.?[0-9]*$");
    }

    private void buildRowButtons() {
        rowButtons.clear();

        for (int i = 0; i < visibleRows; i++) {
            final int visibleIdx = i;

            int bx = listX + RECIPIENT_ICON_PAD;
            int bw = rowW - RECIPIENT_ICON_PAD;

            Button row = Button.builder(Component.literal(""), b -> onRowClicked(visibleIdx))
                    .bounds(bx, listY + visibleIdx * rowH, bw, rowH)
                    .build();

            rowButtons.add(row);
            addRenderableWidget(row);
        }
    }



    // -------------------------
    // Scrollable list
    // -------------------------

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amountX, double amountY) {
        if (mouseX >= listX && mouseX <= listX + rowW && mouseY >= listY && mouseY <= listY + listH) {
            int dir = (amountY > 0) ? -1 : 1;

           if (tab == Tab.INBOX) {
                inboxScroll = clampScroll(inboxScroll + dir, inbox().size());
                inboxSelected = clampIndex(inboxSelected, inbox().size());
            } else if (tab == Tab.COMPOSE) {
                recipientScroll = clampScroll(recipientScroll + dir, recipients().size());
                recipientSelected = clampIndex(recipientSelected, recipients().size());
                requestPolicyForSelectedRecipient();
            } else { // NEWS
                newsScroll = clampScroll(newsScroll + dir, news().size());
                newsSelected = clampIndex(newsSelected, news().size());
            }

            refreshRows();
            refreshButtons();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amountX, amountY);
    }

    private int clampScroll(int scroll, int totalSize) {
        int maxScroll = Math.max(0, totalSize - rowButtons.size());
        if (scroll < 0) scroll = 0;
        if (scroll > maxScroll) scroll = maxScroll;
        return scroll;
    }

    private int clampIndex(int idx, int totalSize) {
        if (totalSize <= 0) return 0;
        if (idx < 0) idx = 0;
        if (idx >= totalSize) idx = totalSize - 1;
        return idx;
    }

    private void onRowClicked(int visibleIdx) {
        if (tab == Tab.INBOX) {
            int actual = inboxScroll + visibleIdx;
            if (actual >= 0 && actual < inbox().size()) inboxSelected = actual;
            // no policy request here
        } else if (tab == Tab.COMPOSE) {
            int actual = recipientScroll + visibleIdx;
            if (actual >= 0 && actual < recipients().size()) recipientSelected = actual;

            requestPolicyForSelectedRecipient(); // <-- MOVE IT HERE
        } else {
            int actual = newsScroll + visibleIdx;
            if (actual >= 0 && actual < news().size()) newsSelected = actual;
        }
        refreshButtons();
    }



    private void setTab(Tab t) {
        tab = t;

        boolean isInbox = (t == Tab.INBOX);
        boolean isCompose = (t == Tab.COMPOSE);
        boolean isNews = (t == Tab.NEWS);
        
        inboxTabBtn.active = !isInbox;
        composeTabBtn.active = !isCompose;
        newsTabBtn.active = !isNews;

        if (isInbox) {
            Letter sel = getSelectedInbox();
            boolean actionable = isActionable(sel);
            boolean ackOnly = isAcknowledgeOnly(sel);

            acceptBtn.visible = actionable;
            refuseBtn.visible = actionable;
            ackBtn.visible = (!actionable) && ackOnly;

            acceptBtn.active = actionable;
            refuseBtn.active = actionable;
            ackBtn.active = ackOnly;
        } else {
            acceptBtn.visible = false;
            refuseBtn.visible = false;
            ackBtn.visible = false;
         
        }

       boolean showCompose = isCompose;
        kindBtn.visible = showCompose;
        aTypeBtn.visible = showCompose;
        bTypeBtn.visible = showCompose;
        aAmtBox.visible = showCompose;
        bAmtBox.visible = showCompose;
        maxAmtBox.visible = showCompose;
        noteBox.visible = showCompose;
        cbBtn.visible = showCompose;
        sendBtn.visible = showCompose;

        refreshRows();
        refreshButtons();

        if (t == Tab.COMPOSE) {
            recipientsRefreshCooldown = 0;     // force immediate refresh
            requestRecipientsRefresh();        // get fresh server relations
            requestPolicyForSelectedRecipient();
        }
    }

    // -------------------------
    // Data
    // -------------------------

    private List<Letter> inbox() {
        return ClientMailCache.getInbox();
    }

    private List<String> news() {
        return ClientNewsCache.get();
    }

    private List<mailRecipientsSyncS2CPayload.Entry> recipients() {
        return ClientMailRecipientsCache.get();
    }

    private Letter getSelectedInbox() {
        List<Letter> list = inbox();
        if (list.isEmpty()) return null;
        inboxSelected = clampIndex(inboxSelected, list.size());
        return list.get(inboxSelected);
    }

    private mailRecipientsSyncS2CPayload.Entry getSelectedRecipient() {
        List<mailRecipientsSyncS2CPayload.Entry> list = recipients();
        if (list.isEmpty()) return null;
        recipientSelected = clampIndex(recipientSelected, list.size());
        return list.get(recipientSelected);
    }

    private void requestPolicyForSelectedRecipient() {
        var rec = getSelectedRecipient();
        if (rec == null) return;
        ClientPlayNetworking.send(new name.kingdoms.payload.mailPolicyRequestC2SPayload(rec.kingdomId()));
    }

    public void requestPolicyForSelectedRecipientPublic() {
        requestPolicyForSelectedRecipient();
    }

    // -------------------------
    // Inbox actions
    // -------------------------

    private void onAccept() {
        Letter l = getSelectedInbox();
        if (!isActionable(l)) return;
        ClientPlayNetworking.send(new mailActionC2SPayload(l.id(), mailActionC2SPayload.Action.ACCEPT));
    }

    private void onRefuse() {
        Letter l = getSelectedInbox();
        if (!isActionable(l)) return;
        ClientPlayNetworking.send(new mailActionC2SPayload(l.id(), mailActionC2SPayload.Action.REFUSE));
    }

    private void onAcknowledge() {
        Letter l = getSelectedInbox();
        if (!isAcknowledgeOnly(l)) return;
        ClientPlayNetworking.send(new mailActionC2SPayload(l.id(), mailActionC2SPayload.Action.ACKNOWLEDGE));
    }

    // -------------------------
    // Compose
    // -------------------------

    private void cycleKind() {
        composeKind = nextKindInCompose(composeKind);

        if (composeKind == Letter.Kind.CONTRACT && composeBType == composeAType) {
            composeBType = nextRes(composeBType);
        }

        kindBtn.setMessage(Component.literal("Kind: " + composeKind.name()));
        aTypeBtn.setMessage(Component.literal(labelAType()));
        bTypeBtn.setMessage(Component.literal(labelBType()));

        refreshButtons();
    }

    private void cycleCb() {
        Letter.CasusBelli[] vals = Letter.CasusBelli.values();
        int i = composeCb.ordinal() + 1;
        if (i >= vals.length) i = 0;
        composeCb = vals[i];
        cbBtn.setMessage(Component.literal("CB: " + composeCb.name()));
        refreshButtons();
    }

    private void cycleAType() {
        composeAType = nextRes(composeAType);
        if (composeKind == Letter.Kind.CONTRACT && composeBType == composeAType) {
            composeBType = nextRes(composeBType);
        }
        aTypeBtn.setMessage(Component.literal(labelAType()));
        bTypeBtn.setMessage(Component.literal(labelBType()));
        clampComposeToEconomy();
        refreshButtons();
    }

    private void cycleBType() {
        composeBType = nextRes(composeBType);
        if (composeBType == composeAType) composeBType = nextRes(composeBType);
        bTypeBtn.setMessage(Component.literal(labelBType()));
        clampComposeToEconomy();
        refreshButtons();
    }

    private static ResourceType nextRes(ResourceType cur) {
        ResourceType[] vals = ResourceType.values();
        int i = cur.ordinal() + 1;
        if (i >= vals.length) i = 0;
        return vals[i];
    }

    private void onSend() {
        var rec = getSelectedRecipient();
        if (rec == null) return;

        clampComposeToEconomy();
        if (!isComposeValid()) return;

        updateComposeBlockMsg();
        if (!composeBlockMsg.isEmpty()) return;

        double aAmt = parseAmt(aAmtBox.getValue());
        double bAmt = parseAmt(bAmtBox.getValue());
        double maxAmt = parseAmt(maxAmtBox.getValue());

        UUID reqId = UUID.randomUUID();

        ResourceType bType = (composeKind == Letter.Kind.CONTRACT) ? composeBType : null;
        double outBAmt = (composeKind == Letter.Kind.CONTRACT) ? bAmt : 0.0;
        double outMax = (composeKind == Letter.Kind.CONTRACT) ? maxAmt : 0.0;

        // canned note for compliment/insult (no input box)
        String note;
        if (composeKind == Letter.Kind.COMPLIMENT) note = "Your court is impressive.";
        else if (composeKind == Letter.Kind.INSULT) note = "Your rule is an embarrassment.";
        else note = kindUsesNote(composeKind) ? noteBox.getValue() : "";

        Letter.CasusBelli cb = (composeKind == Letter.Kind.WAR_DECLARATION) ? composeCb : null;

        long expiresTick = 0; // ultimatum has NO deadline now

        ClientPlayNetworking.send(new mailSendC2SPayload(
                reqId,
                rec.kingdomId(),
                composeKind,
                composeAType,
                aAmt,
                bType,
                outBAmt,
                outMax,
                cb,
                note,
                expiresTick
        ));
    }

    private void refreshRows() {
        if (tab == Tab.INBOX) {
            List<Letter> list = inbox();
            inboxScroll = clampScroll(inboxScroll, list.size());

            for (int i = 0; i < rowButtons.size(); i++) {
                Button row = rowButtons.get(i);
                int actual = inboxScroll + i;
                if (actual < list.size()) {
                    Letter l = list.get(actual);
                    row.setMessage(Component.literal(summarize(l)));
                } else row.setMessage(Component.literal(""));

                            }
        } else if (tab == Tab.COMPOSE) {
            List<mailRecipientsSyncS2CPayload.Entry> list = recipients();
            recipientScroll = clampScroll(recipientScroll, list.size());

            for (int i = 0; i < rowButtons.size(); i++) {
                Button row = rowButtons.get(i);
                int actual = recipientScroll + i;
                if (actual < list.size()) {
                    var e = list.get(actual);
                    row.setMessage(recipientRowLabel(e));
                } else row.setMessage(Component.literal(""));
            }

        } else { // NEWS
            List<String> list = news();
            newsScroll = clampScroll(newsScroll, list.size());

            for (int i = 0; i < rowButtons.size(); i++) {
                Button row = rowButtons.get(i);
                int actual = newsScroll + i;
                if (actual < list.size()) row.setMessage(Component.literal(list.get(actual)));
                else row.setMessage(Component.literal(""));
            }
        }
    }


    private void refreshButtons() {
        
        if (tab == Tab.NEWS) {
            // nothing to refresh besides row labels
            return;
        }

        if (tab == Tab.INBOX) {
            Letter l = getSelectedInbox();
            boolean actionable = isActionable(l);
            boolean ackOnly = isAcknowledgeOnly(l);

            inboxAcceptBlockMsg = "";

            if (actionable) {
                inboxAcceptBlockMsg = acceptBlockedReason(l);
                boolean canAccept = inboxAcceptBlockMsg.isEmpty();

                acceptBtn.active = canAccept;
                refuseBtn.active = true;

                acceptBtn.visible = true;
                refuseBtn.visible = true;

                ackBtn.active = false;
                ackBtn.visible = false;
            } else {
                acceptBtn.active = false;
                refuseBtn.active = false;

                acceptBtn.visible = false;
                refuseBtn.visible = false;

                ackBtn.active = ackOnly;
                ackBtn.visible = ackOnly;
            }
            return;
        }


        boolean usesA = kindUsesEconomyA(composeKind);
        boolean isContract = kindUsesContractB(composeKind);

        String kindLabel = "Kind: " + composeKind.name();
        var rec = getSelectedRecipient();
        if (rec != null) {
            var d = ClientMailPolicyCache.get(rec.kingdomId(), composeKind);
            if (d == null) kindLabel += " (...)";
            else if (!d.allowed()) kindLabel += " (blocked)";
        }
        kindBtn.setMessage(Component.literal(kindLabel));
        aTypeBtn.setMessage(Component.literal(labelAType()));
        bTypeBtn.setMessage(Component.literal(labelBType()));

        aTypeBtn.active = usesA;
        aAmtBox.setEditable(usesA);

        bTypeBtn.active = isContract;
        bAmtBox.setEditable(isContract);
        maxAmtBox.setEditable(isContract);

        cbBtn.active = kindUsesCb(composeKind);

        aTypeBtn.visible = usesA;
        aAmtBox.visible  = usesA;

        bTypeBtn.visible  = isContract;
        bAmtBox.visible   = isContract;
        maxAmtBox.visible = isContract;

        cbBtn.visible = kindUsesCb(composeKind);

        noteBox.visible = kindUsesNote(composeKind);
        if (!kindUsesNote(composeKind)) noteBox.setValue("");

        updateComposeBlockMsg();
        boolean blocked = !composeBlockMsg.isEmpty();

        sendBtn.active = !blocked && isComposeValid();
    }

    private boolean isComposeValid() {
        var rec = getSelectedRecipient();
        if (rec == null) return false;

        // diplomatic-only kinds
        if (composeKind == Letter.Kind.COMPLIMENT ||
            composeKind == Letter.Kind.INSULT ||
            composeKind == Letter.Kind.WARNING ||
            composeKind == Letter.Kind.ALLIANCE_PROPOSAL ||
            composeKind == Letter.Kind.ALLIANCE_BREAK ||
            composeKind == Letter.Kind.WHITE_PEACE ||
            composeKind == Letter.Kind.SURRENDER ||
            composeKind == Letter.Kind.WAR_DECLARATION) {
            return true;
        }

        if (composeKind == Letter.Kind.ULTIMATUM) {
            return parseAmt(aAmtBox.getValue()) > 0;
        }

        // request/offer/contract
        double aAmt = parseAmt(aAmtBox.getValue());
        if (aAmt <= 0) return false;

        if (composeKind == Letter.Kind.OFFER) {
            return playerCanAfford(composeAType, aAmt);
        }

        if (composeKind == Letter.Kind.CONTRACT) {
            double bAmt = parseAmt(bAmtBox.getValue());
            double maxAmt = parseAmt(maxAmtBox.getValue());
            if (bAmt <= 0 || maxAmt <= 0) return false;
            if (composeBType == composeAType) return false;
           
                // You are the sender: you must afford A per trade (or at least A once, depending on how you apply it)
                return playerCanAfford(composeAType, aAmt);
        }

        return true;
    }

    private void clampComposeToEconomy() {
        if (tab != Tab.COMPOSE) return;
        if (!kindUsesEconomyA(composeKind)) return;

        double aAmt = parseAmt(aAmtBox.getValue());
        double bAmt = parseAmt(bAmtBox.getValue());
        double maxAmt = parseAmt(maxAmtBox.getValue());

        if (composeKind == Letter.Kind.OFFER) {
            double haveA = ClientEconomyView.get(composeAType);
            if (aAmt > haveA) aAmtBox.setValue(fmt(haveA));
        }

        if (composeKind == Letter.Kind.CONTRACT) {
            // Clamp A (you give) to what YOU have
            double haveA = ClientEconomyView.get(composeAType);
            if (aAmt > haveA) aAmtBox.setValue(fmt(haveA));

            // Keep maxAmount >= A per trade so at least 1 trade is possible
            double aPerTrade = parseAmt(aAmtBox.getValue());
            double clampedMax = Math.max(maxAmt, aPerTrade);
            if (Math.abs(clampedMax - maxAmt) > 0.000001) maxAmtBox.setValue(fmt(clampedMax));

            // DO NOT clamp B to your economy (they pay B, not you)
        }

    }

    private boolean playerCanAfford(ResourceType type, double amt) {
        return ClientEconomyView.get(type) >= amt;
    }

    private String acceptBlockedReason(Letter l) {
        if (l == null) return "No letter selected.";
        if (l.status() != Letter.Status.PENDING) return "Already resolved.";

        // Only meaningful for kinds that have accept actions
        if (!isActionable(l)) return "";

        // What does the PLAYER have to pay to accept?
        // Based on your server logic:
        // - REQUEST: player pays A
        // - CONTRACT: player pays B
        // - ULTIMATUM (AI -> player): player pays A
        // - OFFER: player pays nothing
        ResourceType costType = null;
        double costAmt = 0.0;

        switch (l.kind()) {
            case REQUEST -> { // player pays A
                costType = l.aType();
                costAmt = l.aAmount();
            }
            case CONTRACT -> { // player pays B (at least once)
                costType = l.bType();
                costAmt = l.bAmount();
            }
            case ULTIMATUM -> {
                // Only costs player resources if AI sent the ultimatum
                if (l.fromIsAi()) {
                    costType = l.aType();
                    costAmt = l.aAmount();
                }
            }
            case OFFER -> {
                // Accepting an offer costs player nothing
                return "";
            }
            default -> {
                return "";
            }
        }

        if (costType == null) return "";

        double have = ClientEconomyView.get(costType);
        if (have + 0.000001 < costAmt) {
            return "Not enough " + costType.name() + " (need " + fmt(costAmt) + ", have " + fmt(have) + ").";
        }
        return "";
    }


    @Override
    public void tick() {
        super.tick();

        // Keep recipient relations fresh from the server while composing
        if (tab == Tab.COMPOSE) {
            if (recipientsRefreshCooldown <= 0) {
                requestRecipientsRefresh();
                recipientsRefreshCooldown = RECIPIENTS_REFRESH_PERIOD;
            } else {
                recipientsRefreshCooldown--;
            }
        } else {
            recipientsRefreshCooldown = 0; // reset when not composing
        }

        if (tab == Tab.INBOX) inboxSelected = clampIndex(inboxSelected, inbox().size());
        else if (tab == Tab.COMPOSE) recipientSelected = clampIndex(recipientSelected, recipients().size());
        else newsSelected = clampIndex(newsSelected, news().size());

        refreshRows();
        clampComposeToEconomy();
        refreshButtons();
    }

    // -------------------------
    // Render
    // -------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, this.width, this.height, 0xAA000000);
        g.drawString(this.font, this.title, left, Math.max(4, top - 36), 0xFFFFFFFF);

        int panelBottom = this.panelBottom;

        g.fill(left, panelTop, listRight, panelBottom, 0x66000000);
        g.fill(detailsLeft, panelTop, rightEdge, panelBottom, 0x66000000);



        if (tab == Tab.INBOX) renderInboxDetails(g);
        else if (tab == Tab.COMPOSE) renderComposeDetails(g);
        else renderNewsDetails(g);

        // scroll hint
        g.drawString(this.font, "(mouse wheel to scroll list)", left + 8, panelBottom + 6, 0x888888);

        super.render(g, mouseX, mouseY, delta);

        // Draw recipient head icons on top of the list rows (compose only)
        if (tab == Tab.COMPOSE) {
            List<mailRecipientsSyncS2CPayload.Entry> list = recipients();
            recipientScroll = clampScroll(recipientScroll, list.size());

            for (int i = 0; i < rowButtons.size(); i++) {
                int actual = recipientScroll + i;
                if (actual >= list.size()) continue;

                var e = list.get(actual);
                ItemStack icon = recipientHeadStack(e);

                int ix = listX + 1;
                int iy = listY + i * rowH + 2;

                if (icon.isEmpty()) {
                    g.fill(ix, iy, ix + 16, iy + 16, 0xFF444444);
                } else {
                    g.renderItem(icon, ix, iy);
                }


            }
        }
    }

    private void renderInboxDetails(GuiGraphics g) {
        Letter sel = getSelectedInbox();
        int dx = detailsLeft + 10;
        int dy = top + 25;

        if (sel == null) {
            g.drawString(this.font, "No letters.", dx, dy, 0xFFFFFFFF);
            return;
        }

        g.drawString(this.font, summarize(sel), dx, dy, 0xFFFFFFFF);
        dy += 14;

        g.drawString(this.font, "Status: " + sel.status(), dx, dy, 0xAAAAAAAA);
        dy += 12;

        g.drawString(this.font, "From: " + safe(sel.fromName()), dx, dy, 0xFFFFFFFF);
        dy += 18;

        switch (sel.kind()) {
            case REQUEST -> {
                boolean fromAi = sel.fromIsAi();
                boolean resolved = sel.status() != Letter.Status.PENDING;

                String header;
                if (!resolved) {
                    header = fromAi ? "They request:" : "You request:";
                } else if (sel.status() == Letter.Status.ACCEPTED) {
                    header = fromAi ? "Accepted. You gave:" : "Accepted. They gave:";
                } else if (sel.status() == Letter.Status.REFUSED) {
                    header = fromAi ? "Refused. They requested:" : "Refused. You requested:";
                } else { // EXPIRED
                    header = fromAi ? "Expired. They requested:" : "Expired. You requested:";
                }

                g.drawString(this.font, header, dx, dy, 0xFFFFFFFF);
                dy += 12;

                g.drawString(this.font, fmt(sel.aAmount()) + " " + sel.aType(), dx, dy, 0xFFFFFFFF);
            }

            case OFFER -> {
                boolean fromAi = sel.fromIsAi();
                boolean resolved = sel.status() != Letter.Status.PENDING;

                String header;
                if (!resolved) {
                    header = fromAi ? "They offer:" : "You offer:";
                } else if (sel.status() == Letter.Status.ACCEPTED) {
                    header = fromAi ? "Accepted. They gave:" : "Accepted. You gave:";
                } else if (sel.status() == Letter.Status.REFUSED) {
                    header = fromAi ? "Refused. They offered:" : "Refused. You offered:";
                } else { // EXPIRED
                    header = fromAi ? "Expired. They offered:" : "Expired. You offered:";
                }

                g.drawString(this.font, header, dx, dy, 0xFFFFFFFF);
                dy += 12;

                g.drawString(this.font, fmt(sel.aAmount()) + " " + sel.aType(), dx, dy, 0xFFFFFFFF);
            }

            case CONTRACT -> {
                boolean fromAi = sel.fromIsAi();
                boolean resolved = sel.status() != Letter.Status.PENDING;

                String header;
                if (!resolved) {
                    header = fromAi ? "They propose a contract:" : "You propose a contract:";
                } else if (sel.status() == Letter.Status.ACCEPTED) {
                    header = "Contract executed.";
                } else if (sel.status() == Letter.Status.REFUSED) {
                    header = "Contract refused.";
                } else { // EXPIRED
                    header = "Contract expired.";
                }

                g.drawString(this.font, header, dx, dy, 0xFFFFFFFF);
                dy += 12;

                // Direction depends on who sent it
                if (fromAi) {
                    // AI -> you: you pay B, you get A
                    g.drawString(this.font,
                            "You pay: " + fmt(sel.bAmount()) + " " + safeRes(sel.bType()),
                            dx, dy, 0xFFFFFFFF);
                    dy += 12;

                    g.drawString(this.font,
                            "You get: " + fmt(sel.aAmount()) + " " + sel.aType(),
                            dx, dy, 0xFFFFFFFF);
                    dy += 12;

                    g.drawString(this.font,
                            "Cap: " + fmt(sel.maxAmount()) + " " + sel.aType(),
                            dx, dy, 0xAAAAAAAA);
                } else {
                    // You -> them: you give A, they pay B
                    g.drawString(this.font,
                            "You give: " + fmt(sel.aAmount()) + " " + sel.aType(),
                            dx, dy, 0xFFFFFFFF);
                    dy += 12;

                    g.drawString(this.font,
                            "They pay: " + fmt(sel.bAmount()) + " " + safeRes(sel.bType()),
                            dx, dy, 0xFFFFFFFF);
                    dy += 12;

                    g.drawString(this.font,
                            "Cap: " + fmt(sel.maxAmount()) + " " + sel.aType(),
                            dx, dy, 0xAAAAAAAA);
                }
            }

            case ULTIMATUM -> {
                g.drawString(this.font, "They demand:", dx, dy, 0xFFFFFFFF); dy += 12;
                g.drawString(this.font, fmt(sel.aAmount()) + " " + sel.aType(), dx, dy, 0xFFFFFFFF);
            }
            case WAR_DECLARATION -> {
                g.drawString(this.font, "Casus Belli: " + (sel.cb() == null ? "UNKNOWN" : sel.cb().name()), dx, dy, 0xFFFFFFFF); dy += 12;
            }
            default -> {
            }
        }

        // Always show body text if present (works for economic + non-economic)
        String body = sel.note();
        if (body != null && !body.isBlank()) {
            int bx = detailsLeft + 10;
            int by = dy + 10;// adjust if you want it higher/lower
            int maxW = rightEdge - (detailsLeft + 20);
            drawWrapped(g, body, bx, by, maxW, 0xFFDDDDDD, 6);
        }

        // Show accept-block reason near buttons
        if (inboxAcceptBlockMsg != null && !inboxAcceptBlockMsg.isBlank()) {
            int msgX = detailsLeft + 10;
            int msgY = panelBottom + 6;
            int maxW = rightEdge - (detailsLeft + 20);
            drawWrapped(g, inboxAcceptBlockMsg, msgX, msgY, maxW, 0xFFFF7777, 2);
        }


    }

    private void renderNewsDetails(GuiGraphics g) {
        int dx = detailsLeft + 10;
        int dy = top + 25;

        List<String> list = news();
        if (list.isEmpty()) {
            g.drawString(this.font, "No news yet.", dx, dy, 0xFFFFFFFF);
            return;
        }

        newsSelected = clampIndex(newsSelected, list.size());
        String text = list.get(newsSelected);

        g.drawString(this.font, "News", dx, dy, 0xFFFFFFFF);
        dy += 14;

        // simple wrap-ish: draw in chunks
        int maxChars = 44;
        for (int i = 0; i < text.length(); i += maxChars) {
            String part = text.substring(i, Math.min(text.length(), i + maxChars));
            g.drawString(this.font, part, dx, dy, 0xFFDDDDDD);
            dy += 12;
            if (dy > detailsBottom - 12) break;
        }
    }


    private void renderComposeDetails(GuiGraphics g) {
        int x = detailsLeft + 10;
        int y = detailsTop;

        var rec = getSelectedRecipient();
        String toText = (rec == null) ? "(none)" : rec.name();

        int rel = (rec == null) ? 0 : rec.relation();
        int color = 0xFFFFFFFF;
        if (rel >= REL_ALLY_THRESHOLD) color = 0xFF55FF55;      // green-ish
        else if (rel <= REL_ENEMY_THRESHOLD) color = 0xFFFF5555; // red-ish

        if (rec != null) {
            // draw tiny heraldry next to "To:"
            ItemStack herald = recipientHeraldryStack(rec);
            if (!herald.isEmpty()) g.renderItem(herald, x, y - 2);

            // (optional) head too:
            ItemStack head = recipientHeadStack(rec);
            if (!head.isEmpty()) g.renderItem(head, x + 18, y - 2);

            x += 36; // shift text right if you drew two icons
        }


        g.drawString(font, "To: " + toText, x, y, color);

        y += 12;

        // Draw contract labels aligned to the actual edit boxes (responsive!)
        if (composeKind == Letter.Kind.CONTRACT) {
            int labelX = aAmtBox.getX() + aAmtBox.getWidth() + 6;
            g.drawString(font, "A per trade", labelX, aAmtBox.getY() + 5, 0x888888);
            g.drawString(font, "B per trade", labelX, bAmtBox.getY() + 5, 0x888888);
            g.drawString(font, "Cap (A total)", labelX, maxAmtBox.getY() + 5, 0x888888);
        }

        // If blocked, draw it ABOVE the Send button so it never goes off-screen
        if (!composeBlockMsg.isEmpty() && sendBtn != null && sendBtn.visible) {
            int msgX = detailsLeft + 10;
            int maxW = rightEdge - (detailsLeft + 20);

            int msgY = sendBtn.getY() - 26; // 1 line above send button
            if (msgY < detailsTop + 24) msgY = detailsTop + 24;

            drawWrapped(g, composeBlockMsg, msgX, msgY, maxW, 0xFFFF7777, 2);
        }

        // Small helper text: clamp it inside the panel
        int hintY = detailsBottom - 14;
        if (hintY < y + 10) hintY = y + 10;

        if (composeKind == Letter.Kind.COMPLIMENT) {
            g.drawString(font, "Compliment sends a canned message.", x, hintY, 0xAAAAAA);
        } else if (composeKind == Letter.Kind.INSULT) {
            g.drawString(font, "Insult sends a canned message.", x, hintY, 0xAAAAAA);
        } else if (composeKind == Letter.Kind.ULTIMATUM) {
            g.drawString(font, "Ultimatum has no deadline (GDD).", x, hintY, 0xAAAAAA);
        }
    }


    // -------------------------
    // Formatting helpers
    // -------------------------

    private static String safe(String s) {
        return (s == null || s.isBlank()) ? "Unknown" : s;
    }

    private static double parseAmt(String s) {
        try {
            if (s == null || s.isBlank()) return 0;
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String fmt(double v) {
        if (Math.abs(v - Math.round(v)) < 0.000001) return Long.toString(Math.round(v));
        return String.format(Locale.US, "%.2f", v);
    }

   private static String summarize(Letter l) {
        if (l == null) return "";

        if (l.subject() != null && !l.subject().isBlank()) {
            return l.subject();
        }

        boolean sentByPlayer = !l.fromIsAi();
        boolean resolved = l.status() != Letter.Status.PENDING;
        
        String kindLabel = switch (l.kind()) {
            case OFFER -> "Offer";
            case REQUEST -> "Request";
            case CONTRACT -> "Contract";
            case ULTIMATUM -> "Ultimatum";
            case WAR_DECLARATION -> "War";
            case ALLIANCE_PROPOSAL -> "Alliance";
            case WHITE_PEACE -> "White Peace";
            case SURRENDER -> "Surrender";
            case ALLIANCE_BREAK -> "Alliance Break";
            case COMPLIMENT -> "Compliment";
            case INSULT -> "Insult";
            case WARNING -> "Warning";
            default -> l.kind().name();
        };

        // OFFER: perspective + tense aware
        if (l.kind() == Letter.Kind.OFFER) {
            String prefix;
            if (!resolved) {
                prefix = sentByPlayer ? "You offer " : "They offer ";
            } else if (l.status() == Letter.Status.ACCEPTED) {
                prefix = sentByPlayer ? "Accepted. You gave " : "Accepted. They gave ";
            } else if (l.status() == Letter.Status.REFUSED) {
                prefix = sentByPlayer ? "Refused. You offered " : "Refused. They offered ";
            } else { // EXPIRED
                prefix = sentByPlayer ? "Expired. You offered " : "Expired. They offered ";
            }

            return prefix + fmt(l.aAmount()) + " " + l.aType();
        }

        if (l.kind() == Letter.Kind.REQUEST) {
            String prefix;
            if (!resolved) {
                prefix = sentByPlayer ? "You request " : "They request ";
            } else if (l.status() == Letter.Status.ACCEPTED) {
                prefix = sentByPlayer ? "Accepted. You received " : "Accepted. They received ";
            } else if (l.status() == Letter.Status.REFUSED) {
                prefix = sentByPlayer ? "Refused. You requested " : "Refused. They requested ";
            } else { // EXPIRED
                prefix = sentByPlayer ? "Expired. You requested " : "Expired. They requested ";
            }

            return prefix + fmt(l.aAmount()) + " " + l.aType();
        }

        if (l.kind() == Letter.Kind.CONTRACT) {
            if (!resolved) {
                if (l.fromIsAi()) {
                    // AI -> you: you pay B to receive A
                    return "Contract: "
                            + fmt(l.bAmount()) + " " + safeRes(l.bType())
                            + " → "
                            + fmt(l.aAmount()) + " " + l.aType();
                } else {
                    // You -> them: you give A to receive B
                    return "Contract: "
                            + fmt(l.aAmount()) + " " + l.aType()
                            + " → "
                            + fmt(l.bAmount()) + " " + safeRes(l.bType());
                }
            }

            return switch (l.status()) {
                case ACCEPTED -> "Trade executed";
                case REFUSED  -> "Contract refused";
                case EXPIRED  -> "Contract expired";
                default       -> "Contract";
            };
        }



        boolean hasAmt = Math.abs(l.aAmount()) > 0.000001;

        return switch (l.kind()) {
            case REQUEST -> (sentByPlayer ? "You request: " : "Request: ")
                    + (hasAmt ? fmt(l.aAmount()) : "(no amount)") + " " + l.aType();

            case CONTRACT -> "Contract: "
                    + fmt(l.bAmount()) + " " + safeRes(l.bType()) + " → "
                    + fmt(l.aAmount()) + " " + l.aType()
                    + " (cap " + fmt(l.maxAmount()) + " " + l.aType() + ")";

            case ULTIMATUM ->
                    kindLabel + ": " + fmt(l.aAmount()) + " " + l.aType();

            case WAR_DECLARATION ->
                    kindLabel + ": " + (l.cb() == null ? "UNKNOWN" : l.cb().name());

            default -> kindLabel;
        };
    }



    private static String safeRes(ResourceType t) {
        return t == null ? "?" : t.name();
    }

    // --- Recipient list visuals ---
    private static final int RECIPIENT_ICON_PAD = 36;
    private static final int REL_ALLY_THRESHOLD  = 40;
    private static final int REL_ENEMY_THRESHOLD = -40;

    private Component recipientRowLabel(mailRecipientsSyncS2CPayload.Entry e) {
        if (e == null) return Component.literal("");

        Component prefix = Component.empty();
        int rel = e.relation();

        if (rel >= REL_ALLY_THRESHOLD) {
            prefix = Component.literal("ALLY ").withStyle(ChatFormatting.GREEN);
        } else if (rel <= REL_ENEMY_THRESHOLD) {
            prefix = Component.literal("ENEMY ").withStyle(ChatFormatting.RED);
        }

        Component name = Component.literal(e.name());

        // keep your existing suffix
        Component suffix = Component.empty();
        if (e.isAi()) {
            suffix = Component.literal("  [rel " + fmtSigned(rel) + "]")
                    .withStyle(ChatFormatting.GRAY);
        }

        return Component.empty().append(prefix).append(name).append(suffix);
    }

   private ItemStack recipientHeadStack(mailRecipientsSyncS2CPayload.Entry e) {
        if (e == null || !e.isAi()) return ItemStack.EMPTY;

        int id = Mth.clamp(e.headSkinId(), 0, kingSkinPoolState.MAX_SKIN_ID);

        Object raw = name.kingdoms.modItem.KING_HEADS[id];
        if (!(raw instanceof Item item)) return ItemStack.EMPTY;

        return new ItemStack(item);
    }


    private ItemStack recipientHeraldryStack(mailRecipientsSyncS2CPayload.Entry e) {
        if (e == null) return ItemStack.EMPTY;
        ItemStack h = e.heraldry();
        return (h == null) ? ItemStack.EMPTY : h;
    }


}
