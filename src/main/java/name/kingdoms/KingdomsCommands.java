package name.kingdoms;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

public final class KingdomsCommands {

    public static void register() {
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> register(dispatcher)
        );
    }

    private static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("kingdoms")
                .requires(src -> src.hasPermission(2)) // OP only
                .then(Commands.literal("listai")
                        .executes(ctx -> listAi(ctx.getSource()))
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

        ai.kingdoms.values().forEach(k -> {
            src.sendSuccess(() -> Component.literal(
                    "- " + k.name + " @ " +
                            k.origin.getX() + ", " +
                            k.origin.getY() + ", " +
                            k.origin.getZ()
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

        String target = targetRaw == null ? "" : targetRaw.trim();
        if (target.isBlank()) {
            src.sendFailure(Component.literal("Usage: /kingdoms inspect <kingdom name | king name>"));
            return 0;
        }

        // 1) King name (ONLINE player username) -> inspect their kingdom
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

        // 2) Player/world kingdom name -> inspect that kingdom
        var kMatch = ks.getAllKingdoms().stream()
                .filter(k -> k != null && k.name != null && k.name.equalsIgnoreCase(target))
                .findFirst();

        if (kMatch.isPresent()) {
            return inspectPlayerKingdom(src, kMatch.get(), null);
        }

        // 3) AI kingdom name -> inspect AI stocks/soldiers
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
       final String kingLine = "Owner: " + (k.owner == null ? "None" : k.owner.toString())
        + (kingNameOrNull != null ? (" (" + kingNameOrNull + ")") : "");

        src.sendSuccess(() -> Component.literal(kingLine), false);
        src.sendSuccess(() -> Component.literal("=== Kingdom Inspect (Player/World) ==="), false);
        src.sendSuccess(() -> Component.literal("Name: " + (k.name == null ? "Unknown" : k.name)), false);
        src.sendSuccess(() -> Component.literal("Id: " + k.id), false);
        src.sendSuccess(() -> Component.literal(kingLine), false);
        src.sendSuccess(() -> Component.literal("Origin: " +
                k.origin.getX() + ", " + k.origin.getY() + ", " + k.origin.getZ()), false);

        // Soldiers estimate (garrisons*50)
        int garrisons = k.active.getOrDefault("garrison", 0);
        int max = Math.max(0, garrisons * 50);
        int alive = max; // until you track real losses
        src.sendSuccess(() -> Component.literal("Soldiers (est): " + alive + " / " + max + " (garrisons=" + garrisons + ")"), false);

        // Resources
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
        src.sendSuccess(() -> Component.literal("=== Kingdom Inspect (AI) ==="), false);
        src.sendSuccess(() -> Component.literal("Name: " + aiK.name), false);
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

    private static void sendRes(CommandSourceStack src, String label, double v) {
        src.sendSuccess(() -> Component.literal(" - " + label + ": " +
                String.format(Locale.US, "%.1f", v)), false);
    }
}
