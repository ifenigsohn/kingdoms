package name.kingdoms.pressure;

import name.kingdoms.kingdomState;
import name.kingdoms.payload.KingdomEventsSyncS2CPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

public final class KingdomEventsNet {
    private KingdomEventsNet() {}

    public static void sendMyKingdomEvents(MinecraftServer server, ServerPlayer player) {
        var ks = kingdomState.get(server);
        var pk = ks.getPlayerKingdom(player.getUUID());
        if (pk == null) {
            ServerPlayNetworking.send(player, new KingdomEventsSyncS2CPayload(List.of()));
            return;
        }

        long now = server.getTickCount();
        var ps = KingdomPressureState.get(server);
        var list = ps.getEvents(pk.id);

        var out = new ArrayList<KingdomEventsSyncS2CPayload.Entry>();

        for (var e : list) {
            if (e == null) continue;
            if (now >= e.endTick()) continue;

            double econMult = 1.0;
            double hap = 0.0;
            double sec = 0.0;
            int rel = 0;

            var eff = e.effects();
            if (eff != null) {
                Double econPct = eff.get(KingdomPressureState.Stat.ECONOMY);
                if (econPct != null) econMult *= Math.max(0.0, 1.0 + econPct);

                Double h = eff.get(KingdomPressureState.Stat.HAPPINESS);
                if (h != null) hap += h;

                Double s = eff.get(KingdomPressureState.Stat.SECURITY);
                if (s != null) sec += s;

                Double r = eff.get(KingdomPressureState.Stat.RELATIONS);
                if (r != null) rel += (int)Math.round(r);
            }

            int secs = (int)Math.max(0, (e.endTick() - now) / 20L);

            out.add(new KingdomEventsSyncS2CPayload.Entry(
                    e.typeId(),
                    e.causer(),
                    (e.relScope() == null ? "GLOBAL" : e.relScope().name()),
                    econMult, hap, sec, rel,
                    secs
            ));
        }

        ServerPlayNetworking.send(player, new KingdomEventsSyncS2CPayload(out));
    }
}
