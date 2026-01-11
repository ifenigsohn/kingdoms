// src/main/java/name/kingdoms/diplomacy/EconomyMutator.java
package name.kingdoms.diplomacy;

import name.kingdoms.kingdomState;

public final class EconomyMutator {
    private EconomyMutator() {}

    public static double get(kingdomState.Kingdom k, ResourceType t) {
        return switch (t) {
            case GOLD -> k.gold;
            case MEAT -> k.meat;
            case GRAIN -> k.grain;
            case FISH -> k.fish;
            case WOOD -> k.wood;
            case METAL -> k.metal;
            case ARMOR -> k.armor;
            case WEAPONS -> k.weapons;
            case GEMS -> k.gems;
            case HORSES -> k.horses;
            case POTIONS -> k.potions;
        };
    }

    public static void add(kingdomState.Kingdom k, ResourceType t, double delta) {
        switch (t) {
            case GOLD -> k.gold += delta;
            case MEAT -> k.meat += delta;
            case GRAIN -> k.grain += delta;
            case FISH -> k.fish += delta;
            case WOOD -> k.wood += delta;
            case METAL -> k.metal += delta;
            case ARMOR -> k.armor += delta;
            case WEAPONS -> k.weapons += delta;
            case GEMS -> k.gems += delta;
            case HORSES -> k.horses += delta;
            case POTIONS -> k.potions += delta;
        }
        clampNonNegative(k, t);
    }

    private static void clampNonNegative(kingdomState.Kingdom k, ResourceType t) {
        double v = get(k, t);
        if (v < 0) add(k, t, -v); // undo negative (cheap clamp)
    }
}
