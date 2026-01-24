package name.kingdoms.blueprint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class WorldgenToggleState extends SavedData {
    private static final String DATA_NAME = "kingdoms_worldgen_toggle";

    private boolean enabled = true;

    public WorldgenToggleState() {}

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        setDirty();
    }

    // --- codec/persistence ---
    private static final Codec<WorldgenToggleState> CODEC =
            RecordCodecBuilder.create(inst -> inst.group(
                    Codec.BOOL.optionalFieldOf("enabled", true).forGetter(s -> s.enabled)
            ).apply(inst, (en) -> {
                WorldgenToggleState s = new WorldgenToggleState();
                s.enabled = en;
                return s;
            }));

    public static final SavedDataType<WorldgenToggleState> TYPE =
            new SavedDataType<>(
                    DATA_NAME,
                    WorldgenToggleState::new,
                    CODEC,
                    null
            );

    public static WorldgenToggleState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }
}
