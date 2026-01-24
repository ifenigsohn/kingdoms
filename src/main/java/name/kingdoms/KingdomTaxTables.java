package name.kingdoms;

import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;

import static java.util.Map.entry;

public final class KingdomTaxTables {
    private KingdomTaxTables() {}

    private static final Map<String, List<Item>> JOB_TAX_ITEMS = Map.ofEntries(
            entry("farm",    List.of(Items.WHEAT, Items.BREAD, Items.CARROT, Items.POTATO)),
            entry("butcher", List.of(Items.BEEF, Items.PORKCHOP, Items.CHICKEN, Items.MUTTON)),
            entry("fishing", List.of(Items.COD, Items.SALMON)),
            entry("wood",    List.of(Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG)),
            entry("metal",   List.of(Items.IRON_NUGGET, Items.IRON_INGOT, Items.COAL)),
            entry("gem",     List.of(Items.EMERALD, Items.LAPIS_LAZULI, Items.DIAMOND)),
            entry("alchemy", List.of(Items.REDSTONE, Items.GLOWSTONE_DUST, Items.POTION)),
            entry("armor",   List.of(Items.LEATHER, Items.IRON_NUGGET, Items.IRON_INGOT)),
            entry("weapon",  List.of(Items.STICK, Items.IRON_NUGGET, Items.IRON_AXE)),

            // optional extras
            entry("shop",     List.of(Items.EMERALD, Items.GOLD_NUGGET)),
            entry("tavern",   List.of(Items.GOLD_NUGGET)),
            entry("chapel",   List.of(Items.BREAD)),
            entry("nobility", List.of(Items.GOLD_NUGGET))

    );

    public static ItemStack rollTax(String jobId, RandomSource rng) {
        List<Item> pool = JOB_TAX_ITEMS.get(jobId);
        if (pool == null || pool.isEmpty()) pool = List.of(Items.WHEAT);

        int count = 1 + rng.nextInt(3); // 1–3
        Item item = pool.get(rng.nextInt(pool.size()));
        return new ItemStack(item, count);
    }

    public static Component rollNoPayLine(RandomSource rng) {
        String[] lines = new String[] {
                "For the crown, my lord.",
        "Your due, sire.",
        "As promised, my lord.",
        "A humble tribute, my lord.",
        "The fruits of my labor, sire.",
        "As the law commands, my lord.",
        "From my work to your coffers, sire.",
        "I bring what is owed, my lord.",
        "For king and kingdom, sire.",
        "My duty is fulfilled, my lord.",
        "As agreed, your share, sire.",
        "The tithe, my lord.",
        "What little I have, I give gladly, sire.",
        "Earned honestly, my lord.",
        "From field and hand, sire.",
        "I have set this aside for you, my lord.",
        "The kingdom prospers, sire.",
        "By your leave, my lord.",
        "May it serve the realm well, sire.",
        "With respect, my lord."
        };
        return Component.literal(lines[rng.nextInt(lines.length)]);
    }

    public static Component rollPayLine(RandomSource rng, ItemStack tax) {
        String[] lines = new String[] {
                "My lord… I cannot pay again so soon.",
                "Forgive me, my lord. Not yet.",
                "I have already paid my due for the day, sire.",
                "The purse is empty today, my lord.",
                "Tomorrow, my lord. I swear it.",
                "I gave what I could earlier, sire.",
                "The day’s labor is not yet done, my lord.",
                "I have nothing left to offer today, sire.",
                "The coffers must wait until tomorrow, my lord.",
                "I beg your patience, sire.",
                "All has already been given this day, my lord.",
                "There is nothing more to take today, sire.",
                "I must work another day before I can pay again, my lord.",
                "The tally is settled for today, sire.",
                "I have already rendered my share, my lord.",
                "The account is closed until morning, sire.",
                "I would not cheat you, my lord, but there is nothing left today.",
                "The law allows but once a day, sire.",
                "I ask your leave until tomorrow, my lord.",
                "My hands are empty for now, sire."
        };
        return Component.literal(lines[rng.nextInt(lines.length)] + " (")
                .append(Component.literal(tax.getCount() + "x "))
                .append(tax.getHoverName())
                .append(Component.literal(")"));
    }
}
