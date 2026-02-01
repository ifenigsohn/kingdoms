package name.kingdoms;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import name.kingdoms.diplomacy.AiDiplomacyTicker;
import name.kingdoms.diplomacy.Letter;
import name.kingdoms.diplomacy.ResourceType;

import name.kingdoms.blueprint.WorldgenToggleState;
import name.kingdoms.diplomacy.AiDiplomacyEvent;
import name.kingdoms.diplomacy.AiDiplomacyEventState;
import name.kingdoms.diplomacy.AiEconomyMutator;
import name.kingdoms.diplomacy.AiRelationNormalizer;
import name.kingdoms.diplomacy.AllianceState;
import name.kingdoms.news.KingdomNewsState;
import name.kingdoms.pressure.KingdomPressureState;
import name.kingdoms.pressure.PressureCatalog;
import name.kingdoms.war.BattleZone;
import name.kingdoms.war.WarState;
import name.kingdoms.sim.SimRunWriter;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import name.kingdoms.ambient.AmbientContext;
import name.kingdoms.ambient.AmbientEvent;
import name.kingdoms.ambient.AmbientEvents;
import name.kingdoms.ambient.ScriptedAmbientEvent;




import java.util.Locale;

public final class KingdomsCommands {
        
    public static void register() {
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> register(dispatcher)
        );
    }

        private static boolean getBoolArgOrDefault(CommandContext<CommandSourceStack> ctx, String name, boolean def) {
        try { return BoolArgumentType.getBool(ctx, name); } catch (Exception e) { return def; }
        }

        private static int getIntArgOrDefault(CommandContext<CommandSourceStack> ctx, String name, int def) {
        try { return IntegerArgumentType.getInteger(ctx, name); } catch (Exception e) { return def; }
        }

        private static String getStringArgOrNull(CommandContext<CommandSourceStack> ctx, String name) {
        try { return StringArgumentType.getString(ctx, name); } catch (Exception e) { return null; }
        }

        private static int parseIntField(String s, String key) {
        var m = java.util.regex.Pattern.compile("\\b" + key + "=(-?\\d+)\\b").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
        }

        private static double parseDoubleField(String s, String key) {
        var m = java.util.regex.Pattern.compile("\\b" + key + "=([0-9]+\\.[0-9]+)\\b").matcher(s);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
        }

        private static boolean parseBoolField(String s, String key) {
        var m = java.util.regex.Pattern.compile("\\b" + key + "=(true|false)\\b").matcher(s);
        return m.find() && Boolean.parseBoolean(m.group(1));
        }


        private static int masterSimExec(CommandContext<CommandSourceStack> ctx, String aiList) {
                int steps = IntegerArgumentType.getInteger(ctx, "steps");
                int ticksPerStep = IntegerArgumentType.getInteger(ctx, "ticksPerStep");

                boolean file = getBoolArgOrDefault(ctx, "file", true);
                boolean silent = getBoolArgOrDefault(ctx, "silent", false);
                int every = getIntArgOrDefault(ctx, "every", 10);
                String label = getStringArgOrNull(ctx, "label");

                return masterSim(ctx.getSource(), steps, ticksPerStep, aiList, file, silent, every, label);
        }




    private static void register(CommandDispatcher<CommandSourceStack> d) {
        
        
        d.register(
                Commands.literal("kingdom_event")
                        .then(Commands.argument("typeId", StringArgumentType.word())
                        .executes(ctx -> {
                                ServerPlayer p = ctx.getSource().getPlayerOrException();
                                var server = ctx.getSource().getServer();

                                var ks = kingdomState.get(server);
                                var pk = ks.getPlayerKingdom(p.getUUID());
                                if (pk == null) {
                                ctx.getSource().sendFailure(Component.literal("You are not in a kingdom."));
                                return 0;
                                }

                                String typeId = StringArgumentType.getString(ctx, "typeId");
                                long now = server.getTickCount();

                                PressureCatalog.Template tpl = switch (typeId) {
                                case "push_production" -> PressureCatalog.PUSH_PRODUCTION();
                                case "ease_workload" -> PressureCatalog.EASE_WORKLOAD();
                                case "increase_patrols" -> PressureCatalog.INCREASE_PATROLS();
                                case "sow_discontent" -> PressureCatalog.SOW_DISCONTENT();
                                default -> null;
                                };

                                if (tpl == null) {
                                ctx.getSource().sendFailure(Component.literal("Unknown template: " + typeId));
                                return 0;
                                }

                                KingdomPressureState.get(server).addEvent(
                                pk.id, pk.id,
                                tpl.typeId(),
                                tpl.effects(),
                                KingdomPressureState.RelScope.GLOBAL,
                                now,
                                tpl.durationTicks()
                                );

                                ctx.getSource().sendSuccess(() -> Component.literal("Applied event: " + tpl.typeId()), false);
                                return 1;
                        })
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 120))
                                .executes(ctx -> {
                                ServerPlayer p = ctx.getSource().getPlayerOrException();
                                var server = ctx.getSource().getServer();

                                var ks = kingdomState.get(server);
                                var pk = ks.getPlayerKingdom(p.getUUID());
                                if (pk == null) {
                                        ctx.getSource().sendFailure(Component.literal("You are not in a kingdom."));
                                        return 0;
                                }

                                String typeId = StringArgumentType.getString(ctx, "typeId");
                                int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
                                long duration = minutes * PressureCatalog.MINUTE;

                                PressureCatalog.Template tpl = switch (typeId) {
                                        case "push_production" -> PressureCatalog.PUSH_PRODUCTION();
                                        case "ease_workload" -> PressureCatalog.EASE_WORKLOAD();
                                        case "increase_patrols" -> PressureCatalog.INCREASE_PATROLS();
                                        case "sow_discontent" -> PressureCatalog.SOW_DISCONTENT();
                                        default -> null;
                                };

                                if (tpl == null) {
                                        ctx.getSource().sendFailure(Component.literal("Unknown template: " + typeId));
                                        return 0;
                                }

                                long now = server.getTickCount();

                                KingdomPressureState.get(server).addEvent(
                                        pk.id, pk.id,
                                        tpl.typeId(),
                                        tpl.effects(),
                                        KingdomPressureState.RelScope.GLOBAL,
                                        now,
                                        duration
                                );

                                ctx.getSource().sendSuccess(() -> Component.literal("Applied event: " + tpl.typeId() + " for " + minutes + "m"), false);
                                return 1;
                                })
                        )
                        )
                );

        
        
        
        
        
        
        d.register(Commands.literal("kingdoms")
                .requires(src -> src.hasPermission(2)) // OP only
                .then(Commands.literal("listai")
                        .executes(ctx -> listAi(ctx.getSource()))
                )
                .then(Commands.literal("worldgen")
                        .then(Commands.literal("status")
                                .executes(ctx -> worldgenStatus(ctx.getSource()))
                        )
                        .then(Commands.literal("on")
                                .executes(ctx -> worldgenSet(ctx.getSource(), true))
                        )
                        .then(Commands.literal("off")
                                .executes(ctx -> worldgenSet(ctx.getSource(), false))
                        )
                        .then(Commands.literal("toggle")
                                .executes(ctx -> worldgenToggle(ctx.getSource()))
                        )
                )

                

                .then(Commands.literal("tpai")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> tpAi(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))
                        )
                )
                                // ============================================================
                                // /kingdoms inspect <kingdom name | king name>
                                // - king name = ONLINE player username
                                // - kingdom name = AI or player/world kingdom
                                // ============================================================
                                .then(Commands.literal("inspect")
                                        .then(Commands.argument("target", StringArgumentType.greedyString())
                                                .executes(ctx -> inspect(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "target")))
                                        )
                                )

                                .then(Commands.literal("ai")
                        // /kingdoms ai debug <name>
                        .then(Commands.literal("debug")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> aiDebug(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))
                                )
                        )
                        
                        // /kingdoms ai settrait <name> <trait> <value>
                        .then(Commands.literal("settrait")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("trait", StringArgumentType.word())
                                                .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0, 1.0))
                                                        .executes(ctx -> aiSetTrait(
                                                                ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "name"),
                                                                StringArgumentType.getString(ctx, "trait"),
                                                                DoubleArgumentType.getDouble(ctx, "value")
                                                        ))
                                                )
                                        )
                                )
                        )

                        // /kingdoms ai setres <name> <resource> <amount>
                        .then(Commands.literal("setres")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("resource", StringArgumentType.word())
                                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.0))
                                                        .executes(ctx -> aiSetRes(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        StringArgumentType.getString(ctx, "resource"),
                                                        DoubleArgumentType.getDouble(ctx, "amount")
                                                        ))
                                                )
                                        )
                                )
                        )

                        // /kingdoms ai addres <name> <resource> <delta>
                        .then(Commands.literal("addres")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("resource", StringArgumentType.word())
                                                .then(Commands.argument("delta", DoubleArgumentType.doubleArg(-1_000_000.0, 1_000_000.0))
                                                        .executes(ctx -> aiAddRes(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        StringArgumentType.getString(ctx, "resource"),
                                                        DoubleArgumentType.getDouble(ctx, "delta")
                                                        ))
                                                )
                                        )
                                )
                        )

                        // optional: /kingdoms ai setsol <name> <alive> [max]
                        .then(Commands.literal("setsol")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("alive", IntegerArgumentType.integer(0, 100000))
                                                .executes(ctx -> aiSetSoldiers(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        IntegerArgumentType.getInteger(ctx, "alive"),
                                                        null
                                                ))
                                                .then(Commands.argument("max", IntegerArgumentType.integer(1, 100000))
                                                        .executes(ctx -> aiSetSoldiers(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        IntegerArgumentType.getInteger(ctx, "alive"),
                                                        IntegerArgumentType.getInteger(ctx, "max")
                                                        ))
                                                )
                                        )
                                )
                        )

                        // /kingdoms ai sim <name> <n>
                        .then(Commands.literal("sim")
                                .then(Commands.literal("master")
                                        .then(Commands.argument("steps", IntegerArgumentType.integer(1, 50000))
                                                .then(Commands.argument("ticksPerStep", IntegerArgumentType.integer(1, 72000))
                                                // no list
                                                .executes(ctx -> masterSimExec(ctx, null))

                                                // with list
                                                .then(Commands.argument("aiList", StringArgumentType.greedyString())
                                                        .executes(ctx -> masterSimExec(ctx, StringArgumentType.getString(ctx, "aiList")))
                                                )

                                                // optional flags (chainable)
                                                .then(Commands.literal("file").then(Commands.argument("file", BoolArgumentType.bool())
                                                        .executes(ctx -> masterSimExec(ctx, getStringArgOrNull(ctx, "aiList")))
                                                        .then(Commands.literal("silent").then(Commands.argument("silent", BoolArgumentType.bool())
                                                        .executes(ctx -> masterSimExec(ctx, getStringArgOrNull(ctx, "aiList")))
                                                        .then(Commands.literal("every").then(Commands.argument("every", IntegerArgumentType.integer(1, 10000))
                                                                .executes(ctx -> masterSimExec(ctx, getStringArgOrNull(ctx, "aiList")))
                                                                .then(Commands.literal("label").then(Commands.argument("label", StringArgumentType.greedyString())
                                                                .executes(ctx -> masterSimExec(ctx, getStringArgOrNull(ctx, "aiList")))
                                                                ))
                                                        ))
                                                        .then(Commands.literal("label").then(Commands.argument("label", StringArgumentType.greedyString())
                                                                .executes(ctx -> masterSimExec(ctx, getStringArgOrNull(ctx, "aiList")))
                                                        ))
                                                        ))
                                                        .then(Commands.literal("every").then(Commands.argument("every", IntegerArgumentType.integer(1, 10000))
                                                        .executes(ctx -> masterSimExec(ctx, getStringArgOrNull(ctx, "aiList")))
                                                        ))
                                                        .then(Commands.literal("label").then(Commands.argument("label", StringArgumentType.greedyString())
                                                        .executes(ctx -> masterSimExec(ctx, getStringArgOrNull(ctx, "aiList")))
                                                        ))
                                                ))

                                                // also allow starting at silent/every/label without file keyword if you want:
                                                .then(Commands.literal("silent").then(Commands.argument("silent", BoolArgumentType.bool())
                                                        .executes(ctx -> masterSimExec(ctx, getStringArgOrNull(ctx, "aiList")))
                                                ))
                                                .then(Commands.literal("every").then(Commands.argument("every", IntegerArgumentType.integer(1, 10000))
                                                        .executes(ctx -> masterSimExec(ctx, getStringArgOrNull(ctx, "aiList")))
                                                ))
                                                .then(Commands.literal("label").then(Commands.argument("label", StringArgumentType.greedyString())
                                                        .executes(ctx -> masterSimExec(ctx, getStringArgOrNull(ctx, "aiList")))
                                                ))
                                                )
                                        )
                                )


                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 5000))
                                                .executes(ctx -> aiSim(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        IntegerArgumentType.getInteger(ctx, "n")
                                                ))
                                        )
                                )
                        )
                )

                // -------------------------
                // Diplomacy debug / test harness
                // -------------------------
                .then(Commands.literal("diplomacy")

                        .then(Commands.literal("sim")
                                .then(Commands.argument("from", StringArgumentType.word())
                                        .then(Commands.argument("to", StringArgumentType.word())
                                                .then(Commands.argument("n", IntegerArgumentType.integer(1, 5000))
                                                        .executes(ctx -> diplomacySim(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "from"),
                                                        StringArgumentType.getString(ctx, "to"),
                                                        IntegerArgumentType.getInteger(ctx, "n")
                                                        ))
                                                )
                                        )
                                )
                        )

                        .then(Commands.literal("explain")
                                .then(Commands.argument("from", StringArgumentType.word())
                                        .then(Commands.argument("to", StringArgumentType.word())
                                                .executes(ctx -> explainDiplomacy(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "from"),
                                                        StringArgumentType.getString(ctx, "to")))
                                        )
                                )
                        )
                        .then(Commands.literal("force_generate")
                                .then(Commands.argument("from", StringArgumentType.word())
                                        .then(Commands.argument("to", StringArgumentType.word())
                                                .executes(ctx -> forceGenerateDiplomacy(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "from"),
                                                        StringArgumentType.getString(ctx, "to")))
                                        )
                                )
                        )
                        .then(Commands.literal("force_send")
                                .then(Commands.argument("from", StringArgumentType.word())
                                        .then(Commands.argument("to", StringArgumentType.word())
                                                .then(Commands.argument("kind", StringArgumentType.word())
                                                        // base (no extra args)
                                                        .executes(ctx -> forceSendDiplomacy(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "from"),
                                                                StringArgumentType.getString(ctx, "to"),
                                                                StringArgumentType.getString(ctx, "kind"),
                                                                null, 0.0, null, 0.0, 0.0, null
                                                        ))
                                                        // econ: aType aAmt
                                                        .then(Commands.argument("aType", StringArgumentType.word())
                                                                .then(Commands.argument("aAmt", DoubleArgumentType.doubleArg(0.0))
                                                                        .executes(ctx -> forceSendDiplomacy(ctx.getSource(),
                                                                                StringArgumentType.getString(ctx, "from"),
                                                                                StringArgumentType.getString(ctx, "to"),
                                                                                StringArgumentType.getString(ctx, "kind"),
                                                                                StringArgumentType.getString(ctx, "aType"),
                                                                                DoubleArgumentType.getDouble(ctx, "aAmt"),
                                                                                null, 0.0, 0.0, null
                                                                        ))
                                                                        // contract: bType bAmt maxAmt
                                                                        .then(Commands.argument("bType", StringArgumentType.word())
                                                                                .then(Commands.argument("bAmt", DoubleArgumentType.doubleArg(0.0))
                                                                                        .then(Commands.argument("maxAmt", DoubleArgumentType.doubleArg(0.0))
                                                                                                .executes(ctx -> forceSendDiplomacy(ctx.getSource(),
                                                                                                        StringArgumentType.getString(ctx, "from"),
                                                                                                        StringArgumentType.getString(ctx, "to"),
                                                                                                        StringArgumentType.getString(ctx, "kind"),
                                                                                                        StringArgumentType.getString(ctx, "aType"),
                                                                                                        DoubleArgumentType.getDouble(ctx, "aAmt"),
                                                                                                        StringArgumentType.getString(ctx, "bType"),
                                                                                                        DoubleArgumentType.getDouble(ctx, "bAmt"),
                                                                                                        DoubleArgumentType.getDouble(ctx, "maxAmt"),
                                                                                                        null
                                                                                                ))
                                                                                        )
                                                                                )
                                                                        )
                                                                )
                                                        )
                                                        // war decl: casus belli
                                                        .then(Commands.argument("cb", StringArgumentType.word())
                                                                .executes(ctx -> forceSendDiplomacy(ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "from"),
                                                                        StringArgumentType.getString(ctx, "to"),
                                                                        StringArgumentType.getString(ctx, "kind"),
                                                                        null, 0.0, null, 0.0, 0.0,
                                                                        StringArgumentType.getString(ctx, "cb")
                                                                ))
                                                        )
                                                )
                                        )
                                )
                        )
                )
                        // AI↔AI debugging / forcing (writes AI events + news; optional AI relations if present)
                        .then(Commands.literal("explain_ai")
                                .then(Commands.argument("from", StringArgumentType.word())
                                        .then(Commands.argument("to", StringArgumentType.word())
                                                .executes(ctx -> explainDiplomacyAi(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "from"),
                                                        StringArgumentType.getString(ctx, "to")))
                                        )
                                )
                        )
                        .then(Commands.literal("force_send_ai")
                                .then(Commands.argument("from", StringArgumentType.word())
                                        .then(Commands.argument("to", StringArgumentType.word())
                                                .then(Commands.argument("kind", StringArgumentType.word())
                                                        // base (no args)
                                                        .executes(ctx -> forceSendAiAction(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "from"),
                                                                StringArgumentType.getString(ctx, "to"),
                                                                StringArgumentType.getString(ctx, "kind"),
                                                                null, 0.0,
                                                                null, 0.0,
                                                                0.0,
                                                                null))
                                                        // economy: aType aAmt
                                                        .then(Commands.argument("aType", StringArgumentType.word())
                                                                .then(Commands.argument("aAmt", DoubleArgumentType.doubleArg(0.0))
                                                                        .executes(ctx -> forceSendAiAction(ctx.getSource(),
                                                                                StringArgumentType.getString(ctx, "from"),
                                                                                StringArgumentType.getString(ctx, "to"),
                                                                                StringArgumentType.getString(ctx, "kind"),
                                                                                StringArgumentType.getString(ctx, "aType"),
                                                                                DoubleArgumentType.getDouble(ctx, "aAmt"),
                                                                                null, 0.0,
                                                                                0.0,
                                                                                null))
                                                                        // contract: + bType bAmt maxAmt
                                                                        .then(Commands.argument("bType", StringArgumentType.word())
                                                                                .then(Commands.argument("bAmt", DoubleArgumentType.doubleArg(0.0))
                                                                                        .then(Commands.argument("maxAmt", DoubleArgumentType.doubleArg(0.0))
                                                                                                .executes(ctx -> forceSendAiAction(ctx.getSource(),
                                                                                                        StringArgumentType.getString(ctx, "from"),
                                                                                                        StringArgumentType.getString(ctx, "to"),
                                                                                                        StringArgumentType.getString(ctx, "kind"),
                                                                                                        StringArgumentType.getString(ctx, "aType"),
                                                                                                        DoubleArgumentType.getDouble(ctx, "aAmt"),
                                                                                                        StringArgumentType.getString(ctx, "bType"),
                                                                                                        DoubleArgumentType.getDouble(ctx, "bAmt"),
                                                                                                        DoubleArgumentType.getDouble(ctx, "maxAmt"),
                                                                                                        null))
                                                                                        )
                                                                                )
                                                                        )
                                                                )
                                                        )
                                                        // war decl: optional casus belli
                                                        .then(Commands.argument("cb", StringArgumentType.word())
                                                                .executes(ctx -> forceSendAiAction(ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "from"),
                                                                        StringArgumentType.getString(ctx, "to"),
                                                                        StringArgumentType.getString(ctx, "kind"),
                                                                        null, 0.0,
                                                                        null, 0.0,
                                                                        0.0,
                                                                        StringArgumentType.getString(ctx, "cb")))
                                                        )
                                                )
                                        )
                                )
                        )
                        .then(Commands.literal("force_event_ai")
                                .then(Commands.argument("from", StringArgumentType.word())
                                        .then(Commands.argument("to", StringArgumentType.word())
                                                .then(Commands.argument("type", StringArgumentType.word())
                                                        .executes(ctx -> forceAiEvent(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "from"),
                                                                StringArgumentType.getString(ctx, "to"),
                                                                StringArgumentType.getString(ctx, "type")))
                                                )
                                        )
                                )
                        )
                        .then(Commands.literal("events_recent")
                                .then(Commands.argument("max", IntegerArgumentType.integer(1, 200))
                                        .executes(ctx -> listAiEvents(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "max")))
                                )
                        )
                        .then(Commands.literal("force_tick_ai")
                                .then(Commands.argument("from", StringArgumentType.word())
                                        .executes(ctx -> forceTickAiOnce(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "from"),
                                                6))
                                        .then(Commands.argument("maxTargets", IntegerArgumentType.integer(1, 32))
                                                .executes(ctx -> forceTickAiOnce(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "from"),
                                                        IntegerArgumentType.getInteger(ctx, "maxTargets")))
                                        )
                                )
                        )


                // -------------------------
                // War debug / controls
                // -------------------------
                .then(Commands.literal("war")
                        .then(Commands.literal("list")
                                .executes(ctx -> warList(ctx.getSource()))
                        )
                        .then(Commands.literal("status")
                                .then(Commands.argument("a", StringArgumentType.word())
                                        .then(Commands.argument("b", StringArgumentType.word())
                                                .executes(ctx -> warStatus(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "a"),
                                                        StringArgumentType.getString(ctx, "b")))
                                        )
                                )
                        )
                        .then(Commands.literal("declare")
                                .then(Commands.argument("a", StringArgumentType.word())
                                        .then(Commands.argument("b", StringArgumentType.word())
                                                .executes(ctx -> warDeclare(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "a"),
                                                        StringArgumentType.getString(ctx, "b")))
                                        )
                                )
                        )
                        .then(Commands.literal("peace")
                                .then(Commands.argument("a", StringArgumentType.word())
                                        .then(Commands.argument("b", StringArgumentType.word())
                                                .executes(ctx -> warPeace(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "a"),
                                                        StringArgumentType.getString(ctx, "b")))
                                        )
                                )
                        )
                        .then(Commands.literal("zone_get")
                                .then(Commands.argument("a", StringArgumentType.word())
                                        .then(Commands.argument("b", StringArgumentType.word())
                                                .executes(ctx -> warZoneGet(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "a"),
                                                        StringArgumentType.getString(ctx, "b")))
                                        )
                                )
                        )
                        .then(Commands.literal("zone_set")
                                .then(Commands.argument("a", StringArgumentType.word())
                                        .then(Commands.argument("b", StringArgumentType.word())
                                                .then(Commands.argument("minX", IntegerArgumentType.integer())
                                                        .then(Commands.argument("minZ", IntegerArgumentType.integer())
                                                                .then(Commands.argument("maxX", IntegerArgumentType.integer())
                                                                        .then(Commands.argument("maxZ", IntegerArgumentType.integer())
                                                                                .executes(ctx -> warZoneSet(ctx.getSource(),
                                                                                        StringArgumentType.getString(ctx, "a"),
                                                                                        StringArgumentType.getString(ctx, "b"),
                                                                                        IntegerArgumentType.getInteger(ctx, "minX"),
                                                                                        IntegerArgumentType.getInteger(ctx, "minZ"),
                                                                                        IntegerArgumentType.getInteger(ctx, "maxX"),
                                                                                        IntegerArgumentType.getInteger(ctx, "maxZ")))
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                        .then(Commands.literal("zone_clear")
                                .then(Commands.argument("a", StringArgumentType.word())
                                        .then(Commands.argument("b", StringArgumentType.word())
                                                .executes(ctx -> warZoneClear(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "a"),
                                                        StringArgumentType.getString(ctx, "b")))
                                        )
                                )
                        )
                        .then(Commands.literal("tick_ai")
                                .executes(ctx -> warTickAi(ctx.getSource()))
                        )
                )

                .then(Commands.literal("ambient")
                        .then(Commands.literal("list")
                                .executes(ctx -> ambientList(ctx.getSource()))
                        )
                        .then(Commands.literal("roll")
                                .executes(ctx -> ambientRoll(ctx.getSource()))
                        )
                        .then(Commands.literal("spawn")
                                .then(Commands.argument("eventId", StringArgumentType.word())
                                // /kingdoms ambient spawn <eventId>
                                .executes(ctx -> ambientSpawn(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "eventId"),
                                        null,
                                        false
                                ))
                                // /kingdoms ambient spawn <eventId> <variantId>
                                .then(Commands.argument("variantId", StringArgumentType.word())
                                        .executes(ctx -> ambientSpawn(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "eventId"),
                                                StringArgumentType.getString(ctx, "variantId"),
                                                false
                                        ))
                                        // /kingdoms ambient spawn <eventId> <variantId> <ignoreGate>
                                        .then(Commands.argument("ignoreGate", BoolArgumentType.bool())
                                        .executes(ctx -> ambientSpawn(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "eventId"),
                                                StringArgumentType.getString(ctx, "variantId"),
                                                BoolArgumentType.getBool(ctx, "ignoreGate")
                                        ))
                                        )
                                )
                                )
                        )
                        )


        );
    }

    // -------------------------
    // /kingdoms listai
    // -------------------------
    private static int listAi(CommandSourceStack src) {
        var server = src.getServer();
        var ai = aiKingdomState.get(server);

        if (ai.kingdoms.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No AI kingdoms."), false);
            return 1;
        }

        src.sendSuccess(() -> Component.literal("AI Kingdoms:"), false);

       ai.kingdoms.forEach((key, k) -> {
        src.sendSuccess(() -> Component.literal(
                "- " + k.name
                + " key=" + key
                + " id=" + k.id
                + " kingUuid=" + k.kingUuid
                + " gold=" + String.format(Locale.US, "%.1f", k.gold)
        ), false);
        });

        return ai.kingdoms.size();
    }

    // -------------------------
    // /kingdoms tpai <name>
    // -------------------------
    private static int tpAi(CommandSourceStack src, String name) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("Player only."));
            return 0;
        }

        var ai = aiKingdomState.get(src.getServer());

        var match = ai.kingdoms.values().stream()
                .filter(k -> k.name.equalsIgnoreCase(name))
                .findFirst();

        if (match.isEmpty()) {
            src.sendFailure(Component.literal(
                    "No AI kingdom named '" + name + "'"
            ));
            return 0;
        }

        var k = match.get();
        var pos = k.origin;

        player.teleportTo(
                (ServerLevel) player.level(),
                pos.getX() + 0.5,
                pos.getY() + 1,
                pos.getZ() + 0.5,
                null, player.getYRot(),
                player.getXRot(), false
        );

        src.sendSuccess(() -> Component.literal(
                "Teleported to " + k.name + " (" +
                        pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")"
        ), false);

        return 1;
    }

       // -------------------------
        // /kingdoms inspect <target>
        // target can be:
        // - ONLINE player username (king)
        // - kingdom name (player/world kingdom)
        // - AI kingdom name
        // -------------------------
        private static int inspect(CommandSourceStack src, String targetRaw) {
        var server = src.getServer();
        var ks = kingdomState.get(server);
        var ai = aiKingdomState.get(server);

        String target = (targetRaw == null) ? "" : targetRaw.trim();
        if (target.isBlank()) {
                src.sendFailure(Component.literal("Usage: /kingdoms inspect <kingdom name | ONLINE king name>"));
                return 0;
        }

        // 1) ONLINE player username -> inspect their kingdom
        var onlineMatch = server.getPlayerList().getPlayers().stream()
                .filter(p -> p.getGameProfile().name().equalsIgnoreCase(target))
                .findFirst();

        if (onlineMatch.isPresent()) {
                var p = onlineMatch.get();
                var k = ks.getPlayerKingdom(p.getUUID());
                if (k == null) {
                src.sendFailure(Component.literal("Player '" + p.getGameProfile().name() + "' has no kingdom."));
                return 0;
                }
                return inspectPlayerKingdom(src, k, p.getGameProfile().name());
        }

        // 2) Kingdom name (player/world kingdom)
        var kMatch = ks.getAllKingdoms().stream()
                .filter(k -> k != null && k.name != null && k.name.equalsIgnoreCase(target))
                .findFirst();

                if (kMatch.isPresent()) {
                        var k = kMatch.get();

                        // ✅ If this "kingdomState" kingdom is actually an AI kingdom, read aiKingdomState economy instead
                        if (ai.isAiKingdom(k.id)) {
                                var aiK = ai.getById(k.id);
                                if (aiK != null) {
                                return inspectAiKingdom(src, aiK);
                                }
                                // If AI state missing for some reason, fall back to kingdomState print
                        }

                        return inspectPlayerKingdom(src, k, null);
                }


        // 3) AI kingdom name
        var aiMatch = ai.kingdoms.values().stream()
                .filter(k -> k != null && k.name != null && k.name.equalsIgnoreCase(target))
                .findFirst();

        if (aiMatch.isPresent()) {
                return inspectAiKingdom(src, aiMatch.get());
        }

        src.sendFailure(Component.literal(
                "No kingdom/king found for '" + target + "'. Try a kingdom name, or an ONLINE player's username."
        ));
        return 0;
        }

        // -------------------------
        // Inspect: Player/World Kingdom
        // -------------------------
        private static int inspectPlayerKingdom(CommandSourceStack src, kingdomState.Kingdom k, String kingNameOrNull) {
        final String name = (k.name == null || k.name.isBlank()) ? "Unknown" : k.name;

        final String ownerLine =
        "Owner: " + (k.owner == null ? "None" : k.owner.toString())
        + (kingNameOrNull != null ? (" (" + kingNameOrNull + ")") : "");

        src.sendSuccess(() -> Component.literal("=== Kingdom Inspect (Player/World) ==="), false);
        src.sendSuccess(() -> Component.literal("Name: " + name), false);
        src.sendSuccess(() -> Component.literal("Id: " + k.id), false);
        src.sendSuccess(() -> Component.literal(ownerLine), false);
        src.sendSuccess(() -> Component.literal("Origin: " +
                k.origin.getX() + ", " + k.origin.getY() + ", " + k.origin.getZ()), false);

        int garrisons = k.active.getOrDefault("garrison", 0);
        int max = Math.max(0, garrisons * 50);
        int alive = max; // until you track real losses
        src.sendSuccess(() -> Component.literal("Soldiers (est): " + alive + " / " + max + " (garrisons=" + garrisons + ")"), false);

        src.sendSuccess(() -> Component.literal("Resources:"), false);
        sendRes(src, "Gold", k.gold);
        sendRes(src, "Meat", k.meat);
        sendRes(src, "Grain", k.grain);
        sendRes(src, "Fish", k.fish);
        sendRes(src, "Wood", k.wood);
        sendRes(src, "Metal", k.metal);
        sendRes(src, "Armor", k.armor);
        sendRes(src, "Weapons", k.weapons);
        sendRes(src, "Gems", k.gems);
        sendRes(src, "Horses", k.horses);
        sendRes(src, "Potions", k.potions);

        return 1;
        }

        // -------------------------
        // Inspect: AI Kingdom
        // -------------------------
        private static int inspectAiKingdom(CommandSourceStack src, aiKingdomState.AiKingdom aiK) {
        String name = (aiK.name == null || aiK.name.isBlank()) ? "Unknown" : aiK.name;

        src.sendSuccess(() -> Component.literal("=== Kingdom Inspect (AI) ==="), false);
        src.sendSuccess(() -> Component.literal("Name: " + name), false);
        src.sendSuccess(() -> Component.literal("Id: " + aiK.id), false);
        src.sendSuccess(() -> Component.literal("Origin: " +
                aiK.origin.getX() + ", " + aiK.origin.getY() + ", " + aiK.origin.getZ()), false);

        int alive = Math.max(0, aiK.aliveSoldiers);
        int max = Math.max(1, aiK.maxSoldiers);
        src.sendSuccess(() -> Component.literal("Soldiers: " + alive + " / " + max), false);

        src.sendSuccess(() -> Component.literal("Resources:"), false);
        sendRes(src, "Gold", aiK.gold);
        sendRes(src, "Meat", aiK.meat);
        sendRes(src, "Grain", aiK.grain);
        sendRes(src, "Fish", aiK.fish);
        sendRes(src, "Wood", aiK.wood);
        sendRes(src, "Metal", aiK.metal);
        sendRes(src, "Armor", aiK.armor);
        sendRes(src, "Weapons", aiK.weapons);
        sendRes(src, "Gems", aiK.gems);
        sendRes(src, "Horses", aiK.horses);
        sendRes(src, "Potions", aiK.potions);

        return 1;
        }

        

        private static int aiDebug(CommandSourceStack src, String nameRaw) {
        var server = src.getServer();
        var ai = aiKingdomState.get(server);

        UUID id = findAiByName(ai, nameRaw);
        if (id == null) {
                src.sendFailure(Component.literal("No AI kingdom match for: " + nameRaw));
                return 0;
        }

        var k = ai.getById(id);
        if (k == null || k.personality == null) {
                src.sendFailure(Component.literal("AI kingdom/personality missing for: " + nameRaw));
                return 0;
        }

        var p = k.personality;

        src.sendSuccess(() -> Component.literal("=== AI Personality Debug ==="), false);
        src.sendSuccess(() -> Component.literal("Name: " + (k.name == null ? "Unknown" : k.name)), false);
        src.sendSuccess(() -> Component.literal("Id: " + k.id), false);
        src.sendSuccess(() -> Component.literal("generosity=" + fmt2(p.generosity())), false);
        src.sendSuccess(() -> Component.literal("greed=" + fmt2(p.greed())), false);
        src.sendSuccess(() -> Component.literal("trustBias=" + fmt2(p.trustBias())), false);
        src.sendSuccess(() -> Component.literal("honor=" + fmt2(p.honor())), false);
        src.sendSuccess(() -> Component.literal("aggression=" + fmt2(p.aggression())), false);
        src.sendSuccess(() -> Component.literal("pragmatism=" + fmt2(p.pragmatism())), false);
        src.sendSuccess(() -> Component.literal("soldiers=" + Math.max(0, k.aliveSoldiers) + " / " + Math.max(1, k.maxSoldiers)), false);

        return 1;
        }

        private static int aiSetTrait(CommandSourceStack src, String nameRaw, String traitRaw, double valueRaw) {
        var server = src.getServer();
        var ai = aiKingdomState.get(server);

        UUID id = findAiByName(ai, nameRaw);
        if (id == null) {
                src.sendFailure(Component.literal("No AI kingdom match for: " + nameRaw));
                return 0;
        }

        var k = ai.getById(id);
        if (k == null || k.personality == null) {
                src.sendFailure(Component.literal("AI kingdom/personality missing for: " + nameRaw));
                return 0;
        }

        double value = clamp01(valueRaw);
        String trait = traitRaw == null ? "" : traitRaw.toLowerCase(Locale.ROOT).trim();

        var p = k.personality;

        aiKingdomState.KingdomPersonality np = switch (trait) {
                case "generosity" -> new aiKingdomState.KingdomPersonality(value, p.greed(), p.trustBias(), p.honor(), p.aggression(), p.pragmatism());
                case "greed" -> new aiKingdomState.KingdomPersonality(p.generosity(), value, p.trustBias(), p.honor(), p.aggression(), p.pragmatism());
                case "trust", "trustbias" -> new aiKingdomState.KingdomPersonality(p.generosity(), p.greed(), value, p.honor(), p.aggression(), p.pragmatism());
                case "honor" -> new aiKingdomState.KingdomPersonality(p.generosity(), p.greed(), p.trustBias(), value, p.aggression(), p.pragmatism());
                case "aggression" -> new aiKingdomState.KingdomPersonality(p.generosity(), p.greed(), p.trustBias(), p.honor(), value, p.pragmatism());
                case "pragmatism" -> new aiKingdomState.KingdomPersonality(p.generosity(), p.greed(), p.trustBias(), p.honor(), p.aggression(), value);
                default -> null;
        };

        if (np == null) {
                src.sendFailure(Component.literal("Unknown trait: " + traitRaw + " (use generosity/greed/trustBias/honor/aggression/pragmatism)"));
                return 0;
        }

        k.personality = np;
        ai.markDirty();

        src.sendSuccess(() -> Component.literal("Set " + trait + " to " + fmt2(value) + " for " + (k.name == null ? id.toString() : k.name)), false);
        return 1;
        }

        private static int aiSim(CommandSourceStack src, String nameRaw, int n) {
        var server = src.getServer();
        var ai = aiKingdomState.get(server);

        UUID id = findAiByName(ai, nameRaw);
        if (id == null) {
                src.sendFailure(Component.literal("No AI kingdom match for: " + nameRaw));
                return 0;
        }

        var k = ai.getById(id);
        if (k == null || k.personality == null) {
                src.sendFailure(Component.literal("AI kingdom/personality missing for: " + nameRaw));
                return 0;
        }

        // neutral-ish scenario for sampling
        int rel = 0;
        boolean allied = false;
        boolean atWar = false;
        int senderSoldiers = Math.max(0, k.aliveSoldiers);
        int recipientSoldiers = 80;

        EnumMap<Letter.Kind, Integer> counts = new EnumMap<>(Letter.Kind.class);
        var rng = server.overworld().getRandom();

        for (int i = 0; i < n; i++) {
                Letter.Kind kind = AiDiplomacyTicker.pickKind(
                        rng,
                        k.personality,
                        rel,
                        allied,
                        atWar,
                        senderSoldiers,
                        recipientSoldiers
                );
                counts.put(kind, counts.getOrDefault(kind, 0) + 1);
        }

        src.sendSuccess(() -> Component.literal("=== AI Kind Sim: " + (k.name == null ? id.toString() : k.name) + " n=" + n + " ==="), false);
        for (Map.Entry<Letter.Kind, Integer> e : counts.entrySet()) {
                src.sendSuccess(() -> Component.literal(e.getKey().name() + ": " + e.getValue()), false);
        }

        return 1;
        }

        private static UUID findAiByName(aiKingdomState ai, String needleRaw) {
        if (ai == null) return null;
        String needle = (needleRaw == null ? "" : needleRaw.trim()).toLowerCase(Locale.ROOT);
        if (needle.isBlank()) return null;
        
        String needle2 = needle.replace('_', ' ');

        

        UUID best = null;
        int bestScore = -1;

        for (var entry : ai.kingdoms.entrySet()) {
                UUID id = entry.getKey();
                var k = entry.getValue();
                if (k == null || k.name == null) continue;

                String name = k.name.toLowerCase(Locale.ROOT);

                int score = -1;
                if (name.equals(needle) || name.equals(needle2)) {
                score = 1000;
                } else if (name.contains(needle) || name.contains(needle2)) {
                score = 500 + Math.max(needle.length(), needle2.length());
                }


                if (score > bestScore) {
                bestScore = score;
                best = id;
                }
        }

        return best;
        }

        private static String fmt2(double v) {
        return String.format(Locale.US, "%.2f", v);
        }

        private static int worldgenStatus(CommandSourceStack src) {
                ServerLevel overworld = src.getServer().overworld();
                if (overworld == null) {
                src.sendFailure(Component.literal("No overworld loaded."));
                return 0;
                }
                boolean en = WorldgenToggleState.get(overworld).isEnabled();
                src.sendSuccess(() -> Component.literal("Kingdoms worldgen is " + (en ? "ON" : "OFF")), false);
                return 1;
        }

    private static int worldgenSet(CommandSourceStack src, boolean enabled) {
        ServerLevel overworld = src.getServer().overworld();
        if (overworld == null) {
            src.sendFailure(Component.literal("No overworld loaded."));
            return 0;
        }
        WorldgenToggleState.get(overworld).setEnabled(enabled);
        src.sendSuccess(() -> Component.literal("Kingdoms worldgen set to " + (enabled ? "ON" : "OFF")), true);
        return 1;
    }

    private static int worldgenToggle(CommandSourceStack src) {
        ServerLevel overworld = src.getServer().overworld();
        if (overworld == null) {
            src.sendFailure(Component.literal("No overworld loaded."));
            return 0;
        }
        WorldgenToggleState st = WorldgenToggleState.get(overworld);
        boolean now = !st.isEnabled();
        st.setEnabled(now);
        src.sendSuccess(() -> Component.literal("Kingdoms worldgen toggled to " + (now ? "ON" : "OFF")), true);
        return 1;
    }


        

    // ============================================================
    // Diplomacy commands helpers
    // ============================================================

        private static int masterSim(
                CommandSourceStack src,
                int steps,
                int ticksPerStep,
                String aiListRaw,
                boolean fileEnabled,
                boolean silent,
                int every,
                String label
        ) {
        var server = src.getServer();
        var level = server.overworld();

        var ks = kingdomState.get(server);
        var aiState = aiKingdomState.get(server);
        var war = WarState.get(server);
        var alliance = AllianceState.get(server);
        var relState = name.kingdoms.diplomacy.AiRelationsState.get(server);

        // --- SNAPSHOT (for non-destructive sims / A-B tests) ---
        var snapAi = aiState.exportSnapshot();                 // Map<UUID, AiKingdomSnap>
        var snapRel = relState.exportRel();                    // Map<String, Integer>
        var snapAlly = alliance.exportAllies();                // Map<UUID, List<UUID>>
        var snapWar = war.exportState();                       // WarState.WarSnapshot
        var snapSchedule = AiDiplomacyTicker.exportSchedule(); // Map<UUID, Long>



        // Resolve subset (optional)
        java.util.Set<UUID> only = null;
        if (aiListRaw != null && !aiListRaw.isBlank()) {
                only = new java.util.HashSet<>();
                String[] parts = aiListRaw.split(",");
                for (String p : parts) {
                String tok = p.trim();
                if (tok.isEmpty()) continue;

                // allow underscores for spaces
                tok = tok.replace('_', ' ');

                var tr = resolveTarget(src, tok);
                if (tr.kingdom == null) {
                        src.sendFailure(Component.literal("Unknown AI token in list: " + p.trim()));
                        return 0;
                }
                if (aiState.getById(tr.kingdom.id) == null) {
                        src.sendFailure(Component.literal("Not an AI kingdom: " + tok));
                        return 0;
                }
                only.add(tr.kingdom.id);
                }

                if (only.size() < 2) {
                src.sendFailure(Component.literal("Need at least 2 AI kingdoms in aiList."));
                return 0;
                }
        }
        
        long simTick = server.getTickCount();
        final java.util.Set<UUID> onlyFinal = only;

                if (!silent) {
                        src.sendSuccess(() -> Component.literal("=== MasterSim AI Personalities ==="), false);

                                for (var e : aiState.kingdoms.entrySet()) {
                                        UUID id = e.getKey();
                                        if (onlyFinal != null && !onlyFinal.contains(id)) continue;

                                        var k = e.getValue();
                                        if (k == null || k.personality == null) continue;

                                        var p = k.personality;
                                        String line = String.format(Locale.US,
                                        "- %s (%s) gen=%.2f greed=%.2f trust=%.2f honor=%.2f aggr=%.2f prag=%.2f",
                                        (k.name == null ? "Unknown" : k.name),
                                        k.id,
                                        p.generosity(), p.greed(), p.trustBias(), p.honor(), p.aggression(), p.pragmatism()
                                        );
                                        src.sendSuccess(() -> Component.literal(line), false);
                                }
                }


                // Writer
                SimRunWriter writer = null;
                try {
                if (fileEnabled) {
                        writer = SimRunWriter.create(server, label);

                        final String outPath = writer.runDir.toString(); // capture for lambda
                        if (!silent) {
                        src.sendSuccess(() -> Component.literal("MasterSim logging to: " + outPath), false);
                        }

                          // --- run meta (once) ---
                        JsonObject meta = new JsonObject();
                        meta.addProperty("label", label == null ? "" : label);
                        meta.addProperty("steps", steps);
                        meta.addProperty("ticksPerStep", ticksPerStep);
                        meta.addProperty("subsetMode", (onlyFinal == null ? "ALL" : "LIST"));
                        meta.addProperty("subsetCount", (onlyFinal == null ? aiState.kingdoms.size() : onlyFinal.size()));
                        writer.writeRunMeta(meta);

                        // --- START snapshots (all kingdoms in scope) ---
                        long now = simTick;
                        for (var e : aiState.kingdoms.entrySet()) {
                                UUID id = e.getKey();
                                if (onlyFinal != null && !onlyFinal.contains(id)) continue;

                                var k = e.getValue();
                                if (k == null || k.personality == null) continue;

                                var p = k.personality;

                                writer.logKingdomSnapshot(
                                        "START", 0, now,
                                        k.id.toString(), (k.name == null ? "" : k.name),
                                        p.generosity(), p.greed(), p.trustBias(), p.honor(), p.aggression(), p.pragmatism(),
                                        Math.max(0, k.aliveSoldiers), Math.max(0, k.maxSoldiers),
                                        k.gold, k.meat, k.grain, k.fish,
                                        k.wood, k.metal, k.armor, k.weapons,
                                        k.gems, k.horses, k.potions
                                );
                        }

                        writer.flush(); // optional but nice

                }


                // Header (throttled)
                if (!silent) {
                src.sendSuccess(() -> Component.literal(
                        "=== MasterSim start steps=" + steps + " ticksPerStep=" + ticksPerStep +
                                (onlyFinal == null ? " (ALL AI)" : " (subset=" + onlyFinal.size() + ")") +
                                " file=" + fileEnabled +
                                " silent=" + silent +
                                " every=" + Math.max(1, every) +
                                (label == null ? "" : (" label=" + label)) +
                                " ==="
                ), false);
                }

                AiDiplomacyTicker.clearSchedule();

                int printEvery = Math.max(1, every);

                for (int s = 1; s <= steps; s++) {
                long stepStart = simTick;
                int stepNum = s;
                int actionsThisStep = 0;


                // --- snapshots BEFORE ---
                int warsBefore = war.wars().size();
                int alliancesBefore = countAlliancesSafe(server);

                java.util.Map<UUID, aiKingdomState.EconomyData> ecoBefore = new java.util.HashMap<>();
                java.util.Map<UUID, Integer> soldiersBefore = new java.util.HashMap<>();

                for (var e : aiState.kingdoms.entrySet()) {
                        UUID id = e.getKey();
                        if (onlyFinal != null && !onlyFinal.contains(id)) continue;
                        var k = e.getValue();
                        if (k == null) continue;
                        ecoBefore.put(id, aiKingdomState.EconomyData.from(k));
                        soldiersBefore.put(id, Math.max(0, k.aliveSoldiers));
                }

                // --- simulate ticks for this step ---
                java.util.List<String> stepLogs = new java.util.ArrayList<>();
                java.util.List<Object> stepEvents = new java.util.ArrayList<>();


                for (int i = 0; i < ticksPerStep; i++) {
                simTick++;

                
                AiRelationNormalizer.onSimTick(server, simTick);


                // economy
                if (simTick % 400L == 0) {
                aiState.tickEconomies(level);
                }


                // diplomacy (real scheduling)
                var dipTick = AiDiplomacyTicker.debugStep(server, simTick, false, onlyFinal);
                
                if (dipTick.events() != null && !dipTick.events().isEmpty()) {
                        stepEvents.addAll(dipTick.events());
                }

                if (writer != null && dipTick.events() != null) {
                        for (var ev : dipTick.events()) {
                                String out = ev.outcome();
                                if (out == null || !out.startsWith("DECISION_KIND_PICK")) continue;

                                try {
                                com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
                                obj.addProperty("step", stepNum);
                                obj.addProperty("tick", ev.tick());
                                obj.addProperty("type", "KIND_PICK");

                                obj.addProperty("fromId", ev.fromId().toString());
                                obj.addProperty("toId", ev.toId().toString());

                                // Parse fields from the string
                                obj.addProperty("rel", parseIntField(out, "rel"));
                                obj.addProperty("allied", parseBoolField(out, "allied"));
                                obj.addProperty("atWarWithOther", parseBoolField(out, "atWar"));
                                obj.addProperty("fromSoldiers", parseIntField(out, "sA"));
                                obj.addProperty("toSoldiers", parseIntField(out, "sB"));

                                obj.addProperty("pWar", parseDoubleField(out, "pWar"));
                                obj.addProperty("pUlt", parseDoubleField(out, "pUlt"));

                                // Parse weights: KIND=0.123 KIND2=0.456 ...
                                com.google.gson.JsonObject ww = new com.google.gson.JsonObject();
                                java.util.regex.Matcher m = java.util.regex.Pattern
                                        .compile("([A-Z_]+)=([0-9]+\\.[0-9]+)")
                                        .matcher(out);
                                while (m.find()) {
                                        String k = m.group(1);
                                        double v = Double.parseDouble(m.group(2));
                                        // skip the pWar/pUlt keys if they match the pattern
                                        if (k.equals("pWar") || k.equals("pUlt")) continue;
                                        ww.addProperty(k, v);
                                }
                                obj.add("weights", ww);

                                writer.logDecision(obj);
                                } catch (Exception e) {
                                Kingdoms.LOGGER.error("Failed to write decision snapshot", e);
                                }
                        }
                        }


            

                if (writer != null && dipTick.events() != null) {
                for (var ev : dipTick.events()) {
                        try {
                        writer.logEvent(
                                s,
                                ev.tick(),
                                ev.kind().name(),
                                ev.fromId().toString(),
                                ev.toId().toString(),
                                ev.outcome(),
                                ev.relBefore(),
                                ev.relAfter(),
                                ev.relDelta()
                        );
                        } catch (Exception ioe) {
                        // don't crash the sim for logging
                        }
                }
}



                actionsThisStep += dipTick.actions();

                // keep the detailed per-action lines for file logging / occasional chat
                if (dipTick.log() != null && !dipTick.log().isEmpty()) {
                        stepLogs.addAll(dipTick.log());
                }

                // war (clocked)
                war.tickAiWars(server, simTick);
                }

                long stepEnd = simTick;


                // --- snapshots AFTER ---
                int warsAfter = war.wars().size();
                int alliancesAfter = countAlliancesSafe(server);

                int warsDelta = warsAfter - warsBefore;
                int allyDelta = alliancesAfter - alliancesBefore;

                // Economy deltas sample (optional)
                StringBuilder topDelta = new StringBuilder();
                int shown = 0;

                for (var e : aiState.kingdoms.entrySet()) {
                        UUID id = e.getKey();
                        if (onlyFinal != null && !onlyFinal.contains(id)) continue;
                        var k = e.getValue();
                        if (k == null) continue;

                        var pre = ecoBefore.get(id);
                        if (pre == null) continue;

                        double dGold = k.gold - pre.gold();
                        double dGrain = k.grain - pre.grain();
                        double dMeat  = k.meat  - pre.meat();
                        double dFish  = k.fish  - pre.fish();
                        int dSold = Math.max(0, k.aliveSoldiers) - soldiersBefore.getOrDefault(id, 0);

                        if (shown < 6) {
                        topDelta.append("  ")
                                .append(aiState.getNameById(id)).append(": ")
                                .append("Δgold=").append(String.format(Locale.US, "%.0f", dGold)).append(" ")
                                .append("Δfood=").append(String.format(Locale.US, "%.0f", (dGrain + dMeat + dFish))).append(" ")
                                .append("Δsold=").append(dSold)
                                .append("\n");
                        shown++;
                        }
                }

                
                int actions = actionsThisStep;
                var dipLog = stepLogs;

                // -------------------------
                // FILE LOGGING
                // -------------------------
                if (writer != null) {
                int offers = 0, requests = 0, contracts = 0, warsCount = 0, alliancesCount = 0, other = 0;

                for (Object ev : stepEvents) {
                String out = reflectString(ev, "outcome");
                if (out != null && out.startsWith("TRY_")) continue;

                String kindName = reflectEnumName(ev, "kind");
                if (kindName == null) {
                        other++;
                        continue;
                }

                switch (kindName) {
                        case "OFFER" -> offers++;
                        case "REQUEST" -> requests++;
                        case "CONTRACT" -> contracts++;
                        case "WAR_DECLARATION" -> warsCount++;
                        case "ALLIANCE_PROPOSAL" -> alliancesCount++;
                        default -> other++;
                }
                }


                writer.logStep(
                        stepNum,
                        stepStart,
                        stepEnd,
                        actionsThisStep,
                        warsDelta,
                        allyDelta,
                        offers,
                        requests,
                        contracts,
                        warsCount,
                        alliancesCount,
                        other
                );

                // -------------------------
                // MID economy snapshots
                // -------------------------
                int snapEvery = 50; // tune this (every 50 steps)

                if (writer != null && (stepNum % snapEvery == 0)) {
                long now = simTick;

                for (var e : aiState.kingdoms.entrySet()) {
                        UUID id = e.getKey();
                        if (onlyFinal != null && !onlyFinal.contains(id)) continue;

                        var k = e.getValue();
                        if (k == null || k.personality == null) continue;
                        var p = k.personality;

                        writer.logKingdomSnapshot(
                                "MID",
                                stepNum,
                                now,
                                k.id.toString(),
                                (k.name == null ? "" : k.name),

                                // personality
                                p.generosity(),
                                p.greed(),
                                p.trustBias(),
                                p.honor(),
                                p.aggression(),
                                p.pragmatism(),

                                // soldiers
                                Math.max(0, k.aliveSoldiers),
                                Math.max(0, k.maxSoldiers),

                                // economy
                                k.gold,
                                k.meat,
                                k.grain,
                                k.fish,
                                k.wood,
                                k.metal,
                                k.armor,
                                k.weapons,
                                k.gems,
                                k.horses,
                                k.potions
                        );
                }
                }


                // -------------------------
                // WAR SNAPSHOTS (per step)
                // -------------------------
                if (writer != null && !war.wars().isEmpty()) {
                for (String wk : war.wars()) {

                        String[] parts = wk.split("\\|");
                        if (parts.length != 2) continue;

                        UUID a, b;
                        try {
                        a = UUID.fromString(parts[0]);
                        b = UUID.fromString(parts[1]);
                        } catch (Exception ex) {
                        continue;
                        }

                        var aiA = aiState.getById(a);
                        var aiB = aiState.getById(b);
                        if (aiA == null || aiB == null) continue;

                        var sim = war.getAiSimView(a, b);

                        double aFood = aiA.grain + aiA.meat + aiA.fish;
                        double bFood = aiB.grain + aiB.meat + aiB.fish;

                        writer.logWarSnapshot(
                                stepNum,
                                simTick,
                                wk,
                                a.toString(),
                                b.toString(),
                                Math.max(0, aiA.aliveSoldiers),
                                Math.max(1, aiA.maxSoldiers),
                                Math.max(0, aiB.aliveSoldiers),
                                Math.max(1, aiB.maxSoldiers),
                                sim.moraleA(),
                                sim.moraleB(),
                                aiA.gold,
                                aFood,
                                aiB.gold,
                                bFood
                        );
                }
                }



                if (stepNum % 20 == 0) writer.flush();
                }




                // -------------------------
                // CHAT OUTPUT (THROTTLED)
                // -------------------------
                if (!silent && (stepNum == 1 || stepNum % printEvery == 0 || stepNum == steps)) {
                        src.sendSuccess(() -> Component.literal(
                                "Step " + stepNum +
                                        " t=" + stepStart + "→" + stepEnd +
                                        " actions=" + actions +
                                        " warsΔ=" + warsDelta +
                                        " alliancesΔ=" + allyDelta
                        ), false);

                        if (topDelta.length() > 0) {
                        src.sendSuccess(() -> Component.literal("Economy/Soldiers deltas (sample):\n" + topDelta), false);
                        }

                        // If you still want per-action logs in chat, keep them only when not silent:
                        for (String line : stepLogs) {
                                src.sendSuccess(() -> Component.literal("  " + line), false);
                        }

                }

         
                }

                

                // ALWAYS write END snapshots if we are writing files
                if (writer != null) {
                long now = simTick; // IMPORTANT: use simTick, not server.getTickCount()
                for (var e : aiState.kingdoms.entrySet()) {
                        UUID id = e.getKey();
                        if (onlyFinal != null && !onlyFinal.contains(id)) continue;

                        var k = e.getValue();
                        if (k == null || k.personality == null) continue;

                        var p = k.personality;

                        writer.logKingdomSnapshot(
                                "END", steps, now,
                                k.id.toString(), (k.name == null ? "" : k.name),
                                p.generosity(), p.greed(), p.trustBias(), p.honor(), p.aggression(), p.pragmatism(),
                                Math.max(0, k.aliveSoldiers), Math.max(0, k.maxSoldiers),
                                k.gold, k.meat, k.grain, k.fish,
                                k.wood, k.metal, k.armor, k.weapons,
                                k.gems, k.horses, k.potions
                        );
                }
                        writer.flush();
                }

                if (!silent) {
                        src.sendSuccess(() -> Component.literal("=== MasterSim done ==="), false);
                }


                return 1;

                } catch (Exception e) {
                Kingdoms.LOGGER.error("MasterSim failed", e); // <-- stack trace in latest.log
                src.sendFailure(Component.literal("MasterSim failed: " + e));
                return 0;
                } finally {
                if (writer != null) {
                        try { writer.close(); } catch (Exception ignored) {}
                }

                try {
                        aiState.importSnapshot(snapAi);
                        relState.importRel(snapRel);
                        alliance.importAllies(snapAlly);
                        war.importState(snapWar);
                        AiDiplomacyTicker.importSchedule(snapSchedule);
                } catch (Throwable ignored) {}
                }

        }

        private static int countAlliancesSafe(net.minecraft.server.MinecraftServer server) {
                try {
                        var st = AllianceState.get(server);

                        // If you have a method like countAlliances(), use it:
                        try {
                        var m = st.getClass().getMethod("countAlliances");
                        Object v = m.invoke(st);
                        if (v instanceof Integer i) return i;
                        } catch (NoSuchMethodException ignored) {}

                        // If you store a map/set internally, we can reflect it (best-effort)
                        for (var f : st.getClass().getDeclaredFields()) {
                        f.setAccessible(true);
                        Object o = f.get(st);
                        if (o instanceof java.util.Collection<?> c) return c.size();
                        if (o instanceof java.util.Map<?,?> m) return m.size();
                        }

                        return 0;
                } catch (Throwable t) {
                        return 0;
                }
                }



    private static int explainDiplomacy(CommandSourceStack src, String fromToken, String toToken) {
        var server = src.getServer();
        var level = server.overworld();

        var ks = kingdomState.get(server);
        var ai = aiKingdomState.get(server);

        var fromResolved = resolveTarget(src, fromToken);
        var toResolved   = resolveTarget(src, toToken);

        if (fromResolved.kingdom == null || toResolved.kingdom == null) {
            src.sendFailure(Component.literal("Unknown from/to. Try kingdom name, UUID, 'me', or online player name."));
            return 0;
        }

        UUID fromKid = fromResolved.kingdom.id;
        UUID toKid   = toResolved.kingdom.id;

        var war = name.kingdoms.war.WarState.get(server);
        var alliance = name.kingdoms.diplomacy.AllianceState.get(server);

        boolean atWar = war.isAtWar(fromKid, toKid);
        boolean allied = alliance.isAllied(fromKid, toKid);

        String fromName = safeName(fromResolved.kingdom, ai);
        String toName   = safeName(toResolved.kingdom, ai);

        src.sendSuccess(() -> Component.literal("=== Diplomacy Explain ==="), false);
        src.sendSuccess(() -> Component.literal("From: " + fromName + " (" + fromKid + ")"), false);
        src.sendSuccess(() -> Component.literal("To:   " + toName + " (" + toKid + ")"), false);
        src.sendSuccess(() -> Component.literal("At war: " + atWar + " | Allied: " + allied), false);

        // If receiver is a player (or 'me'), show the player<->AI relation used for mail policy.
        if (toResolved.playerId != null) {
            var relState = name.kingdoms.diplomacy.DiplomacyRelationsState.get(server);
            int rel = relState.getRelation(toResolved.playerId, fromKid);
            src.sendSuccess(() -> Component.literal("PlayerRelation(toPlayer, fromKingdom): " + rel), false);
        }

        // If from is AI, show personality + current stocks snapshot
        var fromAi = ai.getById(fromKid);
        if (fromAi != null) {
            src.sendSuccess(() -> Component.literal("From personality: " + fromAi.personality), false);
            src.sendSuccess(() -> Component.literal(
                    String.format(Locale.US,
                            "From stocks: gold=%.1f meat=%.1f grain=%.1f fish=%.1f wood=%.1f metal=%.1f armor=%.1f weapons=%.1f gems=%.1f",
                            fromAi.gold, fromAi.meat, fromAi.grain, fromAi.fish, fromAi.wood, fromAi.metal, fromAi.armor, fromAi.weapons, fromAi.gems
                    )), false);
        }

        // If to is AI, show personality
        var toAi = ai.getById(toKid);
        if (toAi != null) {
            src.sendSuccess(() -> Component.literal("To personality: " + toAi.personality), false);
        }

        // If it's AI->player and not at war, show what policy would pick.
        if (toResolved.playerId != null && fromAi != null) {
            var relState = name.kingdoms.diplomacy.DiplomacyRelationsState.get(server);
            int rel = relState.getRelation(toResolved.playerId, fromKid);

            String policyPick;
            if (atWar) {
                policyPick = "WAR_MODE (peace-only)";
            } else {
                var opt = name.kingdoms.diplomacy.DiplomacyLetterPolicy.chooseOutgoing(
                        level.getRandom(), rel, fromAi.personality, false, allied,false
                );
                policyPick = opt.map(Enum::name).orElse("NONE");
            }

            src.sendSuccess(() -> Component.literal("Policy pick (if sending now): " + policyPick), false);
        }

        return 1;
    }

    private static int forceGenerateDiplomacy(CommandSourceStack src, String fromToken, String toToken) {
        var server = src.getServer();
        var level = server.overworld();

        var ks = kingdomState.get(server);
        var ai = aiKingdomState.get(server);

        var fromResolved = resolveTarget(src, fromToken);
        var toResolved   = resolveTarget(src, toToken);

        if (fromResolved.kingdom == null || toResolved.kingdom == null) {
            src.sendFailure(Component.literal("Unknown from/to. Try kingdom name, UUID, 'me', or online player name."));
            return 0;
        }

        // Only support delivering to a PLAYER inbox (your mailbox is player keyed).
        if (toResolved.playerId == null) {
            src.sendFailure(Component.literal("force_generate currently only supports sending to a player inbox. Use an ONLINE player name or 'me' for <to>."));
            return 0;
        }

        UUID fromKid = fromResolved.kingdom.id;
        var fromAi = ai.getById(fromKid);
        if (fromAi == null) {
            src.sendFailure(Component.literal("force_generate requires <from> to be an AI kingdom."));
            return 0;
        }

        long now = src.getServer().getTickCount();
        Letter letter = name.kingdoms.diplomacy.DiplomacyMailGenerator.makeImmediateProposal(
                level, server, fromKid, toResolved.playerId, now
        );

        if (letter == null) {
            src.sendSuccess(() -> Component.literal("AI generated no letter (policy NONE, blocked, or peace gate declined)."), false);
            return 1;
        }

        var mailbox = name.kingdoms.diplomacy.DiplomacyMailboxState.get(level);
        mailbox.addLetter(toResolved.playerId, letter);

        // keep client inbox synced (exists in your project)
        var player = server.getPlayerList().getPlayer(toResolved.playerId);
        if (player != null) {
            name.kingdoms.network.serverMail.syncInbox(player, mailbox.getInbox(toResolved.playerId));
        }

        src.sendSuccess(() -> Component.literal("Generated + delivered: " + letter.kind() + " from " + letter.fromName()), false);
        return 1;
    }

    private static int forceSendDiplomacy(
            CommandSourceStack src,
            String fromToken,
            String toToken,
            String kindToken,
            String aTypeToken, double aAmt,
            String bTypeToken, double bAmt,
            double maxAmt,
            String casusBelliToken
    ) {
        var server = src.getServer();
        var level = server.overworld();

        var ai = aiKingdomState.get(server);

        var fromResolved = resolveTarget(src, fromToken);
        var toResolved   = resolveTarget(src, toToken);

        if (fromResolved.kingdom == null || toResolved.kingdom == null) {
            src.sendFailure(Component.literal("Unknown from/to. Try kingdom name, UUID, 'me', or online player name."));
            return 0;
        }

        if (toResolved.playerId == null) {
            src.sendFailure(Component.literal("force_send currently only supports sending to a player inbox. Use an ONLINE player name or 'me' for <to>."));
            return 0;
        }

        Letter.Kind kind;
        try {
            kind = Letter.Kind.valueOf(kindToken.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            src.sendFailure(Component.literal("Unknown letter kind: " + kindToken));
            return 0;
        }

        UUID fromKid = fromResolved.kingdom.id;
        boolean fromIsAi = ai.getById(fromKid) != null;
        String fromName = safeName(fromResolved.kingdom, ai);

        long now = src.getServer().getTickCount();
        long expires = now + (20L * 60L * 10L);
        String note = "[FORCED]";

        ResourceType aType = (aTypeToken == null) ? null : parseResType(aTypeToken);
        ResourceType bType = (bTypeToken == null) ? null : parseResType(bTypeToken);

        Letter.CasusBelli cb = null;
        if (casusBelliToken != null) {
            try {
                cb = Letter.CasusBelli.valueOf(casusBelliToken.trim().toUpperCase(Locale.ROOT));
            } catch (Exception ignored) {
                cb = Letter.CasusBelli.UNKNOWN;
            }
        }

        Letter letter = switch (kind) {
            case REQUEST -> Letter.request(fromKid, fromIsAi, fromName, toResolved.playerId,
                    aType != null ? aType : ResourceType.GOLD, (aAmt > 0 ? aAmt : 25.0),
                    now, expires, note);

            case OFFER -> Letter.offer(fromKid, fromIsAi, fromName, toResolved.playerId,
                    aType != null ? aType : ResourceType.GOLD, (aAmt > 0 ? aAmt : 25.0),
                    now, expires, note);

            case CONTRACT -> Letter.contract(fromKid, fromIsAi, fromName, toResolved.playerId,
                    aType != null ? aType : ResourceType.METAL, (aAmt > 0 ? aAmt : 20.0),
                    bType != null ? bType : ResourceType.WOOD, (bAmt > 0 ? bAmt : 20.0),
                    (maxAmt > 0 ? maxAmt : 120.0),
                    now, expires, note);

            case ULTIMATUM -> Letter.ultimatum(fromKid, fromIsAi, fromName, toResolved.playerId,
                    aType != null ? aType : ResourceType.GOLD, (aAmt > 0 ? aAmt : 50.0),
                    now, expires, note);

            case WAR_DECLARATION -> Letter.warDeclaration(fromKid, fromIsAi, fromName, toResolved.playerId,
                    (cb == null ? Letter.CasusBelli.UNKNOWN : cb),
                    now, expires, note);

            case COMPLIMENT -> Letter.compliment(fromKid, fromIsAi, fromName, toResolved.playerId, now, expires, note);
            case INSULT -> Letter.insult(fromKid, fromIsAi, fromName, toResolved.playerId, now, expires, note);
            case WARNING -> Letter.warning(fromKid, fromIsAi, fromName, toResolved.playerId, now, expires, note);

            case ALLIANCE_PROPOSAL -> Letter.allianceProposal(fromKid, fromIsAi, fromName, toResolved.playerId, now, expires, note);
            case WHITE_PEACE -> Letter.whitePeace(fromKid, fromIsAi, fromName, toResolved.playerId, now, expires, note);
            case SURRENDER -> Letter.surrender(fromKid, fromIsAi, fromName, toResolved.playerId, now, expires, note);

            default -> null;
        };

        if (letter == null) {
            src.sendFailure(Component.literal("force_send not implemented for kind: " + kind));
            return 0;
        }

        var mailbox = name.kingdoms.diplomacy.DiplomacyMailboxState.get(level);
        mailbox.addLetter(toResolved.playerId, letter);

        var player = server.getPlayerList().getPlayer(toResolved.playerId);
        if (player != null) {
            name.kingdoms.network.serverMail.syncInbox(player, mailbox.getInbox(toResolved.playerId));
        }

        src.sendSuccess(() -> Component.literal("Forced letter delivered: " + letter.kind() + " from " + letter.fromName()), false);
        return 1;
    }

    private static ResourceType parseResType(String token) {
        if (token == null) return null;
        try {
                return ResourceType.valueOf(token.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
                return null;
        }
        }


        private static int aiSetRes(CommandSourceStack src, String nameRaw, String resRaw, double amountRaw) {
                var server = src.getServer();
                var ai = aiKingdomState.get(server);

                        UUID id = findAiByName(ai, nameRaw);
                        if (id == null) {
                                src.sendFailure(Component.literal("No AI kingdom match for: " + nameRaw));
                                return 0;
                        }

                        var k = ai.getById(id);
                        if (k == null) {
                                src.sendFailure(Component.literal("AI kingdom missing for: " + nameRaw));
                                return 0;
                        }

                        ResourceType rt = parseResType(resRaw);
                        if (rt == null) {
                                src.sendFailure(Component.literal("Unknown resource: " + resRaw));
                                return 0;
                        }

                        double amount = Math.max(0.0, amountRaw);

                        double before = AiEconomyMutator.get(k, rt);
                        AiEconomyMutator.add(k, rt, amount - before);

                        ai.markDirty();

                        src.sendSuccess(() -> Component.literal(
                                "Set " + rt.name() + " to " + String.format(Locale.US, "%.1f", amount) +
                                        " for " + (k.name == null ? id.toString() : k.name)
                        ), false);

                return 1;
        }


        private static int aiAddRes(CommandSourceStack src, String nameRaw, String resRaw, double delta) {
                var server = src.getServer();
                var ai = aiKingdomState.get(server);

                UUID id = findAiByName(ai, nameRaw);
                if (id == null) {
                        src.sendFailure(Component.literal("No AI kingdom match for: " + nameRaw));
                        return 0;
                }

                var k = ai.getById(id);
                if (k == null) {
                        src.sendFailure(Component.literal("AI kingdom missing for: " + nameRaw));
                        return 0;
                }

                ResourceType rt = parseResType(resRaw);
                if (rt == null) {
                        src.sendFailure(Component.literal("Unknown resource: " + resRaw));
                        return 0;
                }

                double before = AiEconomyMutator.get(k, rt);
                AiEconomyMutator.add(k, rt, delta);
                double after = AiEconomyMutator.get(k, rt);

                ai.markDirty();

                src.sendSuccess(() -> Component.literal(
                        "Added " + String.format(Locale.US, "%.1f", delta) + " " + rt.name() +
                        " for " + (k.name == null ? id.toString() : k.name) +
                        " (now " + String.format(Locale.US, "%.1f", after) + ")"
                ), false);

                return 1;
                }

                private static int aiSetSoldiers(CommandSourceStack src, String nameRaw, int alive, Integer maxOrNull) {
                var server = src.getServer();
                var ai = aiKingdomState.get(server);

                UUID id = findAiByName(ai, nameRaw);
                if (id == null) {
                        src.sendFailure(Component.literal("No AI kingdom match for: " + nameRaw));
                        return 0;
                }

                var k = ai.getById(id);
                if (k == null) {
                        src.sendFailure(Component.literal("AI kingdom missing for: " + nameRaw));
                        return 0;
                }

                if (maxOrNull != null) k.maxSoldiers = Math.max(1, maxOrNull);
                k.aliveSoldiers = Math.max(0, Math.min(alive, k.maxSoldiers));

                ai.markDirty();

                src.sendSuccess(() -> Component.literal(
                        "Set soldiers for " + (k.name == null ? id.toString() : k.name) +
                        ": " + k.aliveSoldiers + " / " + k.maxSoldiers
                ), false);

                return 1;
                }


    // ============================================================
    // War commands helpers
    // ============================================================

    private static int warList(CommandSourceStack src) {
        var server = src.getServer();
        var ks = kingdomState.get(server);
        var war = name.kingdoms.war.WarState.get(server);

        var set = war.wars();
        if (set.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No active wars."), false);
            return 1;
        }

        StringBuilder sb = new StringBuilder("Active wars:\n");
        for (String k : set) {
            String[] parts = k.split("\\|");
            if (parts.length != 2) continue;
            UUID a = UUID.fromString(parts[0]);
            UUID b = UUID.fromString(parts[1]);
            sb.append("- ").append(nameOf(ks, a)).append(" vs ").append(nameOf(ks, b)).append("\n");
        }

        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int warStatus(CommandSourceStack src, String aToken, String bToken) {
        var server = src.getServer();
        var ks = kingdomState.get(server);

        var aR = resolveTarget(src, aToken);
        var bR = resolveTarget(src, bToken);

        if (aR.kingdom == null || bR.kingdom == null) {
            src.sendFailure(Component.literal("Unknown kingdom(s)."));
            return 0;
        }

        var war = name.kingdoms.war.WarState.get(server);
        boolean atWar = war.isAtWar(aR.kingdom.id, bR.kingdom.id);

        src.sendSuccess(() -> Component.literal("War status: " +
                safeName(aR.kingdom, aiKingdomState.get(server)) + " vs " +
                safeName(bR.kingdom, aiKingdomState.get(server)) + " => " + (atWar ? "AT WAR" : "peace")), false);

        var z = war.getZone(aR.kingdom.id, bR.kingdom.id);
        if (z.isPresent()) {
            var zone = z.get();
            src.sendSuccess(() -> Component.literal("Zone: minX=" + zone.minX() + " minZ=" + zone.minZ()
                    + " maxX=" + zone.maxX() + " maxZ=" + zone.maxZ()), false);
        } else {
            src.sendSuccess(() -> Component.literal("Zone: (none)"), false);
        }

        return 1;
    }

    private static int warDeclare(CommandSourceStack src, String aToken, String bToken) {
        var server = src.getServer();

        var aR = resolveTarget(src, aToken);
        var bR = resolveTarget(src, bToken);

        if (aR.kingdom == null || bR.kingdom == null) {
            src.sendFailure(Component.literal("Unknown kingdom(s)."));
            return 0;
        }

        var war = name.kingdoms.war.WarState.get(server);
        war.declareWar(server, aR.kingdom.id, bR.kingdom.id);

        src.sendSuccess(() -> Component.literal("Declared war: " +
                safeName(aR.kingdom, aiKingdomState.get(server)) + " vs " +
                safeName(bR.kingdom, aiKingdomState.get(server))), false);
        return 1;
    }

    private static int warPeace(CommandSourceStack src, String aToken, String bToken) {
        var server = src.getServer();

        var aR = resolveTarget(src, aToken);
        var bR = resolveTarget(src, bToken);

        if (aR.kingdom == null || bR.kingdom == null) {
            src.sendFailure(Component.literal("Unknown kingdom(s)."));
            return 0;
        }

        var war = name.kingdoms.war.WarState.get(server);
        war.makePeace(aR.kingdom.id, bR.kingdom.id);

        src.sendSuccess(() -> Component.literal("Made peace: " +
                safeName(aR.kingdom, aiKingdomState.get(server)) + " vs " +
                safeName(bR.kingdom, aiKingdomState.get(server))), false);
        return 1;
    }

    private static int warZoneGet(CommandSourceStack src, String aToken, String bToken) {
        var server = src.getServer();

        var aR = resolveTarget(src, aToken);
        var bR = resolveTarget(src, bToken);

        if (aR.kingdom == null || bR.kingdom == null) {
            src.sendFailure(Component.literal("Unknown kingdom(s)."));
            return 0;
        }

        var war = name.kingdoms.war.WarState.get(server);
        var opt = war.getZone(aR.kingdom.id, bR.kingdom.id);
        if (opt.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No zone for that pair."), false);
            return 1;
        }

        var z = opt.get();
        src.sendSuccess(() -> Component.literal("Zone: minX=" + z.minX() + " minZ=" + z.minZ()
                + " maxX=" + z.maxX() + " maxZ=" + z.maxZ()), false);
        return 1;
    }

    private static int warZoneSet(CommandSourceStack src, String aToken, String bToken, int minX, int minZ, int maxX, int maxZ) {
        var server = src.getServer();

        var aR = resolveTarget(src, aToken);
        var bR = resolveTarget(src, bToken);

        if (aR.kingdom == null || bR.kingdom == null) {
            src.sendFailure(Component.literal("Unknown kingdom(s)."));
            return 0;
        }

        var war = name.kingdoms.war.WarState.get(server);
        BattleZone zone = new BattleZone(minX, minZ, maxX, maxZ);
        war.setZone(aR.kingdom.id, bR.kingdom.id, zone);



        src.sendSuccess(() -> Component.literal("Set zone for pair."), false);
        return 1;
    }

    private static int warZoneClear(CommandSourceStack src, String aToken, String bToken) {
        var server = src.getServer();

        var aR = resolveTarget(src, aToken);
        var bR = resolveTarget(src, bToken);

        if (aR.kingdom == null || bR.kingdom == null) {
            src.sendFailure(Component.literal("Unknown kingdom(s)."));
            return 0;
        }

        var war = name.kingdoms.war.WarState.get(server);
        war.clearZone(aR.kingdom.id, bR.kingdom.id);

        src.sendSuccess(() -> Component.literal("Cleared zone for pair."), false);
        return 1;
    }

        private static int warTickAi(CommandSourceStack src) {
                var server = src.getServer();
                var war = name.kingdoms.war.WarState.get(server);
                war.tickAiWars(server);
                src.sendSuccess(() -> Component.literal("Ran WarState.tickAiWars()."), false);
                return 1;
        }
    // ============================================================
    // Target resolution shared
    // ============================================================

    private record TargetResolved(UUID playerId, kingdomState.Kingdom kingdom) {}

    private static TargetResolved resolveTarget(CommandSourceStack src, String token) {
        if (token == null) return new TargetResolved(null, null);
        String t = token.trim();
        String t2 = t.replace('_', ' ');

        var server = src.getServer();
        var ks = kingdomState.get(server);

        // "me" / "self" => player + their kingdom
        if (t.equalsIgnoreCase("me") || t.equalsIgnoreCase("self") || t.equalsIgnoreCase("player")) {
            try {
                var p = src.getPlayerOrException();
                var k = ks.getPlayerKingdom(p.getUUID());
                return new TargetResolved(p.getUUID(), k);
            } catch (Exception e) {
                return new TargetResolved(null, null);
            }
        }

        // online player name => their kingdom
        var online = server.getPlayerList().getPlayers().stream()
                .filter(p -> p.getGameProfile().name().equalsIgnoreCase(t))
                .findFirst();
        if (online.isPresent()) {
            var p = online.get();
            var k = ks.getPlayerKingdom(p.getUUID());
            return new TargetResolved(p.getUUID(), k);
        }

        // UUID => kingdom id direct
        try {
            UUID id = UUID.fromString(t);
            var k = ks.getKingdom(id);
            if (k != null) return new TargetResolved(null, k);
        } catch (Exception ignored) {}

        // name match in kingdomState (accept underscores as spaces)
        String q1 = t.toLowerCase(Locale.ROOT);
        String q2 = t2.toLowerCase(Locale.ROOT);

        for (var k : ks.getAllKingdoms()) {
        if (k == null || k.name == null) continue;
        String kn = k.name.trim().toLowerCase(Locale.ROOT);
        if (kn.equals(q1) || kn.equals(q2)) return new TargetResolved(null, k);
        }

        // name match in AI state (fallback) (accept underscores as spaces)
        var ai = aiKingdomState.get(server);
        var aiMatch = ai.kingdoms.values().stream()
                .filter(k -> k != null && k.name != null &&
                        (k.name.equalsIgnoreCase(t) || k.name.equalsIgnoreCase(t2)))
                .findFirst();

        if (aiMatch.isPresent()) {
        var k = ks.getKingdom(aiMatch.get().id);
        return new TargetResolved(null, k);
        }


        return new TargetResolved(null, null);
    }

    private static String safeName(kingdomState.Kingdom k, aiKingdomState ai) {
        if (k == null) return "Unknown Kingdom";
        if (k.name != null && !k.name.isBlank()) return k.name;
        if (ai != null) {
            var a = ai.getById(k.id);
            if (a != null && a.name != null && !a.name.isBlank()) return a.name;
        }
        return "Unknown Kingdom";
    }

    private static String nameOf(kingdomState ks, UUID id) {
        var k = ks.getKingdom(id);
        if (k == null) return id.toString();
        return (k.name == null || k.name.isBlank()) ? id.toString() : k.name;
    }
private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
        }


    private static void sendRes(CommandSourceStack src, String label, double v) {
        src.sendSuccess(() -> Component.literal(" - " + label + ": " +
                String.format(Locale.US, "%.1f", v)), false);
    }

    

    // -------------------------
    // AI↔AI helpers (debug)
    // -------------------------

    private static String reflectString(Object target, String methodName) {
        if (target == null) return null;
        try {
                var m = target.getClass().getMethod(methodName);
                Object v = m.invoke(target);
                return (v == null) ? null : v.toString();
        } catch (Throwable t) {
                return null;
        }
        }

        private static String reflectEnumName(Object target, String methodName) {
        if (target == null) return null;
        try {
                var m = target.getClass().getMethod(methodName);
                Object v = m.invoke(target);
                if (v instanceof Enum<?> e) return e.name();
                return (v == null) ? null : v.toString();
        } catch (Throwable t) {
                return null;
        }
        }


    private static int diplomacySim(CommandSourceStack src, String fromToken, String toToken, int n) {
        var server = src.getServer();
        var level = server.overworld();

        var ks = kingdomState.get(server);
        var ai = aiKingdomState.get(server);
        var war = WarState.get(server);
        var alliance = AllianceState.get(server);

        var fromR = resolveTarget(src, fromToken);
        var toR   = resolveTarget(src, toToken);

        if (fromR.kingdom == null || toR.kingdom == null) {
                src.sendFailure(Component.literal("Unknown from/to. Use kingdom name/UUID, 'me', or online player name."));
                return 0;
        }

        UUID fromId = fromR.kingdom.id;
        UUID toId   = toR.kingdom.id;

        var fromAi = ai.getById(fromId);
        if (fromAi == null || fromAi.personality == null) {
                src.sendFailure(Component.literal("Sender must be an AI kingdom (with personality)."));
                return 0;
        }

        boolean atWar = war.isAtWar(fromId, toId);
        boolean allied = alliance.isAllied(fromId, toId);

        // Context vars
        int rel = 0;
        int senderSoldiers = Math.max(0, fromAi.aliveSoldiers);
        int recipientSoldiers = 0;

        var toAi = ai.getById(toId);

        if (toAi != null) {
                // AI -> AI relation
                Integer r = aiRelGet(server, fromId, toId);
                rel = (r == null) ? 0 : r;
                recipientSoldiers = Math.max(0, toAi.aliveSoldiers);
        } else if (toR.playerId != null) {
                // AI -> Player relation
                var relState = name.kingdoms.diplomacy.DiplomacyRelationsState.get(server);
                rel = relState.getRelation(toR.playerId, fromId);

                // approximate player soldiers from garrisons (same assumption as your inspect)
                var playerK = ks.getPlayerKingdom(toR.playerId);
                if (playerK != null) {
                int garrisons = playerK.active.getOrDefault("garrison", 0);
                recipientSoldiers = Math.max(0, garrisons * 50);
                }
        }

        EnumMap<Letter.Kind, Integer> counts = new EnumMap<>(Letter.Kind.class);
        var rng = level.getRandom();

        for (int i = 0; i < n; i++) {
                Letter.Kind kind = AiDiplomacyTicker.pickKind(
                rng,
                fromAi.personality,
                rel,
                allied,
                atWar,
                senderSoldiers,
                recipientSoldiers
                );
                counts.put(kind, counts.getOrDefault(kind, 0) + 1);
        }

        String fromName = safeName(fromR.kingdom, ai);
        String toName   = safeName(toR.kingdom, ai);

        StringBuilder sb = new StringBuilder();
        sb.append("=== Diplomacy Sim (pair) n=").append(n).append(" ===\n");
        sb.append("From: ").append(fromName).append("\n");
        sb.append("To:   ").append(toName).append("\n");
        sb.append("rel=").append(rel).append(" allied=").append(allied).append(" atWar=").append(atWar).append("\n");
        sb.append("soldiers: from=").append(senderSoldiers).append(" to=").append(recipientSoldiers).append("\n");

        // Stable, useful order
        Letter.Kind[] order = {
                Letter.Kind.OFFER,
                Letter.Kind.REQUEST,
                Letter.Kind.CONTRACT,
                Letter.Kind.COMPLIMENT,
                Letter.Kind.ALLIANCE_PROPOSAL,
                Letter.Kind.WARNING,
                Letter.Kind.INSULT,
                Letter.Kind.ULTIMATUM,
                Letter.Kind.WAR_DECLARATION,
                Letter.Kind.WHITE_PEACE,
                Letter.Kind.SURRENDER
        };

        int escalation = 0;
        for (Letter.Kind k : order) {
                int c = counts.getOrDefault(k, 0);
                if (c <= 0) continue;
                double pct = (c * 100.0) / n;

                sb.append(k.name()).append(": ").append(c)
                .append(" (").append(String.format(Locale.US, "%.1f", pct)).append("%)\n");

                if (k == Letter.Kind.ULTIMATUM || k == Letter.Kind.WAR_DECLARATION) escalation += c;
        }

        sb.append("Escalation (ULTIMATUM+WAR): ").append(escalation)
        .append(" (").append(String.format(Locale.US, "%.1f", escalation * 100.0 / n)).append("%)");

        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
        }


    private static int explainDiplomacyAi(CommandSourceStack src, String fromToken, String toToken) {
        var server = src.getServer();
        var ks = kingdomState.get(server);
        var aiState = aiKingdomState.get(server);
        var war = WarState.get(server);
        var alliance = AllianceState.get(server);

        var fromR = resolveTarget(src, fromToken);
        var toR   = resolveTarget(src, toToken);

        if (fromR.kingdom == null || toR.kingdom == null) {
            src.sendFailure(Component.literal("Unknown kingdom(s)."));
            return 0;
        }

        UUID fromId = fromR.kingdom.id;
        UUID toId   = toR.kingdom.id;

        var fromAi = aiState.getById(fromId);
        var toAi   = aiState.getById(toId);

        if (fromAi == null || toAi == null) {
            src.sendFailure(Component.literal("Both kingdoms must be AI kingdoms for explain_ai."));
            return 0;
        }

        boolean atWar = war.isAtWar(fromId, toId);
        boolean allied = alliance.isAllied(fromId, toId);

        Integer rel = aiRelGet(server, fromId, toId);

        StringBuilder sb = new StringBuilder();
        sb.append("AI↔AI Diplomacy Explain\n");
        sb.append("from=").append(safeName(fromR.kingdom)).append(" to=").append(safeName(toR.kingdom)).append("\n");
        sb.append("rel=").append(rel == null ? "(AiRelationsState missing)" : rel).append(" allied=").append(allied).append(" atWar=").append(atWar).append("\n");
        sb.append("fromPersonality=").append(fromAi.personality == null ? "null" : fromAi.personality.toString()).append("\n");
        sb.append("toPersonality=").append(toAi.personality == null ? "null" : toAi.personality.toString()).append("\n");

        // quick resource snapshot (top few)
        sb.append("fromRes: gold=").append(fmt(fromAi.gold))
                .append(" wood=").append(fmt(fromAi.wood))
                .append(" metal=").append(fmt(fromAi.metal))
                .append(" grain=").append(fmt(fromAi.grain))
                .append(" meat=").append(fmt(fromAi.meat))
                .append(" fish=").append(fmt(fromAi.fish))
                .append("\n");
        sb.append("toRes: gold=").append(fmt(toAi.gold))
                .append(" wood=").append(fmt(toAi.wood))
                .append(" metal=").append(fmt(toAi.metal))
                .append(" grain=").append(fmt(toAi.grain))
                .append(" meat=").append(fmt(toAi.meat))
                .append(" fish=").append(fmt(toAi.fish))
                .append("\n");

        // Preview weights using your public helper
        int relVal = (rel == null) ? 0 : rel;
        var weights = AiDiplomacyTicker.previewWeights(fromAi.personality, relVal, allied, false, 0, 0);
        sb.append("weights=").append(weights.toString()).append("\n");

        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int forceAiEvent(CommandSourceStack src, String fromToken, String toToken, String typeToken) {
        var server = src.getServer();
        var ks = kingdomState.get(server);
        var aiState = aiKingdomState.get(server);

        var fromR = resolveTarget(src, fromToken);
        var toR   = resolveTarget(src, toToken);

        if (fromR.kingdom == null || toR.kingdom == null) {
            src.sendFailure(Component.literal("Unknown kingdom(s)."));
            return 0;
        }

        UUID fromId = fromR.kingdom.id;
        UUID toId   = toR.kingdom.id;

        if (aiState.getById(fromId) == null || aiState.getById(toId) == null) {
            src.sendFailure(Component.literal("Both must be AI kingdoms for force_event_ai."));
            return 0;
        }

        AiDiplomacyEvent.Type type;
        try {
            type = AiDiplomacyEvent.Type.valueOf(typeToken.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            src.sendFailure(Component.literal("Unknown AI event type: " + typeToken));
            return 0;
        }

        var level = server.overworld();
        var evState = AiDiplomacyEventState.get(level);
        long now = src.getServer().getTickCount();

        String desc = type + ": " + safeName(fromR.kingdom) + " -> " + safeName(toR.kingdom);
        evState.add(new AiDiplomacyEvent(fromId, toId, type, now, desc));

        // Also drop a news entry so you can see it in UI
        var news = KingdomNewsState.get(level);
        news.add(now, "[AI] " + desc);

        src.sendSuccess(() -> Component.literal("Added AI event: " + desc), false);
        return 1;
    }

    private static int listAiEvents(CommandSourceStack src, int max) {
        var server = src.getServer();
        var level = server.overworld();
        var ks = kingdomState.get(server);

        var evState = AiDiplomacyEventState.get(level);
        var recent = evState.getRecent(max);

        if (recent.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No AI diplomacy events recorded."), false);
            return 1;
        }

        StringBuilder sb = new StringBuilder("Recent AI diplomacy events:\n");
        for (var e : recent) {
            String from = nameOf(ks, e.fromAi());
            String to   = nameOf(ks, e.toAi());
            sb.append("- ").append(e.type()).append(" ").append(from).append(" -> ").append(to)
              .append(" @").append(e.gameTime()).append(" : ").append(e.description())
              .append("\n");
        }

        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int forceSendAiAction(
            CommandSourceStack src,
            String fromToken,
            String toToken,
            String kindToken,
            String aTypeToken, double aAmt,
            String bTypeToken, double bAmt,
            double maxAmt,
            String cbToken
    ) {
        var server = src.getServer();
        var level = server.overworld();
        var ks = kingdomState.get(server);
        var aiState = aiKingdomState.get(server);
        var war = WarState.get(server);
        var alliance = AllianceState.get(server);
        var news = KingdomNewsState.get(level);

        var fromR = resolveTarget(src, fromToken);
        var toR   = resolveTarget(src, toToken);

        if (fromR.kingdom == null || toR.kingdom == null) {
            src.sendFailure(Component.literal("Unknown kingdom(s)."));
            return 0;
        }

        UUID fromId = fromR.kingdom.id;
        UUID toId   = toR.kingdom.id;

        var fromAi = aiState.getById(fromId);
        var toAi   = aiState.getById(toId);

        if (fromAi == null || toAi == null) {
            src.sendFailure(Component.literal("Both must be AI kingdoms for force_send_ai."));
            return 0;
        }

        Letter.Kind kind;
        try {
            kind = Letter.Kind.valueOf(kindToken.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            src.sendFailure(Component.literal("Unknown Letter.Kind: " + kindToken));
            return 0;
        }

        ResourceType aType = (aTypeToken == null) ? ResourceType.GOLD : parseResourceOrDefault(aTypeToken, ResourceType.GOLD);
        ResourceType bType = (bTypeToken == null) ? ResourceType.WOOD : parseResourceOrDefault(bTypeToken, ResourceType.WOOD);

        Letter.CasusBelli cb = null;
        if (cbToken != null) {
            try {
                cb = Letter.CasusBelli.valueOf(cbToken.trim().toUpperCase(Locale.ROOT));
            } catch (Exception ignored) {
                cb = Letter.CasusBelli.UNKNOWN;
            }
        }

        long now = src.getServer().getTickCount();

        // Execute immediately (treat as "accepted") for deterministic testing.
        switch (kind) {
            case WAR_DECLARATION -> {
                war.declareWar(server, fromId, toId);
                aiRelAdd(server, fromId, toId, -80);
                news.add(now, "[WAR] " + safeName(fromR.kingdom) + " declared war on " + safeName(toR.kingdom));
                AiDiplomacyEventState.get(level).add(new AiDiplomacyEvent(fromId, toId, AiDiplomacyEvent.Type.WAR_DECLARED, now,
                        safeName(fromR.kingdom) + " -> " + safeName(toR.kingdom)));
            }
            case WHITE_PEACE, SURRENDER -> {
                war.makePeace(fromId, toId);
                aiRelAdd(server, fromId, toId, +10);
                news.add(now, "[PEACE] " + safeName(fromR.kingdom) + " made peace with " + safeName(toR.kingdom));
                AiDiplomacyEventState.get(level).add(new AiDiplomacyEvent(fromId, toId, AiDiplomacyEvent.Type.PEACE_SIGNED, now,
                        safeName(fromR.kingdom) + " -> " + safeName(toR.kingdom)));
            }
            case ALLIANCE_PROPOSAL -> {
                boolean ok = alliance.addAlliance(fromId, toId);
                if (ok) {
                    aiRelAdd(server, fromId, toId, +25);
                    news.add(now, "[ALLIANCE] " + safeName(fromR.kingdom) + " allied with " + safeName(toR.kingdom));
                    AiDiplomacyEventState.get(level).add(new AiDiplomacyEvent(fromId, toId, AiDiplomacyEvent.Type.ALLIANCE_FORMED, now,
                            safeName(fromR.kingdom) + " -> " + safeName(toR.kingdom)));
                } else {
                    news.add(now, "[ALLIANCE] " + safeName(fromR.kingdom) + " failed to ally with " + safeName(toR.kingdom));
                }
            }
            case INSULT -> {
                aiRelAdd(server, fromId, toId, -10);
                news.add(now, "[DIP] " + safeName(fromR.kingdom) + " insulted " + safeName(toR.kingdom));
            }
            case COMPLIMENT -> {
                aiRelAdd(server, fromId, toId, +8);
                news.add(now, "[DIP] " + safeName(fromR.kingdom) + " complimented " + safeName(toR.kingdom));
            }
            case WARNING -> {
                aiRelAdd(server, fromId, toId, -3);
                news.add(now, "[DIP] " + safeName(fromR.kingdom) + " warned " + safeName(toR.kingdom));
            }
            case REQUEST -> {
                // recipient gives aAmt to sender (if possible)
                if (aAmt <= 0) aAmt = 25.0;
                double can = Math.min(aAmt, AiEconomyMutator.get(toAi, aType));
                AiEconomyMutator.add(toAi, aType, -can);
                AiEconomyMutator.add(fromAi, aType, +can);
                aiRelAdd(server, fromId, toId, (can >= aAmt * 0.9) ? +3 : -2);
                aiState.markDirty();
                news.add(now, "[ECO] " + safeName(toR.kingdom) + " paid " + fmt(can) + " " + aType + " to " + safeName(fromR.kingdom) + " (request).");
            }
            case OFFER -> {
                // sender gives aAmt to recipient (if possible)
                if (aAmt <= 0) aAmt = 25.0;
                double can = Math.min(aAmt, AiEconomyMutator.get(fromAi, aType));
                AiEconomyMutator.add(fromAi, aType, -can);
                AiEconomyMutator.add(toAi, aType, +can);
                aiRelAdd(server, fromId, toId, +2);
                aiState.markDirty();
                news.add(now, "[ECO] " + safeName(fromR.kingdom) + " sent " + fmt(can) + " " + aType + " to " + safeName(toR.kingdom) + " (offer).");
            }
            case CONTRACT -> {
                if (aAmt <= 0) aAmt = 20.0;
                if (bAmt <= 0) bAmt = 20.0;
                if (maxAmt <= 0) maxAmt = Math.max(aAmt, bAmt) * 6.0;

                // Execute one "step" immediately: sender gives a, recipient gives b (bounded)
                double giveA = Math.min(aAmt, AiEconomyMutator.get(fromAi, aType));
                double giveB = Math.min(bAmt, AiEconomyMutator.get(toAi, bType));

                AiEconomyMutator.add(fromAi, aType, -giveA);
                AiEconomyMutator.add(toAi, aType, +giveA);

                AiEconomyMutator.add(toAi, bType, -giveB);
                AiEconomyMutator.add(fromAi, bType, +giveB);

                aiRelAdd(server, fromId, toId, +1);
                aiState.markDirty();

                news.add(now, "[ECO] Contract executed: " + safeName(fromR.kingdom) + " gave " + fmt(giveA) + " " + aType +
                        " and received " + fmt(giveB) + " " + bType + " from " + safeName(toR.kingdom) + ".");
            }
            case ULTIMATUM -> {
                // For debug: if recipient can pay, they pay; otherwise war.
                if (aAmt <= 0) aAmt = 50.0;
                double have = AiEconomyMutator.get(toAi, aType);
                if (have >= aAmt) {
                    AiEconomyMutator.add(toAi, aType, -aAmt);
                    AiEconomyMutator.add(fromAi, aType, +aAmt);
                    aiRelAdd(server, fromId, toId, -10);
                    aiState.markDirty();
                    news.add(now, "[WAR] Ultimatum paid: " + safeName(toR.kingdom) + " paid " + fmt(aAmt) + " " + aType + " to " + safeName(fromR.kingdom) + ".");
                } else {
                    war.declareWar(server, fromId, toId);
                    aiRelAdd(server, fromId, toId, -80);
                    news.add(now, "[WAR] Ultimatum refused: " + safeName(fromR.kingdom) + " went to war with " + safeName(toR.kingdom) + ".");
                    AiDiplomacyEventState.get(level).add(new AiDiplomacyEvent(fromId, toId, AiDiplomacyEvent.Type.WAR_DECLARED, now,
                            safeName(fromR.kingdom) + " -> " + safeName(toR.kingdom)));
                }
            }
            default -> {
                news.add(now, "[AI] Forced AI action kind not handled: " + kind);
            }
        }

        src.sendSuccess(() -> Component.literal("Forced AI action executed: " + kind + " (" + safeName(fromR.kingdom) + " -> " + safeName(toR.kingdom) + ")"), false);
        return 1;
    }

    private static int forceTickAiOnce(CommandSourceStack src, String fromToken, int maxTargets) {
        var server = src.getServer();
        var level = server.overworld();
        var ks = kingdomState.get(server);
        var aiState = aiKingdomState.get(server);
        var war = WarState.get(server);
        var alliance = AllianceState.get(server);

        var fromR = resolveTarget(src, fromToken);
        if (fromR.kingdom == null) {
            src.sendFailure(Component.literal("Unknown from kingdom."));
            return 0;
        }

        var fromAi = aiState.getById(fromR.kingdom.id);
        if (fromAi == null) {
            src.sendFailure(Component.literal("From kingdom must be AI for force_tick_ai."));
            return 0;
        }

        // pick up to maxTargets other AI kingdoms
        var ids = new java.util.ArrayList<>(aiState.kingdoms.keySet());
        ids.remove(fromR.kingdom.id);
        java.util.Collections.shuffle(ids, new java.util.Random(server.getTickCount()));
        if (ids.size() > maxTargets) ids = new java.util.ArrayList<>(ids.subList(0, maxTargets));

        int did = 0;
        for (UUID toId : ids) {
            var toK = ks.getKingdom(toId);
            if (toK == null) continue;

            boolean atWar = war.isAtWar(fromR.kingdom.id, toId);
            boolean allied = alliance.isAllied(fromR.kingdom.id, toId);
            int rel = 0;
            Integer relObj = aiRelGet(server, fromR.kingdom.id, toId);
            if (relObj != null) rel = relObj;

            // use public helper to pick a kind (soldiers unknown in this command, pass 0)
            var kind = AiDiplomacyTicker.pickKind(level.getRandom(), fromAi.personality, rel, allied, false, 0, 0);

            // Provide cheap default econ params
            String aType = "GOLD";
            double aAmt = 20.0;

            forceSendAiAction(src,
                    safeName(fromR.kingdom),
                    safeName(toK),
                    kind.name(),
                    aType, aAmt,
                    null, 0.0,
                    0.0,
                    null);

            did++;
        }
        
        
        int didFinal = did;

        src.sendSuccess(() -> Component.literal(
                "force_tick_ai ran for " + safeName(fromR.kingdom) + " targets=" + didFinal
        ), false);

        return 1;
    }

    private static String safeName(kingdomState.Kingdom k) {
        return (k == null || k.name == null || k.name.isBlank())
                ? "Unknown Kingdom"
                : k.name;
        }

        private static ResourceType parseResourceOrDefault(String token, ResourceType def) {
        if (token == null) return def;
        try {
                return ResourceType.valueOf(token.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
                return def;
        }
        }



    // -------------------------
    // AiRelationsState reflection bridge (so commands still compile even if class is renamed/missing)
    // -------------------------

    private static Integer aiRelGet(net.minecraft.server.MinecraftServer server, UUID fromAi, UUID toAi) {
        try {
            Class<?> cls = Class.forName("name.kingdoms.diplomacy.AiRelationsState");
            Object st = cls.getMethod("get", net.minecraft.server.MinecraftServer.class).invoke(null, server);
            Object v = cls.getMethod("get", UUID.class, UUID.class).invoke(st, fromAi, toAi);
            if (v instanceof Integer i) return i;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void aiRelAdd(net.minecraft.server.MinecraftServer server, UUID fromAi, UUID toAi, int delta) {
        try {
            Class<?> cls = Class.forName("name.kingdoms.diplomacy.AiRelationsState");
            Object st = cls.getMethod("get", net.minecraft.server.MinecraftServer.class).invoke(null, server);
            cls.getMethod("add", UUID.class, UUID.class, int.class).invoke(st, fromAi, toAi, delta);
        } catch (Throwable ignored) {}
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.0f", v);
    }

    private static int ambientList(CommandSourceStack src) {
        StringBuilder sb = new StringBuilder("Ambient events:\n");
        for (AmbientEvent e : AmbientEvents.allEvents()) {
                sb.append("- ").append(e.id()).append("\n");
                if (e instanceof ScriptedAmbientEvent se) {
                var ids = se.variantIds();
                if (!ids.isEmpty()) sb.append("    variants: ").append(String.join(", ", ids)).append("\n");
                }

        }
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
        }

        private static int ambientRoll(CommandSourceStack src) {
        ServerPlayer player;
        try { player = src.getPlayerOrException(); }
        catch (Exception e) { src.sendFailure(Component.literal("Player only.")); return 0; }

        ServerLevel level = (ServerLevel) player.level();
        var server = src.getServer();
        var ks = kingdomState.get(server);
        var war = WarState.get(server);

        AmbientContext ctx = AmbientContext.build(server, level, player, ks, war);



        AmbientEvent ev = AmbientEvents.pick(ctx);
        if (ev == null) {
                src.sendFailure(Component.literal("No ambient event matched right now (all weights/gates 0)."));
                return 0;
        }

        ev.run(ctx);

        // apply effects (same pattern as AmbientManager)
        var eff = ev.effects(ctx);
        if (eff != null && !eff.isEmpty()) {
        for (var a : eff) {
                try { if (a != null) a.apply(ctx); } catch (Throwable ignored) {}
        }
        }


        src.sendSuccess(() -> Component.literal("[Ambient] Rolled: " + ev.id()), false);
        return 1;
        }

        private static int ambientSpawn(CommandSourceStack src, String eventId, String variantIdOrNull, boolean ignoreGate) {
        ServerPlayer player;
        try { player = src.getPlayerOrException(); }
        catch (Exception e) { src.sendFailure(Component.literal("Player only.")); return 0; }

        ServerLevel level = (ServerLevel) player.level();
        var server = src.getServer();
        var ks = kingdomState.get(server);
        var war = WarState.get(server);

        AmbientContext ctx = AmbientContext.build(server, level, player, ks, war);


        ScriptedAmbientEvent ev = AmbientEvents.getScriptedById(eventId);
        if (ev == null) {
                src.sendFailure(Component.literal("Unknown eventId: '" + eventId + "'. Try /kingdoms ambient list"));
                return 0;
        }

        boolean ok;
        if (variantIdOrNull == null) {
                ev.run(ctx);
                ok = true;
        } else {
                ok = ev.runForced(ctx, variantIdOrNull, ignoreGate, false);
        }

        if (!ok) {
                src.sendFailure(Component.literal("Failed to spawn event '" + eventId + "' variant '" + variantIdOrNull +
                        "' (missing variant OR gate failed; try ignoreGate=true)."));
                return 0;
        }

        // If runForced already applied effects, you can remove this.
        // If you want consistent behavior for both paths, keep effects here and remove them from runForced.
        var eff = ev.effects(ctx);
        if (eff != null && !eff.isEmpty()) {
        for (var a : eff) {
                try { if (a != null) a.apply(ctx); } catch (Throwable ignored) {}
        }
        }


        src.sendSuccess(() -> Component.literal("[Ambient] Spawned: " + eventId +
                (variantIdOrNull != null ? (" variant=" + variantIdOrNull + " ignoreGate=" + ignoreGate) : "")), false);
        return 1;
        }



}
