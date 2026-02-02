package name.kingdoms.pressure;

import net.minecraft.server.MinecraftServer;

import java.util.UUID;

public final class PolicyModifiers {
    private PolicyModifiers() {}

    public record Mods(
            double guardGoldCostMult,
            double tavernGoldInMult,
            double shopGoldOutMult,
            double soldierRegenMult
    ) {
        public static final Mods DEFAULT = new Mods(1.0, 1.0, 1.0, 1.0);
    }

    /** Compute “non-stat” modifiers from active pressure events on THIS kingdom. */
    public static Mods compute(MinecraftServer server, UUID kingdomId) {
        if (server == null || kingdomId == null) return Mods.DEFAULT;

        long now = server.getTickCount();
        var ps = KingdomPressureState.get(server);
        var ev = ps.getEvents(kingdomId);
        if (ev == null || ev.isEmpty()) return Mods.DEFAULT;

        double guardCost = 1.0;
        double tavernIn = 1.0;
        double shopOut = 1.0;
        double regen = 1.0;

        for (var e : ev) {
            if (e == null) continue;
            if (now >= e.endTick()) continue;
            String t = e.typeId();
            if (t == null) continue;

            switch (t) {
                // Patrol policy affects guard GOLD cost
                case "increase_patrols" -> guardCost *= 1.50; // tune
                case "decrease_patrols" -> guardCost *= 0.70;

                // Tavern policy affects tavern GOLD input
                case "alcohol_subsidies" -> tavernIn *= 0.50;

                // Shop policy affects shop GOLD output
                case "market_subsidies" -> shopOut *= 0.50;

                // Rations affect soldier regen
                case "double_rations" -> regen *= 1.50;
                case "halve_rations" -> regen *= 0.70;

                default -> {}
            }
        }

        return new Mods(guardCost, tavernIn, shopOut, regen);
    }
}
