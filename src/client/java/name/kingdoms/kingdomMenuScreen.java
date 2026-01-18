package name.kingdoms;

import name.kingdoms.payload.CreateKingdomPayload;
import name.kingdoms.payload.disbandKingdomPayload;
import name.kingdoms.payload.requestBorderWandPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public class kingdomMenuScreen extends Screen {

    private final BlockPos origin;
    private final boolean exists;
    private final String kingdomName;

    private EditBox nameBox;

    public kingdomMenuScreen(BlockPos origin) {
        this(origin, false, "");
    }

    public kingdomMenuScreen(BlockPos origin, boolean exists, String kingdomName) {
        super(Component.literal("Kingdom"));
        this.origin = origin;
        this.exists = exists;
        this.kingdomName = (kingdomName == null) ? "" : kingdomName;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int cy = height / 2;

        boolean hasName = exists && !kingdomName.trim().isEmpty();

        if (hasName && !playerHasBorderWand()) {
            addRenderableWidget(
                    Button.builder(Component.literal("Set Borders"), b -> {
                        b.active = false;

                        // Request wand from server
                        ClientPlayNetworking.send(new requestBorderWandPayload());

                        // Show instructions in chat
                        if (minecraft != null && minecraft.player != null) {
                            minecraft.player.displayClientMessage(Component.literal(
                                    "Left/Right click with the border wand to set borders. " +
                                            "Borders are shown with particles. " +
                                            "Once they are set as a square, throw the wand away. " +
                                            "You can always re-set the borders again."
                            ), false);
                        }

                        onClose();
                    }).bounds(cx - 70, cy - 40, 140, 20).build()
            );
        }

        if (!exists) {
            nameBox = new EditBox(font, cx - 100, cy - 10, 200, 20, Component.literal("Kingdom Name"));
            nameBox.setMaxLength(32);
            addRenderableWidget(nameBox);

            addRenderableWidget(
                    Button.builder(Component.literal("Create Kingdom"), b -> {
                        String name = nameBox.getValue().trim();
                        if (name.isEmpty()) return;

                        b.active = false;
                        ClientPlayNetworking.send(new CreateKingdomPayload(origin, name));
                        onClose();
                    }).bounds(cx - 60, cy + 20, 120, 20).build()
            );
        } else {
            // Open confirm screen instead of one-click disband
            addRenderableWidget(
                    Button.builder(Component.literal("Disband Kingdom"), b -> {
                        if (minecraft == null) return;
                        minecraft.setScreen(new DisbandConfirmScreen(this, origin));
                    }).bounds(cx - 60, cy + 20, 120, 20).build()
            );
        }

        addRenderableWidget(
                Button.builder(Component.literal("Close"), b -> onClose())
                        .bounds(cx - 40, cy + 50, 80, 20).build()
        );
    }

    private boolean playerHasBorderWand() {
        if (minecraft == null || minecraft.player == null) return false;

        var player = minecraft.player;
        var inv = player.getInventory();

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(modItem.BORDER_WAND)) return true;
        }

        ItemStack offhand = player.getOffhandItem();
        return !offhand.isEmpty() && offhand.is(modItem.BORDER_WAND);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float delta) {
        gui.fill(0, 0, this.width, this.height, 0xAA000000);

        int cx = width / 2;
        int titleY = height / 2 - 70;

        if (!exists) {
            gui.drawCenteredString(font, title, cx, titleY, 0xFFFFFFFF);
        } else {
            gui.drawCenteredString(font, Component.literal("Current Kingdom: " + kingdomName), cx, titleY, 0xFFFFFFFF);
        }

        super.render(gui, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Simple confirmation modal for disbanding the kingdom.
     */
    private static final class DisbandConfirmScreen extends Screen {

        private final Screen parent;
        private final BlockPos origin;

        protected DisbandConfirmScreen(Screen parent, BlockPos origin) {
            super(Component.literal("Confirm Disband"));
            this.parent = parent;
            this.origin = origin;
        }

        @Override
        protected void init() {
            int cx = width / 2;
            int cy = height / 2;

            addRenderableWidget(
                    Button.builder(Component.literal("Yes, disband kingdom"), b -> {
                        b.active = false;
                        ClientPlayNetworking.send(new disbandKingdomPayload(origin));
                        onClose();
                    }).bounds(cx - 90, cy + 10, 180, 20).build()
            );

            addRenderableWidget(
                    Button.builder(Component.literal("No"), b -> onClose())
                            .bounds(cx - 40, cy + 35, 80, 20).build()
            );
        }

        @Override
        public void render(GuiGraphics gui, int mouseX, int mouseY, float delta) {
            gui.fill(0, 0, this.width, this.height, 0xCC000000);

            int cx = width / 2;
            int y = height / 2 - 45;

            gui.drawCenteredString(font, Component.literal("Are you sure you want to disband your kingdom?"), cx, y, 0xFFFFFFFF);
            y += 14;
            gui.drawCenteredString(font, Component.literal("This cannot be undone."), cx, y, 0xFFFF5555);

            super.render(gui, mouseX, mouseY, delta);
        }

        @Override
        public void onClose() {
            if (minecraft != null) {
                minecraft.setScreen(parent);
            }
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
