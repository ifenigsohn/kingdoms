package name.kingdoms.ambient;

import name.kingdoms.diplomacy.DiplomacyRelationsState;
import name.kingdoms.kingdomState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

public final class AmbientEffects {
    private AmbientEffects() {}

    private static ScriptedAmbientEvent.SpawnVariant V(ScriptedAmbientEvent.Predicate gate, List<ScriptedAmbientEvent.SpawnPlan> spawns, String id) {
        return new ScriptedAmbientEvent.SpawnVariant(gate, spawns, id);
    }

    public static AmbientEffect giveItem(ItemStack stack) {
        return ctx -> {
            if (stack == null || stack.isEmpty()) return;
            ServerPlayer sp = ctx.player();
            boolean ok = sp.getInventory().add(stack.copy());
            if (!ok) sp.drop(stack.copy(), false);
        };
    }

    public static AmbientEffect changeRelationWithHereKingdom(int delta) {
        return ctx -> {
            if (ctx.hereKingdom() == null) return;
            UUID kid = ctx.hereKingdom().id;
            DiplomacyRelationsState.get(ctx.server()).addRelation(ctx.player().getUUID(), kid, delta);
        };
    }

    public static AmbientEffect changePlayerEconomy(ResourceTypeLike r, double delta) {
        // placeholder: you’ll map resources to kingdomState fields in step 2/3
        return ctx -> {};
    }

    public static AmbientEffect forceAiLetter(Object placeholder) {
        // placeholder: in step 3 we’ll define a proper hook into DiplomacyResponseQueue / MailGenerator
        return ctx -> {};
    }

    // tiny helper type so we can fill this later
    public enum ResourceTypeLike { GOLD, FOOD, WOOD, METAL }

    public static AmbientEffect addToPlayerKingdomResource(String resource, double delta) {
        return ctx -> {
            if (ctx.playerKingdom() == null) return;
            var k = ctx.playerKingdom();
            double v = switch (resource) {
                case "gold" -> k.gold;
                case "meat" -> k.meat;
                case "grain" -> k.grain;
                case "fish" -> k.fish;
                case "wood" -> k.wood;
                case "metal" -> k.metal;
                case "armor" -> k.armor;
                case "weapons" -> k.weapons;
                case "gems" -> k.gems;
                case "horses" -> k.horses;
                case "potions" -> k.potions;
                default -> 0.0;
            };

            double next = Math.max(0.0, v + delta);

            switch (resource) {
                case "gold" -> k.gold = next;
                case "meat" -> k.meat = next;
                case "grain" -> k.grain = next;
                case "fish" -> k.fish = next;
                case "wood" -> k.wood = next;
                case "metal" -> k.metal = next;
                case "armor" -> k.armor = next;
                case "weapons" -> k.weapons = next;
                case "gems" -> k.gems = next;
                case "horses" -> k.horses = next;
                case "potions" -> k.potions = next;
            }

            name.kingdoms.kingdomState.get(ctx.server()).markDirty();
        };
    }

    public static AmbientEffect changeRelation(java.util.UUID kingdomId, int delta) {
        return ctx -> {
            if (kingdomId == null) return;
            name.kingdoms.diplomacy.DiplomacyRelationsState.get(ctx.server())
                    .addRelation(ctx.player().getUUID(), kingdomId, delta);
        };
    }


}
