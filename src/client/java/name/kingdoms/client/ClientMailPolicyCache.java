package name.kingdoms.client;

import name.kingdoms.diplomacy.Letter;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ClientMailPolicyCache {
    public record Decision(boolean allowed, String reason) {}

    private static final Map<UUID, EnumMap<Letter.Kind, Decision>> byRecipient = new HashMap<>();

    private ClientMailPolicyCache() {}

    public static void put(UUID toKingdomId, EnumMap<Letter.Kind, Decision> map) {
        if (toKingdomId == null || map == null) return;
        byRecipient.put(toKingdomId, map);
    }

    public static Decision get(UUID toKingdomId, Letter.Kind kind) {
        if (toKingdomId == null || kind == null) return null;
        var m = byRecipient.get(toKingdomId);
        if (m == null) return null;
        return m.get(kind);
    }
}
