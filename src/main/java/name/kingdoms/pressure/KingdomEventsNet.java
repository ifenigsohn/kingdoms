package name.kingdoms.pressure;

import name.kingdoms.kingdomState;
import name.kingdoms.payload.KingdomEventsSyncS2CPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class KingdomEventsNet {
    private KingdomEventsNet() {}

    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    public static void sendMyKingdomEvents(MinecraftServer server, ServerPlayer player) {
        long nowTickCount = server.getTickCount();

        var ks = kingdomState.get(server);
        var pk = ks.getPlayerKingdom(player.getUUID());
        if (pk == null) {
            ServerPlayNetworking.send(player, new KingdomEventsSyncS2CPayload(nowTickCount, List.of()));
            return;
        }

        ServerLevel sl = (ServerLevel) player.level();
        long nowGameTime = sl.getGameTime();

        var ps = KingdomPressureState.get(server);
        var list = ps.getEvents(pk.id);

        var out = new ArrayList<KingdomEventsSyncS2CPayload.Entry>();

        for (var e : list) {
            if (e == null) continue;
            if (nowTickCount >= e.endTick()) continue;

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
                if (r != null) rel += (int) Math.round(r);
            }

            long ticksRemaining = e.endTick() - nowTickCount;
            long endGameTime = nowGameTime + Math.max(0L, ticksRemaining);

            UUID causer = e.causer();
            String causerName = "";

            // If it's not the "global" zero UUID, try to resolve a name
            if (causer != null && !causer.equals(ZERO_UUID)) {
                var ck = ks.getKingdom(causer);
                if (ck != null && ck.name != null) causerName = ck.name;
            }

            out.add(new KingdomEventsSyncS2CPayload.Entry(
                    e.typeId(),
                    causer,
                    causerName,
                    (e.relScope() == null ? "GLOBAL" : e.relScope().name()),
                    econMult, hap, sec,
                    rel,
                    endGameTime // END GAME TIME (world gameTime)
            ));
        }

        ServerPlayNetworking.send(player, new KingdomEventsSyncS2CPayload(nowTickCount, out));
    }
}
