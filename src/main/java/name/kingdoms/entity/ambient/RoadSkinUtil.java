package name.kingdoms.entity.ambient;

import name.kingdoms.kingdomState;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Field;
import java.util.UUID;

public final class RoadSkinUtil {
    private RoadSkinUtil() {}

    public static int pickMilitarySkinBase(ServerLevel sl, UUID kid, int families) {
        if (kid == null) return 0;
        if (families < 1) families = 1;

        // 1) try player kingdoms (kingdomState) for an “assigned skin type”
        try {
            var ks = kingdomState.get(sl.getServer());
            var k = ks.getKingdom(kid);
            if (k != null) {
                for (String fName : new String[]{"militarySkinType", "soldierSkinType", "skinType", "bannerSkinType"}) {
                    try {
                        Field f = k.getClass().getField(fName);
                        Object v = f.get(k);
                        if (v instanceof Integer i) return Math.floorMod(i, families);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        // 2) fallback deterministic per-kingdom
        return Math.floorMod(kid.hashCode(), families);
    }
}
