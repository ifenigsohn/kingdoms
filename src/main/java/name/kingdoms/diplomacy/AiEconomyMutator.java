package name.kingdoms.diplomacy;

import name.kingdoms.aiKingdomState;

public final class AiEconomyMutator {
    private AiEconomyMutator() {}

    public static double get(aiKingdomState.AiKingdom k, ResourceType t) {
        return switch (t) {
            case GOLD -> k.gold;
            case WOOD -> k.wood;
            case METAL -> k.metal;
            case GEMS -> k.gems;
            case POTIONS -> k.potions;
            case ARMOR -> k.armor;
            case HORSES -> k.horses;
            case WEAPONS -> k.weapons;
            case MEAT -> k.meat;
            case FISH -> k.fish;
            case GRAIN -> k.grain;

            default -> 0.0;
        };
    }

    public static void add(aiKingdomState.AiKingdom k, ResourceType t, double delta) {
        switch (t) {
            case GOLD -> k.gold += delta;
            case WOOD -> k.wood += delta;
            case METAL -> k.metal += delta;
            case GEMS -> k.gems += delta;
            case POTIONS -> k.potions += delta;
            case ARMOR -> k.armor += delta;
            case HORSES -> k.horses += delta;
            case WEAPONS -> k.weapons += delta;
            case MEAT -> k.meat += delta;
            case FISH -> k.fish += delta;
            case GRAIN -> k.grain += delta;
            
            default -> {}
        }
    }
}
