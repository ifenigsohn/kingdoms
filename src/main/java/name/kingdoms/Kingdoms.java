package name.kingdoms;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.horse.Horse;
import name.kingdoms.ambient.AmbientPropManager;
import name.kingdoms.blueprint.BlueprintPlacerEngine;
import name.kingdoms.blueprint.KingdomSatelliteSpawner;
import name.kingdoms.blueprint.RoadBuilder;
import name.kingdoms.blueprint.worldGenBluePrintAutoSpawner;
import name.kingdoms.diplomacy.AiRelationNormalizer;
import name.kingdoms.diplomacy.DiplomacyMailboxState;
import name.kingdoms.diplomacy.DiplomacyResponseQueue;
import name.kingdoms.entity.aiKingdomEntity;
import name.kingdoms.entity.kingdomWorkerEntity;
import name.kingdoms.entity.modEntities;
import name.kingdoms.entity.ai.aiKingdomNPCEntity;
import name.kingdoms.payload.kingdomTransitionS2CPayload;
import name.kingdoms.payload.mailInboxSyncPayload;
import name.kingdoms.war.WarPendingTicker;
import name.kingdoms.war.WarState;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Objects;

public class Kingdoms implements ModInitializer {
    
    public static final String MOD_ID = "kingdoms";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static BlockEntityType<jobBlockEntity> JOB_BLOCK_ENTITY;
    public static EntityType<kingdomWorkerEntity> KINGDOM_WORKER_ENTITY_TYPE;
    public static EntityType<aiKingdomEntity> AI_KINGDOM_ENTITY_TYPE;
    public static EntityType<aiKingdomNPCEntity> AI_KINGDOM_NPC_ENTITY_TYPE;
    public static EntityType<name.kingdoms.entity.ai.RoadAmbientNPCEntity> ROAD_AMBIENT_NPC;

    
    public static SimpleParticleType SLEEP_Z_PARTICLE;

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
    
    public static kingdomsClientProxy PROXY = new kingdomsClientProxy();

    private static void cleanupRetinueMounts(MinecraftServer server) {
    for (ServerLevel level : server.getAllLevels()) {
        for (Entity e : level.getAllEntities()) {
            if (e instanceof Horse && e.getTags().contains("kingdoms_retinue_mount")) {
                // discard passengers first (optional, but avoids rider weirdness)
                for (Entity p : e.getPassengers()) p.discard();
                e.discard();
            }
        }
    }
}


    private static final long TRANSITION_COOLDOWN_TICKS = 20L * 10L; // 10 seconds
    private static long transitionTick = 0;

    private static final Map<UUID, UUID> LAST_KINGDOM_AT = new HashMap<>();
    private static final Map<UUID, Long> LAST_TRANSITION_MSG_TICK = new HashMap<>();




    private static int terminalTickCounter = 0;
    private static int ecoTickCounter = 0;

    private static void tickEconomy(MinecraftServer server) {
        terminalTickCounter++;
        if (terminalTickCounter >= 20) { // 1 second
            terminalTickCounter = 0;
            kingdomState.get(server).validateTerminals(server);
        }

        ecoTickCounter++;
        if (ecoTickCounter < 6000) return; // 5 minutes for playtest
        ecoTickCounter = 0;

        var ks = kingdomState.get(server);
        for (var k : ks.getAllKingdoms()) applyEconomyStep(server, k, 10.0);
        ks.markDirty();
    }

    private static int playerRegenTickCounter = 0;

    private static void tickPlayerTickets(MinecraftServer server) {
        // run every 20 ticks = 1 second
        playerRegenTickCounter++;
        if (playerRegenTickCounter < 20) return;
        playerRegenTickCounter = 0;

        var ks = kingdomState.get(server);
        var ws = WarState.get(server);

        boolean changed = false;

        for (var k : ks.getAllKingdoms()) {
            if (k == null) continue;

            // only player kingdoms (AI in kingdomState hasTerminal=false; owner is still set, but simplest is: AI exists in aiKingdomState)
            boolean isAi = (aiKingdomState.get(server).getById(k.id) != null);
            if (isAi) continue;

            int max = kingdomState.computePlayerTicketsMax(k);

            // initialize if needed
            if (k.ticketsAlive < 0) {
                k.ticketsAlive = max;
                k.ticketsRegenBuf = 0.0;
                changed = true;
            }

            // clamp down if max decreased (lost job blocks)
            if (k.ticketsAlive > max) {
                k.ticketsAlive = max;
                if (k.ticketsRegenBuf > 0) k.ticketsRegenBuf = 0.0;
                changed = true;
            }

            // no regen during war
            if (ws.isAtWarWithAny(k.id)) continue;

            // regen rate: 5 per minute = 5/60 per second
            double basePerSec = (5.0 / 60.0);
            double mult = name.kingdoms.pressure.KingdomModifiers
                    .compute(server, k.id, null)
                    .soldierRegenMult();

            k.ticketsRegenBuf += basePerSec * mult;


            int gain = (int) Math.floor(k.ticketsRegenBuf);
            if (gain > 0 && k.ticketsAlive < max) {
                int before = k.ticketsAlive;
                k.ticketsAlive = Math.min(max, k.ticketsAlive + gain);
                k.ticketsRegenBuf -= gain;
                if (k.ticketsAlive != before) changed = true;
            } else if (gain > 0) {
                // if already full, don't accumulate infinite buffer
                k.ticketsRegenBuf = 0.0;
            }
        }

        if (changed) ks.markDirty();
    }

    private static int playerTroopTickCounter = 0;

    private static void tickPlayerTroops(MinecraftServer server) {
        // run once per second (20 ticks)
        playerTroopTickCounter++;
        if (playerTroopTickCounter < 20) return;
        playerTroopTickCounter = 0;

        var ks = kingdomState.get(server);
        var ws = WarState.get(server);
        var ai = aiKingdomState.get(server);

        boolean changed = false;

        for (var k : ks.getAllKingdoms()) {
            if (k == null) continue;

            // skip AI kingdoms (they have their own pool)
            if (ai.getById(k.id) != null) continue;

            int max = kingdomState.computePlayerTicketsMax(k);

            // init
            if (k.ticketsAlive < 0) {
                k.ticketsAlive = max;
                k.ticketsRegenBuf = 0.0;
                changed = true;
            }

            // clamp if max shrank
            if (k.ticketsAlive > max) {
                k.ticketsAlive = max;
                k.ticketsRegenBuf = 0.0;
                changed = true;
            }

            // regen only when not at war
            if (ws.isAtWarWithAny(k.id)) continue;

            // 5 per minute = 5/60 per second
            k.ticketsRegenBuf += (5.0 / 60.0);

            int gain = (int) Math.floor(k.ticketsRegenBuf);
            if (gain > 0) {
                int before = k.ticketsAlive;
                k.ticketsAlive = Math.min(max, k.ticketsAlive + gain);
                k.ticketsRegenBuf -= gain;

                // if full, don't accumulate infinite buffer
                if (k.ticketsAlive >= max) k.ticketsRegenBuf = 0.0;

                if (k.ticketsAlive != before) changed = true;
            }
        }

        if (changed) ks.markDirty();
    }


    private static int calendarSyncCooldown = 0;

    private static void tickCalendar(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return;

        var cal = name.kingdoms.time.kingdomCalendarState.get(server);
        cal.ensureInitialized(server);

        int beforeY = cal.year, beforeM = cal.month, beforeD = cal.day;

        cal.tick(overworld);

        boolean changed = (cal.year != beforeY || cal.month != beforeM || cal.day != beforeD);
        if (!changed) return;

        // Broadcast to all players (once per day change)
        var pkt = new name.kingdoms.payload.calendarSyncPayload(cal.year, cal.month, cal.day);
        for (var player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, pkt);
        }
    }


    private static void tickKingdomTransitions(MinecraftServer server) {
        transitionTick++;

        var ks = kingdomState.get(server);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == null) continue;
            if (!(player.level() instanceof ServerLevel level)) continue;

            var pos = player.blockPosition();

            // Uses claim map (which is driven by borders)
            var kNow = ks.getKingdomAt(level, pos);
            UUID nowId = (kNow == null) ? null : kNow.id;

            UUID prevId = LAST_KINGDOM_AT.get(player.getUUID());
            if (Objects.equals(prevId, nowId)) continue; // no change

            // update “where player is” immediately (prevents repeated spam when cooldown blocks)
            LAST_KINGDOM_AT.put(player.getUUID(), nowId);

            long lastMsg = LAST_TRANSITION_MSG_TICK.getOrDefault(player.getUUID(), -999999L);
            if (transitionTick - lastMsg < TRANSITION_COOLDOWN_TICKS) {
                continue;
            }

            // Decide message:
            // - Entering: when moving into a kingdom (including switching kingdoms)
            // - Exiting: only when leaving into wilderness (null)
            if (nowId != null) {
                ServerPlayNetworking.send(player, new kingdomTransitionS2CPayload(true, kNow.name));
                LAST_TRANSITION_MSG_TICK.put(player.getUUID(), transitionTick);
            } else if (prevId != null) {
                var prevK = ks.getKingdom(prevId);
                if (prevK != null) {
                    ServerPlayNetworking.send(player, new kingdomTransitionS2CPayload(false, prevK.name));
                    LAST_TRANSITION_MSG_TICK.put(player.getUUID(), transitionTick);
                }
            }
        }
    }

    private static void applyEconomyStep(MinecraftServer server, kingdomState.Kingdom k, double seconds) {
        var d = KingdomEconomyCalc.compute(server, k);

        k.gold    += d.dGold()    * seconds;
        k.meat    += d.dMeat()    * seconds;
        k.grain   += d.dGrain()   * seconds;
        k.fish    += d.dFish()    * seconds;

        k.wood    += d.dWood()    * seconds;
        k.metal   += d.dMetal()   * seconds;
        k.armor   += d.dArmor()   * seconds;
        k.weapons += d.dWeapons() * seconds;

        k.gems    += d.dGems()    * seconds;
        k.horses  += d.dHorses()  * seconds;
        k.potions += d.dPotions() * seconds;

        // clamp
        k.gold = Math.max(0, k.gold);
        k.meat = Math.max(0, k.meat);
        k.grain = Math.max(0, k.grain);
        k.fish = Math.max(0, k.fish);
        k.wood = Math.max(0, k.wood);
        k.metal = Math.max(0, k.metal);
        k.armor = Math.max(0, k.armor);
        k.weapons = Math.max(0, k.weapons);
        k.gems = Math.max(0, k.gems);
        k.horses = Math.max(0, k.horses);
        k.potions = Math.max(0, k.potions);
    }





    @Override
    public void onInitialize() {
        
        BlueprintPlacerEngine.init();
        worldGenBluePrintAutoSpawner.init();
        RoadBuilder.init();
        name.kingdoms.diplomacy.DiplomacyMailGenerator.init();
        KingdomsCommands.register();
        DiplomacyResponseQueue.init();
        name.kingdoms.diplomacy.AiDiplomacyTicker.init();
        modEntities.register();
        name.kingdoms.war.WarBattleManager.init();
        name.kingdoms.diplomacy.DiplomacyRelationNormalizer.init();
        KingdomSatelliteSpawner.init();
        ThroneSeatManager.hook();
        AiRelationNormalizer.init();
        ServerTickEvents.END_SERVER_TICK.register(Kingdoms::tickCalendar);
        ServerTickEvents.END_SERVER_TICK.register(name.kingdoms.ambient.AmbientManager::tick);
        AmbientPropManager.init();
        ServerTickEvents.END_SERVER_TICK.register(Kingdoms::tickPlayerTroops);
        name.kingdoms.pressure.GlobalPressureEvents.init();


        WarPendingTicker.init();
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            WarState.get(server).tickPendingWars(server);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            WarState.get(server).tickAiWars(server);
        });


        // Blocks/items
        modBlock.initalize();
        modItem.initalize();
        kingdomProtection.register();

        ModEffects.register();

        
        // ---- Road ambient NPC entity registration ----
        ResourceLocation roadNpcId = ResourceLocation.fromNamespaceAndPath(MOD_ID, "road_ambient_npc");
        ResourceKey<EntityType<?>> roadNpcKey = ResourceKey.create(Registries.ENTITY_TYPE, roadNpcId);

        ROAD_AMBIENT_NPC = Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                roadNpcId,
                FabricEntityTypeBuilder.createMob()
                        .entityFactory(name.kingdoms.entity.ai.RoadAmbientNPCEntity::new)
                        .spawnGroup(MobCategory.MISC)
                        .dimensions(EntityDimensions.fixed(0.6f, 1.8f))
                        .trackRangeBlocks(64)
                        .trackedUpdateRate(3)
                        .build(roadNpcKey)
        );

        FabricDefaultAttributeRegistry.register(
                ROAD_AMBIENT_NPC,
                name.kingdoms.entity.ai.RoadAmbientNPCEntity.createAttributes()
        );


        name.kingdoms.network.networkInit.registerPayloadTypes();
        name.kingdoms.network.networkInit.registerServerReceivers();

        ServerLifecycleEvents.SERVER_STOPPING.register(Kingdoms::cleanupRetinueMounts);
        ServerTickEvents.END_SERVER_TICK.register(Kingdoms::tickEconomy);
        ServerTickEvents.END_SERVER_TICK.register(Kingdoms::tickKingdomTransitions);

        //CONTROLS SPEED OF AI KINGDOM RESOURCE UPDATES 
        ServerTickEvents.END_SERVER_TICK.register(server -> {
        // 20 seconds = 400 ticks
        long t = server.getTickCount();
        if (t % 400 != 0) return;

        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        aiKingdomState.get(server).tickEconomies(overworld);

    });

       ServerTickEvents.END_SERVER_TICK.register(server -> {
        name.kingdoms.entity.ambient.AmbientRoadManager.tick(server);
    });

    ServerTickEvents.END_SERVER_TICK.register(server -> {
        long t = server.getTickCount();
        if (t % 20 != 0) return; // once per second
        name.kingdoms.pressure.KingdomPressureState.get(server).tick(t);
    });



        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var cal = name.kingdoms.time.kingdomCalendarState.get(server);
            cal.ensureInitialized(server);

            ServerPlayNetworking.send(handler.player,
                    new name.kingdoms.payload.calendarSyncPayload(cal.year, cal.month, cal.day));
        });



        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var player = handler.player;
            server.execute(() -> {
                if (!(player.level() instanceof ServerLevel level)) return;

                var mail = DiplomacyMailboxState.get(level);
                ServerPlayNetworking.send(player, new mailInboxSyncPayload(mail.getInbox(player.getUUID())));
            });
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;

            var ks = name.kingdoms.kingdomState.get(server);
            var k  = ks.getPlayerKingdom(player.getUUID());
            if (k == null) return;

            if (k.royalGuardsEnabled) {
                name.kingdoms.entity.RoyalGuardManager.setEnabled(player, true);
            }
        });

        // ---- Entity registration ----
        ResourceLocation workerId = ResourceLocation.fromNamespaceAndPath(MOD_ID, "kingdom_worker");
        ResourceKey<EntityType<?>> workerKey = ResourceKey.create(Registries.ENTITY_TYPE, workerId);
        
        KINGDOM_WORKER_ENTITY_TYPE = Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                workerId,
                FabricEntityTypeBuilder.createMob()
                        .entityFactory(kingdomWorkerEntity::new)
                        .spawnGroup(MobCategory.MISC)
                        .dimensions(EntityDimensions.fixed(0.6f, 1.8f))
                        .trackRangeBlocks(64)
                        .trackedUpdateRate(3)
                        .build(workerKey)
        );

        

        ResourceLocation npcId = ResourceLocation.fromNamespaceAndPath(MOD_ID, "kingdom_npc");
        ResourceKey<EntityType<?>> npcKey = ResourceKey.create(Registries.ENTITY_TYPE, npcId);

        AI_KINGDOM_NPC_ENTITY_TYPE = Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                npcId,
                FabricEntityTypeBuilder.createMob()
                        .entityFactory(aiKingdomNPCEntity::new)
                        .spawnGroup(MobCategory.MISC)
                        .dimensions(EntityDimensions.fixed(0.6f, 1.8f))
                        .trackRangeBlocks(64)
                        .trackedUpdateRate(3)
                        .build(npcKey)
        );


        var id = ResourceLocation.fromNamespaceAndPath(MOD_ID, "ai_kingdom_entity");
        var key = ResourceKey.create(Registries.ENTITY_TYPE, id);

        AI_KINGDOM_ENTITY_TYPE = Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                id,
                FabricEntityTypeBuilder.create(MobCategory.MISC, aiKingdomEntity::new)
                        .dimensions(EntityDimensions.fixed(0.6F, 1.95F))
                        .build(key)
        );

        
        FabricDefaultAttributeRegistry.register(
                AI_KINGDOM_ENTITY_TYPE,
                aiKingdomEntity.createAttributes()
        );

        FabricDefaultAttributeRegistry.register(
                AI_KINGDOM_NPC_ENTITY_TYPE,
                aiKingdomNPCEntity.createAttributes()
        );

        FabricDefaultAttributeRegistry.register(
                KINGDOM_WORKER_ENTITY_TYPE,
                kingdomWorkerEntity.createAttributes()
        );

        // init the respawn manager once entity type exists
        RetinueRespawnManager.init(KINGDOM_WORKER_ENTITY_TYPE);

        // Hook join/quit so retinue is maintained without needing a block interaction
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            if (!(player.level() instanceof ServerLevel sl)) return;

            // If you want the respawn manager effects/timers to work
            RetinueRespawnManager.onJoin(player);

            // THIS is the critical fix: ensure retinue on join
            var ks = kingdomState.get(server);
            var k = ks.getPlayerKingdom(player.getUUID());
            if (k != null && RetinueRespawnManager.isEnabled(player.getUUID())) {
                RetinueSpawner.ensureRetinue(sl, player, k);
                ks.markDirty();
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.player;

            // Optional: if you intentionally despawn on disconnect
            RetinueRespawnManager.onDisconnect(player);

            // IMPORTANT: clear stored UUIDs so you don't keep pointing at dead entities
            if (player.level() instanceof ServerLevel sl) {
                var ks = kingdomState.get(server);
                var k = ks.getPlayerKingdom(player.getUUID());
                if (k != null) {
                    k.retinueScribe = null;
                    k.retinueTreasurer = null;
                    k.retinueGeneral = null;
                    ks.markDirty();
                }
            }
        });


        


        // ---- Block entity registration ----
        JOB_BLOCK_ENTITY = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "job_block_entity"),
                FabricBlockEntityTypeBuilder.create(
                        jobBlockEntity::new,
                        modBlock.grain_block,
                        modBlock.butcher_block,
                        modBlock.fish_block,
                        modBlock.stable_block,
                        modBlock.metal_block,
                        modBlock.gem_block,
                        modBlock.wood_block,
                        modBlock.alchemy_block,
                        modBlock.weapon_block,
                        modBlock.armor_block,
                        modBlock.guard_block,
                        modBlock.training_block,
                        modBlock.garrison_block,
                        modBlock.chapel_block,
                        modBlock.tavern_block,
                        modBlock.shop_block,
                        modBlock.nobility_block
                ).build()
        );

        // ---- Commands ----
        registerCommands();

        // ---- Border wand: left click sets A ----
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (level.isClientSide()) return InteractionResult.PASS;

            ItemStack held = player.getItemInHand(hand);
            if (!held.is(modItem.BORDER_WAND)) return InteractionResult.PASS;

            borderSelection.setFirst(player.getUUID(), pos.immutable());

            borderSelection.Selection sel = borderSelection.get(player.getUUID());
            BlockPos a = sel == null ? null : sel.first();
            BlockPos b = sel == null ? null : sel.second();

            String aStr = (a == null) ? "unset" : a.toShortString();
            String bStr = (b == null) ? "unset" : b.toShortString();

            player.displayClientMessage(Component.literal("Border corners: A=" + aStr + " B=" + bStr), true);

            return InteractionResult.SUCCESS;
        });

        // ---- Border wand: right click sets B; sneak-right-click clears; applies if both exist ----
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide()) return InteractionResult.PASS;

            ItemStack held = player.getItemInHand(hand);
            if (!held.is(modItem.BORDER_WAND)) return InteractionResult.PASS;

            if (player.isShiftKeyDown()) {
                borderSelection.clear(player.getUUID());

                if (level instanceof ServerLevel sl) {
                    var ks = kingdomState.get(sl.getServer());
                    var k = ks.getPlayerKingdom(player.getUUID());
                    if (k != null) {
                        ks.clearKingdomBorder(sl, k); // <-- THIS is what actually unclaims cells
                        ks.markDirty();
                        player.displayClientMessage(Component.literal("Kingdom border cleared."), true);
                        return InteractionResult.SUCCESS;
                    }
                }

                player.displayClientMessage(Component.literal("Border selection cleared."), true);
                return InteractionResult.SUCCESS;
            }

            borderSelection.setSecond(player.getUUID(), hit.getBlockPos().immutable());

            borderSelection.Selection sel = borderSelection.get(player.getUUID());
            BlockPos a = sel == null ? null : sel.first();
            BlockPos b = sel == null ? null : sel.second();

            String aStr = (a == null) ? "unset" : a.toShortString();
            String bStr = (b == null) ? "unset" : b.toShortString();

            player.displayClientMessage(Component.literal("Border corners: A=" + aStr + " B=" + bStr), true);

            if (a != null && b != null && level instanceof ServerLevel sl) {
                var ks = kingdomState.get(sl.getServer());
                var k = ks.getOrThrowPlayerKingdom(player.getUUID());

                int minX = Math.min(a.getX(), b.getX());
                int maxX = Math.max(a.getX(), b.getX());
                int minZ = Math.min(a.getZ(), b.getZ());
                int maxZ = Math.max(a.getZ(), b.getZ());

               boolean ok = ks.trySetKingdomBorder(sl, k, minX, maxX, minZ, maxZ);

                if (!ok) {
                        player.displayClientMessage(Component.literal(
                                "Border overlaps another kingdom. Pick a different area."
                        ), false);
                        return InteractionResult.SUCCESS; // keep selection so they can adjust
                }

                ks.markDirty();

                // --- PUSH UPDATED BORDERS TO ALL PLAYERS ---
                for (ServerPlayer p : sl.getServer().getPlayerList().getPlayers()) {
                    ServerPlayNetworking.send(
                        p,
                        new name.kingdoms.payload.bordersSyncPayload(
                            ks.getAllKingdoms().stream()
                                .filter(kk -> kk.hasBorder)
                                .map(kk -> new name.kingdoms.payload.bordersSyncPayload.Entry(
                                        kk.id,
                                        kk.name,
                                        ok, kk.borderMinX, kk.borderMaxX,
                                        kk.borderMinZ, kk.borderMaxZ,
                                        0xFF000000 | (kk.id.hashCode() & 0x00FFFFFF),
                                        kk.owner.equals(p.getUUID())
                                ))
                                .toList()
                        )
                    );
                }


                player.displayClientMessage(Component.literal(
                        "Kingdom border applied: X[" + minX + "," + maxX + "] Z[" + minZ + "," + maxZ + "]"
                ), false);

                borderSelection.clear(player.getUUID());

            }

            return InteractionResult.SUCCESS;
        });
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            
            name.kingdoms.blueprint.BlueprintPlaceCommand.register(dispatcher, "kingdoms");

            
            dispatcher.register(
                    Commands.literal("kingdom_set")
                            .then(Commands.argument("resource", StringArgumentType.word())
                                    .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                            .executes(commandCtx -> {

                                                String resource = StringArgumentType.getString(commandCtx, "resource").toLowerCase();
                                                int amount = IntegerArgumentType.getInteger(commandCtx, "amount");

                                                var state = kingdomState.get(commandCtx.getSource().getServer());
                                                var player = commandCtx.getSource().getPlayerOrException();
                                                var k = state.getOrThrowPlayerKingdom(player.getUUID());

                                                switch (resource) {
                                                    case "gold":    k.gold    = amount; break;
                                                    case "meat":    k.meat    = amount; break;
                                                    case "grain":   k.grain   = amount; break;
                                                    case "fish":    k.fish    = amount; break;
                                                    case "wood":    k.wood    = amount; break;
                                                    case "metal":   k.metal   = amount; break;
                                                    case "armor":   k.armor   = amount; break;
                                                    case "weapons": k.weapons = amount; break;
                                                    case "gems":    k.gems    = amount; break;
                                                    case "horses":  k.horses  = amount; break;
                                                    case "potions": k.potions = amount; break;
                                                    default:
                                                        commandCtx.getSource().sendFailure(
                                                                Component.literal("Unknown resource: " + resource)
                                                        );
                                                        return 0;
                                                }

                                                state.markDirty();

                                                commandCtx.getSource().sendSuccess(
                                                        () -> Component.literal("Set " + resource + " to " + amount),
                                                        false
                                                );

                                                return 1;
                                            })
                                    )
                            )
            );

            dispatcher.register(
                    Commands.literal("kingdom_add")
                            .then(Commands.argument("resource", StringArgumentType.word())
                                    .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                            .executes(commandCtx -> {

                                                String resource = StringArgumentType.getString(commandCtx, "resource").toLowerCase();
                                                int amount = IntegerArgumentType.getInteger(commandCtx, "amount");

                                                var state = kingdomState.get(commandCtx.getSource().getServer());
                                                var player = commandCtx.getSource().getPlayerOrException();
                                                var k = state.getOrThrowPlayerKingdom(player.getUUID());

                                                switch (resource) {
                                                    case "gold":    k.gold    += amount; break;
                                                    case "meat":    k.meat    += amount; break;
                                                    case "grain":   k.grain   += amount; break;
                                                    case "fish":    k.fish    += amount; break;
                                                    case "wood":    k.wood    += amount; break;
                                                    case "metal":   k.metal   += amount; break;
                                                    case "armor":   k.armor   += amount; break;
                                                    case "weapons": k.weapons += amount; break;
                                                    case "gems":    k.gems    += amount; break;
                                                    case "horses":  k.horses  += amount; break;
                                                    case "potions": k.potions += amount; break;
                                                    default:
                                                        commandCtx.getSource().sendFailure(
                                                                Component.literal("Unknown resource: " + resource)
                                                        );
                                                        return 0;
                                                }

                                                state.markDirty();

                                                commandCtx.getSource().sendSuccess(
                                                        () -> Component.literal("Added " + amount + " " + resource),
                                                        false
                                                );

                                                return 1;
                                            })
                                    )
                            )
            );

            dispatcher.register(
                    Commands.literal("kingdom_take")
                            .then(Commands.argument("resource", StringArgumentType.word())
                                    .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                            .executes(commandCtx -> {

                                                String resource = StringArgumentType.getString(commandCtx, "resource").toLowerCase();
                                                int amount = IntegerArgumentType.getInteger(commandCtx, "amount");

                                                var state = kingdomState.get(commandCtx.getSource().getServer());
                                                var player = commandCtx.getSource().getPlayerOrException();
                                                var k = state.getOrThrowPlayerKingdom(player.getUUID());

                                                boolean success = true;

                                                switch (resource) {
                                                    case "gold":
                                                        if (k.gold < amount) success = false; else k.gold -= amount;
                                                        break;
                                                    case "meat":
                                                        if (k.meat < amount) success = false; else k.meat -= amount;
                                                        break;
                                                    case "grain":
                                                        if (k.grain < amount) success = false; else k.grain -= amount;
                                                        break;
                                                    case "fish":
                                                        if (k.fish < amount) success = false; else k.fish -= amount;
                                                        break;
                                                    case "wood":
                                                        if (k.wood < amount) success = false; else k.wood -= amount;
                                                        break;
                                                    case "metal":
                                                        if (k.metal < amount) success = false; else k.metal -= amount;
                                                        break;
                                                    case "armor":
                                                        if (k.armor < amount) success = false; else k.armor -= amount;
                                                        break;
                                                    case "weapons":
                                                        if (k.weapons < amount) success = false; else k.weapons -= amount;
                                                        break;
                                                    case "gems":
                                                        if (k.gems < amount) success = false; else k.gems -= amount;
                                                        break;
                                                    case "horses":
                                                        if (k.horses < amount) success = false; else k.horses -= amount;
                                                        break;
                                                    case "potions":
                                                        if (k.potions < amount) success = false; else k.potions -= amount;
                                                        break;
                                                    default:
                                                        commandCtx.getSource().sendFailure(
                                                                Component.literal("Unknown resource: " + resource)
                                                        );
                                                        return 0;
                                                }

                                                if (!success) {
                                                    commandCtx.getSource().sendFailure(
                                                            Component.literal("Not enough " + resource + " to remove!")
                                                    );
                                                    return 0;
                                                }

                                                state.markDirty();

                                                commandCtx.getSource().sendSuccess(
                                                        () -> Component.literal("Removed " + amount + " " + resource),
                                                        false
                                                );

                                                return 1;
                                            })
                                    )
                            )
            );

            dispatcher.register(
                    Commands.literal("kingdom_stats")
                            .executes(commandCtx -> {
                                var state = kingdomState.get(commandCtx.getSource().getServer());
                                var player = commandCtx.getSource().getPlayerOrException();
                                var k = state.getOrThrowPlayerKingdom(player.getUUID());

                                String msg =
                                        "Kingdom: " + k.name + "\n" +
                                                "Gold: "    + (int)k.gold    + "\n" +
                                                "Meat: "    + (int)k.meat    + "\n" +
                                                "Grain: "   + (int)k.grain   + "\n" +
                                                "Fish: "    + (int)k.fish    + "\n" +
                                                "Wood: "    + (int)k.wood    + "\n" +
                                                "Metal: "   + (int)k.metal   + "\n" +
                                                "Armor: "   + (int)k.armor   + "\n" +
                                                "Weapons: " + (int)k.weapons + "\n" +
                                                "Gems: "    + (int)k.gems    + "\n" +
                                                "Horses: "  + (int)k.horses  + "\n" +
                                                "Potions: " + (int)k.potions;

                                commandCtx.getSource().sendSuccess(
                                        () -> Component.literal(msg),
                                        false
                                );
                                return 1;
                            })
            );
        });
   

    }
    
}
