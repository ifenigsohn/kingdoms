package name.kingdoms;

import name.kingdoms.payload.ecoSyncPayload;
import name.kingdoms.payload.treasuryBuyJobPayload;
import name.kingdoms.payload.treasuryShopSyncPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import name.kingdoms.payload.ecoRequestPayload;



import java.util.*;

/**
 * Treasury UI:
 *  - Left: resources
 *  - Right: categorized shop list (ordered), collapsible groups
 */
public class treasuryScreen extends Screen {


    private final BlockPos treasuryPos;

    // Widgets
    private Button closeBtn;
    private final List<Button> buyButtons = new ArrayList<>();
    private final List<Integer> buyButtonItemIndex = new ArrayList<>();
    private final List<Button> groupButtons = new ArrayList<>();
    private final List<Integer> groupButtonItemIndex = new ArrayList<>();

    // Layout
    private static final int PAD = 12;
    private static final int GAP = 8;

    private static final int HEADER_H = 18;
    private static final int ROW_H = 78;

    private static final int BUY_W = 44;
    private static final int BUY_H = 18;

    private static final int GROUP_W = 28;
    private static final int GROUP_H = 16;

    // Display window for deltas
    private static final double DELTA_SECONDS = 10.0;

    // Pixel scrolling for mixed-height list
    private int scrollPx = 0;

    // Collapsible groups
    private final Map<String, Boolean> collapsed = new HashMap<>();

    // Render list
    private List<RenderItem> renderItems = List.of();

    // Group spec + order
    private static final List<Group> GROUPS = List.of(
            new Group("food", "Food", List.of("farm", "butcher", "fishing")),
            new Group("materials", "Materials", List.of("wood", "metal", "gem")),
            new Group("gear", "Gear", List.of("stable", "weapon", "armor")),
            new Group("military", "Military", List.of("guard", "garrison", "training")),
            new Group("civic", "Civic", List.of("nobility", "alchemy", "chapel")),
            new Group("services", "Services", List.of("tavern", "shop"))
    );

    private static final double FOOD_EPS = 1e-9;

    private static int foodTypesPresent(double meat, double grain, double fish) {
        int t = 0;
        if (meat  > FOOD_EPS) t++;
        if (grain > FOOD_EPS) t++;
        if (fish  > FOOD_EPS) t++;
        return t;
    }

    private static double multForTypes(int types) {
        return (types == 3) ? 1.20 : (types == 2) ? 1.10 : 1.00;
    }

    private static double minPositive(double meat, double grain, double fish) {
        double m = Double.POSITIVE_INFINITY;
        if (meat  > FOOD_EPS) m = Math.min(m, meat);
        if (grain > FOOD_EPS) m = Math.min(m, grain);
        if (fish  > FOOD_EPS) m = Math.min(m, fish);
        return (m == Double.POSITIVE_INFINITY) ? 0.0 : m;
    }

    /** Same piecewise “variety bonus” effective food as server. */
    private static double effectiveFood(double meat, double grain, double fish) {
        // clamp negatives (can happen when projecting)
        if (meat  < 0) meat  = 0;
        if (grain < 0) grain = 0;
        if (fish  < 0) fish  = 0;

        double eff = 0.0;

        while (true) {
            int types = foodTypesPresent(meat, grain, fish);
            if (types == 0) break;

            double mult = multForTypes(types);
            double step = minPositive(meat, grain, fish);

            eff += step * types * mult;

            if (meat  > FOOD_EPS) meat  -= step;
            if (grain > FOOD_EPS) grain -= step;
            if (fish  > FOOD_EPS) fish  -= step;

            if (meat  < FOOD_EPS) meat  = 0.0;
            if (grain < FOOD_EPS) grain = 0.0;
            if (fish  < FOOD_EPS) fish  = 0.0;
        }

        return eff;
    }


    private record Group(String key, String title, List<String> jobIds) {}

    private interface RenderItem {
        int height();
        boolean isHeader();
    }

    private record HeaderItem(String groupKey, String title) implements RenderItem {
        @Override public int height() { return HEADER_H; }
        @Override public boolean isHeader() { return true; }
    }

    private record EntryItem(treasuryShopSyncPayload.Entry entry) implements RenderItem {
        @Override public int height() { return ROW_H; }
        @Override public boolean isHeader() { return false; }
    }

    public treasuryScreen(BlockPos treasuryPos) {
        super(Component.literal("Treasury"));
        this.treasuryPos = treasuryPos;
        for (Group g : GROUPS) collapsed.put(g.key(), false);
    }

    /* -----------------------------
       LOCK STATE
     ----------------------------- */

    private boolean isLocked() {
        ecoSyncPayload eco = kingdomsClient.CLIENT_ECO;
        return eco == null || !eco.connected();
    }

    /* -----------------------------
       INIT
     ----------------------------- */

    @Override
    public void init() {
        super.init();

        closeBtn = Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(this.width - 62, 8, 54, 18)
                .build();

        // Only build interactive widgets when connected
        if (!isLocked()) {
            rebuildAll();
        } else {
            this.clearWidgets();
            this.addRenderableWidget(closeBtn);
        }
    }

    /** Call this when payload changes or after toggling groups. */
    public void rebuildAll() {
        buildRenderItems();
        clampScroll();
        rebuildShopWidgets();
    }

    /* -----------------------------
       SHOP GEOMETRY
     ----------------------------- */

    private int shopHeaderY() { return 40; }
    private int shopTopY() { return shopHeaderY() + 18; }
    private int shopBottomY() { return this.height - 30; }

    private int shopPanelLeft() { return this.width / 2 + 20; }
    private int shopPanelRight() { return this.width - PAD; }

    private int buyX() { return shopPanelRight() - BUY_W; }
    private int groupBtnX() { return shopPanelRight() - GROUP_W; }

    private int viewHeight() {
        return Math.max(1, shopBottomY() - shopTopY());
    }

    private int totalHeight() {
        int sum = 0;
        for (RenderItem it : renderItems) sum += it.height();
        return sum;
    }

    private void clampScroll() {
        int max = Math.max(0, totalHeight() - viewHeight());
        scrollPx = Math.max(0, Math.min(scrollPx, max));
    }

    /* -----------------------------
       BUILD LIST
     ----------------------------- */

    private void buildRenderItems() {
        treasuryShopSyncPayload shop = kingdomsClient.CLIENT_TREASURY_SHOP;
        if (shop == null) {
            renderItems = List.of();
            return;
        }

        Map<String, treasuryShopSyncPayload.Entry> byId = new HashMap<>();
        for (treasuryShopSyncPayload.Entry e : shop.entries()) byId.put(e.jobId(), e);

        List<RenderItem> out = new ArrayList<>();
        Set<String> used = new HashSet<>();

        for (Group g : GROUPS) {
            out.add(new HeaderItem(g.key(), g.title()));

            if (!Boolean.TRUE.equals(collapsed.get(g.key()))) {
                for (String id : g.jobIds()) {
                    treasuryShopSyncPayload.Entry e = byId.get(id);
                    if (e != null) {
                        out.add(new EntryItem(e));
                        used.add(id);
                    }
                }
            }
        }

        // Unknown jobs (future-proof)
        List<treasuryShopSyncPayload.Entry> unknown = new ArrayList<>();
        for (treasuryShopSyncPayload.Entry e : shop.entries()) {
            if (!used.contains(e.jobId())) unknown.add(e);
        }
        unknown.sort(Comparator.comparing(treasuryShopSyncPayload.Entry::jobId));

        if (!unknown.isEmpty()) {
            String key = "other";
            collapsed.putIfAbsent(key, false);

            out.add(new HeaderItem(key, "Other"));
            if (!Boolean.TRUE.equals(collapsed.get(key))) {
                for (treasuryShopSyncPayload.Entry e : unknown) out.add(new EntryItem(e));
            }
        }

        renderItems = out;
    }

    /* -----------------------------
       WIDGETS
     ----------------------------- */

    private void rebuildShopWidgets() {
        this.clearWidgets();
        buyButtons.clear();
        buyButtonItemIndex.clear();
        groupButtons.clear();
        groupButtonItemIndex.clear();

        if (closeBtn != null) this.addRenderableWidget(closeBtn);

        // Don’t build any shop widgets while locked
        if (isLocked()) return;

        int y = shopTopY() - scrollPx;

        for (int i = 0; i < renderItems.size(); i++) {
            RenderItem it = renderItems.get(i);
            int h = it.height();

            if (y + h < shopTopY()) { y += h; continue; }
            if (y > shopBottomY()) break;

            if (it.isHeader()) {
                HeaderItem hi = (HeaderItem) it;
                boolean isCollapsed = Boolean.TRUE.equals(collapsed.get(hi.groupKey()));
                Component label = Component.literal(isCollapsed ? "[+]" : "[-]");

                Button gb = Button.builder(label, btn -> {
                            collapsed.put(hi.groupKey(), !Boolean.TRUE.equals(collapsed.get(hi.groupKey())));
                            rebuildAll();
                        })
                        .bounds(groupBtnX(), y + 1, GROUP_W, GROUP_H)
                        .build();

                groupButtons.add(gb);
                groupButtonItemIndex.add(i);
                this.addRenderableWidget(gb);

            } else {
                EntryItem ei = (EntryItem) it;
                treasuryShopSyncPayload.Entry e = ei.entry();

                Button buy = Button.builder(Component.literal("Buy"), btn -> {
                            ClientPlayNetworking.send(new treasuryBuyJobPayload(treasuryPos, e.jobId(), 1));
                        })
                        .bounds(buyX(), y + 44, BUY_W, BUY_H)
                        .build();

                buyButtons.add(buy);
                buyButtonItemIndex.add(i);
                this.addRenderableWidget(buy);
            }

            y += h;
        }
    }

    /* -----------------------------
    INPUT (LOCKED) - 1.21.10 event signatures
    ----------------------------- */

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        if (isLocked()) return false;
        return super.mouseClicked(event, isDoubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (isLocked()) return false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (isLocked()) return false;
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (isLocked()) return false;

        int stepPx = 18; // tweak feel: 12–24
        if (verticalAmount > 0) scrollPx -= stepPx;   // wheel up
        else if (verticalAmount < 0) scrollPx += stepPx; // wheel down

        clampScroll();
        rebuildShopWidgets();
        return true;
    }


    @Override
    public boolean keyPressed(KeyEvent event) {
        // Let Screen handle ESC etc.
        if (isLocked()) return super.keyPressed(event);
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (isLocked()) return false;
        return super.charTyped(event);
    } 

    /* -----------------------------
       RENDER
     ----------------------------- */

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        this.renderTransparentBackground(g);

        ecoSyncPayload eco = kingdomsClient.CLIENT_ECO;

        // HARD LOCK VIEW
        if (eco == null) {
            g.drawCenteredString(this.font, "Treasury", this.width / 2, 12, 0xFFFFFFFF);
            g.drawCenteredString(this.font, "(waiting for server...)", this.width / 2, 34, 0xAAAAAAAA);
            super.render(g, mouseX, mouseY, delta);
            return;
        }

        if (!eco.connected()) {
            g.drawCenteredString(this.font, "NOT CONNECTED TO KINGDOM", this.width / 2, 12, 0xFFFF4444);
            g.drawCenteredString(this.font,
                    "Place this treasury inside a kingdom border to use it.",
                    this.width / 2, 34, 0xFFFFFFFF);
            super.render(g, mouseX, mouseY, delta);
            return;
        }

        // NORMAL TITLE
        String name = eco.kingdomName();
        if (name == null || name.isBlank()) name = "Kingdom";
        g.drawCenteredString(this.font, name + " Treasury", this.width / 2, 12, 0xFFFFFFFF);

        // LEFT: economy
        int leftX = this.width / 2 - 220;
        int y = 54;
        g.drawString(this.font, "Resources", leftX, y, 0xFFFFFFFF);
        y += 14;

        // Happiness line (colored)
        double h = eco.happiness();
        int hColor = (h >= 7.0) ? 0xFF22CC22 : (h >= 4.0) ? 0xFFFFCC00 : 0xFFFF4444;
        String band = (h >= 7.0) ? "High" : (h >= 4.0) ? "Medium" : "Low";
        g.drawString(this.font, String.format("Happiness: %.2f (%s)", h, band), leftX, y, hColor);
        y += 14;

        // Security line (colored)
        double s = eco.securityValue();
        double req = eco.requiredSecurity();
        String sBand = eco.securityBand();
        int sColor = (s >= 0.40) ? 0xFF22CC22 : (s >= req) ? 0xFFFFCC00 : 0xFFFF4444;

        // If you want the "3:10" style shown:
        int per10 = (int) Math.round(s * 10.0);

        g.drawString(this.font,
                String.format("Security: %d:10 (%.2f / %.2f, %s)", per10, s, req, sBand),
                leftX, y, sColor);
        y += 14;


        y = drawLine(g, leftX, y, "Gold", eco.gold(), eco.dGold() * DELTA_SECONDS);

        double meat  = eco.meat();
        double grain = eco.grain();
        double fish  = eco.fish();

        // Project base pools forward by DELTA_SECONDS using the server-provided per-second deltas
        double meat2  = meat  + eco.dMeat()  * DELTA_SECONDS;
        double grain2 = grain + eco.dGrain() * DELTA_SECONDS;
        double fish2  = fish  + eco.dFish()  * DELTA_SECONDS;

        // Effective food now + then (piecewise), and delta over DELTA_SECONDS
        double foodNow  = effectiveFood(meat, grain, fish);
        double foodThen = effectiveFood(meat2, grain2, fish2);
        double dFood = foodThen - foodNow;

        // Show effective food with proper delta
        y = drawLine(g, leftX, y, "Food (effective)", foodNow, dFood);

        // Still show base pools (deltas are already /10s)
        y = drawLine(g, leftX, y, "Meat",  meat,  eco.dMeat()  * DELTA_SECONDS);
        y = drawLine(g, leftX, y, "Grain", grain, eco.dGrain() * DELTA_SECONDS);
        y = drawLine(g, leftX, y, "Fish",  fish,  eco.dFish()  * DELTA_SECONDS);
        y = drawLine(g, leftX, y, "Wood",    eco.wood(),    eco.dWood()    * DELTA_SECONDS);
        y = drawLine(g, leftX, y, "Metal",   eco.metal(),   eco.dMetal()   * DELTA_SECONDS);
        y = drawLine(g, leftX, y, "Armor",   eco.armor(),   eco.dArmor()   * DELTA_SECONDS);
        y = drawLine(g, leftX, y, "Weapons", eco.weapons(), eco.dWeapons() * DELTA_SECONDS);
        y = drawLine(g, leftX, y, "Gems",    eco.gems(),    eco.dGems()    * DELTA_SECONDS);
        y = drawLine(g, leftX, y, "Horses",  eco.horses(),  eco.dHorses()  * DELTA_SECONDS);
        y = drawLine(g, leftX, y, "Potions", eco.potions(), eco.dPotions() * DELTA_SECONDS);

        // RIGHT: shop
        int panelLeft = shopPanelLeft();
        int panelRight = shopPanelRight();
        int headerY = shopHeaderY();
        g.drawString(this.font, "Buy Job Blocks", panelLeft, headerY, 0xFFFFFFFF);

        treasuryShopSyncPayload shop = kingdomsClient.CLIENT_TREASURY_SHOP;
        if (shop == null) {
            g.drawString(this.font, "(waiting for shop list...)", panelLeft, headerY + 14, 0xAAAAAAAA);
            super.render(g, mouseX, mouseY, delta);
            return;
        }

        if (renderItems.isEmpty() && !shop.entries().isEmpty()) {
            rebuildAll();
        }

        int textRight = buyX() - GAP;
        int textW = Math.max(0, textRight - panelLeft);

        int curY = shopTopY() - scrollPx;

        for (int i = 0; i < renderItems.size(); i++) {
            RenderItem it = renderItems.get(i);
            int ih = it.height();

            if (curY + ih < shopTopY()) { curY += ih; continue; }
            if (curY > shopBottomY()) break;

            if (it.isHeader()) {
                HeaderItem hi = (HeaderItem) it;
                g.fill(panelLeft - 4, curY - 2, panelRight, curY - 2 + HEADER_H, 0x77000000);

                boolean isCollapsed = Boolean.TRUE.equals(collapsed.get(hi.groupKey()));
                String title = hi.title() + (isCollapsed ? " (collapsed)" : "");
                g.drawString(this.font, title, panelLeft, curY + 4, 0xFFFFFFFF);

            } else {
                EntryItem ei = (EntryItem) it;
                treasuryShopSyncPayload.Entry e = ei.entry();

                g.fill(panelLeft - 4, curY - 4, panelRight, curY - 4 + ROW_H - 2, 0x55000000);

                Component n = Component.translatable(blockNameKey(e.jobId()));
                Component desc = Component.translatable(blockDescKey(e.jobId()));

                g.drawString(this.font,
                        this.font.plainSubstrByWidth(n.getString(), textW),
                        panelLeft, curY, 0xFFEFEFEF);

                List<FormattedCharSequence> descLines = this.font.split(desc, textW);
                int maxDescLines = Math.min(3, descLines.size());
                for (int li = 0; li < maxDescLines; li++) {
                    g.drawString(this.font, descLines.get(li), panelLeft, curY + 12 + li * 10, 0xFFB0B0B0);
                }

                int afterDescY = curY + 12 + maxDescLines * 10;

                String cost = "Cost: " + costString(e);
                g.drawString(this.font, this.font.plainSubstrByWidth(cost, textW), panelLeft, afterDescY + 2, 0xFFE0E0E0);

                jobDefinition def = jobDefinition.byId(e.jobId());
                String io = (def == null)
                        ? "In: -   Out: -"
                        : ("In: " + ioString(def, true) + "   Out: " + ioString(def, false));
                g.drawString(this.font, this.font.plainSubstrByWidth(io, textW), panelLeft, afterDescY + 14, 0xFFE0E0E0);
            }

            curY += ih;
        }

        // button enabled state
        for (int b = 0; b < buyButtons.size(); b++) {
            int itemIdx = buyButtonItemIndex.get(b);
            if (itemIdx < 0 || itemIdx >= renderItems.size()) continue;
            RenderItem it = renderItems.get(itemIdx);
            if (it instanceof EntryItem ei) {
                buyButtons.get(b).active = canAfford(eco, ei.entry());
            }
        }

        super.render(g, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }


    /* -----------------------------
       ECON / COST / IO HELPERS
     ----------------------------- */

    private static int deltaColor(double d) {
        if (d > 1.0e-9)  return 0xFF55FF55;  // green
        if (d < -1.0e-9) return 0xFFFF5555;  // red
        return 0xFFFFFF55;                   // yellow
    }

    /** per10s is already scaled to DELTA_SECONDS. */
    private int drawLine(GuiGraphics g, int x, int y, String label, double value, double per10s) {
        String v = fmt(value);
        String d = fmtSigned(per10s) + "/10s";

        String left = label + ": " + v + " (";
        String right = ")";

        int baseColor = 0xFFE0E0E0;

        g.drawString(this.font, left, x, y, baseColor, false);
        int dx = x + this.font.width(left);
        g.drawString(this.font, d, dx, y, deltaColor(per10s), false);
        int rx = dx + this.font.width(d);
        g.drawString(this.font, right, rx, y, baseColor, false);

        return y + 12;
    }

    private boolean canAfford(ecoSyncPayload eco, treasuryShopSyncPayload.Entry e) {
        return  eco.gold()    + 1e-9 >= e.costGold() &&
                eco.meat()    + 1e-9 >= e.costMeat() &&
                eco.grain()   + 1e-9 >= e.costGrain() &&
                eco.fish()    + 1e-9 >= e.costFish() &&
                eco.wood()    + 1e-9 >= e.costWood() &&
                eco.metal()   + 1e-9 >= e.costMetal() &&
                eco.armor()   + 1e-9 >= e.costArmor() &&
                eco.weapons() + 1e-9 >= e.costWeapons() &&
                eco.gems()    + 1e-9 >= e.costGems() &&
                eco.horses()  + 1e-9 >= e.costHorses() &&
                eco.potions() + 1e-9 >= e.costPotions();
    }

    private String fmt(double v) {
        if (Math.abs(v) < 1e-6) return "0";
        if (Math.floor(v) == v) return Integer.toString((int) v);
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private String fmtSigned(double v) {
        String s = fmt(Math.abs(v));
        return (v >= 0 ? "+" : "-") + s;
    }

    private String costString(treasuryShopSyncPayload.Entry e) {
        StringBuilder sb = new StringBuilder();
        append(sb, "g", e.costGold());
        append(sb, "meat", e.costMeat());
        append(sb, "grain", e.costGrain());
        append(sb, "fish", e.costFish());
        append(sb, "wood", e.costWood());
        append(sb, "metal", e.costMetal());
        append(sb, "armor", e.costArmor());
        append(sb, "weap", e.costWeapons());
        append(sb, "gems", e.costGems());
        append(sb, "horses", e.costHorses());
        append(sb, "pot", e.costPotions());
        return sb.length() == 0 ? "(free)" : sb.toString();
    }

    private void append(StringBuilder sb, String label, double v) {
        if (Math.abs(v) < 1e-6) return;
        if (sb.length() > 0) sb.append(", ");
        if (Math.floor(v) == v) sb.append((int) v);
        else sb.append(String.format(Locale.ROOT, "%.2f", v));
        sb.append(" ").append(label);
    }

    private static String ioString(jobDefinition def, boolean inputs) {
        StringBuilder sb = new StringBuilder();

        if (inputs) {
            appendIO(sb, "g", def.inGold());
            appendIO(sb, "food", def.inFood());
            appendIO(sb, "wood", def.inWood());
            appendIO(sb, "metal", def.inMetal());
            appendIO(sb, "armor", def.inArmor());
            appendIO(sb, "weap", def.inWeapons());
            appendIO(sb, "gems", def.inGems());
            appendIO(sb, "horses", def.inHorses());
            appendIO(sb, "pot", def.inPotions());
        } else {
            appendIO(sb, "g", def.outGold());
            appendIO(sb, "meat", def.outMeat());
            appendIO(sb, "grain", def.outGrain());
            appendIO(sb, "fish", def.outFish());
            appendIO(sb, "wood", def.outWood());
            appendIO(sb, "metal", def.outMetal());
            appendIO(sb, "armor", def.outArmor());
            appendIO(sb, "weap", def.outWeapons());
            appendIO(sb, "gems", def.outGems());
            appendIO(sb, "horses", def.outHorses());
            appendIO(sb, "pot", def.outPotions());
        }

        return sb.length() == 0 ? "-" : sb.toString();
    }

    private static void appendIO(StringBuilder sb, String label, double v) {
        if (Math.abs(v) < 1e-6) return;
        if (sb.length() > 0) sb.append(" ");
        if (Math.floor(v) == v) sb.append((int) v);
        else sb.append(String.format(Locale.ROOT, "%.2f", v));
        sb.append(" ").append(label);
    }

    private static String blockNameKey(String jobId) {
        return "block.kingdoms." + blockPath(jobId);
    }

    private static String blockDescKey(String jobId) {
        return "tooltip.kingdoms." + blockPath(jobId);
    }

    private static String blockPath(String jobId) {
        return switch (jobId) {
            case "farm"     -> "grain_block";
            case "butcher"  -> "butcher_block";
            case "fishing"  -> "fish_block";

            case "wood"     -> "wood_block";
            case "metal"    -> "metal_block";
            case "gem"      -> "gem_block";

            case "stable"   -> "stable_block";
            case "weapon"   -> "weapon_block";
            case "armor"    -> "armor_block";

            case "guard"    -> "guard_block";
            case "garrison" -> "garrison_block";
            case "training" -> "training_block";

            case "nobility" -> "nobility_block";
            case "alchemy"  -> "alchemy_block";
            case "chapel"   -> "chapel_block";

            case "tavern"   -> "tavern_block";
            case "shop"     -> "shop_block";

            default -> jobId + "_block";
        };
    }
   
    @Override
    public void tick() {
        super.tick();
        if (this.minecraft == null || this.minecraft.level == null) return;
        if (isLocked()) return;

        // refresh eco every 2 seconds while open
        if ((this.minecraft.level.getGameTime() % 40L) == 0L) {
            ClientPlayNetworking.send(new ecoRequestPayload());
        }
    }

}
