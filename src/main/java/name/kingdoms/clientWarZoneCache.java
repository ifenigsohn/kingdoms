package name.kingdoms;

import java.util.List;
import java.util.UUID;

public final class clientWarZoneCache {
    private clientWarZoneCache() {}

    public record Zone(UUID enemyId, String enemyName, int minX, int minZ, int maxX, int maxZ) {}

    // Replace the whole list on sync (no concurrent modification while rendering)
    public static volatile List<Zone> ZONES = List.of();
    public static volatile long lastSyncMs = 0;

    public static void setAll(List<Zone> zones) {
        ZONES = List.copyOf(zones);
        lastSyncMs = System.currentTimeMillis();
    }
}
