package name.kingdoms.diplomacy;

import name.kingdoms.aiKingdomState;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

public final class DiplomacyRelationNormalizer {
    private DiplomacyRelationNormalizer() {}

    // Every 20 minutes: 20 min * 60 sec * 20 ticks = 24000 ticks
    private static final long PERIOD_TICKS = 20L * 60L * 30L; // DEV DEBUG FOR PLAYTEST 20 MINUTES

    // Move 5 points toward 0 each period
    private static final int STEP = 2;

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
            int target = relState.getBaseline(e.playerId(), e.kingdomId());
            int nv = nudgeTowardTarget(v, target, STEP);


            if (nv != v) {
                relState.setRelationNoDirty(e.playerId(), e.kingdomId(), nv);
                anyChanged = true;
            }
        }

        if (anyChanged) {
            relState.setDirty(); // exactly once
        }
    }

    private static int nudgeTowardTarget(int v, int target, int step) {
        if (v < target) return Math.min(target, v + step);
        if (v > target) return Math.max(target, v - step);
        return v;
    }

}
