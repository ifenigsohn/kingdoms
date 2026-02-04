package name.kingdoms.pressure;

import name.kingdoms.aiKingdomState;
import name.kingdoms.diplomacy.AllianceState;
import name.kingdoms.war.WarState;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.*;

public final class AiInterKingdomPressureTicker {
    private AiInterKingdomPressureTicker() {}

    // How often each AI kingdom tries to generate a diplomatic ripple
    private static final int BASE_INTERVAL_TICKS = 20 * 60 * 8; // 8 minutes
    private static final int JITTER_TICKS = 20 * 60 * 4;        // +/- 4 minutes

    // Global safety: at most one AI↔AI pressure event per 2 minutes
    private static final long GLOBAL_COOLDOWN_TICKS = 20L * 60L * 2L;
    private static long nextGlobalAllowed = 0L;

    // Per-kingdom scheduling
    private static final Map<UUID, Long> nextDueByKingdom = new HashMap<>();

    // Optional: a stable "world causer" constant if you ever need it
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(AiInterKingdomPressureTicker::tick);
    }

    private static long nextDelay(ServerLevel level) {
        int jitter = (JITTER_TICKS <= 0) ? 0 : level.random.nextInt(JITTER_TICKS * 2 + 1) - JITTER_TICKS;
        return Math.max(20, BASE_INTERVAL_TICKS + jitter);
    }

    private static void tick(MinecraftServer server) {
        if (server == null) return;
        long now = server.getTickCount();
        if (now < nextGlobalAllowed) return;

        ServerLevel level = server.overworld();
        if (level == null) return;

        var aiState = aiKingdomState.get(server);
        if (aiState == null || aiState.kingdoms == null || aiState.kingdoms.size() < 2) return;

        // Pick one "speaker" AI kingdom that is due
        UUID aId = null;
        for (var aiK : aiState.kingdoms.values()) {
            if (aiK == null || aiK.id == null) continue;

            long due = nextDueByKingdom.getOrDefault(aiK.id, 0L);
            if (due == 0L) {
                nextDueByKingdom.put(aiK.id, now + nextDelay(level));
                continue;
            }
            if (now < due) continue;

            aId = aiK.id;
            break;
        }
        if (aId == null) return;

        // Reschedule immediately (prevents multiple fires same tick)
        nextDueByKingdom.put(aId, now + nextDelay(level));

        // Choose a different AI kingdom as the "target"
        UUID bId = pickOtherAi(aiState, level, aId);
        if (bId == null) return;

        boolean atWar = false;
        boolean allied = false;
        try { atWar = WarState.get(server).isAtWar(aId, bId); } catch (Throwable ignored) {}
        try { allied = AllianceState.get(server).isAllied(aId, bId); } catch (Throwable ignored) {}

        // Pick an effect type based on war/alliance with small randomness
        EffectKind kind = chooseEffectKind(level, atWar, allied);

        // Apply the effect as CAUSER_ONLY pressure between A and B (both directions)
        applyInterKingdomEffect(server, level, aId, bId, kind, now);

        // Global cooldown
        nextGlobalAllowed = now + GLOBAL_COOLDOWN_TICKS;
    }

    private static UUID pickOtherAi(aiKingdomState aiState, ServerLevel level, UUID aId) {
        // Collect candidate IDs
        var ids = new ArrayList<UUID>(aiState.kingdoms.size());
        for (var aiK : aiState.kingdoms.values()) {
            if (aiK == null || aiK.id == null) continue;
            if (aiK.id.equals(aId)) continue;
            ids.add(aiK.id);
        }
        if (ids.isEmpty()) return null;

        return ids.get(level.random.nextInt(ids.size()));
    }

    private enum EffectKind {
        PRAISE, SLANDER, TRADE_DISPUTE, BORDER_INCIDENT, ENVOYS
    }

    private static EffectKind chooseEffectKind(ServerLevel level, boolean atWar, boolean allied) {
        float r = level.random.nextFloat();

        if (atWar) {
            // war => more negative drift
            if (r < 0.55f) return EffectKind.BORDER_INCIDENT;
            if (r < 0.85f) return EffectKind.SLANDER;
            return EffectKind.TRADE_DISPUTE;
        }

        if (allied) {
            // allies => more positive drift
            if (r < 0.45f) return EffectKind.ENVOYS;
            if (r < 0.80f) return EffectKind.PRAISE;
            return EffectKind.TRADE_DISPUTE; // allies can still squabble
        }

        // neutral => mixed
        if (r < 0.25f) return EffectKind.PRAISE;
        if (r < 0.50f) return EffectKind.SLANDER;
        if (r < 0.75f) return EffectKind.TRADE_DISPUTE;
        return EffectKind.BORDER_INCIDENT;
    }

    private static void applyInterKingdomEffect(MinecraftServer server, ServerLevel level,
                                               UUID aId, UUID bId, EffectKind kind, long now) {
        var ps = KingdomPressureState.get(server);
        if (ps == null) return;

        // Pick template/effects
        String typeId;
        EnumMap<KingdomPressureState.Stat, Double> eff = new EnumMap<>(KingdomPressureState.Stat.class);
        int relDelta = 0;

        switch (kind) {
            case PRAISE -> {
                typeId = "ai_gossip_praise"; // optional template id
                relDelta = +10;
            }
            case SLANDER -> {
                typeId = "ai_gossip_slander";
                relDelta = -10;
            }
            case ENVOYS -> {
                typeId = "ai_envoys_between_kings";
                relDelta = +6;
            }
            case TRADE_DISPUTE -> {
                typeId = "ai_trade_dispute";
                relDelta = -6;
                // small econ “feel” in AI fuzz below
            }
            default -> { // BORDER_INCIDENT
                typeId = "ai_border_incident";
                relDelta = -8;
                // small security “feel” in AI fuzz below
            }
        }

        eff.put(KingdomPressureState.Stat.RELATIONS, (double) relDelta);

        long dur = 12L * PressureCatalog.MINUTE;

        // Cooldown: don't stack the same effect while active for this pair
        // (hasActiveByCauser checks "causer has this typeId active anywhere")
        if (ps.hasActiveByCauser(aId, typeId, now) || ps.hasActiveByCauser(bId, typeId, now)) {
            return;
        }

        // A's opinion of B (CAUSER_ONLY, causer=B)
        ps.addEvent(
                bId,            // causer
                aId,            // causee
                typeId,
                eff,
                KingdomPressureState.RelScope.CAUSER_ONLY,
                now,
                dur
        );

        // B's opinion of A (CAUSER_ONLY, causer=A)
        ps.addEvent(
                aId,
                bId,
                typeId,
                eff,
                KingdomPressureState.RelScope.CAUSER_ONLY,
                now,
                dur
        );

        ps.markDirty();

        // Optional immediate “fuzz” on AI state (makes hover menu feel it)
        applyAiFuzz(server, aId, kind, relDelta);
        applyAiFuzz(server, bId, kind, relDelta);
    }

    private static void applyAiFuzz(MinecraftServer server, UUID kid, EffectKind kind, int relDelta) {
        var aiState = aiKingdomState.get(server);
        var aiK = aiState.getById(kid);
        if (aiK == null) return;

        // Tiny, safe nudges so it's noticeable but not destructive:
        // - border incident => security down
        // - trade dispute => gold down slightly
        // - praise/envoys => happiness up slightly
        // - slander => happiness down slightly
        switch (kind) {
            case BORDER_INCIDENT -> aiK.security = net.minecraft.util.Mth.clamp(aiK.security - 2, 0, 100);
            case TRADE_DISPUTE -> aiK.gold = Math.max(0.0, aiK.gold * 0.985); // -1.5%
            case PRAISE, ENVOYS -> aiK.happiness = net.minecraft.util.Mth.clamp(aiK.happiness + 2, 0, 100);
            case SLANDER -> aiK.happiness = net.minecraft.util.Mth.clamp(aiK.happiness - 2, 0, 100);
        }

        // If relations are strongly negative, small chance of soldier attrition (tension)
        if (relDelta <= -8 && server.overworld().random.nextFloat() < 0.10f) {
            aiK.aliveSoldiers = Math.max(0, aiK.aliveSoldiers - 1);
        }

        aiState.markDirty();
    }
}
