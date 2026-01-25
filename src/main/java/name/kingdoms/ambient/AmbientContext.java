package name.kingdoms.ambient;


import name.kingdoms.IKingSpawnerBlock;
import name.kingdoms.aiKingdomState;
import name.kingdoms.kingdomState;
import name.kingdoms.war.WarState;
import name.kingdoms.diplomacy.AllianceState;
import name.kingdoms.diplomacy.DiplomacyRelationsState;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collection;
import java.util.UUID;

public record AmbientContext(
        MinecraftServer server,
        ServerLevel level,
        ServerPlayer player,
        BlockPos pos,

        // where are we?
        kingdomState.Kingdom hereKingdom,    // null if wilderness
        kingdomState.Kingdom playerKingdom,  // null if player has no kingdom

        // war-space
        boolean inWarZone,
        boolean nearWarZone,

        // kingdom war status
        boolean kingdomAtWar,

        // diplomacy
        boolean alliedHere,
        int relationHere,

        // ai personality access (optional)
        aiKingdomState.AiKingdom aiHere,

        // distances (squared; -1 means unknown)
        int distHereOriginSq,
        int distHereTerminalSq,
        int distPlayerOriginSq,
        int distPlayerTerminalSq,

        // border
        boolean insideHereBorder,
        boolean insidePlayerBorder,
        int distToHereBorderSq,
        int distToPlayerBorderSq,

        // proximity scans (sampled)
        int distToNearestKingdomBlockSq,
        int distToNearestJobBlockSq,
        int distToNearestNpcSpawnerSq,
        int distToNearestKingSpawnerSq,

        // ai kingdom proximity
        UUID nearestAiKingdomId,
        int nearestAiKingdomDistSq,
        UUID nearestOtherAiKingdomId,
        int nearestOtherAiKingdomDistSq
) {
    public boolean inKingdom() { return hereKingdom != null; }
    public UUID hereId() { return hereKingdom == null ? null : hereKingdom.id; }

    public boolean nearPlayerBorder(int blocks) {
        return distToPlayerBorderSq >= 0 && distToPlayerBorderSq <= blocks * blocks;
    }
    public boolean nearHereBorder(int blocks) {
        return distToHereBorderSq >= 0 && distToHereBorderSq <= blocks * blocks;
    }
    public boolean nearJobBlocks(int blocks) {
        return distToNearestJobBlockSq >= 0 && distToNearestJobBlockSq <= blocks * blocks;
    }
    public boolean nearNpcSpawner(int blocks) {
        return distToNearestNpcSpawnerSq >= 0 && distToNearestNpcSpawnerSq <= blocks * blocks;
    }
    public boolean nearKingSpawner(int blocks) {
        return distToNearestKingSpawnerSq >= 0 && distToNearestKingSpawnerSq <= blocks * blocks;
    }

    public static AmbientContext build(
            MinecraftServer server,
            ServerLevel level,
            ServerPlayer player,
            kingdomState ks,
            WarState war
    ) {
        BlockPos pos = player.blockPosition();

        var here = ks.getKingdomAt(level, pos);
        var pk = ks.getPlayerKingdom(player.getUUID());

        boolean inWarZone = WarZoneUtil.isInsideAnyWarZone(server, pos);
        boolean nearWarZone = !inWarZone && WarZoneUtil.isNearAnyWarZone(server, pos, 32);

        boolean kingdomAtWar = (here != null) && war.isAtWarWithAny(here.id);

        boolean alliedHere = false;
        int relationHere = 0;
        aiKingdomState.AiKingdom aiHere = null;

        if (here != null && pk != null && !here.id.equals(pk.id)) {
            alliedHere = AllianceState.get(server).isAllied(pk.id, here.id);
            relationHere = DiplomacyRelationsState.get(server).getRelation(player.getUUID(), here.id);
        }
        if (here != null) {
            aiHere = aiKingdomState.get(server).getById(here.id);
        }

        // ---- distance helpers (squared) ----
        int distHereOriginSq = distSq2D(pos, here == null ? null : here.origin);
        int distHereTerminalSq = distSq2D(pos, here == null ? null : here.terminalPos);

        int distPlayerOriginSq = distSq2D(pos, pk == null ? null : pk.origin);
        int distPlayerTerminalSq = distSq2D(pos, pk == null ? null : pk.terminalPos);

        // ---- border info ----
        boolean insideHereBorder = isInsideBorder(here, pos);
        boolean insidePlayerBorder = isInsideBorder(pk, pos);

        int distToHereBorderSq = borderDistSq(here, pos);
        int distToPlayerBorderSq = borderDistSq(pk, pos);

        // ---- proximity scans (sampled; cheap) ----
        // Tune radius/samples: these run once per ambient pulse (minutes), not every tick.
        int distToNearestJobBlockSq = sampledNearestDistSq(level, pos, 72,
                bs -> bs.getBlock() instanceof name.kingdoms.jobBlock,
                120);

        int distToNearestNpcSpawnerSq = sampledNearestDistSq(level, pos, 96,
                bs -> bs.getBlock() instanceof name.kingdoms.IKingdomSpawnerBlock,
                140);

        // If you haven't created IKingSpawnerBlock yet, leave this as -1 until you do
        int distToNearestKingSpawnerSq = sampledNearestDistSq(level, pos, 96,
            bs -> bs.getBlock() instanceof name.kingdoms.kingdomKingSpawnerBlock,
            140);


        // If you don't have a direct reference to your kingdom block, keep this disabled for now
        int distToNearestKingdomBlockSq = sampledNearestDistSq(level, pos, 96,
        bs -> bs.is(name.kingdoms.modBlock.kingdom_block),
        140);


        // ---- nearest AI kingdom distance ----
        var aiState = aiKingdomState.get(server);
        UUID nearestAiId = null;
        int nearestAiD2 = -1;

        UUID nearestOtherAiId = null;
        int nearestOtherAiD2 = -1;

        Collection<kingdomState.Kingdom> all = ks.getAllKingdoms();
        if (all != null && !all.isEmpty()) {
            for (var k : all) {
                if (k == null || k.id == null) continue;
                if (!aiState.isAiKingdom(k.id)) continue;

                int d2 = distSq2D(pos, k.terminalPos != null ? k.terminalPos : k.origin);

                if (d2 >= 0 && (nearestAiD2 < 0 || d2 < nearestAiD2)) {
                    nearestAiD2 = d2;
                    nearestAiId = k.id;
                }

                // when inside an AI kingdom, measure distance to the nearest OTHER AI kingdom
                if (here != null && here.id != null && here.id.equals(k.id)) continue;
                if (d2 >= 0 && (nearestOtherAiD2 < 0 || d2 < nearestOtherAiD2)) {
                    nearestOtherAiD2 = d2;
                    nearestOtherAiId = k.id;
                }
            }
        }

        return new AmbientContext(
                server, level, player, pos,
                here, pk,
                inWarZone, nearWarZone,
                kingdomAtWar,
                alliedHere, relationHere,
                aiHere,

                distHereOriginSq,
                distHereTerminalSq,
                distPlayerOriginSq,
                distPlayerTerminalSq,

                insideHereBorder,
                insidePlayerBorder,
                distToHereBorderSq,
                distToPlayerBorderSq,

                distToNearestKingdomBlockSq,
                distToNearestJobBlockSq,
                distToNearestNpcSpawnerSq,
                distToNearestKingSpawnerSq,

                nearestAiId,
                nearestAiD2,
                nearestOtherAiId,
                nearestOtherAiD2
        );
    }

    // -----------------------
    // Helpers
    // -----------------------
    private static int distSq2D(BlockPos a, BlockPos b) {
        if (a == null || b == null) return -1;
        int dx = a.getX() - b.getX();
        int dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static boolean isInsideBorder(kingdomState.Kingdom k, BlockPos p) {
        if (k == null || !k.hasBorder) return false;
        int x = p.getX(), z = p.getZ();
        return x >= k.borderMinX && x <= k.borderMaxX && z >= k.borderMinZ && z <= k.borderMaxZ;
    }

    public boolean nearKingdomBlock(int blocks) {
        return distToNearestKingdomBlockSq >= 0 && distToNearestKingdomBlockSq <= blocks * blocks;
    }


    private static int borderDistSq(kingdomState.Kingdom k, BlockPos p) {
        if (k == null || !k.hasBorder) return -1;

        int x = p.getX();
        int z = p.getZ();

        // inside: distance to nearest edge
        if (x >= k.borderMinX && x <= k.borderMaxX && z >= k.borderMinZ && z <= k.borderMaxZ) {
            int dx = Math.min(x - k.borderMinX, k.borderMaxX - x);
            int dz = Math.min(z - k.borderMinZ, k.borderMaxZ - z);
            int d = Math.min(dx, dz);
            return d * d;
        }

        // outside: distance to rectangle
        int dx = 0;
        if (x < k.borderMinX) dx = k.borderMinX - x;
        else if (x > k.borderMaxX) dx = x - k.borderMaxX;

        int dz = 0;
        if (z < k.borderMinZ) dz = k.borderMinZ - z;
        else if (z > k.borderMaxZ) dz = z - k.borderMaxZ;

        return dx * dx + dz * dz;
    }

    private static int sampledNearestDistSq(ServerLevel level, BlockPos center, int radius,
                                           java.util.function.Predicate<BlockState> pred,
                                           int samples) {
        int best = -1;
        int cx = center.getX();
        int cz = center.getZ();

        for (int i = 0; i < samples; i++) {
            int dx = level.random.nextInt(radius * 2 + 1) - radius;
            int dz = level.random.nextInt(radius * 2 + 1) - radius;

            int x = cx + dx;
            int z = cz + dz;

            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos p = new BlockPos(x, y, z);

            BlockState bs = level.getBlockState(p);
            if (!pred.test(bs)) continue;

            int d2 = dx * dx + dz * dz;
            if (best < 0 || d2 < best) best = d2;
        }
        return best;
    }
}
