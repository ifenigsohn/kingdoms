package name.kingdoms.diplomacy;

import name.kingdoms.aiKingdomState;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

public final class DiplomacyRelationNormalizer {
    private DiplomacyRelationNormalizer() {}

    // Every 20 minutes: 20 min * 60 sec * 20 ticks = 24000 ticks
    private static final long PERIOD_TICKS = 20L * 60L * 20L;

    // Move 5 points toward 0 each period
    private static final int STEP = 5;

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(DiplomacyRelationNormalizer::onTick);
    }

    private static void onTick(MinecraftServer server) {
        long now = server.getTickCount();
        if (now <= 0) return;

        // Only run every PERIOD_TICKS
        if (now % PERIOD_TICKS != 0) return;

        var relState = DiplomacyRelationsState.get(server);
        var aiState = aiKingdomState.get(server);

        boolean anyChanged = false;

        for (var e : relState.entries()) {
            // Only normalize relations against AI kingdoms
            if (aiState.getById(e.kingdomId()) == null) continue;

            int v = e.value();
            int nv = nudgeTowardZero(v, STEP);

            if (nv != v) {
                relState.setRelationNoDirty(e.playerId(), e.kingdomId(), nv);
                anyChanged = true;
            }
        }

        if (anyChanged) {
            relState.setDirty(); // exactly once
        }
    }

    private static int nudgeTowardZero(int v, int step) {
        if (v > 0) return Math.max(0, v - step);
        if (v < 0) return Math.min(0, v + step);
        return 0;
    }
}
