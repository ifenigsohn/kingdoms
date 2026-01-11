package name.kingdoms;

public final class clientEconomyCache {
    private clientEconomyCache() {}

    public static double gold, meat, grain, fish, wood, metal, armor, weapons, gems, horses, potions;
    public static boolean hasData = false;

    public static void setAll(
        double gold, double meat, double grain, double fish,
        double wood, double metal, double armor, double weapons,
        double gems, double horses, double potions
    ) {
        clientEconomyCache.gold = gold;
        clientEconomyCache.meat = meat;
        clientEconomyCache.grain = grain;
        clientEconomyCache.fish = fish;
        clientEconomyCache.wood = wood;
        clientEconomyCache.metal = metal;
        clientEconomyCache.armor = armor;
        clientEconomyCache.weapons = weapons;
        clientEconomyCache.gems = gems;
        clientEconomyCache.horses = horses;
        clientEconomyCache.potions = potions;
        clientEconomyCache.hasData = true;
    }
}