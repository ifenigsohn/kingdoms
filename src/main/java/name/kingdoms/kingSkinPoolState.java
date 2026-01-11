package name.kingdoms;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.List;

public class kingSkinPoolState extends SavedData {

    // king_0.png .. king_20.png
    public static final int MAX_SKIN_ID = 20;
    private static final int SKIN_COUNT = MAX_SKIN_ID + 1;

    public static final Codec<kingSkinPoolState> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.INT.listOf().optionalFieldOf("bag", List.of()).forGetter(s -> s.bag)
    ).apply(inst, kingSkinPoolState::new));

    private static final SavedDataType<kingSkinPoolState> TYPE =
            new SavedDataType<>(
                    "kingdoms_king_skin_pool",
                    kingSkinPoolState::new,
                    CODEC,
                    null
            );

    private List<Integer> bag = new ArrayList<>();

    public kingSkinPoolState() {}

    private kingSkinPoolState(List<Integer> bag) {
        this.bag = new ArrayList<>(bag);
    }

    public static kingSkinPoolState get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return new kingSkinPoolState();
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }

    public int nextSkinId(RandomSource rand) {
        if (bag.isEmpty()) refillAndShuffle(rand);

        int id = bag.remove(bag.size() - 1);
        setDirty();
        return id;
    }

    private void refillAndShuffle(RandomSource rand) {
        bag.clear();
        for (int i = 0; i < SKIN_COUNT; i++) bag.add(i);

        // Fisher-Yates shuffle using RandomSource
        for (int i = bag.size() - 1; i > 0; i--) {
            int j = rand.nextInt(i + 1);
            int tmp = bag.get(i);
            bag.set(i, bag.get(j));
            bag.set(j, tmp);
        }
        setDirty();
    }
}
