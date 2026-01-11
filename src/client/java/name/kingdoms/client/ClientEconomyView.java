package name.kingdoms.client;

import name.kingdoms.diplomacy.ResourceType;
import name.kingdoms.payload.ecoSyncPayload;

public final class ClientEconomyView {
    private static volatile ecoSyncPayload last = ecoSyncPayload.zeros();

    private ClientEconomyView() {}

    /** Call this from your ecoSync S2C receiver. */
    public static void set(ecoSyncPayload p) {
        if (p != null) last = p;
    }

    /** Returns the player's kingdom total for this resource. */
    public static double get(ResourceType t) {
        ecoSyncPayload p = last;
        return switch (t) {
            case GOLD -> p.gold();
            case MEAT -> p.meat();
            case GRAIN -> p.grain();
            case FISH -> p.fish();
            case WOOD -> p.wood();
            case METAL -> p.metal();
            case ARMOR -> p.armor();
            case WEAPONS -> p.weapons();
            case GEMS -> p.gems();
            case HORSES -> p.horses();
            case POTIONS -> p.potions();
        };
    }
}
