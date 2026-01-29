package name.kingdoms.diplomacy;

public final class DiplomacyCooldowns {
    private DiplomacyCooldowns() {}

    // 25% faster in-person => 75% of normal
    public static final double IN_PERSON_MULT = 0.75;

    /** Base cooldown in ticks for each mail kind. */
    public static long baseTicks(Letter.Kind kind) {
        return switch (kind) {
            case OFFER, REQUEST, CONTRACT -> 20L * 60L * 2L;     // 2 min
            case COMPLIMENT              -> 20L * 60L * 8L;     // 1 min (fix to what you actually want)
            case WARNING                 -> 20L * 60L * 4L;     // 4 min (or change)
            case INSULT                  -> 20L * 60L * 3L;     // 3 min 
            case ALLIANCE_PROPOSAL       -> 20L * 60L * 10L;    // 10 min
            case ALLIANCE_BREAK          -> 20L * 60L * 10L;    // 10 min
            case ULTIMATUM               -> 20L * 60L * 15L;    // 15 min
            case WAR_DECLARATION         -> 20L * 60L * 20L;    // 20 min
            case WHITE_PEACE, SURRENDER  -> 20L * 60L * 3L;     // 3 min
            default                      -> 20L * 60L * 2L;     // fallback
        };
    }

    public static long ticksFor(Letter.Kind kind, boolean inPerson) {
        long base = baseTicks(kind);
        if (!inPerson) return base;
        return Math.max(20L, (long) Math.floor(base * IN_PERSON_MULT)); // never < 1s
    }

    public static String fmtTicks(long ticks) {
        long sec = (ticks + 19) / 20;
        if (sec < 60) return sec + "s";
        long min = sec / 60;
        long rem = sec % 60;
        return (rem == 0) ? (min + "m") : (min + "m " + rem + "s");
    }
}
