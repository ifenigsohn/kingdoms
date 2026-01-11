package name.kingdoms.diplomacy;

import net.minecraft.util.RandomSource;

public final class PeaceEvaluator {
    private PeaceEvaluator() {}

    public enum Decision { ACCEPT, REFUSE }

    /**
     * Decide whether to accept peace based ONLY on strength:
     * alive/total ratio for each side.
     */
    public static Decision decideAccept(
            RandomSource rng,
            int aiAlive, int aiTotal,
            int enemyAlive, int enemyTotal
    ) {
        double aiFrac = frac(aiAlive, aiTotal);
        double enFrac = frac(enemyAlive, enemyTotal);

        // ratio < 1 means AI is doing worse than enemy
        double ratio = aiFrac / Math.max(0.01, enFrac);

        // If AI is crushed, accept almost always
        if (ratio < 0.45) return Decision.ACCEPT;

        // If AI is winning hard, refuse almost always
        if (ratio > 1.25) return Decision.REFUSE;

        // Middle ground: probabilistic
        double acceptChance = clamp01(0.15 + (1.0 - ratio) * 0.85);

        return (rng.nextDouble() < acceptChance) ? Decision.ACCEPT : Decision.REFUSE;
    }

    /**
     * Should AI send peace proactively?
     * Slightly more conservative than accept.
     */
    public static boolean shouldOfferPeace(
            RandomSource rng,
            int aiAlive, int aiTotal,
            int enemyAlive, int enemyTotal
    ) {
        double aiFrac = frac(aiAlive, aiTotal);
        double enFrac = frac(enemyAlive, enemyTotal);
        double ratio = aiFrac / Math.max(0.01, enFrac);

        // only offer if losing or roughly even
        if (ratio > 1.05) return false;

        double chance = clamp01(0.10 + (1.0 - ratio) * 0.70);
        if (ratio < 0.55) chance = Math.max(chance, 0.60);

        return rng.nextDouble() < chance;
    }

    private static double frac(int a, int b) {
        return (double)Math.max(0, a) / (double)Math.max(1, b);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
