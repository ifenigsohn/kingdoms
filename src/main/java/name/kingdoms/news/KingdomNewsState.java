package name.kingdoms.news;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

public final class KingdomNewsState extends SavedData {

    // -------------------------------------------------
    // Tuning
    // -------------------------------------------------
    private static final int MAX = 400; // keep last N news items
    private static final long TTL_MS = 3L * 60L * 60L * 1000L; // 3 hours
    private static final String DEFAULT_DIM = "minecraft:overworld";

    /**
     * News entry.
     *
     * Backwards compatible:
     * - old saves only have {tick,text}
     * - new fields are optional with defaults
     */
    public record Entry(
            long tick,
            String text,

            // Location for distance filtering (XZ is enough)
            String dimId,
            int x,
            int z,

            // Wall-clock timestamp for TTL pruning
            long createdAtMs
    ) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Codec.LONG.fieldOf("tick").forGetter(Entry::tick),
                Codec.STRING.fieldOf("text").forGetter(Entry::text),

                Codec.STRING.optionalFieldOf("dim", DEFAULT_DIM).forGetter(Entry::dimId),
                Codec.INT.optionalFieldOf("x", 0).forGetter(Entry::x),
                Codec.INT.optionalFieldOf("z", 0).forGetter(Entry::z),

                Codec.LONG.optionalFieldOf("createdAtMs", 0L).forGetter(Entry::createdAtMs)
        ).apply(inst, Entry::new));
    }

    private final List<Entry> entries = new ArrayList<>();

    public KingdomNewsState() {}

    private KingdomNewsState(List<Entry> loaded) {
        if (loaded != null) entries.addAll(loaded);
    }

    private static final Codec<List<Entry>> LIST_CODEC = Entry.CODEC.listOf();

    private static final Codec<KingdomNewsState> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            LIST_CODEC.optionalFieldOf("entries", List.of()).forGetter(s -> s.entries)
    ).apply(inst, KingdomNewsState::new));

    public static final SavedDataType<KingdomNewsState> TYPE =
            new SavedDataType<>("kingdoms_news", KingdomNewsState::new, CODEC, null);

    public static KingdomNewsState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // -------------------------------------------------
    // Adding news
    // -------------------------------------------------

    /** Old callsite compatibility: no location, no ttl timestamp. */
    public void add(long tick, String text) {
        add(tick, text, DEFAULT_DIM, 0, 0, System.currentTimeMillis());
    }

    /** Preferred callsite: include source position (AI origin/terminal) and dimension. */
    public void add(long tick, String text, ServerLevel level, int x, int z) {
        String dim = level.dimension().location().toString();
        add(tick, text, dim, x, z, System.currentTimeMillis());
    }

    public void add(long tick, String text, String dimId, int x, int z, long createdAtMs) {
        if (text == null || text.isBlank()) return;
        if (dimId == null || dimId.isBlank()) dimId = DEFAULT_DIM;
        if (createdAtMs <= 0L) createdAtMs = System.currentTimeMillis();

        // validate dimension id formatting a bit (won't crash if bad)
        try { ResourceLocation.tryParse(dimId); } catch (Throwable ignored) {}

        entries.add(new Entry(tick, text, dimId, x, z, createdAtMs));

        // trim oldest
        if (entries.size() > MAX) {
            entries.subList(0, entries.size() - MAX).clear();
        }

        setDirty();
    }

    // -------------------------------------------------
    // TTL pruning (3 hours)
    // -------------------------------------------------

    /**
     * Ensures old entries loaded from disk get a timestamp the first time we touch them.
     * This prevents "createdAtMs=0" from instantly expiring.
     */
    private void normalizeMissingTimestamps(long nowMs) {
        boolean changed = false;
        ListIterator<Entry> it = entries.listIterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (e.createdAtMs() == 0L) {
                it.set(new Entry(e.tick(), e.text(), e.dimId(), e.x(), e.z(), nowMs));
                changed = true;
            }
        }
        if (changed) setDirty();
    }

    public void pruneExpired() {
        pruneExpired(System.currentTimeMillis());
    }

    public void pruneExpired(long nowMs) {
        normalizeMissingTimestamps(nowMs);

        boolean changed = false;
        Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if ((nowMs - e.createdAtMs()) > TTL_MS) {
                it.remove();
                changed = true;
            }
        }
        if (changed) setDirty();
    }

    // -------------------------------------------------
    // Querying
    // -------------------------------------------------

    /** Old behavior: latest N (no pruning, no distance). */
    public List<Entry> latest(int limit) {
        int n = Math.max(0, Math.min(limit, entries.size()));
        return entries.subList(entries.size() - n, entries.size());
    }

    /**
     * Latest entries within radius (XZ) of an anchor, same dimension.
     * Also prunes expired entries first.
     */
    public List<Entry> latestNear(String anchorDimId, int ax, int az, int radiusBlocks, int limit) {
        long now = System.currentTimeMillis();
        pruneExpired(now);

        if (anchorDimId == null || anchorDimId.isBlank()) anchorDimId = DEFAULT_DIM;

        long r2 = (long) radiusBlocks * (long) radiusBlocks;

        ArrayList<Entry> out = new ArrayList<>(Math.min(limit, 64));

        // iterate from newest to oldest until we collect 'limit'
        for (int i = entries.size() - 1; i >= 0 && out.size() < limit; i--) {
            Entry e = entries.get(i);

            if (!anchorDimId.equals(e.dimId())) continue;

            long dx = (long) e.x() - ax;
            long dz = (long) e.z() - az;
            if ((dx * dx + dz * dz) > r2) continue;

            out.add(e);
        }

        // out is newest->older; UI might want oldest->newest, so reverse if needed at send time
        return out;
    }
}
