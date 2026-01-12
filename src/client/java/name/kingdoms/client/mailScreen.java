package name.kingdoms.client;

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

    private int listX, listY, listH;

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
        rightEdge = this.width - 10;

        int totalMinW = 540;
        int usableW = Math.max(totalMinW, rightEdge - 10);

        left = Math.max(10, (this.width - usableW) / 2);
        top  = Math.max(10, this.height / 2 - 110);

        listW = Math.max(240, (int)(usableW * 0.55));
        detailsW = Math.max(220, usableW - listW - 10);

        listRight = left + listW;
        detailsLeft = listRight + 10;

        rowW = listW - 16;
        detailsInnerW = detailsW - 20;

        listX = left + 8;
        listY = top + 20;
        listH = 9 * 20;

        inboxTabBtn = Button.builder(Component.literal("Inbox"), b -> setTab(Tab.INBOX))
                .bounds(left, top - 22, 80, 20).build();

        composeTabBtn = Button.builder(Component.literal("Compose"), b -> setTab(Tab.COMPOSE))
                .bounds(left + 85, top - 22, 80, 20).build();

        newsTabBtn = Button.builder(Component.literal("News"), b -> {
                setTab(Tab.NEWS);
                requestNews();
            })
            .bounds(left + 170, top - 22, 80, 20).build();


        addRenderableWidget(newsTabBtn);
        addRenderableWidget(inboxTabBtn);
        addRenderableWidget(composeTabBtn);

        int actionX = detailsLeft + 10;
        int actionY = top + 185;
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
        int ry = top + 30;

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
                .bounds(rx, top + 195, Math.min(detailsInnerW, 270), 18).build();
        addRenderableWidget(sendBtn);

        setTab(tab);
    }

    private String labelAType() {
        return switch (composeKind) {
            case OFFER -> "You give (A): " + composeAType.name();
            case REQUEST -> "You request (A): " + composeAType.name();
            case ULTIMATUM -> "They demand (A): " + composeAType.name();
            case CONTRACT -> "They give (A): " + composeAType.name();
            default -> "A: " + composeAType.name();
        };
    }


    private String labelBType() {
        return "You pay (B): " + composeBType.name();
    }

    private boolean isNumber(String s) {
        return s.isEmpty() || s.matches("^[0-9]*\\.?[0-9]*$");
    }

    private void buildRowButtons() {
        rowButtons.clear();

        int rowH = 20;
        for (int i = 0; i < 9; i++) {
            final int visibleIdx = i;
            Button row = Button.builder(Component.literal(""), b -> onRowClicked(visibleIdx))
                    .bounds(listX, listY + visibleIdx * rowH, rowW, rowH)
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
                    String suffix = e.isAi() ? ("  [rel " + fmtSigned(e.relation()) + "]") : "";
                    row.setMessage(Component.literal(e.name() + suffix));
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

            acceptBtn.active = actionable;
            refuseBtn.active = actionable;

            acceptBtn.visible = actionable;
            refuseBtn.visible = actionable;

            ackBtn.active = ackOnly;
            ackBtn.visible = (!actionable) && ackOnly;
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
            return playerCanAfford(composeBType, bAmt);
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
            double haveB = ClientEconomyView.get(composeBType);
            if (bAmt > haveB) bAmtBox.setValue(fmt(haveB));

            double clampedMax = Math.max(maxAmt, parseAmt(aAmtBox.getValue()));
            if (Math.abs(clampedMax - maxAmt) > 0.000001) maxAmtBox.setValue(fmt(clampedMax));
        }
    }

    private boolean playerCanAfford(ResourceType type, double amt) {
        return ClientEconomyView.get(type) >= amt;
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
        g.drawString(this.font, this.title, left, top - 36, 0xFFFFFFFF);

        int panelBottom = top + 220;

        g.fill(left, top + 15, listRight, panelBottom, 0x66000000);
        g.fill(detailsLeft, top + 15, rightEdge, panelBottom, 0x66000000);

        if (tab == Tab.INBOX) renderInboxDetails(g);
        else if (tab == Tab.COMPOSE) renderComposeDetails(g);
        else renderNewsDetails(g);

        // scroll hint
        g.drawString(this.font, "(mouse wheel to scroll list)", left + 8, panelBottom + 6, 0x888888);

        super.render(g, mouseX, mouseY, delta);
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
                g.drawString(this.font, "Contract:", dx, dy, 0xFFFFFFFF); dy += 12;
                g.drawString(this.font, "You pay: " + fmt(sel.bAmount()) + " " + safeRes(sel.bType()), dx, dy, 0xFFFFFFFF); dy += 12;
                g.drawString(this.font, "You get: " + fmt(sel.aAmount()) + " " + sel.aType(), dx, dy, 0xFFFFFFFF); dy += 12;
                g.drawString(this.font, "Cap: " + fmt(sel.maxAmount()) + " " + sel.aType(), dx, dy, 0xAAAAAAAA);
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
            if (dy > top + 210) break;
        }
    }


    private void renderComposeDetails(GuiGraphics g) {
        int x = detailsLeft + 10;
        int y = top + 18;

        var rec = getSelectedRecipient();
        g.drawString(font, "To: " + (rec == null ? "(none)" : rec.name()), x, y, 0xFFFFFFFF);
        y += 12;

        if (!composeBlockMsg.isEmpty()) {
            g.drawString(font, composeBlockMsg, x, y, 0xFFFF7777);
            y += 12;
        }

        // Draw labels for the 3 amount boxes when CONTRACT
        if (composeKind == Letter.Kind.CONTRACT) {
            g.drawString(font, "A per trade", x + 190, top + 52, 0x888888);
            g.drawString(font, "B per trade", x + 190, top + 74, 0x888888);
            g.drawString(font, "Cap (A total)", x + 190, top + 96, 0x888888);
        }

        if (composeKind == Letter.Kind.COMPLIMENT) {
            g.drawString(font, "Compliment sends a canned message.", x, top + 155, 0xAAAAAA);
        } else if (composeKind == Letter.Kind.INSULT) {
            g.drawString(font, "Insult sends a canned message.", x, top + 155, 0xAAAAAA);
        } else if (composeKind == Letter.Kind.ULTIMATUM) {
            g.drawString(font, "Ultimatum has no deadline (GDD).", x, top + 155, 0xAAAAAA);
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
                return "Contract: "
                        + fmt(l.bAmount()) + " " + safeRes(l.bType())
                        + " → "
                        + fmt(l.aAmount()) + " " + l.aType();
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
}
