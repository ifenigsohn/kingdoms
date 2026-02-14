package name.kingdoms.war;

import name.kingdoms.Kingdoms;
import name.kingdoms.aiKingdomState;
import name.kingdoms.kingdomState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

import java.util.UUID;

/**
 * One authoritative place for "war ends" consequences.
 * Used by BOTH: AI-AI sim endings AND player battle endings.
 */
public final class WarPeaceEffects {
    private WarPeaceEffects() {}

    public enum PeaceType {
        WHITE_PEACE,
        SURRENDER
    }

    public record SettlementResult(int goldTaken, int woodTaken, int metalTaken, boolean puppetCreated) {}

    // Tuning knobs (start conservative)
    private static final float WHITE_PEACE_TAKE_MIN = 0.02f; // 2%
    private static final float WHITE_PEACE_TAKE_MAX = 0.06f; // 6%

    private static final float SURRENDER_TAKE_MIN = 0.08f; // 8%
    private static final float SURRENDER_TAKE_MAX = 0.18f; // 18%

    // Puppet chance (only on surrender by default)
    private static final float SURRENDER_PUPPET_CHANCE = 0.22f;

    /** Apply settlement immediately. Safe for AI or player kingdoms. */
    public static SettlementResult apply(MinecraftServer server, UUID winner, UUID loser, PeaceType type) {
        if (server == null || winner == null || loser == null) return new SettlementResult(0,0,0,false);
        if (winner.equals(loser)) return new SettlementResult(0,0,0,false);

        RandomSource rng = server.overworld().getRandom();

        // Puppet attempt first (optional)
        boolean puppet = false;

        if (type == PeaceType.SURRENDER && rng.nextFloat() < SURRENDER_PUPPET_CHANCE) {
            var ks = kingdomState.get(server);

            // If loser was the master of winner, this is a rebellion success -> free winner instead of re-puppeting
            UUID masterOfWinner = ks.getMasterOf(winner);
            if (masterOfWinner != null && masterOfWinner.equals(loser)) {
                ks.clearPuppet(winner);
                puppet = false; // no new puppet created in this case
            } else {
                ks.clearPuppet(loser);                 // prevent chains / stale data
                puppet = ks.setPuppet(loser, winner);  // true if applied
            }

            ks.markDirty();
        }


        float pct = switch (type) {
            case WHITE_PEACE -> Mth.nextFloat(rng, WHITE_PEACE_TAKE_MIN, WHITE_PEACE_TAKE_MAX);
            case SURRENDER -> Mth.nextFloat(rng, SURRENDER_TAKE_MIN, SURRENDER_TAKE_MAX);
        };

        // If puppet succeeded, reduce plunder a lot (keeps it from being too punishing)
        if (puppet) pct *= 0.35f;

        int loserGold  = readResource(server, loser, "gold");
        int loserWood  = readResource(server, loser, "wood");
        int loserMetal = readResource(server, loser, "metal");

        int takeGold  = Math.max(0, Math.round(loserGold  * pct));
        int takeWood  = Math.max(0, Math.round(loserWood  * pct));
        int takeMetal = Math.max(0, Math.round(loserMetal * pct));

        // Clamp so we never go negative
        takeGold  = Mth.clamp(takeGold,  0, loserGold);
        takeWood  = Mth.clamp(takeWood,  0, loserWood);
        takeMetal = Mth.clamp(takeMetal, 0, loserMetal);

        if (takeGold > 0 || takeWood > 0 || takeMetal > 0) {
            addResource(server, loser, "gold",  -takeGold);
            addResource(server, loser, "wood",  -takeWood);
            addResource(server, loser, "metal", -takeMetal);

            addResource(server, winner, "gold",  +takeGold);
            addResource(server, winner, "wood",  +takeWood);
            addResource(server, winner, "metal", +takeMetal);
        }

        Kingdoms.LOGGER.info("[WarPeaceEffects] type={} winner={} loser={} take(g/w/m)=({}/{}/{}) puppet={}",
                type, winner, loser, takeGold, takeWood, takeMetal, puppet
        );

        return new SettlementResult(takeGold, takeWood, takeMetal, puppet);
    }

    // ---------------------------
    // Resource read/write helpers (NO reflection)
    // ---------------------------

    private static int readResource(MinecraftServer server, UUID kingdomId, String field) {
        // AI kingdom?
        var aiState = aiKingdomState.get(server);
        var ai = aiState.getById(kingdomId);
        if (ai != null) {
            return switch (field) {
                case "gold"  -> ai.goldInt();
                case "wood"  -> ai.woodInt();
                case "metal" -> ai.metalInt();
                default -> 0;
            };
        }

        // Player kingdom?
        var ks = kingdomState.get(server);
        var k = ks.getKingdom(kingdomId);
        if (k != null) {
            return switch (field) {
                case "gold"  -> k.goldInt();
                case "wood"  -> k.woodInt();
                case "metal" -> k.metalInt();
                default -> 0;
            };
        }

        return 0;
    }

    private static void addResource(MinecraftServer server, UUID kingdomId, String field, int delta) {
        // AI kingdom?
        var aiState = aiKingdomState.get(server);
        var ai = aiState.getById(kingdomId);
        if (ai != null) {
            switch (field) {
                case "gold" -> ai.setGoldInt(ai.goldInt() + delta);
                case "wood" -> ai.setWoodInt(ai.woodInt() + delta);
                case "metal" -> ai.setMetalInt(ai.metalInt() + delta);
                default -> {}
            }
            aiState.setDirty();
            return;
        }

        // Player kingdom?
        var ks = kingdomState.get(server);
        var k = ks.getKingdom(kingdomId);
        if (k != null) {
            switch (field) {
                case "gold" -> k.setGoldInt(k.goldInt() + delta);
                case "wood" -> k.setWoodInt(k.woodInt() + delta);
                case "metal" -> k.setMetalInt(k.metalInt() + delta);
                default -> {}
            }
            ks.setDirty(); // (you have markDirty(); setDirty() also exists via SavedData
            return;
        }
    }

}
