package name.kingdoms.diplomacy;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class AiDiplomacyEventState extends SavedData {

    private final List<AiDiplomacyEvent> events = new ArrayList<>();

    public static AiDiplomacyEventState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
            new SavedDataType<>(
                "kingdoms_ai_diplo_events",
                AiDiplomacyEventState::new,
                null,
                null
            )
        );
    }

    public void add(AiDiplomacyEvent e) {
        events.add(e);
        setDirty();
    }

    public List<AiDiplomacyEvent> getRecent(int max) {
        return events.size() <= max
                ? List.copyOf(events)
                : events.subList(events.size() - max, events.size());
    }
}
