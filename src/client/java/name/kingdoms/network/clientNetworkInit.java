package name.kingdoms.network;

import org.jetbrains.annotations.Nullable;

import name.kingdoms.clientWarZoneCache;
import name.kingdoms.jobRequirementsScreen;
import name.kingdoms.kingdomMenuScreen;
import name.kingdoms.kingdomsClient;
import name.kingdoms.kingdomsClientProxy;
import name.kingdoms.treasuryScreen;
import name.kingdoms.client.ClientEconomyView;
import name.kingdoms.client.ClientMailCache;
import name.kingdoms.client.KingdomTransitionHUD;
import name.kingdoms.client.diploScreen;
import name.kingdoms.menu.diploMenu;
import name.kingdoms.payload.OpenMailS2CPayload;
import name.kingdoms.payload.OpenTreasuryS2CPayload;
import name.kingdoms.payload.aiTradeInfoS2CPayload;
import name.kingdoms.payload.bordersSyncPayload;
import name.kingdoms.payload.createKingdomResultPayload;
import name.kingdoms.payload.ecoSyncPayload;
import name.kingdoms.payload.jobReqsS2CPayload;
import name.kingdoms.payload.kingdomInfoSyncPayload;
import name.kingdoms.payload.kingdomQueryPayload;
import name.kingdoms.payload.kingdomQueryResultPayload;
import name.kingdoms.payload.kingdomTransitionS2CPayload;
import name.kingdoms.payload.mailInboxSyncPayload;
import name.kingdoms.payload.mailRecipientsSyncS2CPayload;
import name.kingdoms.payload.mailSendResultS2CPayload;
import name.kingdoms.payload.openKingdomMenuPayload;
import name.kingdoms.payload.opendiplomacyS2CPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import name.kingdoms.payload.treasuryShopSyncPayload;
import name.kingdoms.payload.warCommandGroupSyncS2CPayload;
import name.kingdoms.payload.warZonesSyncPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import name.kingdoms.client.ScribeLines;

public final class clientNetworkInit {
    private clientNetworkInit() {}

        private static int LAST_INBOX_COUNT = -1;

        @Nullable
        private static name.kingdoms.entity.kingdomWorkerEntity findNearestScribe(Minecraft mc) {
            if (mc.level == null || mc.player == null) return null;

            double radius = 96.0; // generous so it works even if they’re trailing behind
            var aabb = mc.player.getBoundingBox().inflate(radius);

            name.kingdoms.entity.kingdomWorkerEntity best = null;
            double bestD2 = Double.MAX_VALUE;

            for (var w : mc.level.getEntitiesOfClass(name.kingdoms.entity.kingdomWorkerEntity.class, aabb)) {
                if (!w.isRetinue()) continue;
                if (!"scribe".equals(w.getJobId())) continue; 
                var owner = w.getOwnerUUID();
                if (owner == null || !owner.equals(mc.player.getUUID())) continue;

                double d2 = w.distanceToSqr(mc.player);
                if (d2 < bestD2) { bestD2 = d2; best = w; }
            }
            return best;
        }
    
    public static void registerClientReceivers() {

       ClientPlayNetworking.registerGlobalReceiver(bordersSyncPayload.TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> {
                name.kingdoms.kingdomsClient.CLIENT_BORDERS = payload;

                boolean haveKingdom = false;
                boolean hasBorder = true;

                // Server includes your kingdom entry even if hasBorder==false
                for (var e : payload.entries()) {
                    if (e.isYours()) {
                        haveKingdom = true;
                        hasBorder = e.hasBorder();
                        break;
                    }
                }

                name.kingdoms.kingdomsClient.YOU_HAVE_A_KINGDOM = haveKingdom;
                name.kingdoms.kingdomsClient.YOUR_KINGDOM_HAS_BORDER = hasBorder;

                if (Minecraft.getInstance().screen instanceof name.kingdoms.client.kingdomBordersMapScreen s) {
                    s.onBordersUpdated();
                }
            });
        });



        ClientPlayNetworking.registerGlobalReceiver(warZonesSyncPayload.TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> {
               
                var zones = payload.zones().stream()
                        .map(e -> new clientWarZoneCache.Zone(
                                e.enemyId(), e.enemyName(),
                                e.minX(), e.minZ(), e.maxX(), e.maxZ()
                        ))
                        .toList();

                clientWarZoneCache.setAll(zones);
            });
        });


        ClientPlayNetworking.registerGlobalReceiver(
                name.kingdoms.payload.warBattleHudSyncPayload.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> {

                    if (!payload.active()) {
                        name.kingdoms.client.ClientWarBattleStatus.clear();
                        return;
                    }

                    // If this is the first update we’ve seen for this battle,
                    // treat current values as the "start" (max) for the bars.
                    if (!name.kingdoms.client.ClientWarBattleStatus.active) {
                        name.kingdoms.client.ClientWarBattleStatus.begin(
                                payload.friendTickets(),
                                payload.enemyTickets(),
                                payload.friendMorale(),
                                payload.enemyMorale()
                        );
                    }

                    name.kingdoms.client.ClientWarBattleStatus.update(
                            payload.friendTickets(),
                            payload.enemyTickets(),
                            payload.friendMorale(),
                            payload.enemyMorale()
                    );
                })
        );

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
                name.kingdoms.payload.warOverviewSyncS2CPayload.TYPE,
                (payload, ctx) -> {
                    ctx.client().execute(() -> {
                        net.minecraft.client.Minecraft.getInstance()
                                .setScreen(new name.kingdoms.client.WarOverviewScreen(payload));
                    });
                }
        );

        ClientPlayNetworking.registerGlobalReceiver(
                name.kingdoms.payload.kingdomHoverSyncS2CPayload.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> {
                    name.kingdoms.client.clientHoverCardCache.put(payload);
                })
        );

        ClientPlayNetworking.registerGlobalReceiver(name.kingdoms.payload.newsSyncS2CPayload.TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> name.kingdoms.client.ClientNewsCache.set(payload.lines()));
        });

        ClientPlayNetworking.registerGlobalReceiver(kingdomTransitionS2CPayload.TYPE,(payload, ctx) -> ctx.client().execute(() -> {

                    String msg = (payload.entering() ? "Entering " : "Exiting ")
                            + "Kingdom of " + payload.kingdomName();

                    KingdomTransitionHUD.show(Component.literal(msg));
                })
        );

        ClientPlayNetworking.registerGlobalReceiver(
                name.kingdoms.payload.mailPolicySyncS2CPayload.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> {
                    var map = new java.util.EnumMap<name.kingdoms.diplomacy.Letter.Kind, name.kingdoms.client.ClientMailPolicyCache.Decision>(
                            name.kingdoms.diplomacy.Letter.Kind.class
                    );

                    for (var e : payload.entries()) {
                        int ord = e.kindOrdinal();
                        if (ord < 0 || ord >= name.kingdoms.diplomacy.Letter.Kind.values().length) continue;
                        
                        var kind = name.kingdoms.diplomacy.Letter.Kind.values()[ord];
                        map.put(kind, new name.kingdoms.client.ClientMailPolicyCache.Decision(e.allowed(), e.reason()));
                    }

                    name.kingdoms.client.ClientMailPolicyCache.put(payload.toKingdomId(), map);

                    // If the mail screen is open, let it refresh next tick (it already does)
                })
        );


        ClientPlayNetworking.registerGlobalReceiver(OpenMailS2CPayload.TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> {
                var mc = net.minecraft.client.Minecraft.getInstance();
                // Open your mail screen here (scribe UI)
                mc.setScreen(new name.kingdoms.client.mailScreen()); // or new mailScreen(payload.entityId())
                // Optionally: have the screen request inbox/recipients in init()
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(warCommandGroupSyncS2CPayload.TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> {
                var player = net.minecraft.client.Minecraft.getInstance().player;
                if (player == null) return;

                int g = payload.group();

                var main = player.getMainHandItem();
                if (main.getItem() instanceof name.kingdoms.WarCommandItem) {
                    name.kingdoms.WarCommandItem.setGroup(main, g);
                }
                var off = player.getOffhandItem();
                if (off.getItem() instanceof name.kingdoms.WarCommandItem) {
                    name.kingdoms.WarCommandItem.setGroup(off, g);
                }
            });
        });



        ClientPlayNetworking.registerGlobalReceiver(mailSendResultS2CPayload.TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                   mc.gui.getChat().addMessage(Component.literal((payload.accepted() ? "Mail sent: " : "Mail failed: ") + payload.message()));
                }
            });
        });

    

        ClientPlayNetworking.registerGlobalReceiver(mailInboxSyncPayload.TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> {
                // 1) Detect change BEFORE overwriting cache
                int prev = LAST_INBOX_COUNT;
                int now  = (payload.inbox() == null) ? 0 : payload.inbox().size();

                // 2) Update client cache (existing behavior)
                ClientMailCache.setInbox(payload.inbox());

                // Seed on first sync so we don’t announce on join / reconnect
                if (prev < 0) {
                    LAST_INBOX_COUNT = now;
                    return;
                }

                // 3) New mail arrived
                if (now > prev) {
                    var mc = Minecraft.getInstance();
                    if (mc.player == null) {
                        LAST_INBOX_COUNT = now;
                        return;
                    }

                    int delta = now - prev;

                    // Find the scribe entity if present (optional but recommended)
                    var scribe = findNearestScribe(mc);

                    Component sender = (scribe != null)
                            ? scribe.getDisplayName()          // "Edwin (Scribe)"
                            : Component.literal("Scribe");     // fallback

                    String text = ScribeLines.pickLine(delta);

                    Component chatLine = Component.translatable(
                            "chat.type.text",
                            sender,
                            Component.literal(text)
                                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)
                    );

                    // Add it as a real chat line
                    mc.gui.getChat().addMessage(chatLine);

                    mc.player.playSound(SoundEvents.BOOK_PAGE_TURN, 0.6f, 1.0f);
                }

                LAST_INBOX_COUNT = now;

            });
        });

        ClientPlayNetworking.registerGlobalReceiver(mailRecipientsSyncS2CPayload.TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> {
                name.kingdoms.client.ClientMailRecipientsCache.set(payload.recipients());

                if (Minecraft.getInstance().screen instanceof name.kingdoms.client.mailScreen ms) {
                    ms.requestPolicyForSelectedRecipientPublic();
                }
            });
        });

        // --- Diplomacy open screen ---
        
        ClientPlayNetworking.registerGlobalReceiver(opendiplomacyS2CPayload.TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null) return;

                diploMenu menu = new diploMenu(0, mc.player.getInventory());
                menu.setContext(payload.entityId(), payload.entityUuid(), payload.kingdomID(), payload.kingdomName(), payload.relation());

                mc.setScreen(new diploScreen(
                        menu,
                        mc.player.getInventory(),
                        Component.literal("Diplomacy")
                ));
            });
        });

        // --- AI trade info to chat ---
        ClientPlayNetworking.registerGlobalReceiver(aiTradeInfoS2CPayload.TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.gui == null || mc.player == null) return;

                mc.gui.getChat().addMessage(Component.literal("=== " + payload.name() + " ==="));
                mc.gui.getChat().addMessage(Component.literal(
                        "Happiness: " + payload.happiness() + " | Security: " + payload.security()
                ));
                mc.gui.getChat().addMessage(Component.literal(String.format(
                        "Gold %.0f | Wood %.0f | Metal %.0f | Gems %.0f",
                        payload.gold(), payload.wood(), payload.metal(), payload.gems()
                )));
                mc.gui.getChat().addMessage(Component.literal(String.format(
                        "Potions %.0f | Armor %.0f | Horses %.0f | Weapons %.0f",
                        payload.potions(), payload.armor(), payload.horses(), payload.weapons()
                )));
                if (payload.hasBorder()) {
                    mc.gui.getChat().addMessage(Component.literal(String.format(
                            "Border: x[%d..%d] z[%d..%d]",
                            payload.minX(), payload.maxX(), payload.minZ(), payload.maxZ()
                    )));
                }
            });
        });


        
        // --- Economy / kingdom info ---
        ClientPlayNetworking.registerGlobalReceiver(ecoSyncPayload.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> {
                    kingdomsClient.CLIENT_ECO = payload;   // existing behavior
                    ClientEconomyView.set(payload);        // NEW: used by mailScreen
                })
        );

        ClientPlayNetworking.registerGlobalReceiver(kingdomInfoSyncPayload.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> {
                    kingdomsClient.CLIENT_KINGDOM_INFO = payload;

                    if (!payload.hasKingdom()) {
                        kingdomsClient.CLIENT_BORDERS =
                                new name.kingdoms.payload.bordersSyncPayload(java.util.List.of());

                        // Stop any client-side preview particles immediately
                        name.kingdoms.client.borderWandClient.clearSelection();
                    }
                })
        );


        // --- Treasury shop sync ---
        ClientPlayNetworking.registerGlobalReceiver(treasuryShopSyncPayload.TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> {
                kingdomsClient.CLIENT_TREASURY_SHOP = payload;
                if (Minecraft.getInstance().screen instanceof treasuryScreen ts) ts.rebuildAll();
            });
        });

        // --- Open kingdom menu ---
        ClientPlayNetworking.registerGlobalReceiver(openKingdomMenuPayload.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> kingdomsClientProxy.openKingdomMenu(payload.pos()))
        );

        // --- Job requirements screen ---
        ClientPlayNetworking.registerGlobalReceiver(jobReqsS2CPayload.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> {
                    // Ask server for up-to-date economy + kingdom info (same pattern as hover/economy UI)
                    ClientPlayNetworking.send(new name.kingdoms.payload.kingdomInfoRequestPayload());
                    ClientPlayNetworking.send(new name.kingdoms.payload.ecoRequestPayload());

                    Minecraft.getInstance().setScreen(new jobRequirementsScreen(payload));
                })
        );


        // --- Kingdom query result screen ---
        ClientPlayNetworking.registerGlobalReceiver(kingdomQueryResultPayload.TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> Minecraft.getInstance().setScreen(
                    new kingdomMenuScreen(payload.origin(), payload.exists(), payload.name())
            ));
        });

        // --- Open treasury UI ---
        ClientPlayNetworking.registerGlobalReceiver(OpenTreasuryS2CPayload.TYPE, (payload, ctx) -> {
            BlockPos pos = payload.pos();
            ctx.client().execute(() -> kingdomsClientProxy.openTreasury(pos));
        });

        // --- Create kingdom result ---
        ClientPlayNetworking.registerGlobalReceiver(createKingdomResultPayload.TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null) return;

                mc.player.displayClientMessage(Component.literal(payload.message()), false);

                if (kingdomsClient.LAST_KINGDOM_BLOCK != null
                        && ClientPlayNetworking.canSend(kingdomQueryPayload.TYPE)) {

                    ClientPlayNetworking.send(new kingdomQueryPayload(kingdomsClient.LAST_KINGDOM_BLOCK));
                }
            });
        });
    }
}
