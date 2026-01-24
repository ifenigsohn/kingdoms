package name.kingdoms.time;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public class kingdomCalendarState extends SavedData {

    // Stored date
    public int year;
    public int month; // 1..12
    public int day;   // 1..31 depending on month

    // For detecting Minecraft day transitions
    public long lastWorldDay = -1; // worldDay = (dayTime / 24000)

    public kingdomCalendarState() {
        // intentionally empty; init lazily
    }

    /* -----------------------------
       INIT / TICK
     ----------------------------- */

    public void ensureInitialized(MinecraftServer server) {
        if (year == 0) {
            // Random 4-digit start year
            RandomSource r = server.overworld().getRandom();
            year = 1000 + r.nextInt(9000);
            month = 1; // Jan
            day = 1;   // 1st
            setDirty();
        }
    }

    public void tick(ServerLevel overworld) {
        // Advance calendar when Minecraft day changes
        long worldDay = overworld.getDayTime() / 24000L;

        if (lastWorldDay < 0) {
            lastWorldDay = worldDay;
            setDirty();
            return;
        }

        long delta = worldDay - lastWorldDay;
        if (delta <= 0) return;

        // If someone sleeps / time skips, catch up properly
        for (long i = 0; i < delta; i++) {
            advanceOneDay();
        }

        lastWorldDay = worldDay;
        setDirty();
    }

    private static final int[] DAYS_IN_MONTH = new int[] {
            31, // Jan
            28, // Feb (no leap years for now)
            31, // Mar
            30, // Apr
            31, // May
            30, // Jun
            31, // Jul
            31, // Aug
            30, // Sep
            31, // Oct
            30, // Nov
            31  // Dec
    };

    private void advanceOneDay() {
        int dim = DAYS_IN_MONTH[month - 1];
        day++;
        if (day > dim) {
            day = 1;
            month++;
            if (month > 12) {
                month = 1;
                year++;
            }
        }
    }

    /* -----------------------------
       CODEC + ACCESS
     ----------------------------- */

    private static final Codec<kingdomCalendarState> CODEC =
            RecordCodecBuilder.create(inst -> inst.group(
                    Codec.INT.optionalFieldOf("year", 0).forGetter(s -> s.year),
                    Codec.INT.optionalFieldOf("month", 1).forGetter(s -> s.month),
                    Codec.INT.optionalFieldOf("day", 1).forGetter(s -> s.day),
                    Codec.LONG.optionalFieldOf("lastWorldDay", -1L).forGetter(s -> s.lastWorldDay)
            ).apply(inst, (y, m, d, lwd) -> {
                kingdomCalendarState s = new kingdomCalendarState();
                s.year = y;
                s.month = m;
                s.day = d;
                s.lastWorldDay = lwd;
                return s;
            }));

    private static final SavedDataType<kingdomCalendarState> TYPE =
            new SavedDataType<>(
                    "kingdoms_calendar",
                    kingdomCalendarState::new,
                    CODEC,
                    null
            );

    public static kingdomCalendarState get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return new kingdomCalendarState();
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }
}
