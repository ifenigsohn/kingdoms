package name.kingdoms.diplomacy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class DiplomacyCooldownState extends SavedData {
    private static final String DATA_NAME = "kingdoms_diplomacy_cooldowns";

    // key: fromKingdom|toKingdom|KIND  -> lastSentTick
    private final Map<String, Long> lastSent = new HashMap<>();

    public DiplomacyCooldownState() {}

    private DiplomacyCooldownState(Map<String, Long> decoded) {
        if (decoded != null) lastSent.putAll(decoded);
    }

    private static final Codec<Map<String, Long>> MAP_CODEC =
            Codec.unboundedMap(Codec.STRING, Codec.LONG);

    private static final Codec<DiplomacyCooldownState> STATE_CODEC =
            RecordCodecBuilder.create(inst -> inst.group(
                    MAP_CODEC.optionalFieldOf("lastSent", Map.of()).forGetter(s -> s.lastSent)
            ).apply(inst, DiplomacyCooldownState::new));

    public static final SavedDataType<DiplomacyCooldownState> TYPE =
            new SavedDataType<>(DATA_NAME, DiplomacyCooldownState::new, STATE_CODEC, null);

    public static DiplomacyCooldownState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    private static String key(UUID fromKingdomId, UUID toKingdomId, Letter.Kind kind) {
        return fromKingdomId + "|" + toKingdomId + "|" + kind.name();
    }

    public long getLastSent(UUID fromKingdomId, UUID toKingdomId, Letter.Kind kind) {
        Long v = lastSent.get(key(fromKingdomId, toKingdomId, kind));
        return v == null ? 0L : v;
    }

    public void markSent(UUID fromKingdomId, UUID toKingdomId, Letter.Kind kind, long nowTick) {
        lastSent.put(key(fromKingdomId, toKingdomId, kind), nowTick);
        setDirty();
    }
}
