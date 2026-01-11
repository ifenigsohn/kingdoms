package name.kingdoms.blueprint;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public final class BlueprintPlaceCommand {
    private BlueprintPlaceCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, String modId) {
        dispatcher.register(Commands.literal("kingdoms")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("place_blueprint")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> run(ctx.getSource(), modId, StringArgumentType.getString(ctx, "name"), false))
                                .then(Commands.argument("includeAir", BoolArgumentType.bool())
                                        .executes(ctx -> run(
                                                ctx.getSource(),
                                                modId,
                                                StringArgumentType.getString(ctx, "name"),
                                                BoolArgumentType.getBool(ctx, "includeAir")
                                        ))))));
    }

    private static int run(CommandSourceStack src, String modId, String name, boolean includeAir) {
        try {
            MinecraftServer server = src.getServer();
            ServerLevel level = src.getLevel();
            BlockPos origin = BlockPos.containing(src.getPosition());

            Blueprint bp = Blueprint.load(server, modId, name);

            BlueprintPlacerEngine.enqueue(
                    level, bp, origin, modId, includeAir,
                    () -> {}, // onSuccess (optional)
                    () -> {}  // onFail (optional)
            );


            src.sendSuccess(() -> Component.literal(
                    "[Kingdoms] Queued blueprint '" + name + "' at " +
                            "X=" + origin.getX() + " Y=" + origin.getY() + " Z=" + origin.getZ()
            ), false);

            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("[Kingdoms] Failed: " + e.getMessage()));
            return 0;
        }
    }
}
