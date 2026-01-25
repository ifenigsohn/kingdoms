package name.kingdoms.ambient;

import name.kingdoms.war.WarState;
import name.kingdoms.war.BattleZone;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

public final class WarZoneUtil {
    private WarZoneUtil() {}

    // Returns true if pos is inside ANY war zone rectangle currently active
    public static boolean isInsideAnyWarZone(MinecraftServer server, BlockPos pos) {
        var war = WarState.get(server);
        int x = pos.getX();
        int z = pos.getZ();

        // WarState stores zones per root war key; we don’t have direct iteration helpers,
        // so use getZonesFor for each involved kingdom would be expensive.
        // For step 1, we do a cheap approach:
        // - iterate wars() and pull zone via getZone(UUID,UUID) (you have that API).
        for (String pairKey : war.wars()) {
            String[] parts = pairKey.split("\\|");
            if (parts.length != 2) continue;

            try {
                var a = java.util.UUID.fromString(parts[0]);
                var b = java.util.UUID.fromString(parts[1]);

                var opt = war.getZone(a, b);
                if (opt.isEmpty()) continue;

                BattleZone zone = opt.get();
                if (x >= zone.minX() && x <= zone.maxX() && z >= zone.minZ() && z <= zone.maxZ()) return true;

            } catch (Throwable ignored) {}
        }

        return false;
    }

    // Returns true if pos is within "radius" blocks of any war zone rectangle (but not inside)
    public static boolean isNearAnyWarZone(MinecraftServer server, BlockPos pos, int radius) {
        var war = WarState.get(server);
        int x = pos.getX();
        int z = pos.getZ();

        for (String pairKey : war.wars()) {
            String[] parts = pairKey.split("\\|");
            if (parts.length != 2) continue;

            try {
                var a = java.util.UUID.fromString(parts[0]);
                var b = java.util.UUID.fromString(parts[1]);

                var opt = war.getZone(a, b);
                if (opt.isEmpty()) continue;

                BattleZone zone = opt.get();

                // expanded rectangle
                int minX = zone.minX() - radius;
                int maxX = zone.maxX() + radius;
                int minZ = zone.minZ() - radius;
                int maxZ = zone.maxZ() + radius;

                boolean inExpanded = (x >= minX && x <= maxX && z >= minZ && z <= maxZ);
                if (!inExpanded) continue;

                // exclude inside zone itself
                boolean inside = (x >= zone.minX() && x <= zone.maxX() && z >= zone.minZ() && z <= zone.maxZ());
                if (!inside) return true;

            } catch (Throwable ignored) {}
        }

        return false;
    }

    // WarZoneUtil.java
    public record WarPair(java.util.UUID a, java.util.UUID b, BattleZone zone, int distSq) {}

    public static WarPair findNearestWarPair(MinecraftServer server, BlockPos pos, int maxRadius) {
            var war = WarState.get(server);
            int x = pos.getX();
            int z = pos.getZ();

            int bestD2 = Integer.MAX_VALUE;
            WarPair best = null;

            for (String pairKey : war.wars()) {
                String[] parts = pairKey.split("\\|");
                if (parts.length != 2) continue;

                try {
                    var a = java.util.UUID.fromString(parts[0]);
                    var b = java.util.UUID.fromString(parts[1]);

                    var opt = war.getZone(a, b);
                    if (opt.isEmpty()) continue;

                    BattleZone zone = opt.get();

                    // distance from point to rectangle (0 if inside)
                    int dx = 0;
                    if (x < zone.minX()) dx = zone.minX() - x;
                    else if (x > zone.maxX()) dx = x - zone.maxX();

                    int dz = 0;
                    if (z < zone.minZ()) dz = zone.minZ() - z;
                    else if (z > zone.maxZ()) dz = z - zone.maxZ();

                    int d2 = dx*dx + dz*dz;
                    if (d2 > maxRadius * maxRadius) continue;

                    if (d2 < bestD2) {
                        bestD2 = d2;
                        best = new WarPair(a, b, zone, d2);
                    }

                } catch (Throwable ignored) {}
            }

            return best;
        }

        public record NearestWar(
                java.util.UUID a,
                java.util.UUID b,
                name.kingdoms.war.BattleZone zone,
                int distSq,
                net.minecraft.core.BlockPos edgePos
        ) {}

        public static NearestWar findNearestWarEdge(MinecraftServer server, BlockPos pos, int maxRadius) {
        var war = WarState.get(server);
        int x = pos.getX();
        int z = pos.getZ();

        int best = Integer.MAX_VALUE;
        NearestWar bestRes = null;

        for (String pairKey : war.wars()) {
            String[] parts = pairKey.split("\\|");
            if (parts.length != 2) continue;

            try {
                var a = java.util.UUID.fromString(parts[0]);
                var b = java.util.UUID.fromString(parts[1]);

                var opt = war.getZone(a, b);
                if (opt.isEmpty()) continue;

                BattleZone zone = opt.get();

                // distance from point to rectangle
                int dx = 0;
                if (x < zone.minX()) dx = zone.minX() - x;
                else if (x > zone.maxX()) dx = x - zone.maxX();

                int dz = 0;
                if (z < zone.minZ()) dz = zone.minZ() - z;
                else if (z > zone.maxZ()) dz = z - zone.maxZ();

                int d2 = dx*dx + dz*dz;
                if (d2 > maxRadius * maxRadius) continue;

                if (d2 < best) {
                    best = d2;
                    BlockPos edge = closestEdgePoint(zone, pos);
                    bestRes = new NearestWar(a, b, zone, d2, edge);
                }

            } catch (Throwable ignored) {}
        }

        return bestRes;
    }

    private static BlockPos closestEdgePoint(BattleZone zone, BlockPos pos) {
        int x = pos.getX();
        int z = pos.getZ();

        int cx = clamp(x, zone.minX(), zone.maxX());
        int cz = clamp(z, zone.minZ(), zone.maxZ());

        // If inside the zone, snap to the nearest edge
        boolean inside = (x >= zone.minX() && x <= zone.maxX() && z >= zone.minZ() && z <= zone.maxZ());
        if (inside) {
            int left = x - zone.minX();
            int right = zone.maxX() - x;
            int up = z - zone.minZ();
            int down = zone.maxZ() - z;

            int m = Math.min(Math.min(left, right), Math.min(up, down));
            if (m == left) cx = zone.minX();
            else if (m == right) cx = zone.maxX();
            else if (m == up) cz = zone.minZ();
            else cz = zone.maxZ();
        }

        return new BlockPos(cx, pos.getY(), cz);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }



}
