package name.kingdoms.client;

import name.kingdoms.payload.kingdomHoverSyncS2CPayload;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class clientHoverCardCache {
    private clientHoverCardCache() {}

    private static final Map<UUID, kingdomHoverSyncS2CPayload> CACHE = new HashMap<>();

    public static void put(kingdomHoverSyncS2CPayload p) { CACHE.put(p.kingdomId(), p); }
    public static kingdomHoverSyncS2CPayload get(UUID id) { return CACHE.get(id); }
}
