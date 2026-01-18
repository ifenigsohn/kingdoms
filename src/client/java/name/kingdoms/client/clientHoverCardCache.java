package name.kingdoms.client;

import name.kingdoms.payload.kingdomHoverSyncS2CPayload;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class clientHoverCardCache {
    private clientHoverCardCache() {}

    public record Entry(kingdomHoverSyncS2CPayload payload, long receivedAtMs) {}

    private static final Map<UUID, Entry> CACHE = new HashMap<>();

    public static void put(kingdomHoverSyncS2CPayload p) {
        CACHE.put(p.kingdomId(), new Entry(p, System.currentTimeMillis()));
    }

    public static kingdomHoverSyncS2CPayload get(UUID id) {
        Entry e = CACHE.get(id);
        return e == null ? null : e.payload();
    }

    public static long ageMs(UUID id) {
        Entry e = CACHE.get(id);
        return e == null ? Long.MAX_VALUE : (System.currentTimeMillis() - e.receivedAtMs());
    }
}
