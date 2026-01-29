package name.kingdoms.entity.ambient;

import name.kingdoms.ambient.AmbientContext;
import name.kingdoms.ambient.SpawnUtil;
import name.kingdoms.entity.ai.RoadAmbientNPCEntity;
import name.kingdoms.kingdomState;
import name.kingdoms.war.WarState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class AmbientRoadManager {
    private AmbientRoadManager() {}

    private static final Map<UUID, Long> NEXT_DUE = new HashMap<>();

    // tune
    private static final int SEARCH_ROAD_R = 28;
    private static final int SPAWN_RING_MIN = 18;
    private static final int SPAWN_RING_MAX = 34;

    private static final float HORSE_CHANCE = 0.18f;

    // day vs night pacing
    private static final int DAY_MIN_COOLDOWN = 20 * 3;   // 3s
    private static final int DAY_MAX_COOLDOWN = 20 * 10;  // 10s
    private static final int NIGHT_MIN_COOLDOWN = 20 * 40; // 40s
    private static final int NIGHT_MAX_COOLDOWN = 20 * 80; // 80s

    // groups
    private static final int GROUP_MIN = 1;
    private static final int GROUP_MAX = 3;
    private static final int GROUP_CLUSTER_R = 4; // blocks from leader spawn

    private static String pickVisualType(ServerLevel level, boolean isDay) {
        // Day: mostly civilians. Night: more military/patrols.
        int peasantW  = isDay ? 40 : 20;
        int villagerW = isDay ? 22 : 12;
        int nobleW    = isDay ? 4  : 1;

        int soldierW  = isDay ? 18 : 28;
        int guardW    = isDay ? 10 : 22;

        int total = peasantW + villagerW + nobleW + soldierW + guardW;
        int roll = level.random.nextInt(total);

        roll -= peasantW;  if (roll < 0) return "peasant";
        roll -= villagerW; if (roll < 0) return "villager";
        roll -= nobleW;    if (roll < 0) return "noble";
        roll -= soldierW;  if (roll < 0) return "soldier";
        return "guard";
    }

    private static int pickSkinForType(ServerLevel level, String type) {
        int maxExclusive = switch (type) {
            case "guard" -> 1;
            case "noble" -> 25;

            case "peasant" -> 3;
            case "villager" -> 12;
            case "trader" -> 4;
            case "envoy" -> 8;
            case "refugee" -> 3;
            case "scholar" -> 2;

            case "soldier" -> 29;
            case "scout" -> 29;

            default -> 12;
        };
        if (maxExclusive < 1) maxExclusive = 1;
        return level.random.nextInt(maxExclusive);
    }

    private static boolean isMilitaryVisual(String type) {
        return "soldier".equals(type) || "guard".equals(type) || "scout".equals(type);
    }

    public static void tick(MinecraftServer server) {
        long now = server.getTickCount();

        var ks = kingdomState.get(server);
        var war = WarState.get(server);

        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (!(sp.level() instanceof ServerLevel level)) continue;

            long due = NEXT_DUE.getOrDefault(sp.getUUID(), -1L);
            if (due < 0) {
                NEXT_DUE.put(sp.getUUID(), now + 40 + level.random.nextInt(80));
                continue;
            }
            if (now < due) continue;

            // day/night rate
            long time = level.getDayTime() % 24000L;
            boolean isDay = time >= 0 && time < 12000L;

            int cd = isDay
                    ? (DAY_MIN_COOLDOWN + level.random.nextInt(DAY_MAX_COOLDOWN - DAY_MIN_COOLDOWN + 1))
                    : (NIGHT_MIN_COOLDOWN + level.random.nextInt(NIGHT_MAX_COOLDOWN - NIGHT_MIN_COOLDOWN + 1));

            NEXT_DUE.put(sp.getUUID(), now + cd);

            AmbientContext ctx = AmbientContext.build(server, level, sp, ks, war);

            // Optional: keep your “never spawn in war zones”
            if (ctx.inWarZone()) continue;

            // Find road near player
            BlockPos road = RoadUtil.findNearestSpawnRoad(level, sp.blockPosition(), SEARCH_ROAD_R);
            if (road == null) continue;

            // Pick a spawn near road but not on top of player
            BlockPos spawn = SpawnUtil.findRingSpawn(level, sp.blockPosition(), SPAWN_RING_MIN, SPAWN_RING_MAX, 12);
            if (spawn == null) continue;

            // Snap spawn toward the road area
            BlockPos nearRoad = RoadUtil.findNearestSpawnRoad(level, spawn, 8);
            if (nearRoad == null) continue;

            // place on surface and ensure safe space
            BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nearRoad);
            BlockPos feet = surface;

            if (!SpawnUtil.isSafeHumanoidSpawn(level, feet)) continue;
            if (!RoadUtil.hasSpawnRoadPatch(level, feet, 2, 3)) continue;

            if (!RoadUtil.isStandingOnSpawnRoad(level, feet)) {
                BlockPos rr = RoadUtil.findNearestSpawnRoad(level, feet, 2);
                if (rr == null) continue;
                feet = rr;
                if (!SpawnUtil.isSafeHumanoidSpawn(level, feet)) continue;
            }

            // Decide kingdom association once (all group members share it)
            var kHere = ks.getKingdomAt(level, feet);
            UUID kidHere = (kHere == null) ? null : kHere.id;

            // Group size 1–3
            int groupCount = GROUP_MIN + level.random.nextInt(GROUP_MAX - GROUP_MIN + 1);

            // Keep a “leader” feet pos so the group clusters
            BlockPos leaderFeet = feet;
           

            for (int gi = 0; gi < groupCount; gi++) {

                // jitter around leader so they don't stack
                BlockPos candidate = leaderFeet.offset(
                        level.random.nextInt(GROUP_CLUSTER_R * 2 + 1) - GROUP_CLUSTER_R,
                        0,
                        level.random.nextInt(GROUP_CLUSTER_R * 2 + 1) - GROUP_CLUSTER_R
                );

                // Snap each member back onto a SPAWN road near the leader
                BlockPos memberFeet = RoadUtil.findNearestSpawnRoad(level, candidate, 3);
                if (memberFeet == null) memberFeet = leaderFeet;

                // Safety check (air + solid ground)
                if (!SpawnUtil.isSafeHumanoidSpawn(level, memberFeet)) continue;
                if (!RoadUtil.isStandingOnSpawnRoad(level, memberFeet)) continue;

                if (!RoadUtil.hasSpawnRoadPatch(level, feet, 2, 3)) continue;


                // Decide VISUAL TYPE (drives texture folder + nametag)
                String visualType = pickVisualType(level, isDay);
                boolean military = isMilitaryVisual(visualType);

                // Optional: if you DON'T have guard textures, map guard visuals to soldier textures:
                if ("guard".equals(visualType)) visualType = "soldier";

                // Spawn npc
                RoadAmbientNPCEntity npc = name.kingdoms.Kingdoms.ROAD_AMBIENT_NPC.create(level, EntitySpawnReason.EVENT);
                if (npc == null) continue;

                npc.teleportTo(memberFeet.getX() + 0.5, memberFeet.getY(), memberFeet.getZ() + 0.5);
                npc.setYRot(level.random.nextFloat() * 360f);
                npc.setXRot(0f);
                npc.yBodyRot = npc.getYRot();
                npc.yHeadRot = npc.getYRot();

                npc.setAiTypeId(visualType);
                npc.setKingdomUUID(kidHere);

                // Any skin can spawn for that visual type
                npc.setSkinId(pickSkinForType(level, visualType));

                npc.refreshNametag();
                npc.setTtlTicks(isDay ? (20 * 60 * 6) : (20 * 60 * 4));
                level.addFreshEntity(npc);

                // Horse: only for military, and only for the first member (prevents 3 horses)
                if (military && gi == 0 && level.random.nextFloat() < HORSE_CHANCE) {
                    if (SpawnUtil.isSafeMountSpawn(level, memberFeet)) {
                        Horse h = SpawnUtil.spawnAmbientHorse(level, memberFeet);
                        if (h != null) {
                            level.addFreshEntity(h);
                            npc.startRiding(h, true, military); 
                        }
                    }
                }

                // Set leader to first successful spawn so group forms around it
                if (gi == 0) leaderFeet = memberFeet;
            }
        }
    }
}
