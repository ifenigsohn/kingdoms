


package name.kingdoms;

import name.kingdoms.payload.jobReqsS2CPayload;
import name.kingdoms.payload.toggleJobEnabledPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.text.DecimalFormat;
import java.util.*;

public class jobRequirementsScreen extends Screen {

    private static final DecimalFormat NUM = new DecimalFormat("0.##");

    private final jobReqsS2CPayload payload;

    // Scroll state for the REQUIRES list
    private int reqScroll = 0;

    // Remember the screen-space area of the REQUIRES list (so we only scroll when hovering it)
    private int reqAreaTop = 0;
    private int reqAreaBottom = 0;


    // local UI state (we can flip instantly after clicking)
    private boolean enabled;
    private Button toggleBtn;

    public jobRequirementsScreen(jobReqsS2CPayload payload) {
        super(Component.literal("Job Info"));
        this.payload = payload;
        this.enabled = payload.enabled();
    }

    private record JobNote(String text, int color) {}

    @Override
    protected void init() {
        super.init();

        int cx = this.width / 2;
        int btnW = 160;
        int btnH = 20;
        int y = this.height - 28;

        toggleBtn = Button.builder(toggleLabel(), b -> {
                    ClientPlayNetworking.send(new toggleJobEnabledPayload(payload.pos()));
                    enabled = !enabled; // optimistic UI
                    b.setMessage(toggleLabel());
                })
                .bounds(cx - btnW / 2, y, btnW, btnH)
                .build();

        this.addRenderableWidget(toggleBtn);
    }

    private Component toggleLabel() {
        return enabled ? Component.literal("Disable Production")
                       : Component.literal("Enable Production");
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xCC000000);

        int cx = this.width / 2;
        int y = 18;
        int left = Math.max(20, cx - 140);
        int bottomPad = 44;


        String jobId = payload.jobId();
        jobDefinition job = jobDefinition.byId(jobId);
        List<Line> inputs = (job == null) ? Collections.emptyList() : buildInputs(job);


        // Pretty job name + disabled suffix
       MutableComponent prettyJob = Component.translatable("job.kingdoms." + jobId);
        MutableComponent header = Component.literal("Job: ").append(prettyJob);

        if (!enabled) {
            header.append(Component.literal(" (manually disabled)"));
        }

        g.drawCenteredString(this.font, header, cx, y, enabled ? 0xFFFFFFFF : 0xFFFF8888);
        y += 14;

        if (job != null) {
            y = drawCycleLine(g, cx, y, job);
            y += 6;

            y = drawInputsSection(g, left, y, "Inputs (per cycle)", inputs);
            y += 8;
            y = drawSection(g, left, y, "Outputs (per cycle)", buildOutputs(job), 0xFF55FF55);
            y += 12;

        } else {
            g.drawCenteredString(this.font,
                    Component.literal("Unknown job id (client registry missing)."),
                    cx, y, 0xFFFF5555);
            y += 18;
        }
        // Grab requirements data now (we use it for Status notes too)
        Map<String, Integer> required = payload.required();
        Map<String, Integer> have = payload.have();

        // --- Status notes (show why production is blocked) ---
        List<JobNote> notes = (job == null)
                ? Collections.singletonList(new JobNote("Unknown job id (client registry missing).", 0xFFFF7777))
                : buildJobNotes(job, enabled, inputs, required, have);

        y = drawNotesSection(g, left, y, notes);
        y += 12;

        // Now draw the Requirements header
        g.drawCenteredString(this.font,
                Component.literal("Requirements (within " + payload.radius() + " blocks)"),
                cx, y, 0xFFA0A0A0);
        y += 14;


        if (required == null || required.isEmpty()) {
            g.drawCenteredString(this.font, Component.literal("No requirements."), cx, y, 0xFFFFFFFF);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }

        List<String> keys = new ArrayList<>(required.keySet());
        keys.sort(Comparator.comparing(jobRequirementsScreen::displayNameForSort));


        // Define scrollable region
        reqAreaTop = y;
        reqAreaBottom = this.height - bottomPad;

        // How many lines fit
        int maxLines = Math.max(1, (reqAreaBottom - reqAreaTop) / 12);

        // Clamp scroll
        int maxScroll = Math.max(0, keys.size() - maxLines);
        if (reqScroll > maxScroll) reqScroll = maxScroll;
        if (reqScroll < 0) reqScroll = 0;

        // Determine visible range
        int start = reqScroll;
        int end = Math.min(keys.size(), start + maxLines);

        for (int i = start; i < end; i++) {
            String key = keys.get(i);

            int rawNeed = required.getOrDefault(key, 0);
            int rawGot = (have == null) ? 0 : have.getOrDefault(key, 0);

            // Normalize beds: 2 blocks = 1 bed
            boolean isBed =
                    key.equals("minecraft:bed")
                    || key.equals("#minecraft:beds");

            int need = isBed ? (int) Math.ceil(rawNeed / 2.0) : rawNeed;
            int got  = isBed ? (int) Math.floor(rawGot  / 2.0) : rawGot;

            boolean ok = got >= need;

            Component name = displayName(key);
            String suffix = " : " + got + "/" + need + " " + (ok ? "\u2714" : "\u2716");
            int color = ok ? 0xFF55FF55 : 0xFFFF5555;


            g.drawString(this.font, name.copy().append(Component.literal(suffix)), left, y, color, false);

            y += 12;
        }

        // Optional scroll hint
        if (keys.size() > maxLines) {
            int above = start;
            int below = keys.size() - end;

            String hint = "";
            if (above > 0) hint += "\u2191 " + above + " above  ";
            if (below > 0) hint += "\u2193 " + below + " below";

            if (!hint.isEmpty()) {
                g.drawCenteredString(this.font,
                        Component.literal(hint.trim()),
                        cx,
                        this.height - bottomPad + 16,
                        0xFFA0A0A0);
            }
        }


        super.render(g, mouseX, mouseY, partialTick);
    }

    private int drawCycleLine(GuiGraphics g, int cx, int y, jobDefinition job) {
        int ticks = job.getWorkInterval();
        double seconds = ticks / 20.0;

        String timeStr = (seconds >= 60)
                ? NUM.format(seconds / 60.0) + " min"
                : NUM.format(seconds) + " s";

        g.drawCenteredString(this.font,
                Component.literal("Cycle: " + ticks + " ticks (" + timeStr + ")"),
                cx, y, 0xFFA0A0A0);
        return y + 12;
    }

    private int drawSection(GuiGraphics g, int left, int y, String title, List<Line> lines, int accentColor) {
        g.drawString(this.font, Component.literal(title), left, y, 0xFFFFFFFF, false);
        y += 12;

        if (lines.isEmpty()) {
            g.drawString(this.font, Component.literal("None"), left + 8, y, 0xFFA0A0A0, false);
            return y + 12;
        }

        // keep space for the button at bottom
        int bottomPad = 44;
        int maxLines = (this.height - y - bottomPad) / 12;

        int line = 0;
        for (Line l : lines) {
            if (line >= maxLines) break;
            g.drawString(this.font,
                    Component.literal(l.name + ": " + NUM.format(l.amount)),
                    left + 8, y, accentColor, false);
            y += 12;
            line++;
        }

        return y;
    }

    private int drawNotesSection(GuiGraphics g, int left, int y, List<JobNote> notes) {
        g.drawString(this.font, Component.literal("Status"), left, y, 0xFFFFFFFF, false);
        y += 12;

        for (JobNote n : notes) {
            g.drawString(this.font,
                    Component.literal("• " + n.text),
                    left + 8,
                    y,
                    n.color,
                    false);
            y += 12;
        }

        return y;
    }


    private static double ecoAmount(String name) {
        // If your eco sync payload uses ints, Java will auto-widen to double.
        var e = kingdomsClient.CLIENT_ECO;

        return switch (name) {
            case "Gold"    -> e.gold();
            case "Meat"    -> e.meat();
            case "Grain"   -> e.grain();
            case "Fish"    -> e.fish();
            case "Wood"    -> e.wood();
            case "Metal"   -> e.metal();
            case "Armor"   -> e.armor();
            case "Weapons" -> e.weapons();
            case "Gems"    -> e.gems();
            case "Horses"  -> e.horses();
            case "Potions" -> e.potions();
            default -> 0.0;
        };
    }

    private static boolean canAfford(Line in) {
        // If the client eco hasn’t synced yet, this will likely be zeros.
        // That’s fine: it will show red until eco arrives.
        return ecoAmount(in.name) >= in.amount;
    }

    private List<JobNote> buildJobNotes(
            jobDefinition job,
            boolean enabled,
            List<Line> inputs,
            Map<String, Integer> required,
            Map<String, Integer> have
    ) {
        List<JobNote> notes = new ArrayList<>();

        // 1) Manually disabled
        if (!enabled) {
            notes.add(new JobNote("Manually disabled", 0xFFFF7777));
        }

        // 2) Missing inputs
        List<String> missingInputs = new ArrayList<>();
        for (Line l : inputs) {
            if (!canAfford(l)) {
                missingInputs.add(l.name);
            }
        }
        if (!missingInputs.isEmpty()) {
            notes.add(new JobNote(
                    "Missing inputs: " + String.join(", ", missingInputs),
                    0xFFFF7777
            ));
        }

        // 3/4) Missing requirements vs borders
        boolean anyHave = false;
        if (have != null) {
            for (int v : have.values()) {
                if (v > 0) {
                    anyHave = true;
                    break;
                }
            }
        }

        if (required != null && !required.isEmpty() && !anyHave) {
            // If you have ZERO of every required item, you're likely outside borders
            notes.add(new JobNote("Not inside kingdom borders", 0xFFFF7777));
        } else {
            // Otherwise, list specific missing requirements
            List<String> missingReqs = new ArrayList<>();
            if (required != null) {
                for (Map.Entry<String, Integer> e : required.entrySet()) {
                    int need = e.getValue();
                    int got = (have == null) ? 0 : have.getOrDefault(e.getKey(), 0);
                    if (got < need) {
                        missingReqs.add(displayName(e.getKey()).getString());
                    }
                }
            }
            if (!missingReqs.isEmpty()) {
                notes.add(new JobNote(
                        "Missing requirements: " + String.join(", ", missingReqs),
                        0xFFFF7777
                ));
            }
        }


        // 5) If nothing blocked it, say it's producing
        if (notes.isEmpty()) {
            notes.add(new JobNote("Producing normally", 0xFF55FF55));
        }

        return notes;
    }


    private int drawInputsSection(GuiGraphics g, int left, int y, String title, List<Line> lines) {
        g.drawString(this.font, Component.literal(title), left, y, 0xFFFFFFFF, false);
        y += 12;

        if (lines.isEmpty()) {
            g.drawString(this.font, Component.literal("None"), left + 8, y, 0xFFA0A0A0, false);
            return y + 12;
        }

        int bottomPad = 44;
        int maxLines = (this.height - y - bottomPad) / 12;

        int line = 0;
        for (Line l : lines) {
            if (line >= maxLines) break;

            boolean ok = canAfford(l);
            int color = ok ? 0xFF55FF55 : 0xFFFF5555;

            double haveAmt = ecoAmount(l.name);

            g.drawString(this.font,
                    Component.literal(
                            l.name + ": " + NUM.format(l.amount)
                            + "  (" + NUM.format(haveAmt) + " available)"
                    ),
                    left + 8, y, color, false);

            y += 12;
            line++;
        }

        return y;
    }


    private static List<Line> buildInputs(jobDefinition j) {
        List<Line> out = new ArrayList<>();
        addIfNonZero(out, "Gold", j.inGold());
        addIfNonZero(out, "Meat", j.inMeat());
        addIfNonZero(out, "Grain", j.inGrain());
        addIfNonZero(out, "Fish", j.inFish());
        addIfNonZero(out, "Wood", j.inWood());
        addIfNonZero(out, "Metal", j.inMetal());
        addIfNonZero(out, "Armor", j.inArmor());
        addIfNonZero(out, "Weapons", j.inWeapons());
        addIfNonZero(out, "Gems", j.inGems());
        addIfNonZero(out, "Horses", j.inHorses());
        addIfNonZero(out, "Potions", j.inPotions());
        return out;
    }

    private static List<Line> buildOutputs(jobDefinition j) {
        List<Line> out = new ArrayList<>();
        addIfNonZero(out, "Gold", j.outGold());
        addIfNonZero(out, "Meat", j.outMeat());
        addIfNonZero(out, "Grain", j.outGrain());
        addIfNonZero(out, "Fish", j.outFish());
        addIfNonZero(out, "Wood", j.outWood());
        addIfNonZero(out, "Metal", j.outMetal());
        addIfNonZero(out, "Armor", j.outArmor());
        addIfNonZero(out, "Weapons", j.outWeapons());
        addIfNonZero(out, "Gems", j.outGems());
        addIfNonZero(out, "Horses", j.outHorses());
        addIfNonZero(out, "Potions", j.outPotions());
        return out;
    }

    private static void addIfNonZero(List<Line> lines, String name, double amount) {
        if (Math.abs(amount) < 1.0e-9) return;
        lines.add(new Line(name, amount));
    }

    private record Line(String name, double amount) {}

    private static Component displayName(String key) {
        if (key != null && key.startsWith("#")) {
            return Component.literal("Tag: " + key.substring(1));
        }

        try {
            ResourceLocation id = ResourceLocation.parse(key);
            Optional<Block> blockOpt = BuiltInRegistries.BLOCK.getOptional(id);
            if (blockOpt.isPresent()) return blockOpt.get().getName();
        } catch (Exception ignored) {}

        return Component.literal(String.valueOf(key));
    }

    private static String displayNameForSort(String key) {
        if (key != null && key.startsWith("#")) {
            return "zzz_tag_" + key; // push tags below blocks
        }

        try {
            ResourceLocation id = ResourceLocation.parse(key);
            Optional<Block> blockOpt = BuiltInRegistries.BLOCK.getOptional(id);
            if (blockOpt.isPresent()) return blockOpt.get().getName().getString();
        } catch (Exception ignored) {}

        return String.valueOf(key);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        // Only scroll when mouse is over the REQUIRES list region
        if (mouseY >= reqAreaTop && mouseY <= reqAreaBottom) {
            // Scroll direction: wheel up -> deltaY > 0
            if (deltaY > 0) reqScroll = Math.max(0, reqScroll - 1);
            else if (deltaY < 0) reqScroll = reqScroll + 1;

            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

}
