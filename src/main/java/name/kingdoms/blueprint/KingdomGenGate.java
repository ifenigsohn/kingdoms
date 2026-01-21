package name.kingdoms.blueprint;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-region gate to know whether a region is still generating satellites.
 * We use this to delay road generation until ALL satellites for that region
 * have finished (success OR fail).
 */
public final class KingdomGenGate {
    private KingdomGenGate() {}

    // roadsRegionKey -> active satellite tasks count
    private static final Map<Long, Integer> ACTIVE_BY_REGION = new HashMap<>();

    public static boolean isBusy(long regionKey) {
        Integer v = ACTIVE_BY_REGION.get(regionKey);
        return v != null && v > 0;
    }

    /** Call once per satellite task you actually enqueue. */
    public static void beginOne(long regionKey) {
        ACTIVE_BY_REGION.merge(regionKey, 1, Integer::sum);
    }

    /** Call once when a satellite task succeeds OR fails. */
    public static void oneSatelliteFinished(long regionKey) {
        Integer v = ACTIVE_BY_REGION.get(regionKey);
        if (v == null) return;
        int next = v - 1;
        if (next <= 0) ACTIVE_BY_REGION.remove(regionKey);
        else ACTIVE_BY_REGION.put(regionKey, next);
    }

    public static void reset() {
        ACTIVE_BY_REGION.clear();
    }

    // GLOBAL: only one kingdom pipeline at a time
    private static long ACTIVE_REGION = Long.MIN_VALUE;

    public static boolean hasActiveRegion() {
        return ACTIVE_REGION != Long.MIN_VALUE;
    }

    public static long activeRegion() {
        return ACTIVE_REGION;
    }

    public static boolean tryBeginRegion(long regionKey) {
        if (hasActiveRegion()) return false;
        ACTIVE_REGION = regionKey;
        return true;
    }

    public static void endRegion(long regionKey) {
        if (ACTIVE_REGION == regionKey) {
            ACTIVE_REGION = Long.MIN_VALUE;
        }
    }

}
