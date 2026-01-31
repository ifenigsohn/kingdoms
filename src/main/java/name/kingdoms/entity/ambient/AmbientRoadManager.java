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
import net.minecraft.world.phys.AABB;

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

    // total cap (global) to prevent runaway population
    private static final int TOTAL_CAP_PER_LEVEL = 200; 
    private static final int TOTAL_CAP_BUFFER = 8;
    private static int TOTAL_LIVE = 0;
    private static final int TOTAL_CAP = 200; // tune for whole server
    private static final int CAP_PER_PLAYER = 30;     // tune: max road NPCs near player
    private static final int CAP_PLAYER_RADIUS = 35;  // tune: how far "near" means

    public static void onRoadAmbientDespawn() {
        if (TOTAL_LIVE > 0) TOTAL_LIVE--;
    }

    private static final float HORSE_CHANCE = 0.18f;

    // day vs night pacing
    private static final int DAY_MIN_COOLDOWN = 20 * 5;   // 5s
    private static final int DAY_MAX_COOLDOWN = 20 * 30;  // 30s
    private static final int NIGHT_MIN_COOLDOWN = 20 * 40; // 40s
    private static final int NIGHT_MAX_COOLDOWN = 20 * 80; // 80s

    // groups
    private static final int GROUP_MIN = 1;
    private static final int GROUP_MAX = 3;
    private static final int GROUP_CLUSTER_R = 4; // blocks from leader spawn

        // --- Kingdom-paced spawning ---
        private static final Map<UUID, Long> NEXT_DUE_KINGDOM = new HashMap<>();

        private static final int TICKS_PER_CYCLE = 20 * 20; // 20 seconds = 400 ticks
        private static final int CAP_PER_INFRA = 3;         // +5 road NPCs per infra point

        // how far we count existing road NPCs for cap checks
        private static final int CAP_COUNT_RADIUS = 30;
        


        // Key used by AIkingdomNPCSpawnerBlock
        private static final String ROAD_AMBIENT_INFRA_KEY = "road_ambient_infra";

        private static int militarySkinForKingdom(ServerLevel level, UUID kingdomId) {
            if (kingdomId == null) return 0;
            var srv = level.getServer();
            if (srv == null) return 0;

            // player kingdoms (and any AI you mirror into kingdomState)
            var ks = kingdomState.get(srv);
            var k = ks.getKingdom(kingdomId);
            if (k != null) {
                return name.kingdoms.entity.SoldierSkins.clamp(k.soldierSkinId);
            }

            // AI fallback if needed later (if road manager ever spawns for AI kingdoms not in kingdomState)
            var ai = name.kingdoms.aiKingdomState.get(srv).getById(kingdomId);
            if (ai != null) {
                try { return name.kingdoms.entity.SoldierSkins.clamp(ai.soldierSkinId); }
                catch (Throwable ignored) { return 0; }
            }

            return 0;
        }



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

            case "peasant" -> 46;
            case "villager" -> 12;
            case "trader" -> 4;
            case "envoy" -> 8;
            case "refugee" -> 3;
            case "scholar" -> 22;

            case "soldier" -> 29;
            case "scout" -> 29;

            default -> 12;
        };
        if (maxExclusive < 1) maxExclusive = 1;
        return level.random.nextInt(maxExclusive);
    }

    private static int infraPointsForKingdom(kingdomState.Kingdom k) {
        if (k == null) return 0;

        int jobs = k.populationJobs(); // sums job blocks via k.placed
        int spawnerInfra = k.placed.getOrDefault(ROAD_AMBIENT_INFRA_KEY, 0);

        return Math.max(0, jobs + spawnerInfra);
    }

    private static int capForInfra(int infraPoints) {
        return Math.max(0, infraPoints * CAP_PER_INFRA);
    }

    private static int countRoadAmbientTotal(ServerLevel level) {
        // This scans loaded entities only (cheap enough at 20s cycles).
        return level.getEntitiesOfClass(RoadAmbientNPCEntity.class, new AABB(
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY
        ), e -> e != null && e.isAlive()).size();
    }


    private static int countRoadAmbientNear(ServerLevel level, BlockPos center, int radius, UUID kingdomId) {
        AABB box = new AABB(
                center.getX() - radius, center.getY() - radius, center.getZ() - radius,
                center.getX() + radius + 1, center.getY() + radius + 1, center.getZ() + radius + 1
        );

        return level.getEntitiesOfClass(RoadAmbientNPCEntity.class, box, e -> {
            if (e == null || !e.isAlive()) return false;
            UUID ek = e.getKingdomUUID();
            return kingdomId != null && kingdomId.equals(ek);
        }).size();
    }

    private static int countRoadAmbientNearPlayer(ServerLevel level, BlockPos center, int radius, UUID kingdomId) {
        AABB box = new AABB(
                center.getX() - radius, center.getY() - 16, center.getZ() - radius,
                center.getX() + radius + 1, center.getY() + 16, center.getZ() + radius + 1
        );

        return level.getEntitiesOfClass(RoadAmbientNPCEntity.class, box, e -> {
            if (e == null || !e.isAlive()) return false;
            if (kingdomId != null) {
                UUID ek = e.getKingdomUUID();
                if (ek == null || !kingdomId.equals(ek)) return false;
            }
            return true;
        }).size();
    }



    private static boolean isMilitaryVisual(String type) {
        return "soldier".equals(type) || "guard".equals(type) || "scout".equals(type);
    }

    public static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        if (TOTAL_LIVE >= TOTAL_CAP) return;

        var ks = kingdomState.get(server);
        var war = WarState.get(server);

        // Pick one representative player per kingdom (so multiple players don't multiply spawns)
        Map<UUID, ServerPlayer> repPlayer = new HashMap<>();
        Map<UUID, ServerLevel> repLevel = new HashMap<>();
        Map<UUID, BlockPos> repPos = new HashMap<>();

        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (!(sp.level() instanceof ServerLevel level)) continue;

            var k = ks.getKingdomAt(level, sp.blockPosition());
            if (k == null) continue;

            // First player we see in that kingdom becomes representative this tick
            repPlayer.putIfAbsent(k.id, sp);
            repLevel.putIfAbsent(k.id, level);
            repPos.putIfAbsent(k.id, sp.blockPosition());
        }

        // Run spawns per kingdom
        for (var entry : repPlayer.entrySet()) {
            UUID kid = entry.getKey();
            ServerPlayer sp = entry.getValue();
            ServerLevel level = repLevel.get(kid);
            BlockPos playerPos = repPos.get(kid);
            if (level == null || playerPos == null) continue;

            var kHere = ks.getKingdom(kid);
            if (kHere == null) continue;

            int infra = infraPointsForKingdom(kHere);
            if (infra <= 0) continue;

            // Only do work once per 20s cycle per kingdom
            long due = NEXT_DUE_KINGDOM.getOrDefault(kid, -1L);
            if (due < 0) {
                NEXT_DUE_KINGDOM.put(kid, now + 40 + level.random.nextInt(80)); // small stagger
                continue;
            }
            if (now < due) continue;

            // schedule next cycle
            NEXT_DUE_KINGDOM.put(kid, now + TICKS_PER_CYCLE);

            AmbientContext ctx = AmbientContext.build(server, level, sp, ks, war);
            if (ctx.inWarZone()) continue;

            // Cap check (kingdom-local)
            int cap = capForInfra(infra);
            int existing = countRoadAmbientNear(level, playerPos, CAP_COUNT_RADIUS, kid);
            if (existing >= cap) continue;

            // Per-player density cap (prevents crowding near the rep player)
            int nearPlayer = countRoadAmbientNearPlayer(level, playerPos, CAP_PLAYER_RADIUS, kid);
            if (nearPlayer >= CAP_PER_PLAYER) continue;


            // We get "infra" group attempts this cycle
            int groupAttempts = infra;

            for (int attempt = 0; attempt < groupAttempts; attempt++) {
                // Re-check cap as we add NPCs
                existing = countRoadAmbientNear(level, playerPos, CAP_COUNT_RADIUS, kid);
                if (existing >= cap) break;
                if (TOTAL_LIVE >= TOTAL_CAP) break;
                nearPlayer = countRoadAmbientNearPlayer(level, playerPos, CAP_PLAYER_RADIUS, kid);
                if (nearPlayer >= CAP_PER_PLAYER) break;

                // day/night
                long time = level.getDayTime() % 24000L;
                boolean isDay = time >= 0 && time < 12000L;

                // Find road near player
                BlockPos road = RoadUtil.findNearestSpawnRoad(level, playerPos, SEARCH_ROAD_R);
                if (road == null) continue;

                // Pick a spawn near road but not on top of player
                BlockPos spawn = SpawnUtil.findRingSpawn(level, playerPos, SPAWN_RING_MIN, SPAWN_RING_MAX, 12);
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

                // Must be inside the SAME kingdom (so spawns are truly “within borders”)
                var kAtFeet = ks.getKingdomAt(level, feet);
                if (kAtFeet == null || !kid.equals(kAtFeet.id)) continue;

                // Group size 1–3
                int groupCount = GROUP_MIN + level.random.nextInt(GROUP_MAX - GROUP_MIN + 1);
                BlockPos leaderFeet = feet;

                for (int gi = 0; gi < groupCount; gi++) {
                    // Stop if we're at cap mid-group
                    existing = countRoadAmbientNear(level, playerPos, CAP_COUNT_RADIUS, kid);
                    if (existing >= cap) break;
                    if (TOTAL_LIVE >= TOTAL_CAP) break;

                    nearPlayer = countRoadAmbientNearPlayer(level, playerPos, CAP_PLAYER_RADIUS, kid);
                    if (nearPlayer >= CAP_PER_PLAYER) break;


                    BlockPos candidate = leaderFeet.offset(
                            level.random.nextInt(GROUP_CLUSTER_R * 2 + 1) - GROUP_CLUSTER_R,
                            0,
                            level.random.nextInt(GROUP_CLUSTER_R * 2 + 1) - GROUP_CLUSTER_R
                    );

                    BlockPos memberFeet = RoadUtil.findNearestSpawnRoad(level, candidate, 3);
                    if (memberFeet == null) memberFeet = leaderFeet;

                    if (!SpawnUtil.isSafeHumanoidSpawn(level, memberFeet)) continue;
                    if (!RoadUtil.isStandingOnSpawnRoad(level, memberFeet)) continue;
                    if (!RoadUtil.hasSpawnRoadPatch(level, memberFeet, 2, 3)) continue;

                    // Must still be within this kingdom
                    var kMember = ks.getKingdomAt(level, memberFeet);
                    if (kMember == null || !kid.equals(kMember.id)) continue;

                    String visualType = pickVisualType(level, isDay);
                    boolean military = isMilitaryVisual(visualType);

                    if ("guard".equals(visualType)) visualType = "soldier";

                    RoadAmbientNPCEntity npc = name.kingdoms.Kingdoms.ROAD_AMBIENT_NPC.create(level, EntitySpawnReason.EVENT);
                    if (npc == null) continue;

                    npc.teleportTo(memberFeet.getX() + 0.5, memberFeet.getY(), memberFeet.getZ() + 0.5);
                    npc.setYRot(level.random.nextFloat() * 360f);
                    npc.setXRot(0f);
                    npc.yBodyRot = npc.getYRot();
                    npc.yHeadRot = npc.getYRot();

                    npc.setAiTypeId(visualType);
                    npc.setKingdomUUID(kid);

                    int skin;
                    if (military) {
                        skin = militarySkinForKingdom(level, kid);   // <-- kingdom-selected soldier skin
                    } else {
                        skin = pickSkinForType(level, visualType);   // <-- civilians still random
                    }
                    npc.setSkinId(skin);


                    npc.refreshNametag();
                    npc.setTtlTicks(isDay ? (20 * 60 * 6) : (20 * 60 * 4));
                    level.addFreshEntity(npc);
                    TOTAL_LIVE++;

                    // Horse: only for military, and only for the first member
                    if (military && gi == 0 && level.random.nextFloat() < HORSE_CHANCE) {
                        if (SpawnUtil.isSafeMountSpawn(level, memberFeet)) {
                            Horse h = SpawnUtil.spawnAmbientHorse(level, memberFeet);
                            if (h != null) {
                                level.addFreshEntity(h);
                                npc.startRiding(h, true, military);
                            }
                        }
                    }

                    if (gi == 0) leaderFeet = memberFeet;
                }
            }
        }
    }

}
