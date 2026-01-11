package name.kingdoms.blueprint;

public final class KingdomGenGate {
    private KingdomGenGate() {}

    private static int activeSatelliteTasks = 0;

    public static boolean isBusy() {
        return activeSatelliteTasks > 0;
    }

    /** Call once per satellite task you actually enqueue. */
    public static void beginOne() {
        activeSatelliteTasks++;
    }

    /** Call once when a satellite task succeeds OR fails. */
    public static void oneSatelliteFinished() {
        if (activeSatelliteTasks > 0) activeSatelliteTasks--;
    }

    public static void reset() {
        activeSatelliteTasks = 0;
    }
}
