package name.kingdoms.pressure;

import name.kingdoms.aiKingdomState;
import name.kingdoms.kingdomState;
import name.kingdoms.diplomacy.AllianceState;
import name.kingdoms.diplomacy.DiplomacyRelationsState;
import name.kingdoms.war.WarState;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.*;

public final class AiPressureTicker {
    private AiPressureTicker() {}

    // how often each AI tries
    private static final int INTERVAL_TICKS = 20 * 60 * 6; // 6 minutes
    private static final double JITTER_FRAC = 0.35;

    // global spam limiter: at most 1 AI pressure event on player per 3 minutes
    private static final long GLOBAL_PLAYER_CD = 20L * 60L * 3L;
    private static long nextAllowedGlobal = 0L;

    // per-causer spam limiter: each AI can poke player at most once per 12 minutes
    private static final long PER_CAUSER_CD = 20L * 60L * 12L;
    private static final Map<UUID, Long> nextAllowedByAi = new HashMap<>();

    private static final Map<UUID, Long> NEXT_DUE = new HashMap<>();

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(AiPressureTicker::tick);
    }

    private static long nextDelay(net.minecraft.util.RandomSource r) {
        int jitter = (int) (INTERVAL_TICKS * JITTER_FRAC);
        int delta = INTERVAL_TICKS + (jitter == 0 ? 0 : (r.nextInt(jitter * 2 + 1) - jitter));
        return Math.max(20, delta);
    }

    private static void tick(MinecraftServer server) {
        if (server == null) return;
        long nowTick = server.getTickCount();
        if (nowTick < nextAllowedGlobal) return;

        ServerLevel overworld = server.overworld();
        if (overworld == null) return;

        var ks = kingdomState.get(server);
        // You likely have one player kingdom per owner; for now we target "the calling player kingdom":
        // simplest: pick ANY player kingdom that exists (first one). If you support multiple, loop them later.
        kingdomState.Kingdom playerK = null;
        for (var k : ks.getAllKingdoms()) {
            if (k != null && k.owner != null) { playerK = k; break; }
        }
        if (playerK == null) return;

        var ai = aiKingdomState.get(server);
        var war = WarState.get(server);
        var alliance = AllianceState.get(server);

        // pick one AI due this tick (budget 1)
        UUID chosenAiId = null;
        for (var snap : ai.exportSnapshot().values()) {
            if (snap == null || snap.id() == null) continue;

            long due = NEXT_DUE.getOrDefault(snap.id(), 0L);
            if (due == 0L) {
                NEXT_DUE.put(snap.id(), nowTick + nextDelay(overworld.random));
                continue;
            }
            if (nowTick < due) continue;

            // per-causer cd
            long perAi = nextAllowedByAi.getOrDefault(snap.id(), 0L);
            if (nowTick < perAi) {
                NEXT_DUE.put(snap.id(), nowTick + nextDelay(overworld.random));
                continue;
            }

            chosenAiId = snap.id();
            break;
        }

        if (chosenAiId == null) return;

        // reschedule immediately (so it won't run repeatedly if apply fails)
        NEXT_DUE.put(chosenAiId, nowTick + nextDelay(overworld.random));

        var snap = ai.exportSnapshot().get(chosenAiId);
        if (snap == null) return;

        // Relationship + gates
        boolean atWar = war.isAtWar(playerK.id, chosenAiId);
        boolean allied = alliance.isAllied(playerK.id, chosenAiId);

        // Use your existing rel state: player UUID -> AI kingdomId
        // (Assumes playerK.owner != null)
        var relState = DiplomacyRelationsState.get(server);
        int baseRel = (playerK.owner == null) ? 0 : relState.getRelation(playerK.owner, chosenAiId);
        int rel = PressureUtil.effectiveRelation(server, baseRel, playerK.id, chosenAiId);

        // snapshot player stats (use what you have available)
        double econ = playerK.productionMultiplier(); // proxy
        double happiness = playerK.happiness();
        double security = playerK.securityValue();

        // personality
        var p = snap.personality();
        double aggression = (p == null) ? 0.35 : p.aggression();
        double generosity = (p == null) ? 0.50 : p.generosity();
        double greed = (p == null) ? 0.50 : p.greed();
        double prag = (p == null) ? 0.60 : p.pragmatism();

        // choose an action
        PressureCatalog.Template tpl = pickTemplate(overworld.random, rel, atWar, allied, econ, happiness, security,
                aggression, generosity, greed, prag);

        if (tpl == null) return;

        // Apply to player kingdom, with causer = AI kingdom id
        var ps = KingdomPressureState.get(server);

        // hard spam prevention: don't stack identical type from same AI if already active anywhere
        if (ps.hasActiveByCauser(chosenAiId, tpl.typeId(), nowTick)) return;

        // Determine scope: relation-only effects should usually be CAUSER_ONLY
        boolean hasRel = tpl.effects() != null && tpl.effects().get(KingdomPressureState.Stat.RELATIONS) != null;
        KingdomPressureState.RelScope scope = hasRel ? KingdomPressureState.RelScope.CAUSER_ONLY : KingdomPressureState.RelScope.GLOBAL;

        ps.addEvent(
                chosenAiId,
                playerK.id,
                tpl.typeId(),
                tpl.effects(),
                scope,
                nowTick,
                tpl.durationTicks()
        );

        ps.markDirty();

        // UI refresh (player will see it next time they open Events tab, or on next request)
        // If you have a way to push live to the owner, do it:
        // ServerPlayer owner = server.getPlayerList().getPlayer(playerK.owner);
        // if (owner != null) KingdomEventsNet.sendMyKingdomEvents(server, owner);

        // cooldowns
        nextAllowedGlobal = nowTick + GLOBAL_PLAYER_CD;
        nextAllowedByAi.put(chosenAiId, nowTick + PER_CAUSER_CD);
    }

    private static PressureCatalog.Template pickTemplate(
            net.minecraft.util.RandomSource r,
            int rel,
            boolean atWar,
            boolean allied,
            double econ,
            double happiness,
            double security,
            double aggression,
            double generosity,
            double greed,
            double pragmatism
    ) {
        // --- war-specific hostile actions
        if (atWar) {
            if (r.nextDouble() < (0.35 + 0.40 * aggression)) {
                return PressureCatalog.byTypeId("ai_border_raids");
            }
        }

        // --- allied help (especially if player is struggling)
        if (allied && rel > 30) {
            double need = 0.0;
            if (happiness < 4.0) need += 0.5;
            if (security < 0.35) need += 0.5;
            if (r.nextDouble() < (0.15 + 0.35 * generosity + 0.30 * pragmatism + 0.20 * need)) {
                return PressureCatalog.byTypeId("ai_aid_supplies");
            }
            if (r.nextDouble() < (0.10 + 0.25 * generosity)) {
                return PressureCatalog.byTypeId("ai_envoy_praise");
            }
        }

        // --- neutral/hostile meddling (not at war)
        if (!allied) {
            // if relation is low and aggression high -> bandits
            if (rel < -10 && r.nextDouble() < (0.10 + 0.30 * aggression)) {
                return PressureCatalog.byTypeId("ai_fund_bandits");
            }
            // embargo if relation low OR greed high and player economy is strong
            if ((rel < 0 || econ > 1.05) && r.nextDouble() < (0.08 + 0.25 * greed + 0.10 * (econ > 1.05 ? 1 : 0))) {
                return PressureCatalog.byTypeId("ai_trade_embargo");
            }
        }

        // fallback: small chance of praise if friendly
        if (rel > 50 && r.nextDouble() < (0.06 + 0.18 * generosity)) {
            return PressureCatalog.byTypeId("ai_envoy_praise");
        }

        // ====== AiPressureTicker: ADD PICK LOGIC (10 new options) ======
        // Paste this *inside* your pickTemplate(...) method after your existing war/allied/hostile sections.
        // Assumes you have: rel, atWar, allied, happiness, security, econ, aggression, generosity, greed, pragmatism, RandomSource r.
        // Use PressureCatalog.byTypeId("<id>") for selection.

            // --- extra WAR help from allies ---
            if (atWar && allied && rel > 20) {
                if (r.nextDouble() < (0.10 + 0.20 * generosity + 0.15 * pragmatism)) {
                    return PressureCatalog.byTypeId("ai_war_intel");
                }
                if (r.nextDouble() < (0.08 + 0.18 * generosity + 0.10 * pragmatism)) {
                    return PressureCatalog.byTypeId("ai_send_mercenaries");
                }
            }

            // --- allied support when you're struggling ---
            if (allied && rel > 25) {
                double need = 0.0;
                if (happiness < 4.0) need += 0.5;
                if (security < 0.35) need += 0.5;

                if (r.nextDouble() < (0.10 + 0.25 * generosity + 0.20 * pragmatism + 0.20 * need)) {
                    return PressureCatalog.byTypeId("ai_gift_grain");
                }
                if (r.nextDouble() < (0.08 + 0.18 * generosity + 0.12 * pragmatism)) {
                    return PressureCatalog.byTypeId("ai_training_advisors");
                }
                if (r.nextDouble() < (0.06 + 0.16 * generosity)) {
                    return PressureCatalog.byTypeId("ai_pilgrim_blessing");
                }
            }

            // --- non-allied hostile soft power ---
            if (!allied) {
                // rumors when relations are low (personality: aggression + a bit of greed)
                if (rel < 10 && r.nextDouble() < (0.06 + 0.20 * aggression + 0.10 * greed)) {
                    return PressureCatalog.byTypeId("ai_spread_rumors");
                }

                // spy network when relations are bad OR you're strong (pragmatic rivals)
                if ((rel < 0 || econ > 1.05) && r.nextDouble() < (0.05 + 0.16 * pragmatism + 0.08 * aggression)) {
                    return PressureCatalog.byTypeId("ai_spy_network");
                }

                // sabotage stores: more likely when at war, or very low relations
                if ((atWar || rel < -15) && r.nextDouble() < (0.06 + 0.18 * aggression)) {
                    return PressureCatalog.byTypeId("ai_sabotage_stores");
                }

                // bounty hunters: mid-hostile, not necessarily war
                if (rel < 5 && r.nextDouble() < (0.05 + 0.15 * aggression + 0.05 * pragmatism)) {
                    return PressureCatalog.byTypeId("ai_bounty_hunters");
                }

                // smuggler flood: greed-driven destabilization, especially if your security is already low
                double secWeak = (security < 0.35) ? 1.0 : 0.0;
                if (r.nextDouble() < (0.05 + 0.18 * greed + 0.08 * secWeak)) {
                    return PressureCatalog.byTypeId("ai_smuggler_flood");
                }
            }


        return null;
    }
}
