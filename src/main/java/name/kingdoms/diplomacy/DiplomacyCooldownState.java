package name.kingdoms.diplomacy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import name.kingdoms.diplomacy.Letter.Kind;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class DiplomacyCooldownState extends SavedData {
    private static final String DATA_NAME = "kingdoms_diplomacy_cooldowns";

    // key: fromKingdom|toKingdom|KIND -> nextAllowedTick
    private final Map<String, Long> nextAllowedUntil = new HashMap<>();

    public DiplomacyCooldownState() {}

    private DiplomacyCooldownState(Map<String, Long> decoded) {
        if (decoded != null) nextAllowedUntil.putAll(decoded);
    }

    private static String key(UUID fromKingdomId, UUID toKingdomId, Letter.Kind kind) {
        return fromKingdomId + "|" + toKingdomId + "|" + kind.name();
    }

    public long getNextAllowed(UUID fromKingdomId, UUID toKingdomId, Letter.Kind kind) {
        Long v = nextAllowedUntil.get(key(fromKingdomId, toKingdomId, kind));
        return v == null ? 0L : v;
    }



    // ✅ Accurate check: compare nowTick to stored "until"
    public boolean isOnCooldown(UUID fromKingdomId, UUID toKingdomId, Letter.Kind kind, long nowTick) {
        return nowTick < getNextAllowed(fromKingdomId, toKingdomId, kind);
    }

    public long remaining(UUID fromKingdomId, UUID toKingdomId, Letter.Kind kind, long nowTick) {
        long until = getNextAllowed(fromKingdomId, toKingdomId, kind);
        return Math.max(0L, until - nowTick);
    }

    // Lock in the exact cooldown that applied when you sent (normal vs in-person)
    public void markSent(UUID fromKingdomId, UUID toKingdomId, Letter.Kind kind, long nowTick, long cooldownTicks) {
        long until = nowTick + Math.max(0L, cooldownTicks);
        nextAllowedUntil.put(key(fromKingdomId, toKingdomId, kind), until);
        setDirty();
    }

    // --- CODEC / SavedData ---

    private static final Codec<Map<String, Long>> MAP_CODEC =
            Codec.unboundedMap(Codec.STRING, Codec.LONG);

    private static final Codec<DiplomacyCooldownState> STATE_CODEC =
            RecordCodecBuilder.create(inst -> inst.group(
                    MAP_CODEC.optionalFieldOf("nextAllowedUntil", Map.of())
                            .forGetter(s -> s.nextAllowedUntil)
            ).apply(inst, DiplomacyCooldownState::new));

    public static final SavedDataType<DiplomacyCooldownState> TYPE =
            new SavedDataType<>(DATA_NAME, DiplomacyCooldownState::new, STATE_CODEC, null);

    public static DiplomacyCooldownState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

}
