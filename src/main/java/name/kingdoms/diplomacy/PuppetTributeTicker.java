package name.kingdoms.diplomacy;

import name.kingdoms.kingdomState;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PuppetTributeTicker {
    private PuppetTributeTicker() {}

    // every 20 seconds
    private static final long PERIOD_TICKS = 20L * 20L;

    // tune these
    public static final double TRIBUTE_RATE_AI     = 0.20; // 20%
    public static final double TRIBUTE_RATE_PLAYER = 0.10; // 10%

    // last snapshot: kid -> (resource -> value)
    private static final Map<UUID, EnumMap<ResourceType, Double>> LAST = new HashMap<>();

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(PuppetTributeTicker::onTick);
    }

    private static void onTick(MinecraftServer server) {
        long now = server.getTickCount();
        if (now <= 0) return;
        if (now % PERIOD_TICKS != 0) return;

        var ks = kingdomState.get(server);

        // snapshot keys to avoid CME if something changes during loop
        var all = new java.util.ArrayList<>(ks.getAllKingdoms());

        for (var k : all) {
            if (k == null) continue;

            UUID puppetKid = k.id;
            UUID masterKid = ks.getMasterOf(puppetKid);
            if (masterKid == null) {
                // still refresh snapshot
                LAST.put(puppetKid, snapshot(k));
                continue;
            }

            var prev = LAST.get(puppetKid);
            var cur  = snapshot(k);

            if (prev == null) {
                LAST.put(puppetKid, cur);
                continue;
            }

            var master = ks.getKingdom(masterKid);
            if (master == null) {
                // master deleted? free the puppet
                ks.clearPuppet(puppetKid);
                LAST.put(puppetKid, cur);
                continue;
            }

            boolean puppetIsAi = (name.kingdoms.aiKingdomState.get(server).getById(puppetKid) != null);
            double rate = puppetIsAi ? TRIBUTE_RATE_AI : TRIBUTE_RATE_PLAYER;

            // transfer only POSITIVE deltas
            for (ResourceType t : ResourceType.values()) {
                double before = prev.getOrDefault(t, 0.0);
                double after  = cur.getOrDefault(t, 0.0);

                double gain = after - before;
                if (gain <= 0.0) continue;

                double tribute = gain * rate;
                if (tribute <= 0.0) continue;

                // subtract from puppet
                applyDelta(k, t, -tribute);
                // add to master
                applyDelta(master, t, +tribute);
            }

            // resnapshot after transfer so it doesn't cascade forever
            LAST.put(puppetKid, snapshot(k));
        }
    }

    private static EnumMap<ResourceType, Double> snapshot(kingdomState.Kingdom k) {
        var m = new EnumMap<ResourceType, Double>(ResourceType.class);
        m.put(ResourceType.GOLD, k.gold);
        m.put(ResourceType.MEAT, k.meat);
        m.put(ResourceType.GRAIN, k.grain);
        m.put(ResourceType.FISH, k.fish);
        m.put(ResourceType.WOOD, k.wood);
        m.put(ResourceType.METAL, k.metal);
        m.put(ResourceType.ARMOR, k.armor);
        m.put(ResourceType.WEAPONS, k.weapons);
        m.put(ResourceType.GEMS, k.gems);
        m.put(ResourceType.HORSES, k.horses);
        m.put(ResourceType.POTIONS, k.potions);
        return m;
    }

    private static void applyDelta(kingdomState.Kingdom k, ResourceType t, double d) {
        switch (t) {
            case GOLD -> k.gold += d;
            case MEAT -> k.meat += d;
            case GRAIN -> k.grain += d;
            case FISH -> k.fish += d;
            case WOOD -> k.wood += d;
            case METAL -> k.metal += d;
            case ARMOR -> k.armor += d;
            case WEAPONS -> k.weapons += d;
            case GEMS -> k.gems += d;
            case HORSES -> k.horses += d;
            case POTIONS -> k.potions += d;
        }
    }
}
