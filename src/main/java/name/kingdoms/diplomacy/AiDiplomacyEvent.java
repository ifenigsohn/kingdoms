package name.kingdoms.diplomacy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

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

    // 1.21.x: UUID + enums + primitives are straightforward codecs
    public static final Codec<AiDiplomacyEvent> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.xmap(UUID::fromString, UUID::toString)
                    .fieldOf("fromAi").forGetter(AiDiplomacyEvent::fromAi),
            Codec.STRING.xmap(UUID::fromString, UUID::toString)
                    .fieldOf("toAi").forGetter(AiDiplomacyEvent::toAi),
            Codec.STRING.xmap(Type::valueOf, Type::name)
                    .fieldOf("type").forGetter(AiDiplomacyEvent::type),
            Codec.LONG.fieldOf("gameTime").forGetter(AiDiplomacyEvent::gameTime),
            Codec.STRING.fieldOf("description").forGetter(AiDiplomacyEvent::description)
    ).apply(inst, AiDiplomacyEvent::new));
}
