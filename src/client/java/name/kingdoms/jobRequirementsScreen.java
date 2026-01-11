/*package name.kingdoms;

import name.kingdoms.payload.jobReqsS2CPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class jobRequirementsScreen extends Screen {

    private static final DecimalFormat NUM = new DecimalFormat("0.##");

    private final jobReqsS2CPayload payload;

    public jobRequirementsScreen(jobReqsS2CPayload payload) {
        super(Component.literal("Job Info"));
        this.payload = payload;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xCC000000);

        int cx = this.width / 2;
        int y = 18;

        String jobId = payload.jobId();
        jobDefinition job = jobDefinition.byId(jobId);

        g.drawCenteredString(this.font, Component.literal("Job: " + jobId), cx, y, 0xFFFFFFFF);
        y += 14;

        if (job != null) {
            y = drawCycleLine(g, cx, y, job);
            y += 6;

            int left = Math.max(20, cx - 140);

            y = drawSection(g, left, y, "Inputs (per cycle)", buildInputs(job), 0xFFFFAA55);
            y += 8;
            y = drawSection(g, left, y, "Outputs (per cycle)", buildOutputs(job), 0xFF55FF55);
            y += 12;
        } else {
            g.drawCenteredString(this.font,
                    Component.literal("Unknown job id (client registry missing)."),
                    cx, y, 0xFFFF5555);
            y += 18;
        }

        g.drawCenteredString(this.font,
                Component.literal("Requirements (within " + payload.radius() + " blocks)"),
                cx, y, 0xFFA0A0A0);
        y += 14;

        Map<String, Integer> required = payload.required();
        Map<String, Integer> have = payload.have();

        if (required == null || required.isEmpty()) {
            g.drawCenteredString(this.font, Component.literal("No requirements."), cx, y, 0xFFFFFFFF);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }

        List<String> keys = new ArrayList<>(required.keySet());
        keys.sort(Comparator.comparing(jobRequirementsScreen::displayNameForSort));

        int left = Math.max(20, cx - 140);
        int maxLines = (this.height - y - 20) / 12;

        int line = 0;
        for (String key : keys) {
            if (line >= maxLines) break;

            int need = required.getOrDefault(key, 0);
            int got = (have == null) ? 0 : have.getOrDefault(key, 0);

            boolean ok = got >= need;

            Component name = displayName(key);
            String suffix = " : " + got + "/" + need + " " + (ok ? "\u2714" : "\u2716");
            int color = ok ? 0xFF55FF55 : 0xFFFF5555;

            g.drawString(this.font, name.copy().append(Component.literal(suffix)), left, y, color, false);

            y += 12;
            line++;
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

        int maxLines = (this.height - y - 20) / 12;
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
}*/


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

    // local UI state (we can flip instantly after clicking)
    private boolean enabled;
    private Button toggleBtn;

    public jobRequirementsScreen(jobReqsS2CPayload payload) {
        super(Component.literal("Job Info"));
        this.payload = payload;
        this.enabled = payload.enabled();
    }

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

        String jobId = payload.jobId();
        jobDefinition job = jobDefinition.byId(jobId);

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

            int left = Math.max(20, cx - 140);

            y = drawSection(g, left, y, "Inputs (per cycle)", buildInputs(job), 0xFFFFAA55);
            y += 8;
            y = drawSection(g, left, y, "Outputs (per cycle)", buildOutputs(job), 0xFF55FF55);
            y += 12;
        } else {
            g.drawCenteredString(this.font,
                    Component.literal("Unknown job id (client registry missing)."),
                    cx, y, 0xFFFF5555);
            y += 18;
        }

        g.drawCenteredString(this.font,
                Component.literal("Requirements (within " + payload.radius() + " blocks)"),
                cx, y, 0xFFA0A0A0);
        y += 14;

        Map<String, Integer> required = payload.required();
        Map<String, Integer> have = payload.have();

        if (required == null || required.isEmpty()) {
            g.drawCenteredString(this.font, Component.literal("No requirements."), cx, y, 0xFFFFFFFF);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }

        List<String> keys = new ArrayList<>(required.keySet());
        keys.sort(Comparator.comparing(jobRequirementsScreen::displayNameForSort));

        int left = Math.max(20, cx - 140);

        // keep space for the button at bottom
        int bottomPad = 44;
        int maxLines = (this.height - y - bottomPad) / 12;

        int line = 0;
        for (String key : keys) {
            if (line >= maxLines) break;

            int need = required.getOrDefault(key, 0);
            int got = (have == null) ? 0 : have.getOrDefault(key, 0);

            boolean ok = got >= need;

            Component name = displayName(key);
            String suffix = " : " + got + "/" + need + " " + (ok ? "\u2714" : "\u2716");
            int color = ok ? 0xFF55FF55 : 0xFFFF5555;

            g.drawString(this.font, name.copy().append(Component.literal(suffix)), left, y, color, false);

            y += 12;
            line++;
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
}
