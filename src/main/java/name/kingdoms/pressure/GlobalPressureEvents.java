package name.kingdoms.pressure;

import name.kingdoms.kingdomState;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.UUID;

public final class GlobalPressureEvents {
    private GlobalPressureEvents() {}

    // Roll once per MC day near dawn (tune)
    private static long lastRolledDay = -1;

    // Low chance per day. 0.01 = 1% per day.
    private static final double CHANCE_PER_DAY = 0.05;

    // Prevent back-to-back globals even if RNG hits (hard cooldown)
    private static final long GLOBAL_COOLDOWN_TICKS = 20L * 60L * 30L; // 30 minutes
    private static long nextAllowedTick = 0L;

    private static final UUID GLOBAL_CAUSER = new UUID(0L, 0L);

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(GlobalPressureEvents::tick);
    }

    private static void tick(MinecraftServer server) {
        if (server == null) return;
        long nowTick = server.getTickCount();
        if (nowTick < nextAllowedTick) return;

        var overworld = server.overworld();
        if (overworld == null) return;

        // once per day at dawn window
        long dayTime = overworld.getDayTime();
        long day = dayTime / 24000L;
        long timeOfDay = dayTime % 24000L;

        // choose a small window to roll (dawn-ish)
        if (timeOfDay < 1000L || timeOfDay > 1200L) return;

        if (day == lastRolledDay) return;
        lastRolledDay = day;

        // roll chance
        if (overworld.random.nextDouble() > CHANCE_PER_DAY) return;

        // pick one template
        PressureCatalog.Template tpl = pickGlobalTemplate(overworld.random.nextInt(5));
        if (tpl == null) return;

        // apply to ALL kingdoms
        var ks = kingdomState.get(server);
        var ps = KingdomPressureState.get(server);

        for (var k : ks.getAllKingdoms()) {
            if (k == null || k.id == null) continue;

            // don’t stack the same global event if already active on this kingdom
            if (ps.hasActiveEvent(k.id, tpl.typeId(), null, null, nowTick)) continue;

            ps.addEvent(
                    GLOBAL_CAUSER,
                    k.id,
                    tpl.typeId(),
                    tpl.effects(),
                    KingdomPressureState.RelScope.GLOBAL,
                    nowTick,
                    tpl.durationTicks()
            );
        }

        ps.markDirty();

        // cooldown after firing
        nextAllowedTick = nowTick + GLOBAL_COOLDOWN_TICKS;
    }

    private static PressureCatalog.Template pickGlobalTemplate(int r) {
        return switch (r) {
            case 0 -> PressureCatalog.byTypeId("global_plague");
            case 1 -> PressureCatalog.byTypeId("global_bountiful_harvest");
            case 2 -> PressureCatalog.byTypeId("global_bandit_wave");
            case 3 -> PressureCatalog.byTypeId("global_festival");
            default -> PressureCatalog.byTypeId("global_drought");
        };
    }
}
