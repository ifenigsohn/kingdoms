package name.kingdoms.diplomacy;

import java.util.UUID;

public record AiDiplomacyEvent(
        UUID fromAi,
        UUID toAi,
        Type type,
        long gameTime,
        String description
) {
    public enum Type {
        WAR_DECLARED,
        PEACE_SIGNED,
        ALLIANCE_FORMED,
        ALLIANCE_BROKEN
    }
}
