package name.kingdoms.diplomacy;

import name.kingdoms.kingdomState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class DiplomaticRangeUtil {
    private DiplomaticRangeUtil() {}

    private static boolean sameDim(ResourceKey<Level> a, ResourceKey<Level> b) {
        return a != null && a.equals(b);
    }

    public static List<kingdomState.DiplomacyAnchor> anchorsFor(kingdomState.Kingdom k) {
        List<kingdomState.DiplomacyAnchor> out = new ArrayList<>();
        if (k == null) return out;

        // Player terminal anchor
        if (k.hasTerminal) {
            out.add(new kingdomState.DiplomacyAnchor(
                    k.terminalDim,
                    k.terminalPos,
                    k.diplomacyRangeBlocks
            ));
        }

        // Envoy anchors
        out.addAll(k.envoyAnchors);

        // ✅ Fallback: use "origin" (AI spawner / kingdom creation block position)
        if (out.isEmpty() && k.origin != null) {
            out.add(new kingdomState.DiplomacyAnchor(
                    Level.OVERWORLD,   // if AI always spawns in OW
                    k.origin,
                    k.diplomacyRangeBlocks
            ));
        }

        return out;
    }




    /** One-way: can FROM reach TO? */
    public static boolean canReach(MinecraftServer server, UUID fromKid, UUID toKid) {
        if (server == null || fromKid == null || toKid == null) return false;

        var ks = kingdomState.get(server);
        var from = ks.getKingdom(fromKid);
        var to   = ks.getKingdom(toKid);
        if (from == null || to == null) return false;

        var fromAnchors = anchorsFor(from);
        var toAnchors   = anchorsFor(to);

        for (var a : fromAnchors) {
            if (a == null) continue;

            int r = Math.max(0, a.radiusBlocks());
            double r2 = (double) r * (double) r;

            for (var b : toAnchors) {
                if (b == null) continue;
                if (!sameDim(a.dim(), b.dim())) continue;

                double d2 = a.pos().distSqr(b.pos());
                if (d2 <= r2) return true;
            }
        }
        return false;

    }

    public static boolean canPlayerReach(MinecraftServer server, UUID playerId, UUID toKid) {
        if (server == null || playerId == null || toKid == null) return false;
        var ks = kingdomState.get(server);
        var pk = ks.getPlayerKingdom(playerId);
        if (pk == null) return false;
        return canReach(server, pk.id, toKid);
    }


}
