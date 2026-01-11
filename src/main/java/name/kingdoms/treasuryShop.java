package name.kingdoms;

import name.kingdoms.payload.treasuryShopSyncPayload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class treasuryShop {
    private treasuryShop() {}

    public static treasuryShopSyncPayload buildShopPayload() {
        List<treasuryShopSyncPayload.Entry> out = new ArrayList<>();

        for (jobDefinition j : jobDefinition.all()) {
            out.add(new treasuryShopSyncPayload.Entry(
                    j.getId(),
                    j.costGold(), j.costMeat(), j.costGrain(), j.costFish(),
                    j.costWood(), j.costMetal(), j.costArmor(), j.costWeapons(),
                    j.costGems(), j.costHorses(), j.costPotions()
            ));
        }

        out.sort(Comparator.comparing(treasuryShopSyncPayload.Entry::jobId));
        return new treasuryShopSyncPayload(out);
    }
}
