package name.kingdoms.entity;

import name.kingdoms.Kingdoms;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

public final class SoldierSkins {
    private SoldierSkins() {}

    // Set this to (# of skins - 1)
    public static final int MAX_SKIN_ID = 25;

    private static final ResourceLocation FALLBACK =
            ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "textures/entity/soldier.png");

    public static int clamp(int id) {
        return Mth.clamp(id, 0, MAX_SKIN_ID);
    }

    public static int random(RandomSource r) {
        return r.nextInt(MAX_SKIN_ID + 1);
    }

    public static ResourceLocation tex(int id) {
        id = clamp(id);
        return ResourceLocation.fromNamespaceAndPath(
                Kingdoms.MOD_ID, "textures/entity/soldier/soldier_" + id + ".png"
        );
    }

    public static ResourceLocation fallback() {
        return FALLBACK;
    }
}
