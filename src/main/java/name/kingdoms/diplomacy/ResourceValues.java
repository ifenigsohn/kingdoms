package name.kingdoms.diplomacy;

import java.util.EnumMap;

public final class ResourceValues {

    private static final EnumMap<ResourceType, Double> GOLD_VALUE = new EnumMap<>(ResourceType.class);

    static {
        // --- baseline example values (tweak these) ---
        GOLD_VALUE.put(ResourceType.GOLD,    1.0);

        GOLD_VALUE.put(ResourceType.WOOD,    0.15);
        GOLD_VALUE.put(ResourceType.METAL,   0.55);
        GOLD_VALUE.put(ResourceType.GEMS,    2.50);

        GOLD_VALUE.put(ResourceType.MEAT,    0.10);
        GOLD_VALUE.put(ResourceType.GRAIN,   0.08);
        GOLD_VALUE.put(ResourceType.FISH,    0.09);

        GOLD_VALUE.put(ResourceType.ARMOR,   1.40);
        GOLD_VALUE.put(ResourceType.WEAPONS, 1.20);
        GOLD_VALUE.put(ResourceType.HORSES,  1.60);
        GOLD_VALUE.put(ResourceType.POTIONS, 2.00);
    }

    private ResourceValues() {}

    public static double goldValue(ResourceType t) {
        return GOLD_VALUE.getOrDefault(t, 1.0);
    }

    public static double goldValue(ResourceType t, double amount) {
        return goldValue(t) * Math.max(0.0, amount);
    }
}
