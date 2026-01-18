package name.kingdoms.diplomacy;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

public final class AiRelationNormalizer {
    private AiRelationNormalizer() {}

    // 20 minutes
    public static final long PERIOD_TICKS = 20L * 60L * 10L;

    // Gentle decay
    private static final int STEP = 3;

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(AiRelationNormalizer::onTick);
    }

    /** Live game: driven by real server ticks */
    private static void onTick(MinecraftServer server) {
        long now = server.getTickCount();
        runAtTick(server, now);
    }

    /** SIM ENTRY POINT — THIS IS WHAT YOU ARE CALLING */
    public static void onSimTick(MinecraftServer server, long simTick) {
        runAtTick(server, simTick);
    }

    /** Shared logic so sim + live behave identically */
    private static void runAtTick(MinecraftServer server, long nowTick) {
        if (server == null) return;
        if (nowTick <= 0) return;

        if (nowTick % PERIOD_TICKS != 0) return;

        var aiRel = AiRelationsState.get(server);

        boolean anyChanged = false;

        // snapshot keys to mutate backing map
        var keys = new java.util.ArrayList<>(aiRel.entries().keySet());

        for (String k : keys) {
            Integer vObj = aiRel.entries().get(k);
            if (vObj == null) continue;
            int v = vObj;
            int nv = nudgeTowardZero(v, STEP);
            if (nv != v) {
                aiRel.entries().put(k, nv);
                anyChanged = true;
            }
        }

        if (anyChanged) {
            aiRel.setDirty();
        }

    }

    private static int nudgeTowardZero(int v, int step) {
        if (v > 0) return Math.max(0, v - step);
        if (v < 0) return Math.min(0, v + step);
        return 0;
    }
}
