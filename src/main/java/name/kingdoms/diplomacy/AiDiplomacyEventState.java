package name.kingdoms.diplomacy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.List;

public final class AiDiplomacyEventState extends SavedData {

    private static final String DATA_NAME = "kingdoms_ai_diplo_events";

    public static final Codec<AiDiplomacyEventState> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            AiDiplomacyEvent.CODEC.listOf()
                    .fieldOf("events")
                    .orElse(List.of())
                    .forGetter(s -> s.events)
    ).apply(inst, AiDiplomacyEventState::new));

    public static final SavedDataType<AiDiplomacyEventState> TYPE =
            new SavedDataType<>(DATA_NAME, AiDiplomacyEventState::new, CODEC, null);

    private final List<AiDiplomacyEvent> events;

    public AiDiplomacyEventState() {
        this.events = new ArrayList<>();
    }

    private AiDiplomacyEventState(List<AiDiplomacyEvent> events) {
        this.events = new ArrayList<>(events);
    }

    public static AiDiplomacyEventState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public void add(AiDiplomacyEvent e) {
        events.add(e);

        // Optional: cap size so the save file doesn't grow forever
        int cap = 200;
        if (events.size() > cap) {
            events.subList(0, events.size() - cap).clear();
        }

        setDirty();
    }

    public List<AiDiplomacyEvent> getRecent(int max) {
        return events.size() <= max
                ? List.copyOf(events)
                : List.copyOf(events.subList(events.size() - max, events.size()));
    }
}
