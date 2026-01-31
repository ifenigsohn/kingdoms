package name.kingdoms.network;

import java.util.UUID;
import name.kingdoms.payload.OpenMailS2CPayload;
import name.kingdoms.Kingdoms;
import name.kingdoms.RetinueRespawnManager;
import name.kingdoms.RetinueSpawner;
import name.kingdoms.aiKingdomState;
import name.kingdoms.entity.RoyalGuardManager;
import name.kingdoms.entity.aiKingdomEntity;
import name.kingdoms.jobBlock;
import name.kingdoms.jobBlocks;
import name.kingdoms.jobDefinition;
import name.kingdoms.kingSkinPoolState;
import name.kingdoms.kingdomState;
import name.kingdoms.kingdomState.Kingdom;
import name.kingdoms.modItem;
import name.kingdoms.payload.CreateKingdomPayload;
import name.kingdoms.payload.OpenTreasuryS2CPayload;
import name.kingdoms.payload.aiTradeInfoS2CPayload;
import name.kingdoms.payload.aiTradeQueryC2SPayload;
import name.kingdoms.payload.bordersRequestPayload;
import name.kingdoms.payload.createKingdomResultPayload;
import name.kingdoms.payload.diplomacyFreezeC2SPayload;
import name.kingdoms.payload.disbandKingdomPayload;
import name.kingdoms.payload.ecoRequestPayload;
import name.kingdoms.payload.ecoSyncPayload;
import name.kingdoms.payload.jobReqsS2CPayload;
import name.kingdoms.payload.kingdomInfoRequestPayload;
import name.kingdoms.payload.kingdomInfoSyncPayload;
import name.kingdoms.payload.kingdomQueryPayload;
import name.kingdoms.payload.kingdomQueryResultPayload;
import name.kingdoms.payload.kingdomTransitionS2CPayload;
import name.kingdoms.payload.opendiplomacyS2CPayload;
import name.kingdoms.payload.openKingdomMenuPayload;
import name.kingdoms.payload.requestBorderWandPayload;
import name.kingdoms.payload.royalGuardToggleC2SPayload;
import name.kingdoms.payload.setHeraldryPayload;
import name.kingdoms.payload.soldierSkinSelectC2SPayload;
import name.kingdoms.payload.toggleJobEnabledPayload;
import name.kingdoms.payload.treasuryBuyJobPayload;
import name.kingdoms.payload.treasuryOpenPayload;
import name.kingdoms.payload.treasuryOpenResultPayload;
import name.kingdoms.payload.treasuryShopSyncPayload;
import name.kingdoms.payload.warCommandCycleGroupC2SPayload;
import name.kingdoms.payload.warCommandMoveOrderC2SPayload;
import name.kingdoms.war.WarBattleManager;
import name.kingdoms.payload.mailActionC2SPayload;
import name.kingdoms.payload.mailInboxRequestC2SPayload;
import name.kingdoms.payload.mailInboxSyncPayload;
import name.kingdoms.payload.mailPolicySyncS2CPayload;
import name.kingdoms.treasuryShop;
import name.kingdoms.payload.mailRecipientsRequestC2SPayload;
import name.kingdoms.payload.mailRecipientsSyncS2CPayload;
import name.kingdoms.diplomacy.AiLetterText;
import name.kingdoms.diplomacy.DiplomacyMailGenerator;
import name.kingdoms.diplomacy.DiplomacyMailboxState;
import name.kingdoms.diplomacy.DiplomacyResponseQueue;
import name.kingdoms.diplomacy.EconomyMutator;
import name.kingdoms.diplomacy.Letter;
import name.kingdoms.diplomacy.ResourceType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import name.kingdoms.payload.mailSendResultS2CPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import name.kingdoms.payload.mailSendC2SPayload;

public final class networkInit {
    private networkInit() {}



    
    public static void registerPayloadTypes() {
        // ----- C2S -----
        PayloadTypeRegistry.playC2S().register(CreateKingdomPayload.TYPE, CreateKingdomPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(kingdomInfoRequestPayload.TYPE, kingdomInfoRequestPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(kingdomQueryPayload.TYPE, kingdomQueryPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(disbandKingdomPayload.TYPE, disbandKingdomPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ecoRequestPayload.TYPE, ecoRequestPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(treasuryBuyJobPayload.TYPE, treasuryBuyJobPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(requestBorderWandPayload.TYPE, requestBorderWandPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(treasuryOpenPayload.TYPE, treasuryOpenPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(diplomacyFreezeC2SPayload.TYPE, diplomacyFreezeC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(toggleJobEnabledPayload.TYPE, toggleJobEnabledPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(aiTradeQueryC2SPayload.TYPE, aiTradeQueryC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(mailInboxRequestC2SPayload.TYPE, mailInboxRequestC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(mailActionC2SPayload.TYPE, mailActionC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(mailRecipientsRequestC2SPayload.TYPE,mailRecipientsRequestC2SPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(mailSendC2SPayload.TYPE, mailSendC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(bordersRequestPayload.TYPE, bordersRequestPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(name.kingdoms.payload.warZonesRequestPayload.TYPE, name.kingdoms.payload.warZonesRequestPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(name.kingdoms.payload.warCommandCycleGroupC2SPayload.TYPE,name.kingdoms.payload.warCommandCycleGroupC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(warCommandMoveOrderC2SPayload.TYPE,warCommandMoveOrderC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(name.kingdoms.payload.warOverviewRequestC2SPayload.TYPE,name.kingdoms.payload.warOverviewRequestC2SPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(name.kingdoms.payload.kingdomHoverRequestC2SPayload.TYPE,name.kingdoms.payload.kingdomHoverRequestC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(name.kingdoms.payload.newsRequestC2SPayload.TYPE,name.kingdoms.payload.newsRequestC2SPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(name.kingdoms.payload.mailPolicyRequestC2SPayload.TYPE,name.kingdoms.payload.mailPolicyRequestC2SPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(royalGuardToggleC2SPayload.TYPE, royalGuardToggleC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(setHeraldryPayload.TYPE, setHeraldryPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(soldierSkinSelectC2SPayload.TYPE,soldierSkinSelectC2SPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(name.kingdoms.payload.toggleRetinueC2SPayload.TYPE,name.kingdoms.payload.toggleRetinueC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(name.kingdoms.payload.inPersonProposalSendC2SPayload.TYPE,name.kingdoms.payload.inPersonProposalSendC2SPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(name.kingdoms.payload.requestProposalC2SPayload.TYPE,name.kingdoms.payload.requestProposalC2SPayload.STREAM_CODEC);

        // ----- S2C -----
        PayloadTypeRegistry.playS2C().register(aiTradeInfoS2CPayload.TYPE, aiTradeInfoS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(createKingdomResultPayload.TYPE, createKingdomResultPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(kingdomInfoSyncPayload.TYPE, kingdomInfoSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(kingdomQueryResultPayload.TYPE, kingdomQueryResultPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(treasuryOpenResultPayload.TYPE, treasuryOpenResultPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ecoSyncPayload.TYPE, ecoSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(treasuryShopSyncPayload.TYPE, treasuryShopSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(openKingdomMenuPayload.TYPE, openKingdomMenuPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(jobReqsS2CPayload.TYPE, jobReqsS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenTreasuryS2CPayload.TYPE, OpenTreasuryS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(opendiplomacyS2CPayload.TYPE, opendiplomacyS2CPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(mailInboxSyncPayload.TYPE, mailInboxSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(mailRecipientsSyncS2CPayload.TYPE,mailRecipientsSyncS2CPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(mailSendResultS2CPayload.TYPE, mailSendResultS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(name.kingdoms.payload.bordersSyncPayload.TYPE, name.kingdoms.payload.bordersSyncPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(kingdomTransitionS2CPayload.TYPE, kingdomTransitionS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenMailS2CPayload.TYPE, OpenMailS2CPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(name.kingdoms.payload.warZonesSyncPayload.TYPE, name.kingdoms.payload.warZonesSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(name.kingdoms.payload.warBattleHudSyncPayload.TYPE,name.kingdoms.payload.warBattleHudSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(name.kingdoms.payload.warCommandGroupSyncS2CPayload.TYPE,name.kingdoms.payload.warCommandGroupSyncS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(name.kingdoms.payload.warOverviewSyncS2CPayload.TYPE,name.kingdoms.payload.warOverviewSyncS2CPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(name.kingdoms.payload.kingdomHoverSyncS2CPayload.TYPE,name.kingdoms.payload.kingdomHoverSyncS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(mailPolicySyncS2CPayload.TYPE, mailPolicySyncS2CPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(name.kingdoms.payload.newsSyncS2CPayload.TYPE,name.kingdoms.payload.newsSyncS2CPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(name.kingdoms.payload.calendarSyncPayload.TYPE,name.kingdoms.payload.calendarSyncPayload.CODEC);
    }

    public static void registerServerReceivers() {

        ServerPlayNetworking.registerGlobalReceiver(royalGuardToggleC2SPayload.TYPE, (payload, ctx) -> {
            ctx.server().execute(() -> {
                ServerPlayer player = ctx.player();

                var ks = name.kingdoms.kingdomState.get(ctx.server());
                var k  = ks.getPlayerKingdom(player.getUUID());
                if (k == null) return;

                k.royalGuardsEnabled = payload.enabled();
                ks.markDirty();

                name.kingdoms.entity.RoyalGuardManager.setEnabled(player, k.royalGuardsEnabled);
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(
            name.kingdoms.payload.toggleRetinueC2SPayload.TYPE,
            (payload, ctx) -> ctx.server().execute(() -> {
                ServerPlayer player = ctx.player();

                RetinueRespawnManager.setEnabled(ctx.server(), player.getUUID(), payload.enabled());

                if (payload.enabled()) {
                    RetinueRespawnManager.spawnOwnedNow(player);
                }

                player.sendSystemMessage(Component.literal(
                    payload.enabled() ? "Retinue enabled." : "Retinue disabled."
                ));
            })
        );



    ServerPlayNetworking.registerGlobalReceiver(name.kingdoms.payload.requestProposalC2SPayload.TYPE, (payload, ctx) -> {
        ctx.server().execute(() -> {
        var player = ctx.player();
        var server = ctx.server();
        var level = (ServerLevel) player.level();

        var ks = kingdomState.get(server);
        var playerK = ks.getPlayerKingdom(player.getUUID());
        if (playerK == null) {
            player.displayClientMessage(Component.literal("You are not in a kingdom."), false);
            return;
        }

        var toK = ks.getKingdom(payload.toKingdomId());
        if (toK == null) {
            player.displayClientMessage(Component.literal("That kingdom does not exist."), false);
            return;
        }

        var aiState = aiKingdomState.get(server);
        var aiK = aiState.getById(toK.id);
        if (aiK == null) {
            player.displayClientMessage(Component.literal("That kingdom is not AI."), false);
            return;
        }

        // cooldown (same map as in-person proposals)
        long nowTick = server.getTickCount();
        ServerLevel mailLevel = server.overworld();
        var mailbox = DiplomacyMailboxState.get(mailLevel);

        if (mailbox.isProposalOnCooldown(player.getUUID(), toK.id, nowTick)) {
            long rem = mailbox.proposalCooldownRemaining(player.getUUID(), toK.id, nowTick);
            int secs = (int)(rem / 20L);
            player.displayClientMessage(Component.literal("Proposal cooldown: " + secs + "s"), false);
            return;
        }
        mailbox.startProposalCooldown(player.getUUID(), toK.id, nowTick);

        // build a letter FROM AI to PLAYER, but deliver after 10–20s
        var letter = DiplomacyMailGenerator.makeImmediateProposal(mailLevel, server, toK.id, player.getUUID(), nowTick);
        if (letter == null) {
            player.displayClientMessage(Component.literal("No proposal available right now."), false);
            return;
        }

        int delay = 200 + mailLevel.getRandom().nextInt(201);
        mailbox.scheduleDelivery(player.getUUID(), letter, nowTick + delay);

        player.displayClientMessage(Component.literal("The king will respond in " + (delay/20) + "s."), true);
    });
});


                // --- SET HERALDRY (custom banner) ---
        ServerPlayNetworking.registerGlobalReceiver(setHeraldryPayload.TYPE, (payload, ctx) -> {
            ctx.server().execute(() -> {
                var player = ctx.player();

                ItemStack banner = payload.banner();
                if (banner == null || banner.isEmpty()) return;

                // must be a banner
                if (!(banner.getItem() instanceof net.minecraft.world.item.BannerItem)) return;

                // find player's kingdom and store heraldry
                var state = kingdomState.get(ctx.server());
                var k = state.getPlayerKingdom(player.getUUID());
                if (k == null) return;

                k.heraldry = banner.copyWithCount(1);
                state.markDirty(); // or state.setDirty() depending on your class

                // THIS IS THE LINE YOU ADD:
                RoyalGuardManager.applyHeraldryNow(player);

                player.sendSystemMessage(Component.literal("Custom banner set."));
            });
        });




        ServerPlayNetworking.registerGlobalReceiver(
                name.kingdoms.payload.kingdomHoverRequestC2SPayload.TYPE,
                (payload, ctx) -> ctx.server().execute(() -> {

                    var server = ctx.server();
                    var player = ctx.player();

                    var ks = kingdomState.get(server);
                    var aiState = aiKingdomState.get(server);

                    var k = ks.getKingdom(payload.kingdomId());
                    if (k == null) return;

                    ItemStack heraldry = ItemStack.EMPTY;

                    if (k.heraldry != null && !k.heraldry.isEmpty()
                            && k.heraldry.getItem() instanceof net.minecraft.world.item.BannerItem) {

                        // copy as a fresh stack, count = 1
                        heraldry = k.heraldry.copy();
                        heraldry.setCount(1);
                    }



                    // relation (you already use this elsewhere)
                    var relState = name.kingdoms.diplomacy.DiplomacyRelationsState.get(server);
                    int relation = relState.getRelation(player.getUUID(), k.id);

                    // soldiers + tickets
                    int soldiersAlive = 0, soldiersMax = 0;
                    int ticketsMax, ticketsAlive;

                    var aiK = aiState.getById(k.id);
                    if (aiK != null) {
                        soldiersAlive = Math.max(0, aiK.aliveSoldiers);
                        soldiersMax   = Math.max(0, aiK.maxSoldiers);

                        // Tickets: if you don’t have AI tickets yet, mirror soldiers for now.
                        ticketsMax = soldiersMax;
                        ticketsAlive = soldiersAlive;
                    } else {
                        // player kingdom estimate (matches your warOverview)
                        int garrisons = k.active.getOrDefault("garrison", 0);
                        ticketsMax = Math.max(0, garrisons * 50);
                        ticketsAlive = ticketsMax; // until you track losses

                        // If you don’t have player soldier counts yet, use tickets as a proxy (or 0)
                        soldiersMax = ticketsMax;
                        soldiersAlive = ticketsAlive;
                    }

                    // ruler identity for head
                    java.util.UUID rulerId = (k.owner != null) ? k.owner : k.id;
                    String rulerName = "Unknown";

                    var ownerOnline = (k.owner != null) ? server.getPlayerList().getPlayer(k.owner) : null;
                    if (ownerOnline != null) rulerName = ownerOnline.getGameProfile().name();
                    else if (aiK != null && aiK.name != null) rulerName = aiK.name; // "King ____" (not a real skin)
                    else if (k.name != null && !k.name.isBlank()) rulerName = k.name;

                    boolean isAi = (aiK != null);
                   int aiSkinId = isAi
                    ? Mth.clamp(aiK.skinId, 0, kingSkinPoolState.MAX_SKIN_ID)
                    : 0;

                    // economy source
                    double gold, meat, grain, fish, wood, metal, armor, weapons, gems, horses, potions;

                    // --- war source ---
                    var warState = name.kingdoms.war.WarState.get(server);
                    var allianceState = name.kingdoms.diplomacy.AllianceState.get(server);

                    boolean atWar = warState.isAtWarWithAny(k.id);

                    // enemies = everyone we have a war zone with
                    var zones = warState.getZonesFor(server, k.id);
                    var enemyIds = new java.util.ArrayList<java.util.UUID>(zones.size());
                    for (var zv : zones) enemyIds.add(zv.enemyId());

                    // allies = alliance members
                    var allyIds = new java.util.ArrayList<java.util.UUID>(allianceState.alliesOf(k.id));

                    // format short strings for tooltip
                    String allies = formatKingdomNameList(server, ks, aiState, allyIds, 3);
                    String enemies = formatKingdomNameList(server, ks, aiState, enemyIds, 3);


                    if (isAi) {
                        gold    = aiK.gold;
                        meat    = aiK.meat;
                        grain   = aiK.grain;
                        fish    = aiK.fish;
                        wood    = aiK.wood;
                        metal   = aiK.metal;
                        armor   = aiK.armor;
                        weapons = aiK.weapons;
                        gems    = aiK.gems;
                        horses  = aiK.horses;
                        potions = aiK.potions;
                    } else {
                        gold    = k.gold;
                        meat    = k.meat;
                        grain   = k.grain;
                        fish    = k.fish;
                        wood    = k.wood;
                        metal   = k.metal;
                        armor   = k.armor;
                        weapons = k.weapons;
                        gems    = k.gems;
                        horses  = k.horses;
                        potions = k.potions;
                    }

                    ServerPlayNetworking.send(player, new name.kingdoms.payload.kingdomHoverSyncS2CPayload(
                        k.id,
                        (k.name == null ? "" : k.name),

                        rulerId,
                        rulerName,

                        relation,

                        soldiersAlive, soldiersMax,
                        ticketsAlive, ticketsMax,

                        atWar,
                        allies,
                        enemies,

                        gold,
                        meat, grain, fish,
                        wood, metal,
                        armor, weapons,
                        gems, horses, potions,

                        isAi,
                        aiSkinId,
                        heraldry
                ));

                })
        );

        ServerPlayNetworking.registerGlobalReceiver(name.kingdoms.payload.newsRequestC2SPayload.TYPE, (payload, ctx) -> {
            ctx.server().execute(() -> {
                var server = ctx.server();
                var player = ctx.player();

                var ks = name.kingdoms.kingdomState.get(server);
                var pk = ks.getPlayerKingdom(player.getUUID());

                // Player kingdom name (used to identify "player-related" news lines globally)
                final String playerKingdomName =
                        (pk != null && pk.name != null && !pk.name.isBlank()) ? pk.name : "Player Kingdom";

                // Anchor = kingdom terminal if present; else player position
                var dimKey = player.level().dimension();
                var pos = player.blockPosition();
                if (pk != null && pk.hasTerminal) {
                    dimKey = pk.terminalDim;
                    pos = pk.terminalPos;
                }

                var news = name.kingdoms.news.KingdomNewsState.get(server.overworld());

                int limit = Math.max(1, Math.min(payload.limit(), 100));
                int radius = 2000; // tweak

                // 1) Local filtered news (also prunes expired 3h)
                var local = news.latestNear(
                        dimKey.location().toString(),
                        pos.getX(),
                        pos.getZ(),
                        radius,
                        limit
                );

                // Build response with dedupe.
                // We'll output oldest -> newest at the end.
                var outNewestFirst = new java.util.ArrayList<String>(limit);
                var seen = new java.util.HashSet<String>(limit * 2);

                // Helper: is this entry global?
                java.util.function.Predicate<name.kingdoms.news.KingdomNewsState.Entry> isGlobal = e -> {
                    String t = e.text();
                    if (t == null) return false;

                    // Wars / peace / alliance are worldwide
                    if (t.startsWith("[WAR]") || t.startsWith("[PEACE]")) return true;
                    if (t.startsWith("[ALLIANCE]")) return true;

                    // Any news involving the player's kingdom is worldwide (so you see your own actions anywhere)
                    return t.contains(playerKingdomName);
                };

                // Add LOCAL first (newest -> older) until limit
                for (var e : local) {
                    if (outNewestFirst.size() >= limit) break;
                    String t = e.text();
                    if (t == null || t.isBlank()) continue;
                    if (seen.add(t)) outNewestFirst.add(t);
                }

                // 2) Top off with GLOBAL items from the full feed (newest -> older)
                // We iterate from newest to oldest without adding new API: use latest(MAX) as a window.
                // (MAX in your NewsState is 400; requesting 400 is fine.)
                var window = news.latest(400);
                for (int i = window.size() - 1; i >= 0 && outNewestFirst.size() < limit; i--) {
                    var e = window.get(i);
                    if (!isGlobal.test(e)) continue;

                    String t = e.text();
                    if (t == null || t.isBlank()) continue;
                    if (seen.add(t)) outNewestFirst.add(t);
                }

                // Convert to oldest -> newest for nicer UI display
                java.util.Collections.reverse(outNewestFirst);

                ServerPlayNetworking.send(player, new name.kingdoms.payload.newsSyncS2CPayload(outNewestFirst));
            });
        });

        
        ServerPlayNetworking.registerGlobalReceiver(
            name.kingdoms.payload.mailPolicyRequestC2SPayload.TYPE,
            (payload, ctx) -> ctx.server().execute(() -> {

                var player = ctx.player();
                var server = ctx.server();

                var toId = payload.toKingdomId();
                if (toId == null) return;

                var ks = name.kingdoms.kingdomState.get(server);

                Kingdom fromK = ks.getPlayerKingdom(player.getUUID());
                if (fromK == null) return;

                UUID fromId = fromK.id;

                ServerLevel level = (ServerLevel) player.level();
                long nowTick = server.getTickCount();


                var cdState = name.kingdoms.diplomacy.DiplomacyCooldownState.get(level);

                var out = new java.util.ArrayList<name.kingdoms.payload.mailPolicySyncS2CPayload.Entry>();

                for (name.kingdoms.diplomacy.Letter.Kind k : name.kingdoms.diplomacy.Letter.Kind.values()) {
                    var d = name.kingdoms.diplomacy.DiplomacyPlayerSendRules.canSend(server, player, toId, k);

                    long cooldownTicks = cooldownTicksForKind(k);
                    long rem = cdState.remaining(fromId, toId, k, nowTick);


                    int remInt = (int) Math.min(rem, Integer.MAX_VALUE);

                    boolean allowedFinal = d.allowed() && remInt == 0;

                    String reason;
                    if (!d.allowed()) {
                        reason = (d.reason() == null ? "Blocked." : d.reason());
                    } else if (remInt > 0) {
                        reason = "Cooldown (" + (remInt / 20) + "s)";
                    } else {
                        reason = "";
                    }

                    out.add(new name.kingdoms.payload.mailPolicySyncS2CPayload.Entry(
                        k.ordinal(),
                        allowedFinal,
                        reason,
                        remInt
                    ));
                }

                ServerPlayNetworking.send(player, new name.kingdoms.payload.mailPolicySyncS2CPayload(toId, out));
            })
        );



        
                    // --- BORDERS REQUEST (map/wand refresh) ---
                    ServerPlayNetworking.registerGlobalReceiver(name.kingdoms.payload.bordersRequestPayload.TYPE, (payload, ctx) -> {
                        ctx.server().execute(() -> {
                            var player = ctx.player();
                            var state = kingdomState.get(ctx.server());
                            ServerPlayNetworking.send(player, buildBordersPayloadFor(player, state));
                        });
                    });


        ServerPlayNetworking.registerGlobalReceiver(mailSendC2SPayload.TYPE, (payload, ctx) -> {
            ctx.server().execute(() -> {
                var player = ctx.player();
                var server = ctx.server();

                var ks = kingdomState.get(server);
                var playerK = ks.getPlayerKingdom(player.getUUID());
                if (playerK == null) {
                    ServerPlayNetworking.send(player,
                            new mailSendResultS2CPayload(payload.requestId(), false, "You are not in a kingdom."));
                    return;
                }

                // Recipient must exist
                var toK = ks.getKingdom(payload.toKingdomId());
                if (toK == null) {
                    ServerPlayNetworking.send(player,
                            new mailSendResultS2CPayload(payload.requestId(), false, "That kingdom does not exist."));
                    return;
                }

                // ----------------------------
                // Recipient type (AI vs Player kingdom)
                // ----------------------------
                var aiState = aiKingdomState.get(server);
                var aiK = aiState.getById(toK.id);
                boolean toIsAi = (aiK != null);

                if (toK.id.equals(playerK.id)) {
                    ServerPlayNetworking.send(player,
                            new mailSendResultS2CPayload(payload.requestId(), false, "You can't send mail to your own kingdom."));
                    return;
                }

                // ----------------------------
                // Cooldown enforcement (server authoritative)
                // ----------------------------
                ServerLevel mailLevel = server.overworld(); // SavedData lives here
                var cds = name.kingdoms.diplomacy.DiplomacyCooldownState.get(mailLevel);

                long nowTick = server.getTickCount();
                long cd = name.kingdoms.diplomacy.DiplomacyCooldowns.ticksFor(payload.kind(), false);

                if (cds.isOnCooldown(playerK.id, toK.id, payload.kind(), nowTick)) {
                    long rem = cds.remaining(playerK.id, toK.id, payload.kind(), nowTick);
                    ServerPlayNetworking.send(player,
                        new mailSendResultS2CPayload(payload.requestId(), false,
                            "You must wait " + name.kingdoms.diplomacy.DiplomacyCooldowns.fmtTicks(rem) +
                            " before sending " + payload.kind().name() + " again."
                        )
                    );
                    return;
                }






                // If it's a player kingdom, only allow if owner is ONLINE
                ServerPlayer toOwnerOnline = null;
                if (!toIsAi) {
                    if (toK.owner == null) {
                        ServerPlayNetworking.send(player,
                                new mailSendResultS2CPayload(payload.requestId(), false, "That kingdom has no owner."));
                        return;
                    }
                    toOwnerOnline = server.getPlayerList().getPlayer(toK.owner);
                    if (toOwnerOnline == null) {
                        ServerPlayNetworking.send(player,
                                new mailSendResultS2CPayload(payload.requestId(), false, "That kingdom's ruler is offline."));
                        return;
                    }
                }

                

                // ----------------------------
                // Diplomacy policy enforcement (single source of truth)
                // ----------------------------
                var decision = name.kingdoms.diplomacy.DiplomacyPlayerSendRules.canSend(
                        server,
                        player,
                        payload.toKingdomId(),
                        payload.kind()
                );

                if (!decision.allowed()) {
                    ServerPlayNetworking.send(player,
                            new mailSendResultS2CPayload(payload.requestId(), false, decision.reason()));
                    return;
                }

                String note = payload.note() == null ? "" : payload.note();


                // ----------------------------
                // Validation
                // ----------------------------
                boolean kindNeedsPositiveAmount = switch (payload.kind()) {
                    case REQUEST, OFFER, CONTRACT, ULTIMATUM -> true;
                    default -> false;
                };

                if (kindNeedsPositiveAmount && payload.aAmount() <= 0) {
                    ServerPlayNetworking.send(player,
                            new mailSendResultS2CPayload(payload.requestId(), false, "Amount must be > 0."));
                    return;
                }

                if (payload.kind() == Letter.Kind.CONTRACT) {
                    if (payload.bType() == null || payload.bAmount() <= 0 || payload.maxAmount() <= 0) {
                        ServerPlayNetworking.send(player,
                                new mailSendResultS2CPayload(payload.requestId(), false, "Contract values are invalid."));
                        return;
                    }
                }

                // ----------------------------
                // Player affordability enforcement (server-side)
                // ----------------------------
                boolean playerCan = switch (payload.kind()) {
                    case REQUEST -> true;
                    case OFFER -> EconomyMutator.get(playerK, payload.aType()) >= payload.aAmount();
                    case CONTRACT -> EconomyMutator.get(playerK, payload.aType()) >= payload.aAmount();
                    case ULTIMATUM -> true;

                    // non-economic / always allowed to send (validation handled elsewhere)
                    case COMPLIMENT, INSULT, WARNING, WAR_DECLARATION, ALLIANCE_PROPOSAL, ALLIANCE_BREAK,
                    WHITE_PEACE, SURRENDER -> true;

                    default -> throw new IllegalArgumentException("Unexpected value: " + payload.kind());
                };


                if (!playerCan) {
                    ServerPlayNetworking.send(player,
                            new mailSendResultS2CPayload(payload.requestId(), false, "Your kingdom can't afford that."));
                    return;
                }

                // At this point, the send is accepted (validated + allowed).
                // Mark cooldown NOW to prevent double-click / same-tick spam.
                cds.markSent(playerK.id, toK.id, payload.kind(), nowTick, cd);

                

                // ============================================================
                // WAR DECLARATION
                // ============================================================
                if (payload.kind() == Letter.Kind.WAR_DECLARATION) {
                    var relState = name.kingdoms.diplomacy.DiplomacyRelationsState.get(server);
                   

                    if (toIsAi) {
                        relState.addRelation(player.getUUID(), toK.id, -80);
                    }

                    var warState = name.kingdoms.war.WarState.get(server);
                    // Creates wars entry + ensures zone exists
                    warState.declareWar(server, playerK.id, toK.id);

                    // Sync zones to sender
                    {
                        var out = new java.util.ArrayList<name.kingdoms.payload.warZonesSyncPayload.Entry>();
                        for (var zv : warState.getZonesFor(server, playerK.id)) {
                            var enemyId = zv.enemyId();
                            var zone = zv.zone();

                            var ek = ks.getKingdom(enemyId);
                            String enemyName =
                                    (ek != null && ek.name != null && !ek.name.isBlank())
                                            ? ek.name
                                            : aiState.getNameById(enemyId);

                            out.add(new name.kingdoms.payload.warZonesSyncPayload.Entry(
                                    enemyId, enemyName,
                                    zone.minX(), zone.minZ(), zone.maxX(), zone.maxZ()
                            ));
                        }
                        ServerPlayNetworking.send(player, new name.kingdoms.payload.warZonesSyncPayload(out));
                    }


                    // PvP: sync zones + notify other player
                    if (!toIsAi && toOwnerOnline != null) {
                        var out2 = new java.util.ArrayList<name.kingdoms.payload.warZonesSyncPayload.Entry>();
                        for (var zv : warState.getZonesFor(server, toK.id)) {
                            var enemyId = zv.enemyId();
                            var zone = zv.zone();

                            var ek = ks.getKingdom(enemyId);
                            String enemyName =
                                    (ek != null && ek.name != null && !ek.name.isBlank())
                                            ? ek.name
                                            : aiState.getNameById(enemyId);

                            out2.add(new name.kingdoms.payload.warZonesSyncPayload.Entry(
                                    enemyId, enemyName,
                                    zone.minX(), zone.minZ(), zone.maxX(), zone.maxZ()
                            ));
                        }
                        ServerPlayNetworking.send(toOwnerOnline, new name.kingdoms.payload.warZonesSyncPayload(out2));

                        toOwnerOnline.sendSystemMessage(Component.literal("[WAR] " + playerK.name + " has declared war on you!"));

                        // ALSO deliver a visible WAR_DECLARATION letter to the other player (optional but nice)
                        var mailbox = DiplomacyMailboxState.get(mailLevel);
                        long now = mailLevel.getGameTime();

                        mailbox.deliverPlayerMail(
                                toOwnerOnline.getUUID(),
                                playerK.id,
                                (playerK.name == null || playerK.name.isBlank()) ? "Unknown" : playerK.name,
                                Letter.Kind.WAR_DECLARATION,
                                now, 0L,
                                ResourceType.GOLD, 0.0,
                                null, 0.0,
                                0.0,
                                payload.cb(),
                                note
                        );

                        // push updated inbox immediately so they see it without reopening UI
                        ServerPlayNetworking.send(toOwnerOnline,
                                new mailInboxSyncPayload(mailbox.getInbox(toOwnerOnline.getUUID())));
                    }

                    ServerPlayNetworking.send(player,
                            new mailSendResultS2CPayload(payload.requestId(), true, "War declared."));
                    return; 
                }

                // ============================================================
                // ALLIANCE BREAK (leave alliance instantly + heavy relation hit)
                // ============================================================
                if (payload.kind() == Letter.Kind.ALLIANCE_BREAK) {
                    var alliance = name.kingdoms.diplomacy.AllianceState.get(server);
                    var relState = name.kingdoms.diplomacy.DiplomacyRelationsState.get(server);

                    // Break immediately (always allowed)
                    alliance.breakAlliance(playerK.id, toK.id);

                    // Heavy relations cost to the player<->that kingdom relationship
                    if (toIsAi) {
                        relState.addRelation(player.getUUID(), toK.id, -80);
                    }


                    // Optional: deliver a visible informational letter back (AI only)
                    if (toIsAi) {
                        var mailbox = DiplomacyMailboxState.get(mailLevel);

                        long now = server.getTickCount();
                        String toName = (toK.name == null || toK.name.isBlank()) ? "Unknown Kingdom" : toK.name;

                        mailbox.addLetter(player.getUUID(), Letter.warning(
                                toK.id, true, toName,
                                player.getUUID(),
                                now, now + 20L * 60L * 10L,
                                "Alliance has been dissolved."
                        ).withStatus(Letter.Status.ACCEPTED));

                        ServerPlayNetworking.send(player, new mailInboxSyncPayload(mailbox.getInbox(player.getUUID())));
                    }

                    ServerPlayNetworking.send(player,
                            new mailSendResultS2CPayload(payload.requestId(), true, "Alliance broken."));

                            return;
                }

                


                // ----------------------------
                // Normal kinds: queue mail
                // ----------------------------
                if (toIsAi) {
                DiplomacyResponseQueue.queueMail(
                        server,
                        player.getUUID(),
                        aiK.id,
                        payload.kind(),
                        payload.aType(), payload.aAmount(),
                        payload.bType(), payload.bAmount(),
                        payload.maxAmount(),
                        payload.cb(),
                        note
                );

                if (DiplomacyResponseQueue.DEBUG_INSTANT) {
                    DiplomacyResponseQueue.processDueNow(server);
                    ServerPlayNetworking.send(player,
                            new mailSendResultS2CPayload(payload.requestId(), true, "Sent (instant)."));
                       

                            
                } else {
                    ServerPlayNetworking.send(player,
                            new mailSendResultS2CPayload(payload.requestId(), true, "Message delivered. Awaiting response..."));
                           
                }
            } else {
                // PLAYER DELIVERY: store directly in mailbox
                var mailbox = DiplomacyMailboxState.get(mailLevel);

                // Add a helper to DiplomacyMailboxState (next section) and call it here:
                long now = server.overworld().getGameTime(); // or server.getTickCount() if you prefer

                mailbox.deliverPlayerMail(
                        toOwnerOnline.getUUID(),
                        playerK.id,
                        (playerK.name == null || playerK.name.isBlank()) ? "Unknown" : playerK.name,
                        payload.kind(),
                        now, 0L,                         // createdTick, expiresTick
                        payload.aType(), payload.aAmount(),
                        payload.bType(), payload.bAmount(),
                        payload.maxAmount(),
                        payload.cb(),
                        note
                );

                // Optional: push updated inbox immediately
                ServerPlayNetworking.send(toOwnerOnline,
                        new mailInboxSyncPayload(mailbox.getInbox(toOwnerOnline.getUUID())));

                toOwnerOnline.sendSystemMessage(Component.literal("[MAIL] New letter from " +
                        ((playerK.name == null || playerK.name.isBlank()) ? "a kingdom" : playerK.name)));

                ServerPlayNetworking.send(player,
                        new mailSendResultS2CPayload(payload.requestId(), true, "Message delivered to player."));
            }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(name.kingdoms.payload.inPersonProposalSendC2SPayload.TYPE, (payload, ctx) -> {
            ctx.server().execute(() -> {
                var player = ctx.player();
                var server = ctx.server();
                var level = (ServerLevel) player.level();

                // must be in a kingdom
                var ks = kingdomState.get(server);
                var playerK = ks.getPlayerKingdom(player.getUUID());
                if (playerK == null) {
                    ServerPlayNetworking.send(player,
                            new mailSendResultS2CPayload(payload.requestId(), false, "You are not in a kingdom."));
                    return;
                }

                // recipient must exist
                var toK = ks.getKingdom(payload.toKingdomId());
                if (toK == null) {
                    ServerPlayNetworking.send(player,
                            new mailSendResultS2CPayload(payload.requestId(), false, "That kingdom does not exist."));
                    return;
                }

                // must be AI kingdom
                var aiState = aiKingdomState.get(server);
                var aiK = aiState.getById(toK.id);
                if (aiK == null) {
                    ServerPlayNetworking.send(player,
                            new mailSendResultS2CPayload(payload.requestId(), false, "In-person proposals only work for AI kingdoms."));
                    return;
                }

                if (toK.id.equals(playerK.id)) {
                    ServerPlayNetworking.send(player,
                            new mailSendResultS2CPayload(payload.requestId(), false, "You can't send mail to your own kingdom."));
                    return;
                }

                // server-authoritative in-person range check
                var kingEnt = level.getEntity(payload.kingEntityId());
                if (!(kingEnt instanceof LivingEntity) || kingEnt.isRemoved()) {
                    ServerPlayNetworking.send(player,
                            new mailSendResultS2CPayload(payload.requestId(), false, "King not found."));
                    return;
                }

                double maxDist = 8.0;
                if (player.distanceToSqr(kingEnt) > maxDist * maxDist) {
                    ServerPlayNetworking.send(player,
                            new mailSendResultS2CPayload(payload.requestId(), false, "You must be near the king."));
                    return;
                }

                // ----------------------------
                // In-person cooldown (same system, reduced)
                // ----------------------------
                ServerLevel mailLevel = server.overworld();
                var cds = name.kingdoms.diplomacy.DiplomacyCooldownState.get(mailLevel);

                long nowTick = server.getTickCount();
                long cd = name.kingdoms.diplomacy.DiplomacyCooldowns.ticksFor(payload.kind(), true);

                if (cds.isOnCooldown(playerK.id, toK.id, payload.kind(), nowTick)) {
                    long rem = cds.remaining(playerK.id, toK.id, payload.kind(), nowTick);
                    ServerPlayNetworking.send(player,
                        new mailSendResultS2CPayload(payload.requestId(), false,
                            "You must wait " + name.kingdoms.diplomacy.DiplomacyCooldowns.fmtTicks(rem) +
                            " before making that proposal again (in person)."
                        )
                    );
                    return;
                }


              



                // reuse your existing diplomacy policy gate
                var decision = name.kingdoms.diplomacy.DiplomacyPlayerSendRules.canSend(
                        server,
                        player,
                        payload.toKingdomId(),
                        payload.kind()
                );
                if (!decision.allowed()) {
                    ServerPlayNetworking.send(player,
                            new mailSendResultS2CPayload(payload.requestId(), false, decision.reason()));
                    return;
                }

                String note = payload.note() == null ? "" : payload.note();

                // validation
                boolean kindNeedsPositiveAmount = switch (payload.kind()) {
                    case REQUEST, OFFER, CONTRACT, ULTIMATUM -> true;
                    default -> false;
                };
                if (kindNeedsPositiveAmount && payload.aAmount() <= 0) {
                    ServerPlayNetworking.send(player,
                            new mailSendResultS2CPayload(payload.requestId(), false, "Amount must be > 0."));
                    return;
                }
                if (payload.kind() == Letter.Kind.CONTRACT) {
                    if (payload.bType() == null || payload.bAmount() <= 0 || payload.maxAmount() <= 0) {
                        ServerPlayNetworking.send(player,
                                new mailSendResultS2CPayload(payload.requestId(), false, "Contract values are invalid."));
                        return;
                    }
                }

                // affordability (same as normal handler)
                boolean playerCan = switch (payload.kind()) {
                    case REQUEST -> true;
                    case OFFER -> EconomyMutator.get(playerK, payload.aType()) >= payload.aAmount();
                    case CONTRACT -> EconomyMutator.get(playerK, payload.aType()) >= payload.aAmount();
                    case ULTIMATUM -> true;
                    case COMPLIMENT, INSULT, WARNING, WAR_DECLARATION, ALLIANCE_PROPOSAL, ALLIANCE_BREAK,
                        WHITE_PEACE, SURRENDER -> true;
                    default -> throw new IllegalArgumentException("Unexpected value: " + payload.kind());
                };

                if (!playerCan) {
                    ServerPlayNetworking.send(player,
                            new mailSendResultS2CPayload(payload.requestId(), false, "Your kingdom can't afford that."));
                    return;
                }

                // Mark reduced cooldown now that send is accepted
                cds.markSent(playerK.id, toK.id, payload.kind(), nowTick, cd);

                // Delay 10–20s (keep your flavor)
                int delay = 200 + mailLevel.getRandom().nextInt(201);
                var mailbox = DiplomacyMailboxState.get(mailLevel);

                // deliver to AI pipeline AFTER delay (not instantly)
                mailbox.scheduleInPersonToAi(
                        player.getUUID(),
                        toK.id,
                        payload.kind(),
                        payload.aType(), payload.aAmount(),
                        payload.bType(), payload.bAmount(),
                        payload.maxAmount(),
                        payload.cb(),
                        note,
                        nowTick + delay
                );

                ServerPlayNetworking.send(player,
                        new mailSendResultS2CPayload(payload.requestId(), true, "Proposal sent (arrives in " + (delay / 20) + "s)."));

            });
        });


        ServerPlayNetworking.registerGlobalReceiver(warCommandMoveOrderC2SPayload.TYPE, (payload, ctx) -> {
            ctx.server().execute(() -> {
                var player = ctx.player();

                // Optional: require holding the command item
                var main = player.getMainHandItem();
                var off  = player.getOffhandItem();
                boolean holding = main.getItem() instanceof name.kingdoms.WarCommandItem
                            || off.getItem()  instanceof name.kingdoms.WarCommandItem;
                if (!holding) return;

                WarBattleManager.issueMoveOrder(player, payload.pos());
            });
        });


       ServerPlayNetworking.registerGlobalReceiver(mailRecipientsRequestC2SPayload.TYPE, (payload, ctx) -> {
            ctx.server().execute(() -> {
                var player = ctx.player();
                var server = ctx.server();

                var ks = kingdomState.get(server);
                var aiState = aiKingdomState.get(server);
                var relState = name.kingdoms.diplomacy.DiplomacyRelationsState.get(server);

                var out = new java.util.ArrayList<mailRecipientsSyncS2CPayload.Entry>();
                var seen = new java.util.HashSet<java.util.UUID>();

                // -------------------------
                // PLAYER KINGDOMS (online only)
                // -------------------------
                for (var k : ks.getAllKingdoms()) {
                    if (k == null) continue;

                    // don't show yourself
                    if (k.owner != null && k.owner.equals(player.getUUID())) continue;

                    // only selectable if owner is ONLINE
                    ServerPlayer ownerOnline = (k.owner != null) ? server.getPlayerList().getPlayer(k.owner) : null;
                    if (ownerOnline == null) continue;

                    String kName = (k.name == null || k.name.isBlank()) ? "Kingdom" : k.name;
                    String nm = kName + " (" + ownerOnline.getName().getString() + ")";

                    int rel = relState.getRelation(player.getUUID(), k.id);

                    // only used for AI heads (keep 0 for players)
                    int headSkinId = 0;

                    // heraldry: only send a 1-count vanilla banner (or empty)
                    ItemStack heraldry = ItemStack.EMPTY;
                    if (k.heraldry != null && !k.heraldry.isEmpty()
                            && k.heraldry.getItem() instanceof net.minecraft.world.item.BannerItem) {
                        heraldry = k.heraldry.copy();
                        heraldry.setCount(1);
                    }

                    out.add(new mailRecipientsSyncS2CPayload.Entry(k.id, nm, false, rel, headSkinId, heraldry));
                    seen.add(k.id);
                }

                // -------------------------
                // AI KINGDOMS (directly from aiKingdomState)
                // -------------------------
                for (var aiK : aiState.kingdoms.values()) {
                    if (aiK == null) continue;

                    UUID id = aiK.id;
                    if (id == null) continue;

                    // skip duplicates if already added via ks loop
                    if (seen.contains(id)) continue;

                    // skip "yourself" if it ever matches (rare)
                    if (aiK.kingUuid != null && aiK.kingUuid.equals(player.getUUID())) continue;

                    String nm = (aiK.name == null || aiK.name.isBlank()) ? "Unknown" : aiK.name;

                    int rel = relState.getRelation(player.getUUID(), id);

                    int headSkinId = Mth.clamp(aiK.skinId, 0, kingSkinPoolState.MAX_SKIN_ID);

                    // heraldry for AI is stored on kingdomState kingdom if present
                    ItemStack heraldry = ItemStack.EMPTY;
                    var kk = ks.getKingdom(id);
                    if (kk != null && kk.heraldry != null && !kk.heraldry.isEmpty()
                            && kk.heraldry.getItem() instanceof net.minecraft.world.item.BannerItem) {
                        heraldry = kk.heraldry.copy();
                        heraldry.setCount(1);
                    }

                    out.add(new mailRecipientsSyncS2CPayload.Entry(id, nm, true, rel, headSkinId, heraldry));
                    seen.add(id);
                }

                Kingdoms.LOGGER.info("[MAIL] recipients -> {} (ks={}, aiState={})",
                        out.size(), ks.getAllKingdoms().size(), aiState.kingdoms.size());

                ServerPlayNetworking.send(player, new mailRecipientsSyncS2CPayload(java.util.List.copyOf(out)));
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(mailInboxRequestC2SPayload.TYPE, (payload, ctx) -> {
            ctx.server().execute(() -> {
                var player = ctx.player();
                ServerLevel level = ctx.server().overworld();

                // Optional: only players with kingdoms can see mail (matches your design)
                var ks = kingdomState.get(ctx.server());
                if (ks.getPlayerKingdom(player.getUUID()) == null) {
                    ServerPlayNetworking.send(player, new mailInboxSyncPayload(java.util.List.of()));
                    return;
                }

                var mail = DiplomacyMailboxState.get(level);
                ServerPlayNetworking.send(player, new mailInboxSyncPayload(mail.getInbox(player.getUUID())));
            });
        });
            
            

       // --- Mail: accept/refuse/ack letter ---
        ServerPlayNetworking.registerGlobalReceiver(mailActionC2SPayload.TYPE, (payload, ctx) -> {
            ctx.server().execute(() -> {
                ServerPlayer player = ctx.player();
                ServerLevel level = ctx.server().overworld();

                kingdomState kState = kingdomState.get(ctx.server());
                kingdomState.Kingdom playerK = kState.getPlayerKingdom(player.getUUID());
                if (playerK == null) return;

                DiplomacyMailboxState mailbox = DiplomacyMailboxState.get(level);
                Letter letter = mailbox.findLetter(player.getUUID(), payload.letterId());
                if (letter == null) return;

                long now = ctx.server().getTickCount();

                // ----------------------------------------------------
                // NEW: ACKNOWLEDGE = always delete letter, no effects.
                // Works even if status != PENDING.
                // ----------------------------------------------------

                
                if (payload.action() == mailActionC2SPayload.Action.ACKNOWLEDGE) {
                    mailbox.removeLetter(player.getUUID(), letter.id());
                    ServerPlayNetworking.send(player, new mailInboxSyncPayload(mailbox.getInbox(player.getUUID())));
                    return;
                }

                // Below here: only actionable letters
                if (letter.status() != Letter.Status.PENDING) return;

                boolean expired = letter.isExpired(now);

                // ------------------------------
                // Outcome-letter context (AI reply)
                // ------------------------------
                UUID otherK = letter.fromKingdomId();
                boolean fromAi = letter.fromIsAi();

                aiKingdomState aiStateForOutcome = aiKingdomState.get(ctx.server());
                aiKingdomState.AiKingdom aiFrom = fromAi ? aiStateForOutcome.getById(otherK) : null;

                String aiName = (letter.fromName() != null && !letter.fromName().isBlank())
                        ? letter.fromName()
                        : "Unknown Kingdom";

                String toName = (playerK.name != null && !playerK.name.isBlank())
                        ? playerK.name
                        : "your kingdom";

                var relStateForOutcome = name.kingdoms.diplomacy.DiplomacyRelationsState.get(ctx.server());
                int relForOutcome = fromAi ? relStateForOutcome.getRelation(player.getUUID(), otherK) : 0;


                // ----------------------------------------------------
                // Non-economic kinds (can be accept/refuse, but we REMOVE after click)
                // ----------------------------------------------------
                if (letter.kind() == Letter.Kind.COMPLIMENT
                    || letter.kind() == Letter.Kind.INSULT
                    || letter.kind() == Letter.Kind.WARNING
                    || letter.kind() == Letter.Kind.ULTIMATUM
                    || letter.kind() == Letter.Kind.WAR_DECLARATION
                    || letter.kind() == Letter.Kind.ALLIANCE_PROPOSAL
                    || letter.kind() == Letter.Kind.ALLIANCE_BREAK
                    || letter.kind() == Letter.Kind.WHITE_PEACE
                    || letter.kind() == Letter.Kind.SURRENDER) {



                    var relState = name.kingdoms.diplomacy.DiplomacyRelationsState.get(ctx.server());
                    var warState = name.kingdoms.war.WarState.get(ctx.server());
                    var alliance = name.kingdoms.diplomacy.AllianceState.get(ctx.server());

                    boolean accept = (payload.action() == mailActionC2SPayload.Action.ACCEPT);
                    var server = ctx.server();
                    // expired ultimatum behaves like refuse
                    if (letter.kind() == Letter.Kind.ULTIMATUM && expired) accept = false;

                    switch (letter.kind()) {
                        case COMPLIMENT -> {
                            if (letter.fromIsAi() && accept) relState.addRelation(player.getUUID(), otherK, +1);
                            mailbox.removeLetter(player.getUUID(), letter.id());
                        }

                        case INSULT -> {
                            if (letter.fromIsAi()) {
                                relState.addRelation(player.getUUID(), otherK, accept ? -1 : -2);
                            }
                            mailbox.removeLetter(player.getUUID(), letter.id());
                        }

                        case WARNING -> {
                            if (letter.fromIsAi()) {
                                relState.addRelation(player.getUUID(), otherK, accept ? -1 : -2);
                            }
                            mailbox.removeLetter(player.getUUID(), letter.id());
                        }

                        case WAR_DECLARATION -> {
                            if (letter.fromIsAi()) {
                                relState.addRelation(player.getUUID(), otherK, -80);
                            }
                            warState.declareWar(server, playerK.id, otherK);
                            mailbox.removeLetter(player.getUUID(), letter.id());
                        }


                        case WHITE_PEACE -> {
                            // Expired peace offer behaves like refuse
                            if (expired) accept = false;

                            // Only meaningful if actually at war
                            if (!warState.isAtWar(playerK.id, otherK)) {
                                mailbox.removeLetter(player.getUUID(), letter.id());
                                break;
                            }

                            if (accept) {
                                warState.makePeace(playerK.id, otherK);

                                // --- Sync updated war zones immediately after peace ---
                                {
                                    var out = new java.util.ArrayList<name.kingdoms.payload.warZonesSyncPayload.Entry>();
                                    var aiState2 = aiKingdomState.get(server);

                                    for (var zv : warState.getZonesFor(server, playerK.id)) {
                                        var enemyId = zv.enemyId();
                                        var zone = zv.zone();

                                        var ek = kState.getKingdom(enemyId);
                                        String enemyName =
                                                (ek != null && ek.name != null && !ek.name.isBlank())
                                                        ? ek.name
                                                        : aiState2.getNameById(enemyId);

                                        out.add(new name.kingdoms.payload.warZonesSyncPayload.Entry(
                                                enemyId, enemyName,
                                                zone.minX(), zone.minZ(), zone.maxX(), zone.maxZ()
                                        ));
                                    }

                                    ServerPlayNetworking.send(player, new name.kingdoms.payload.warZonesSyncPayload(out));
                                }

                                // Optional: small relation normalization
                                if (letter.fromIsAi()) {
                                    relState.addRelation(player.getUUID(), otherK, +5);
                                }
                            } else {
                                // Refusing peace worsens relations slightly
                                if (letter.fromIsAi()) {
                                    relState.addRelation(player.getUUID(), otherK, -5);
                                }
                            }

                            mailbox.removeLetter(player.getUUID(), letter.id());
                        }

                        case SURRENDER -> {
                            // Expired surrender behaves like refuse
                            if (expired) accept = false;

                            // Only meaningful if actually at war
                            if (!warState.isAtWar(playerK.id, otherK)) {
                                mailbox.removeLetter(player.getUUID(), letter.id());
                                break;
                            }

                            if (accept) {
                                // Surrender accepted => surrendering side (otherK) loses 20% of resources
                                applyDefeatPenalty(server, otherK);

                                warState.makePeace(playerK.id, otherK);

                                // --- Sync updated war zones immediately after peace ---
                                {
                                    var out = new java.util.ArrayList<name.kingdoms.payload.warZonesSyncPayload.Entry>();
                                    var aiState2 = aiKingdomState.get(server);

                                    for (var zv : warState.getZonesFor(server, playerK.id)) {
                                        var enemyId = zv.enemyId();
                                        var zone = zv.zone();

                                        var ek = kState.getKingdom(enemyId);
                                        String enemyName =
                                                (ek != null && ek.name != null && !ek.name.isBlank())
                                                        ? ek.name
                                                        : aiState2.getNameById(enemyId);

                                        out.add(new name.kingdoms.payload.warZonesSyncPayload.Entry(
                                                enemyId, enemyName,
                                                zone.minX(), zone.minZ(), zone.maxX(), zone.maxZ()
                                        ));
                                    }

                                    ServerPlayNetworking.send(player, new name.kingdoms.payload.warZonesSyncPayload(out));
                                }

                                // Optional: small relation normalization
                                if (letter.fromIsAi()) {
                                    relState.addRelation(player.getUUID(), otherK, +8);
                                }
                            } else {
                                // Refusing surrender offer worsens relations slightly
                                if (letter.fromIsAi()) {
                                    relState.addRelation(player.getUUID(), otherK, -1);
                                }
                            }

                            mailbox.removeLetter(player.getUUID(), letter.id());
                        }


                        case ULTIMATUM -> {
                            aiKingdomState aiState = aiKingdomState.get(ctx.server());
                 
                            if (accept) {
                                if (aiFrom == null) accept = false;

                                if (accept) {
                                    if (letter.fromIsAi()) {
                                        // AI -> player ultimatum: player pays AI
                                        double have = EconomyMutator.get(playerK, letter.aType());
                                        if (have < letter.aAmount()) accept = false;
                                        else {
                                            EconomyMutator.add(playerK, letter.aType(), -letter.aAmount());
                                            addAi(aiFrom, letter.aType(), +letter.aAmount());
                                            kState.markDirty();
                                            aiState.setDirty();
                                            ServerPlayNetworking.send(player, ecoSyncPayload.fromKingdomWithProjected(playerK));
                                        }
                                    } else {
                                        // (rare) player->AI ultimatum in inbox: AI pays player
                                        double haveAi = getAi(aiFrom, letter.aType());
                                        if (haveAi < letter.aAmount()) accept = false;
                                        else {
                                            addAi(aiFrom, letter.aType(), -letter.aAmount());
                                            EconomyMutator.add(playerK, letter.aType(), +letter.aAmount());
                                            kState.markDirty();
                                            aiState.setDirty();
                                            ServerPlayNetworking.send(player, ecoSyncPayload.fromKingdomWithProjected(playerK));
                                        }
                                    }
                                }
                            }

                            if (accept) {
                                relState.addRelation(player.getUUID(), otherK, +1);
                                mailbox.removeLetter(player.getUUID(), letter.id());
                            } else {
                                relState.addRelation(player.getUUID(), otherK, -5);
                               warState.declareWar(server, playerK.id, otherK);

                                // remove the ultimatum letter
                                mailbox.removeLetter(player.getUUID(), letter.id());

                                // optional: add a NEW informational letter that can be ACKNOWLEDGED
                                Letter warLetter = Letter.warDeclaration(
                                        otherK, true, letter.fromName(), player.getUUID(),
                                        Letter.CasusBelli.ULTIMATUM_REFUSED,
                                        now, now + 20L * 60L * 10L,
                                        "Ultimatum refused. War is declared."
                                ).withStatus(Letter.Status.ACCEPTED);

                                mailbox.addLetter(player.getUUID(), warLetter);
                            }
                        }

                        case ALLIANCE_PROPOSAL -> {
                            if (expired) accept = false;

                            // Accept = attempt to form alliance (max 3 allies per kingdom)
                            if (accept) {
                                // Block if already at war (recommended)
                                if (warState.isAtWar(playerK.id, otherK)) {
                                    relState.addRelation(player.getUUID(), otherK, -5);
                                } else if (!alliance.canAlly(playerK.id, otherK)) {
                                    // capacity reached on either side
                                    relState.addRelation(player.getUUID(), otherK, -5);
                                } else {
                                    alliance.addAlliance(playerK.id, otherK);
                                    relState.addRelation(player.getUUID(), otherK, +30);
                                }
                            } else {
                                // Refusing alliance hurts relations
                                relState.addRelation(player.getUUID(), otherK, -10);
                            }

                            mailbox.removeLetter(player.getUUID(), letter.id());
                        }

                        case ALLIANCE_BREAK -> {
                            // Leaving alliance is always allowed; big relations hit.
                            boolean wasAllied = alliance.isAllied(playerK.id, otherK);
                            if (wasAllied) {
                                alliance.breakAlliance(playerK.id, otherK);
                                relState.addRelation(player.getUUID(), otherK, -80);
                            } else {
                                // still a negative signal if used improperly
                                relState.addRelation(player.getUUID(), otherK, -10);
                            }

                            mailbox.removeLetter(player.getUUID(), letter.id());
                        }

                        default -> { }
                    }

                    // Add AI acknowledgement letter (player acceptance/refusal noted) after resolving a non-economic action
                    if (fromAi && payload.action() != mailActionC2SPayload.Action.ACKNOWLEDGE) {
                        boolean acceptedOutcome = (payload.action() == mailActionC2SPayload.Action.ACCEPT);

                        // expired ultimatum/peace/surrender behave like refuse
                        if ((letter.kind() == Letter.Kind.ULTIMATUM
                                || letter.kind() == Letter.Kind.WHITE_PEACE
                                || letter.kind() == Letter.Kind.SURRENDER) && expired) {
                            acceptedOutcome = false;
                        }

                        addAiAckLetter(
                        ctx.server(),
                        player,
                        mailbox,
                        aiFrom,
                        otherK,
                        aiName,
                        toName,
                        relForOutcome,
                        letter,
                        accept,   // ✅ final accept after any flips
                        true
                    );

                    }


                    mailbox.setDirty();
                    ServerPlayNetworking.send(player, new mailInboxSyncPayload(mailbox.getInbox(player.getUUID())));
                    return;
                }

                // ----------------------------------------------------
                // Economic kinds
                // ----------------------------------------------------

                // If expired, remove when player interacts (no need to keep)
                if (expired) {
                    mailbox.removeLetter(player.getUUID(), letter.id());
                    mailbox.setDirty();
                    ServerPlayNetworking.send(player, new mailInboxSyncPayload(mailbox.getInbox(player.getUUID())));
                    return;
                }

                // REFUSE economic => remove
                if (payload.action() == mailActionC2SPayload.Action.REFUSE) {
                    mailbox.removeLetter(player.getUUID(), letter.id());
                    mailbox.setDirty();

                                    if (fromAi) {
                    addAiAckLetter(
                            ctx.server(),
                            player,
                            mailbox,
                            aiFrom,
                            otherK,
                            aiName,
                            toName,
                            relForOutcome,
                            letter,
                            false,
                            true
                    );
                }


                    ServerPlayNetworking.send(player, new mailInboxSyncPayload(mailbox.getInbox(player.getUUID())));
                    return;
                }

                // ACCEPT economic
                boolean ok;

                if (letter.fromIsAi()) {
                    aiKingdomState aiState = aiKingdomState.get(ctx.server());
                    if (aiFrom == null) ok = false;
                    else {
                        ok = applyAcceptAi(playerK, aiFrom, letter);
                        aiState.setDirty();
                        kState.markDirty();
                    }
                } else {
                    kingdomState.Kingdom fromK = kState.getKingdom(letter.fromKingdomId());
                    ok = (fromK != null) && applyAccept(kState, playerK, fromK, letter);
                    kState.markDirty();
                }

                if (!ok) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "Trade failed: not enough resources to execute."
                    ));
                    // Keep letter pending in inbox; just re-sync inbox view
                    serverMail.syncInbox(player, mailbox.getInbox(player.getUUID()));
                    return;
                }

                // Remove the letter no matter what; outcome can be communicated via a new response letter if you want
                mailbox.removeLetter(player.getUUID(), letter.id());
                mailbox.setDirty();


                if (fromAi) {
                    addAiAckLetter(
                        ctx.server(),
                        player,
                        mailbox,
                        aiFrom,
                        otherK,
                        aiName,
                        toName,
                        relForOutcome,
                        letter,
                        true,
                        ok// player clicked ACCEPT
                    );

                }


                ServerPlayNetworking.send(player, new mailInboxSyncPayload(mailbox.getInbox(player.getUUID())));
                ServerPlayNetworking.send(player, ecoSyncPayload.fromKingdomWithProjected(playerK));
            });
        });


        // --- AI trade query (used by diplo screen / button) ---
        ServerPlayNetworking.registerGlobalReceiver(aiTradeQueryC2SPayload.TYPE, (payload, ctx) -> {
            ctx.server().execute(() -> {
                var player = ctx.player();
                if (!(player.level() instanceof ServerLevel sl)) return;

                Entity e = sl.getEntity(payload.kingEntityId());
                if (!(e instanceof aiKingdomEntity king)) return;

                var state = aiKingdomState.get(sl.getServer());
                var k = state.getOrCreateForKing(sl, king);

                ServerPlayNetworking.send(player, new aiTradeInfoS2CPayload(
                        k.name,
                        k.happiness,
                        k.security,
                        k.gold, k.wood, k.metal, k.gems, k.potions, k.armor, k.horses, k.weapons,
                        k.hasBorder, k.borderMinX, k.borderMaxX, k.borderMinZ, k.borderMaxZ
                ));
            });
        });

        

        // --- Keepalive freeze while diplomacy screen open ---
        ServerPlayNetworking.registerGlobalReceiver(diplomacyFreezeC2SPayload.TYPE, (payload, ctx) -> {
            ctx.server().execute(() -> {
                var player = ctx.player();
                var level = (ServerLevel) player.level();

                Entity e = level.getEntity(payload.entityId());
                if (!(e instanceof aiKingdomEntity king)) return;

                if (player.distanceToSqr(king) > (24 * 24)) return;

                if (payload.open()) king.freezeForDiplomacy(40);
                else king.clearDiplomacyFreeze();
            });
        });

        // --- Toggle job enabled/disabled ---
        ServerPlayNetworking.registerGlobalReceiver(toggleJobEnabledPayload.TYPE, (payload, ctx) -> {
            ctx.server().execute(() -> {
                var player = ctx.player();
                ServerLevel level = (ServerLevel) player.level();
                var pos = payload.pos();

                if (!level.hasChunkAt(pos)) return;

                var state = level.getBlockState(pos);
                if (!(state.getBlock() instanceof jobBlock)) return;
                if (!state.hasProperty(jobBlock.ENABLED)) return;

                boolean next = !state.getValue(jobBlock.ENABLED);
                level.setBlock(pos, state.setValue(jobBlock.ENABLED, next), 3);

                player.sendSystemMessage(Component.literal(next ? "Production enabled." : "Production disabled."));
            });
        });

        // --- Border wand request ---
        ServerPlayNetworking.registerGlobalReceiver(requestBorderWandPayload.TYPE, (payload, ctx) -> {
            ctx.server().execute(() -> {
                var player = ctx.player();

                boolean has = player.getInventory().contains(new ItemStack(modItem.BORDER_WAND));
                if (has) return;

                var stack = new ItemStack(modItem.BORDER_WAND, 1);
                if (!player.getInventory().add(stack)) player.drop(stack, false);
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(warCommandCycleGroupC2SPayload.TYPE, (payload, ctx) -> {
            ctx.server().execute(() -> {
                var player = ctx.player();

                // change WarBattleManager to return int (0/1/2) or use ordinal
                int g = WarBattleManager.cycleCommandGroupOrdinal(player);

                // update held item so tooltip/model can change
                var stack = player.getMainHandItem();
                if (stack.getItem() instanceof name.kingdoms.WarCommandItem) {
                    name.kingdoms.WarCommandItem.setGroup(stack, g);
                }

                ServerPlayNetworking.send(player, new name.kingdoms.payload.warCommandGroupSyncS2CPayload(g));
            });
        });


        // --- Kingdom query ---
        ServerPlayNetworking.registerGlobalReceiver(kingdomQueryPayload.TYPE, (payload, ctx) -> {
            ctx.server().execute(() -> {
                var player = ctx.player();
                ServerLevel level = (ServerLevel) player.level();

                var state = kingdomState.get(ctx.server());
                var kk = state.getKingdomAt(level, payload.origin());

                boolean exists = (kk != null);
                String kName = exists ? kk.name : "";

                ServerPlayNetworking.send(player, new kingdomQueryResultPayload(payload.origin(), exists, kName));
            });
        });

        // --- Create kingdom ---
        ServerPlayNetworking.registerGlobalReceiver(CreateKingdomPayload.TYPE, (payload, ctx) -> {
            ctx.server().execute(() -> {
                var player = ctx.player();
                if (!(player.level() instanceof ServerLevel level)) return;

                try {
                    var state = kingdomState.get(ctx.server());

                    // 1) Create the kingdom (your existing call)
                    var kk = state.createKingdom(level, player.getUUID(), payload.name(), payload.origin());

                    // 2) Bind the clicked kingdom block as the terminal (CRITICAL)
                    BlockPos origin = payload.origin();
                    state.claimCellForKingdom(level, kk, origin);
                    state.bindTerminal(level, kk, origin);

                    // 3) Spawn / restore retinue immediately
                    if (player instanceof ServerPlayer sp) {
                        RetinueSpawner.ensureRetinue(level, sp, kk);
                    }

                    // 4) Persist
                    state.markDirty();

                    // 5) Response + chat
                    ServerPlayNetworking.send(player, new createKingdomResultPayload(true, kk.name));
                    player.sendSystemMessage(Component.literal("Created kingdom: " + kk.name));

                    // Debug log (server log)
                    Kingdoms.LOGGER.info("[Kingdoms] CreateKingdom OK name={} owner={} origin={}",
                            kk.name, player.getGameProfile().name(), origin);

                } catch (Exception ex) {
                    ServerPlayNetworking.send(player, new createKingdomResultPayload(false, ex.getMessage()));
                    player.sendSystemMessage(Component.literal("Failed to create kingdom: " + ex.getMessage()));

                    Kingdoms.LOGGER.warn("[Kingdoms] CreateKingdom FAILED owner={} origin={} err={}",
                            player.getGameProfile().name(), payload.origin(), ex.toString());
                }
            });
        });


        // --- Disband kingdom ---
        ServerPlayNetworking.registerGlobalReceiver(disbandKingdomPayload.TYPE, (payload, ctx) -> {
            ctx.server().execute(() -> {
                var player = ctx.player();
                ServerLevel level = (ServerLevel) player.level();

                try {
                    var state = kingdomState.get(ctx.server());
                    boolean ok = state.disbandAt(level, player.getUUID(), payload.origin());
                    state.markDirty();

                    if (ok) {
                        // Clear the owner's local UI/wand state immediately
                        ServerPlayNetworking.send(player, new kingdomInfoSyncPayload(false, ""));
                        ServerPlayNetworking.send(player, ecoSyncPayload.zeros());

                        // Refresh map borders for everyone
                        broadcastBorders(ctx.server());

                        player.sendSystemMessage(Component.literal("Kingdom disbanded."));
                    } else {
                        player.sendSystemMessage(Component.literal("No kingdom found here."));
                    }
                } catch (Exception e) {
                    player.sendSystemMessage(Component.literal("Failed to disband: " + e.getMessage()));
                }

            });
        });

        // --- Kingdom info request ---
        ServerPlayNetworking.registerGlobalReceiver(kingdomInfoRequestPayload.TYPE, (payload, ctx) -> {
            ctx.server().execute(() -> {
                var player = ctx.player();
                var state = kingdomState.get(ctx.server());
                var kk = state.getPlayerKingdom(player.getUUID());

                if (kk == null) ServerPlayNetworking.send(player, new kingdomInfoSyncPayload(false, ""));
                else ServerPlayNetworking.send(player, new kingdomInfoSyncPayload(true, kk.name));
            });
        });

        // --- ECONOMY REQUEST ---
        ServerPlayNetworking.registerGlobalReceiver(ecoRequestPayload.TYPE, (payload, ctx) -> {
            ctx.server().execute(() -> {
                var player = ctx.player();
                var level = (ServerLevel) player.level();

                var state = kingdomState.get(ctx.server());

                // 1) Try player-owned kingdom first (if you want that behavior)
                var k = state.getPlayerKingdom(player.getUUID());

                // 2) If player doesn't "own/belong", fall back to "kingdom at player's feet"
                if (k == null) {
                    k = state.getKingdomAt(level, player.blockPosition());
                }

                if (k == null) ServerPlayNetworking.send(player, ecoSyncPayload.zeros());
                else ServerPlayNetworking.send(player, ecoSyncPayload.fromKingdomWithProjected(k));
            });
        });

        // --- Treasury buy job ---
        ServerPlayNetworking.registerGlobalReceiver(treasuryBuyJobPayload.TYPE, (payload, ctx) -> {
            ctx.server().execute(() -> {
                var player = ctx.player();
                var state = kingdomState.get(ctx.server());
                var kk = state.getPlayerKingdom(player.getUUID());
                if (kk == null) return;

                jobDefinition job = jobDefinition.byId(payload.jobId());
                if (job == null) return;

                int qty = Math.max(1, Math.min(payload.qty(), 64));
                if (!canAfford(kk, job, qty)) return;

                spend(kk, job, qty);
                state.markDirty();

                var stack = jobBlocks.stackFor(job.getId(), qty);
                if (!player.getInventory().add(stack)) player.drop(stack, false);

                ServerPlayNetworking.send(player, ecoSyncPayload.fromKingdomWithProjected(kk));
            });
        });

        // --- TREASURY OPEN ---
        ServerPlayNetworking.registerGlobalReceiver(treasuryOpenPayload.TYPE, (payload, ctx) -> {
            ctx.server().execute(() -> {
                var player = ctx.player();
                var level = (ServerLevel) player.level();
                var pos = payload.pos();

                // Always sync shop list first
                ServerPlayNetworking.send(player, treasuryShop.buildShopPayload());

                var ks = kingdomState.get(ctx.server());

                // IMPORTANT: resolve by BLOCK POSITION, not player UUID
                var k = ks.getKingdomAt(level, pos);

                if (k != null) ServerPlayNetworking.send(player, ecoSyncPayload.fromKingdomWithProjected(k));
                else ServerPlayNetworking.send(player, ecoSyncPayload.zeros());
            });
        });
        
        // --- WAR ZONES REQUEST ---
        ServerPlayNetworking.registerGlobalReceiver(
                name.kingdoms.payload.warZonesRequestPayload.TYPE,
                (payload, ctx) -> ctx.server().execute(() -> {

                    net.minecraft.server.level.ServerPlayer player = ctx.player();
                    net.minecraft.server.MinecraftServer server = ctx.server();

                    name.kingdoms.war.WarState ws = name.kingdoms.war.WarState.get(server);
                    name.kingdoms.kingdomState ks = name.kingdoms.kingdomState.get(server);
                    name.kingdoms.aiKingdomState ai = name.kingdoms.aiKingdomState.get(server);

                    name.kingdoms.kingdomState.Kingdom pk = ks.getPlayerKingdom(player.getUUID());
                    if (pk == null) {
                        // optional debug
                        name.kingdoms.Kingdoms.LOGGER.info("[War] zones request: player={} has NO kingdom",
                                player.getScoreboardName());

                        ServerPlayNetworking.send(
                                player,
                                new name.kingdoms.payload.warZonesSyncPayload(java.util.List.of())
                        );
                        return;
                    }

                    //call once, then debug, then iterate
                    java.util.List<name.kingdoms.war.WarState.ZoneView> zones = ws.getZonesFor(server, pk.id);

                    name.kingdoms.Kingdoms.LOGGER.info(
                            "[War] zones request: player={} pk={} wars={} zonesStored={} zonesReturned={}",
                            player.getScoreboardName(),
                            pk.id,
                            ws.wars().size(),
                            // how many zones are in the map right now (if you want this, add a zonesSize() getter; see below)
                            -1,
                            zones.size()
                    );

                    java.util.List<name.kingdoms.payload.warZonesSyncPayload.Entry> out =
                            new java.util.ArrayList<>();

                    for (name.kingdoms.war.WarState.ZoneView zv : zones) {
                        java.util.UUID enemyId = zv.enemyId();
                        name.kingdoms.war.BattleZone zone = zv.zone();

                        name.kingdoms.kingdomState.Kingdom ek = ks.getKingdom(enemyId);
                        String enemyName =
                                (ek != null && ek.name != null && !ek.name.isBlank())
                                        ? ek.name
                                        : ai.getNameById(enemyId);

                        out.add(new name.kingdoms.payload.warZonesSyncPayload.Entry(
                                enemyId,
                                enemyName,
                                zone.minX(), zone.minZ(), zone.maxX(), zone.maxZ()
                        ));
                    }

                    ServerPlayNetworking.send(player, new name.kingdoms.payload.warZonesSyncPayload(out));
                })
        );

        ServerPlayNetworking.registerGlobalReceiver(name.kingdoms.payload.warOverviewRequestC2SPayload.TYPE, (payload, ctx) -> {
            ctx.server().execute(() -> {
                var server = ctx.server();
                var player = ctx.player();

                var ks = name.kingdoms.kingdomState.get(server);
                var pk = ks.getPlayerKingdom(player.getUUID());
                if (pk == null) {
                    ServerPlayNetworking.send(player, new name.kingdoms.payload.warOverviewSyncS2CPayload(
                            0, 0, 0.0,0, java.util.List.of(), java.util.List.of()
                    ));
                    return;
                }

                var aiState = name.kingdoms.aiKingdomState.get(server);
                var warState = name.kingdoms.war.WarState.get(server);
                var alliance = name.kingdoms.diplomacy.AllianceState.get(server);

                // Player tickets (estimate for now)
                int garrisons = pk.active.getOrDefault("garrison", 0);
                int yourMax = Math.max(0, garrisons * 50);
                int yourAlive = yourMax; // until player losses tracked
                double potions = pk.potions;
                int soldierSkinId = pk.soldierSkinId;

                java.util.ArrayList<name.kingdoms.payload.warOverviewSyncS2CPayload.Entry> allies = new java.util.ArrayList<>();
                for (var allyId : alliance.alliesOf(pk.id)) {
                    allies.add(buildEntry(server, ks, aiState, allyId));
                }

                java.util.ArrayList<name.kingdoms.payload.warOverviewSyncS2CPayload.Entry> enemies = new java.util.ArrayList<>();
                for (var zv : warState.getZonesFor(server, pk.id)) { // enemy list from wars
                    enemies.add(buildEntry(server, ks, aiState, zv.enemyId()));
                }

                ServerPlayNetworking.send(player, new name.kingdoms.payload.warOverviewSyncS2CPayload(
                        yourAlive, yourMax, potions, soldierSkinId, allies, enemies
                ));
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(soldierSkinSelectC2SPayload.TYPE, (payload, ctx) -> {
            ctx.server().execute(() -> {
                var server = ctx.server();
                var player = ctx.player();

                var ks = name.kingdoms.kingdomState.get(server);
                var pk = ks.getPlayerKingdom(player.getUUID());
                if (pk == null) return;

                int clamped = net.minecraft.util.Mth.clamp(
                        payload.soldierSkinId(),
                        0,
                        name.kingdoms.entity.SoldierSkins.MAX_SKIN_ID
                );

                pk.soldierSkinId = clamped;
                name.kingdoms.war.WarBattleManager.applySkinToActiveUnits(server, pk.id, clamped);
                ks.markDirty();
            });
        });



    }

    // -------------------------
    // Helpers
    // -------------------------

    private static void addAiAckLetter(
            MinecraftServer server,
            ServerPlayer player,
            DiplomacyMailboxState mailbox,
            aiKingdomState.AiKingdom aiFrom,
            UUID aiKingdomId,
            String aiName,
            String playerKingdomName,
            int rel,
            Letter original,
            boolean playerAccepted,
            boolean executedOk
    ){
        ServerLevel level = server.overworld();
        if (level == null) return;

        RandomSource r = level.getRandom();
        long now = server.getTickCount();

        // Use ACK wording (not “AI accepted/refused”)
        String note = AiLetterText.generateAck(
            r,
            original.kind(),
            playerAccepted,
            executedOk,
            aiName,
            playerKingdomName,
            rel,
            (aiFrom == null ? null : aiFrom.personality),
            original.aType(), original.aAmount(),
            original.bType(), original.bAmount(),
            original.maxAmount(),
            original.cb()
    );

    


        boolean actionable = switch (original.kind()) {
            case REQUEST, OFFER, CONTRACT, ULTIMATUM,
                ALLIANCE_PROPOSAL, WHITE_PEACE, SURRENDER -> true;
            default -> false;
        };

        boolean success = playerAccepted && executedOk;

        String subject;
        if (actionable) {
            subject = (success ? "Accepted: " : "Refused: ") + labelFor(original.kind());
        } else {
            subject = "Acknowledged: " + labelFor(original.kind());
        }

        //status should match outcome for actionable letters
        Letter.Status replyStatus = actionable
                ? (success ? Letter.Status.ACCEPTED : Letter.Status.REFUSED)
                : Letter.Status.ACCEPTED; // ack-only kinds


        Letter reply = new Letter(
                UUID.randomUUID(),
                aiKingdomId,
                player.getUUID(),
                true,
                aiName,
                original.kind(),
                replyStatus,
                now,
                0L,
                original.aType(),
                original.aAmount(),
                original.bType(),
                original.bAmount(),
                original.maxAmount(),
                original.cb(),
                subject,
                note
        );


        mailbox.addLetter(player.getUUID(), reply);
        mailbox.setDirty();
    }


    private static boolean applyAccept(kingdomState kState, kingdomState.Kingdom playerK, kingdomState.Kingdom fromK, Letter letter) {
        return switch (letter.kind()) {
            case REQUEST -> {
                double have = EconomyMutator.get(playerK, letter.aType());
                if (have < letter.aAmount()) yield false;

                EconomyMutator.add(playerK, letter.aType(), -letter.aAmount());
                EconomyMutator.add(fromK, letter.aType(), +letter.aAmount());
                yield true;
            }
            case OFFER -> {
                double have = EconomyMutator.get(fromK, letter.aType());
                if (have < letter.aAmount()) yield false;

                EconomyMutator.add(fromK, letter.aType(), -letter.aAmount());
                EconomyMutator.add(playerK, letter.aType(), +letter.aAmount());
                yield true;
            }
            case CONTRACT -> {
                if (letter.bType() == null) yield false;

                double maxA = Math.max(0, letter.maxAmount());
                double tradeA = Math.max(0.0001, letter.aAmount());
                int maxTrades = (int) Math.floor(maxA / tradeA);
                if (maxTrades <= 0) yield false;

                double playerHaveB = EconomyMutator.get(playerK, letter.bType());
                int affordTrades = (int) Math.floor(playerHaveB / Math.max(0.0001, letter.bAmount()));
                int n = Math.min(maxTrades, affordTrades);
                if (n <= 0) yield false;

                double senderHaveA = EconomyMutator.get(fromK, letter.aType());
                int senderTrades = (int) Math.floor(senderHaveA / tradeA);
                n = Math.min(n, senderTrades);
                if (n <= 0) yield false;

                double giveA = n * tradeA;
                double takeB = n * letter.bAmount();

                EconomyMutator.add(fromK, letter.aType(), -giveA);
                EconomyMutator.add(playerK, letter.aType(), +giveA);

                EconomyMutator.add(playerK, letter.bType(), -takeB);
                EconomyMutator.add(fromK, letter.bType(), +takeB);

                yield true;
            }
              // NEW kinds: not economic "accept" operations (for now)
        case COMPLIMENT, INSULT, WARNING, ULTIMATUM, WAR_DECLARATION, WHITE_PEACE, SURRENDER -> false;
            default -> throw new IllegalArgumentException("Unexpected value: " + letter.kind());
        };
    }

    private static boolean applyAcceptAi(kingdomState.Kingdom playerK, aiKingdomState.AiKingdom aiFrom, Letter letter) {
        return switch (letter.kind()) {
            case REQUEST -> {
                // AI requests resource A from player
                double have = EconomyMutator.get(playerK, letter.aType());
                if (have < letter.aAmount()) yield false;

                EconomyMutator.add(playerK, letter.aType(), -letter.aAmount());
                addAi(aiFrom, letter.aType(), +letter.aAmount());
                yield true;
            }
            case OFFER -> {
                // AI offers resource A to player
                double have = getAi(aiFrom, letter.aType());
                if (have < letter.aAmount()) yield false;

                addAi(aiFrom, letter.aType(), -letter.aAmount());
                EconomyMutator.add(playerK, letter.aType(), +letter.aAmount());
                yield true;
            }
            case CONTRACT -> {
                if (letter.bType() == null) yield false;

                double maxA = Math.max(0, letter.maxAmount());
                double tradeA = Math.max(0.0001, letter.aAmount());
                int maxTrades = (int) Math.floor(maxA / tradeA);
                if (maxTrades <= 0) yield false;

                // player affordability in B
                double playerHaveB = EconomyMutator.get(playerK, letter.bType());
                int affordTrades = (int) Math.floor(playerHaveB / Math.max(0.0001, letter.bAmount()));
                int n = Math.min(maxTrades, affordTrades);
                if (n <= 0) yield false;

                // AI availability in A
                double aiHaveA = getAi(aiFrom, letter.aType());
                int aiTrades = (int) Math.floor(aiHaveA / tradeA);
                n = Math.min(n, aiTrades);
                if (n <= 0) yield false;

                double giveA = n * tradeA;
                double takeB = n * letter.bAmount();

                // AI gives A, player pays B
                addAi(aiFrom, letter.aType(), -giveA);
                EconomyMutator.add(playerK, letter.aType(), +giveA);

                EconomyMutator.add(playerK, letter.bType(), -takeB);
                addAi(aiFrom, letter.bType(), +takeB);

                yield true;
            }
           
              // NEW kinds: not economic "accept" operations (for now)
              case COMPLIMENT, INSULT, WARNING, ULTIMATUM, WAR_DECLARATION, WHITE_PEACE, SURRENDER -> false;
            default -> throw new IllegalArgumentException("Unexpected value: " + letter.kind());
        };
    }

    private static name.kingdoms.payload.warOverviewSyncS2CPayload.Entry buildEntry(
            net.minecraft.server.MinecraftServer server,
            name.kingdoms.kingdomState ks,
            name.kingdoms.aiKingdomState aiState,
            java.util.UUID kingdomId
    ) {
        var k = ks.getKingdom(kingdomId);
        String name = (k != null && k.name != null && !k.name.isBlank())
                ? k.name
                : aiState.getNameById(kingdomId);

        var aiK = aiState.getById(kingdomId);
        if (aiK != null) {
            int alive = Math.max(0, aiK.aliveSoldiers);
            int max = Math.max(1, aiK.maxSoldiers);
            return new name.kingdoms.payload.warOverviewSyncS2CPayload.Entry(kingdomId, name, alive, max);
        }

        // player kingdom estimate (garrisons*50)
        if (k != null) {
            int garrisons = k.active.getOrDefault("garrison", 0);
            int max = Math.max(0, garrisons * 50);
            int alive = max;
            return new name.kingdoms.payload.warOverviewSyncS2CPayload.Entry(kingdomId, name, alive, max);
        }

        return new name.kingdoms.payload.warOverviewSyncS2CPayload.Entry(kingdomId, name, 0, 0);
    }

    public static void sendWarOverviewTo(MinecraftServer server, ServerPlayer player) {
        kingdomState ks = kingdomState.get(server);
        kingdomState.Kingdom pk = ks.getPlayerKingdom(player.getUUID());
        if (pk == null) return;

        aiKingdomState aiState = aiKingdomState.get(server);
        var warState = name.kingdoms.war.WarState.get(server);
        var alliance = name.kingdoms.diplomacy.AllianceState.get(server);

        int garrisons = pk.active.getOrDefault("garrison", 0);
        int yourMax = Math.max(0, garrisons * 50);
        int yourAlive = yourMax;

        double potions = pk.potions;
        int soldierSkinId = pk.soldierSkinId;

        var allies = new java.util.ArrayList<name.kingdoms.payload.warOverviewSyncS2CPayload.Entry>();
        for (java.util.UUID allyId : alliance.alliesOf(pk.id)) {
            allies.add(buildEntry(server, ks, aiState, allyId));
        }

        var enemies = new java.util.ArrayList<name.kingdoms.payload.warOverviewSyncS2CPayload.Entry>();
        for (var zv : warState.getZonesFor(server, pk.id)) {
            enemies.add(buildEntry(server, ks, aiState, zv.enemyId()));
        }

        ServerPlayNetworking.send(player, new name.kingdoms.payload.warOverviewSyncS2CPayload(
                yourAlive, yourMax,
                potions, soldierSkinId,
                allies,
                enemies
        ));
    }

    private static String formatKingdomNameList(
            net.minecraft.server.MinecraftServer server,
            name.kingdoms.kingdomState ks,
            name.kingdoms.aiKingdomState aiState,
            java.util.List<java.util.UUID> ids,
            int maxShown
    ) {
        if (ids == null || ids.isEmpty()) return "None";

        // De-dupe while preserving order
        var seen = new java.util.HashSet<java.util.UUID>();
        var uniq = new java.util.ArrayList<java.util.UUID>(ids.size());
        for (var id : ids) if (seen.add(id)) uniq.add(id);

        int shown = Math.min(maxShown, uniq.size());
        var parts = new java.util.ArrayList<String>(shown);

        for (int i = 0; i < shown; i++) {
            var id = uniq.get(i);

            // prefer kingdomState name
            var kk = ks.getKingdom(id);
            String name = (kk != null && kk.name != null && !kk.name.isBlank()) ? kk.name : null;

            // fallback to ai
            if (name == null) {
                var aiK = aiState.getById(id);
                if (aiK != null && aiK.name != null && !aiK.name.isBlank()) name = aiK.name;
            }

            if (name == null) name = "Unknown";
            parts.add(name);
        }

        int extra = uniq.size() - shown;
        String s = String.join(", ", parts);
        if (extra > 0) s += " (+" + extra + ")";
        return s;
    }


    public static void sendWarOverviewTo(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel sl)) return;
        sendWarOverviewTo(sl.getServer(), player);
    }


    


    // -------------------------
    // AI economy helpers
    // -------------------------

    public static double getAi(aiKingdomState.AiKingdom k, ResourceType t) {
        return switch (t) {
            case GOLD -> k.gold;
            case WOOD -> k.wood;
            case METAL -> k.metal;
            case GEMS -> k.gems;
            case POTIONS -> k.potions;
            case ARMOR -> k.armor;
            case HORSES -> k.horses;
            case WEAPONS -> k.weapons;
            case MEAT -> k.meat;
            case GRAIN -> k.grain;
            case FISH -> k.fish;
        };
    }

    public static void addAi(aiKingdomState.AiKingdom k, ResourceType t, double delta) {
        switch (t) {
            case GOLD -> k.gold += delta;
            case WOOD -> k.wood += delta;
            case METAL -> k.metal += delta;
            case GEMS -> k.gems += delta;
            case POTIONS -> k.potions += delta;
            case ARMOR -> k.armor += delta;
            case HORSES -> k.horses += delta;
            case WEAPONS -> k.weapons += delta;
            case MEAT -> k.meat += delta;
            case GRAIN -> k.grain += delta;
            case FISH -> k.fish += delta;
        }
    }



    private static boolean canAfford(kingdomState.Kingdom k, jobDefinition j, int qty) {
        return  k.gold    >= j.costGold()    * qty &&
                k.meat    >= j.costMeat()    * qty &&
                k.grain   >= j.costGrain()   * qty &&
                k.fish    >= j.costFish()    * qty &&
                k.wood    >= j.costWood()    * qty &&
                k.metal   >= j.costMetal()   * qty &&
                k.armor   >= j.costArmor()   * qty &&
                k.weapons >= j.costWeapons() * qty &&
                k.gems    >= j.costGems()    * qty &&
                k.horses  >= j.costHorses()  * qty &&
                k.potions >= j.costPotions() * qty;
    }

    private static void spend(kingdomState.Kingdom k, jobDefinition j, int qty) {
        k.gold    -= j.costGold()    * qty;
        k.meat    -= j.costMeat()    * qty;
        k.grain   -= j.costGrain()   * qty;
        k.fish    -= j.costFish()    * qty;
        k.wood    -= j.costWood()    * qty;
        k.metal   -= j.costMetal()   * qty;
        k.armor   -= j.costArmor()   * qty;
        k.weapons -= j.costWeapons() * qty;
        k.gems    -= j.costGems()    * qty;
        k.horses  -= j.costHorses()  * qty;
        k.potions -= j.costPotions() * qty;
    }

    private static name.kingdoms.payload.bordersSyncPayload buildBordersPayloadFor(ServerPlayer viewer, kingdomState state) {
        java.util.List<name.kingdoms.payload.bordersSyncPayload.Entry> out = new java.util.ArrayList<>();

        var your = state.getPlayerKingdom(viewer.getUUID()); // may be null

        for (kingdomState.Kingdom k : state.getAllKingdoms()) {
            if (k == null) continue;

            boolean isYours = (k.owner != null && k.owner.equals(viewer.getUUID()));
            boolean include = k.hasBorder || isYours; // KEY CHANGE

            if (!include) continue;

            int color = colorFor(k.id);

            // If no border, send zeros (or any harmless values)
            int minX = k.hasBorder ? k.borderMinX : 0;
            int maxX = k.hasBorder ? k.borderMaxX : 0;
            int minZ = k.hasBorder ? k.borderMinZ : 0;
            int maxZ = k.hasBorder ? k.borderMaxZ : 0;

            out.add(new name.kingdoms.payload.bordersSyncPayload.Entry(
                    k.id,
                    (k.name == null ? "" : k.name),
                    k.hasBorder,     // NEW
                    minX, maxX,
                    minZ, maxZ,
                    color,
                    isYours
            ));
        }

        return new name.kingdoms.payload.bordersSyncPayload(out);
    }


    private static int colorFor(java.util.UUID id) {
        // deterministic bright-ish color from UUID
        int h = id.hashCode();
        int rgb = (h & 0x00FFFFFF);
        // avoid too-dark colors
        rgb |= 0x00202020;
        return 0xFF000000 | rgb;
    }

    public static void broadcastBorders(net.minecraft.server.MinecraftServer server) {
        var state = kingdomState.get(server);
        for (var sp : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(sp, buildBordersPayloadFor(sp, state));
        }
    }


        private static String labelFor(Letter.Kind k) {
            return switch (k) {
                case OFFER -> "Offer";
                case REQUEST -> "Request";
                case CONTRACT -> "Contract";
                case ULTIMATUM -> "Ultimatum";
                case ALLIANCE_PROPOSAL -> "Alliance Proposal";
                case WHITE_PEACE -> "White Peace";
                case SURRENDER -> "Surrender";
                case WAR_DECLARATION -> "War Declaration";
                case ALLIANCE_BREAK -> "Alliance Break";
                case COMPLIMENT -> "Compliment";
                case INSULT -> "Insult";
                case WARNING -> "Warning";
                default -> k.name();
            };
        }

        private static long baseCooldownTicks(Letter.Kind kind) {
            return switch (kind) {
                case OFFER, REQUEST, CONTRACT -> 20L * 60L * 2L;      // 2 min
                case COMPLIMENT, WARNING      -> 20L * 60 * 4L;           // 1 min
                case INSULT                   -> 20L * 60L * 2L;      // 3 min
                case ALLIANCE_PROPOSAL        -> 20L * 60L * 10L;     // 10 min
                case ALLIANCE_BREAK           -> 20L * 60L * 10L;
                case ULTIMATUM                -> 20L * 60L * 15L;     // 15 min
                case WAR_DECLARATION          -> 20L * 60L * 20L;     // 20 min
                case WHITE_PEACE, SURRENDER   -> 20L * 60L * 3L;
                default                       -> 20L * 60L * 2L;
            };
        }

        private static String fmtCooldown(long ticks) {
            long sec = (ticks + 19) / 20;
            if (sec < 60) return sec + "s";
            long min = sec / 60;
            long rem = sec % 60;
            return (rem == 0) ? (min + "m") : (min + "m " + rem + "s");
        }


    // -------------------------
    // War consequences
    // -------------------------
    public static void applyDefeatPenalty(MinecraftServer server, UUID loserKingdomId) {
        if (loserKingdomId == null) return;

        final double KEEP = 0.80; // lose 20%

        // -------- Player / KingdomState side --------
        var ks = kingdomState.get(server);
        var k = ks.getKingdom(loserKingdomId);
        if (k != null) {
            k.gold    *= KEEP;
            k.meat    *= KEEP;
            k.grain   *= KEEP;
            k.fish    *= KEEP;
            k.wood    *= KEEP;
            k.metal   *= KEEP;
            k.armor   *= KEEP;
            k.weapons *= KEEP;
            k.gems    *= KEEP;
            k.horses  *= KEEP;
            k.potions *= KEEP;

            ks.markDirty();
        }

        // -------- AI side --------
        var aiState = aiKingdomState.get(server);
        var aiK = aiState.getById(loserKingdomId);
        if (aiK != null) {
            aiK.gold    *= KEEP;
            aiK.meat    *= KEEP;
            aiK.grain   *= KEEP;
            aiK.fish    *= KEEP;
            aiK.wood    *= KEEP;
            aiK.metal   *= KEEP;
            aiK.armor   *= KEEP;
            aiK.weapons *= KEEP;
            aiK.gems    *= KEEP;
            aiK.horses  *= KEEP;
            aiK.potions *= KEEP;

            aiState.setDirty();
        }
    }

    private static long cooldownTicksForKind(name.kingdoms.diplomacy.Letter.Kind k) {
        // Tune these to match whatever you were using before.
        // 20 ticks = 1 second
        return switch (k) {
            case REQUEST, OFFER, CONTRACT, ULTIMATUM -> 20L * 60L; // 60s
            case COMPLIMENT, INSULT, WARNING -> 20L * 30L;         // 30s
            case WAR_DECLARATION, ALLIANCE_PROPOSAL, ALLIANCE_BREAK,
                WHITE_PEACE, SURRENDER -> 20L * 120L;             // 120s
            default -> 20L * 60L;
        };
    }


}
