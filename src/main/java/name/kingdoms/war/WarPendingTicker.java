package name.kingdoms.war;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

/**
 * Drives pending war zone computation without hitching the server.
 */
public final class WarPendingTicker {

    private WarPendingTicker() {}

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(WarPendingTicker::tick);
    }

    private static void tick(MinecraftServer server) {
        // runs small slices each tick; WarState enforces a hard budget
        WarState.get(server).tickPendingWars(server);
    }
}
