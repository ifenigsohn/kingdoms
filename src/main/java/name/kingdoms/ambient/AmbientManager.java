package name.kingdoms.ambient;

import name.kingdoms.kingdomState;
import name.kingdoms.entity.ai.aiKingdomNPCEntity;
import name.kingdoms.war.WarState;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AmbientManager {
    private AmbientManager() {}

    private static final Map<UUID, Long> NEXT_DUE = new HashMap<>();
    private static final Map<UUID, String> LAST_EVENT = new HashMap<>();

    private record PendingSpeech(long dueTick, UUID speakerId, UUID playerId, String title, String line, int ttlTicks) {}
    private static final List<PendingSpeech> PENDING_SPEECH = new ArrayList<>();

    public static void queueSpeech(MinecraftServer server, UUID speakerId, UUID playerId, String title, String line, int ttlTicks, int delayTicks) {
        if (line == null || line.isBlank()) return;
        PENDING_SPEECH.add(new PendingSpeech(server.getTickCount() + delayTicks, speakerId, playerId, title, line, ttlTicks));
    }


    // “One scene every ~2–4 minutes” feels KCD-ish. Tune as you like.
    private static final int PULSE_TICKS = 20 * 60 * 3; // DEV PLAYTEST DEBUG

    public static void tick(MinecraftServer server) {
        long now = server.getTickCount();

        // ---- deliver delayed speech (helps because entity tracking is not instant) ----
        for (Iterator<PendingSpeech> it = PENDING_SPEECH.iterator(); it.hasNext();) {
            PendingSpeech ps = it.next();
            if (now < ps.dueTick()) continue;
            it.remove();

            ServerPlayer player = server.getPlayerList().getPlayer(ps.playerId());
            if (player == null || !(player.level() instanceof ServerLevel pl)) continue;

            Entity e = pl.getEntity(ps.speakerId());
            if (e instanceof aiKingdomNPCEntity npc) {
                npc.queueAmbientSpeech(player, ps.title(), ps.line(), ps.ttlTicks());
            } else {
                // Fallback: always show *something* even if entity isn't found/tracked
                player.displayClientMessage(Component.literal(ps.title() + ": " + ps.line()), false);
            }
        }


        var ks = kingdomState.get(server);
        var war = WarState.get(server);

        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (!(sp.level() instanceof ServerLevel level)) continue;

            long due = NEXT_DUE.getOrDefault(sp.getUUID(), -1L);
            if (due < 0) {
                // stagger first run across the first pulse window
                NEXT_DUE.put(sp.getUUID(), now + level.random.nextInt(PULSE_TICKS));
                continue;
            }
            if (now < due) continue;

            // schedule next run (with jitter)
            int jitter = (int) (PULSE_TICKS * 0.30);
            long next = now + PULSE_TICKS + (jitter == 0 ? 0 : (level.random.nextInt(jitter * 2 + 1) - jitter));
            NEXT_DUE.put(sp.getUUID(), next);

            AmbientContext ctx = AmbientContext.build(server, level, sp, ks, war);

            // HARD RULE: never spawn ambient inside war zones
            if (ctx.inWarZone()) continue;

            AmbientEvent ev = AmbientEvents.pick(ctx, LAST_EVENT.get(sp.getUUID()));
            if (ev == null) continue;

            LAST_EVENT.put(sp.getUUID(), ev.id());

            ev.run(ctx);

            for (var eff : ev.effects(ctx)) {
                try { eff.apply(ctx); } catch (Throwable ignored) {}
            }
        }
    }
}
