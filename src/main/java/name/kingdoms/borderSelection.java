package name.kingdoms;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class borderSelection {
    private borderSelection() {}

    public record Selection(@Nullable BlockPos first, @Nullable BlockPos second) {}

    private static final Map<UUID, Selection> selections = new HashMap<>();

    public static void setFirst(UUID player, BlockPos pos) {
        Selection cur = selections.get(player);
        selections.put(player, new Selection(pos, cur == null ? null : cur.second()));
    }

    public static void setSecond(UUID player, BlockPos pos) {
        Selection cur = selections.get(player);
        selections.put(player, new Selection(cur == null ? null : cur.first(), pos));
    }

    public static @Nullable Selection get(UUID player) {
        return selections.get(player);
    }

    public static void clear(UUID player) {
        selections.remove(player);
    }
}
