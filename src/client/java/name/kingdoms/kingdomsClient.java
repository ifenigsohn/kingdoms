package name.kingdoms;

import com.mojang.blaze3d.platform.InputConstants;
import name.kingdoms.client.aiKingdomNPCRenderer;
import name.kingdoms.client.borderWandClient;
import name.kingdoms.client.clientProxyImpl;
import name.kingdoms.client.kingdomWorkerRenderer;
import name.kingdoms.client.KingdomTransitionHUD;
import name.kingdoms.client.SoldierRenderer;
import name.kingdoms.client.aiKingdomEntityRenderer;
import name.kingdoms.client.mailScreen;
import name.kingdoms.entity.modEntities;
import name.kingdoms.payload.ecoRequestPayload;
import name.kingdoms.payload.ecoSyncPayload;
import name.kingdoms.payload.kingdomInfoRequestPayload;
import name.kingdoms.payload.kingdomInfoSyncPayload;
import name.kingdoms.payload.kingdomQueryPayload;
import name.kingdoms.payload.mailInboxRequestC2SPayload;
import name.kingdoms.payload.mailRecipientsRequestC2SPayload;
import name.kingdoms.payload.treasuryOpenPayload;
import name.kingdoms.payload.treasuryShopSyncPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

import org.lwjgl.glfw.GLFW;

public class kingdomsClient implements ClientModInitializer {

    public static volatile kingdomInfoSyncPayload CLIENT_KINGDOM_INFO =
            new kingdomInfoSyncPayload(false, "");

    public static volatile treasuryShopSyncPayload CLIENT_TREASURY_SHOP =
            new treasuryShopSyncPayload(java.util.List.of());

    public static volatile ecoSyncPayload CLIENT_ECO =
            ecoSyncPayload.zeros();

    public static KeyMapping OPEN_ECONOMY_KEY;
    public static KeyMapping OPEN_MAIL_KEY;

    private static final KeyMapping.Category KINGDOMS_CATEGORY =
            new KeyMapping.Category(ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "category"));

    public static BlockPos LAST_KINGDOM_BLOCK;
    public static name.kingdoms.payload.bordersSyncPayload CLIENT_BORDERS;

    @Override
    public void onInitializeClient() {

        Kingdoms.PROXY = new clientProxyImpl();
        KingdomTransitionHUD.register();
        name.kingdoms.client.WarBattleHUD.register();
        name.kingdoms.network.clientNetworkInit.registerClientReceivers();
        borderWandClient.init();
        name.kingdoms.client.WarCommandClientHooks.init();

        // LEFT CLICK: cycle command group (footmen/archers/both) while holding WarCommandItem
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
                if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
                if (!(player.getMainHandItem().getItem() instanceof name.kingdoms.WarCommandItem)) return InteractionResult.PASS;

                // send C2S "cycle group" request
                ClientPlayNetworking.send(new name.kingdoms.payload.warCommandCycleGroupC2SPayload());
                return InteractionResult.FAIL; // cancels block breaking
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
                if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
                if (!(player.getMainHandItem().getItem() instanceof name.kingdoms.WarCommandItem)) return InteractionResult.PASS;

                ClientPlayNetworking.send(new name.kingdoms.payload.warCommandCycleGroupC2SPayload());
                return InteractionResult.FAIL; // cancels attacking
        });


        // renderers
        EntityRendererRegistry.register(
                Kingdoms.KINGDOM_WORKER_ENTITY_TYPE,
                (ctx) -> new kingdomWorkerRenderer(ctx)
        );

        EntityRendererRegistry.register(modEntities.SOLDIER, SoldierRenderer::new);

        EntityRendererRegistry.register(
                Kingdoms.AI_KINGDOM_ENTITY_TYPE,
                (ctx) -> new aiKingdomEntityRenderer(ctx)
        );

        EntityRendererRegistry.register(
                Kingdoms.AI_KINGDOM_NPC_ENTITY_TYPE,
                aiKingdomNPCRenderer::new
        );

        // -------------------------
        // Keybinds
        // -------------------------

        OPEN_ECONOMY_KEY = KeyBindingHelper.registerKeyBinding(
                new KeyMapping(
                        "key.kingdoms.open_economy",
                        InputConstants.Type.KEYSYM,
                        GLFW.GLFW_KEY_K,
                        KINGDOMS_CATEGORY
                )
        );

        OPEN_MAIL_KEY = KeyBindingHelper.registerKeyBinding(
                new KeyMapping(
                        "key.kingdoms.open_mail",
                        InputConstants.Type.KEYSYM,
                        GLFW.GLFW_KEY_O,
                        KINGDOMS_CATEGORY
                )
        );

        // -------------------------
        // Key handlers
        // -------------------------

        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            // K opens economy (request latest info first)
            while (OPEN_ECONOMY_KEY.consumeClick()) {
                if (client.player == null) break;

                ClientPlayNetworking.send(new kingdomInfoRequestPayload());
                ClientPlayNetworking.send(new ecoRequestPayload());

                Minecraft.getInstance().setScreen(new kingdomEconomyScreen());
            }

            // O opens mail screen
            while (OPEN_MAIL_KEY.consumeClick()) {
                if (client.player == null) return;

                ClientPlayNetworking.send(new kingdomInfoRequestPayload());
                ClientPlayNetworking.send(new ecoRequestPayload());
                ClientPlayNetworking.send(new mailInboxRequestC2SPayload());
                

                mailScreen.open();
            }
        });
     
    }

    public static void openKingdomMenu(BlockPos pos) {
        LAST_KINGDOM_BLOCK = pos;
        ClientPlayNetworking.send(new kingdomQueryPayload(pos));
    }

    public static void openTreasury(BlockPos pos) {
        if (Minecraft.getInstance().player == null) return;

        // request fresh data (same as K)
        ClientPlayNetworking.send(new kingdomInfoRequestPayload());
        ClientPlayNetworking.send(new ecoRequestPayload());
        ClientPlayNetworking.send(new mailInboxRequestC2SPayload());
        ClientPlayNetworking.send(new mailRecipientsRequestC2SPayload());

        // request shop list (treasury-specific)
        ClientPlayNetworking.send(new treasuryOpenPayload(pos));

        Minecraft.getInstance().setScreen(new treasuryScreen(pos));
    }

    public static void openMail(BlockPos pos) {
        if (Minecraft.getInstance().player == null) return;

        // ask server for latest inbox + recipients list
        ClientPlayNetworking.send(new mailInboxRequestC2SPayload());
        ClientPlayNetworking.send(new mailRecipientsRequestC2SPayload());

        // open UI
        Minecraft.getInstance().setScreen(new mailScreen());
        // If your mailScreen constructor needs pos, change to: new mailScreen(pos)
    }




}
