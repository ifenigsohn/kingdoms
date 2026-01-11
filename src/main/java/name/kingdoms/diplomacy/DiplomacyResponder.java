package name.kingdoms.diplomacy;

import name.kingdoms.aiKingdomState;
import net.minecraft.util.RandomSource;

import java.util.UUID;

public final class DiplomacyResponder {

    private DiplomacyResponder() {}

    public static Letter makeDebugResponse(aiKingdomState ai, UUID aiId, UUID playerId, Letter playerLetter, long nowTick) {
        aiKingdomState.AiKingdom k = ai.getById(aiId);
        if (k == null) return null;

        String fromName = (k.name != null && !k.name.isBlank()) ? k.name : "Unknown Kingdom";
        long expires = nowTick + (20L * 60L * 10L);

        // Debug: 70% accept-ish, 30% refuse-ish
        RandomSource r = RandomSource.create(nowTick ^ aiId.getMostSignificantBits());
        boolean accept = r.nextInt(100) < 70;

        if (accept) {
            return Letter.compliment(aiId, true, fromName, playerId, nowTick, expires,
                    "We will consider it.");
        } else {
            return Letter.insult(aiId, true, fromName, playerId, nowTick, expires,
                    "No.");
        }
    }
}
