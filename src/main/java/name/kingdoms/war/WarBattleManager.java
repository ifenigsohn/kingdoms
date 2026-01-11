package name.kingdoms.war;

import name.kingdoms.aiKingdomState;
import name.kingdoms.entity.SoldierEntity;
import name.kingdoms.entity.modEntities;
import name.kingdoms.kingdomState;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import name.kingdoms.payload.warBattleHudSyncPayload;

import java.util.*;
import java.util.function.Consumer;

public final class WarBattleManager {

    private WarBattleManager() {}

    // --------------------
    // Tuning knobs
    // --------------------
    private static final int SOLDIERS_PER_SIDE = 50;
    private static final int RESPAWN_DELAY_TICKS = 120;     // 6 seconds
    private static final int TARGET_REFRESH_TICKS = 5;
    private static final int FORMATION_REFRESH_TICKS = 10;
    private static final int HUD_SYNC_TICKS = 5; // 4x/sec

    // --- enemy advance (formation leader walks toward player) ---
    private static final double ENEMY_ADVANCE_STEP = 0.8;        // blocks per FORMATION_REFRESH_TICKS
    private static final double ENEMY_ADVANCE_STOP_DIST = 5.0;  // stop this far from player
    private static final int ENEMY_ADVANCE_MARGIN = 4;          // keep leader away from zone edge

    // Morale
    private static final float START_MORALE = 200f;
    private static final float DEATH_MORALE_HIT = 1.5f;
    private static final float KING_DEATH_HIT = 35f;
    private static final int LOSS_WINDOW_TICKS = 20 * 10; // 10 seconds

    private static final double REFORM_FIRST_DIST = 6.0;     // if farther than this, clear target & reform
    private static final double ENGAGE_DIST = 18.0;          // footmen acquire targets within this range
    private static final double DROP_TARGET_DIST = 26.0;     // stop chasing if target gets too far

    private static final double ARCHER_ENGAGE_DIST = 28.0;   // archers acquire farther

    // --- composition ---
    private static final double FRIEND_ARCHER_RATIO = 0.35; // 35% archers
    private static final double ENEMY_ARCHER_RATIO  = 0.35;

    // --- formations ---
    private static final int FOOT_COLS = 10;
    private static final int FOOT_ROWS = 5;
    private static final int FOOT_SLOTS = FOOT_COLS * FOOT_ROWS;

    private static final int ARCH_COLS = 15;
    private static final int ARCH_ROWS = 3;
    private static final int ARCH_SLOTS = ARCH_COLS * ARCH_ROWS;

    private static final double MAX_ORDER_DIST = 50.0;

    // --------------------
    // Runtime
    // --------------------
    private static final Map<UUID, BattleInstance> ACTIVE = new HashMap<>();

    // commander uuid -> (battle, side)
    private record CommanderCtx(BattleInstance battle, Side side) {}
    private static final Map<UUID, CommanderCtx> COMMANDER_INDEX = new HashMap<>();

    private static CommanderCtx findCommanderCtx(ServerPlayer player) {
        return COMMANDER_INDEX.get(player.getUUID());
    }


    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(WarBattleManager::tick);
    }

    // --------------------
    // Commanding / selection
    // --------------------
    private enum CommandGroup { FOOTMEN, ARCHERS, BOTH }
    private enum UnitRole { FOOTMAN, ARCHER }
    private enum Side { FRIEND, ENEMY }

    public enum CommandResult {
        OK,
        NO_BATTLE,
        TOO_FAR,
        OUTSIDE_ZONE
    }

    /** Call this from your left-click C2S packet receiver. */
    public static int cycleCommandGroupOrdinal(ServerPlayer player) {
        CommanderCtx ctx = findCommanderCtx(player);
        if (ctx == null) return -1;

        var orders = (ctx.side == Side.FRIEND) ? ctx.battle.friendOrders : ctx.battle.enemyOrders;

        orders.selected = switch (orders.selected) {
            case FOOTMEN -> CommandGroup.ARCHERS;
            case ARCHERS -> CommandGroup.BOTH;
            case BOTH -> CommandGroup.FOOTMEN;
        };

        return orders.selected.ordinal();
    }

    public static int getCommandGroupOrdinal(ServerPlayer player) {
        CommanderCtx ctx = findCommanderCtx(player);
        if (ctx == null) return -1;
        var orders = (ctx.side == Side.FRIEND) ? ctx.battle.friendOrders : ctx.battle.enemyOrders;
        return orders.selected.ordinal();
    }

    // Your existing API used by WarCommandItem (right-click)
    public static boolean issueMoveOrder(ServerPlayer player, BlockPos clicked) {
        CommanderCtx ctx = findCommanderCtx(player);
        if (ctx == null) return false;

        BattleInstance b = ctx.battle;
        Side side = ctx.side;

        Vec3 raw = Vec3.atCenterOf(clicked);

        if (!withinOrderRange(player, raw, MAX_ORDER_DIST)) {
            player.displayClientMessage(Component.literal("Can't direct army: too far!"), true);
            return false;
        }

        BlockPos rawXZ = new BlockPos(Mth.floor(raw.x), player.blockPosition().getY(), Mth.floor(raw.z));
        if (!b.zone.contains(rawXZ)) {
            player.displayClientMessage(Component.literal("Can't direct army: outside the battle zone!"), true);
            return false;
        }

        BlockPos grounded = groundAt(b.level, raw);

        Vec3 lf = player.getLookAngle();
        Vec3 lookFwd = new Vec3(lf.x, 0, lf.z);
        if (lookFwd.lengthSqr() > 1e-4) lookFwd = lookFwd.normalize();

        var orders = (side == Side.FRIEND) ? b.friendOrders : b.enemyOrders;

        if (orders.selected == CommandGroup.FOOTMEN || orders.selected == CommandGroup.BOTH) {
            orders.footMovePos = grounded;
            orders.footMode = BattleInstance.OrderMode.MOVE;
            if (lookFwd.lengthSqr() > 1e-4) orders.footStableForward = lookFwd;
        }

        if (orders.selected == CommandGroup.ARCHERS || orders.selected == CommandGroup.BOTH) {
            orders.archerMovePos = grounded;
            orders.archerMode = BattleInstance.OrderMode.MOVE;
            if (lookFwd.lengthSqr() > 1e-4) orders.archerStableForward = lookFwd;
        }

        return true;
    }


    public static CommandResult issueFollowOrderEx(ServerPlayer player) {
        CommanderCtx ctx = findCommanderCtx(player);
        if (ctx == null) return CommandResult.NO_BATTLE;

        Vec3 f = flatLook(player);

        var orders = (ctx.side == Side.FRIEND) ? ctx.battle.friendOrders : ctx.battle.enemyOrders;

        orders.footMode = BattleInstance.OrderMode.FOLLOW;
        orders.footMovePos = null;
        orders.footStableForward = f;

        orders.archerMode = BattleInstance.OrderMode.FOLLOW;
        orders.archerMovePos = null;
        orders.archerStableForward = f;

        return CommandResult.OK;
    }


    public static boolean issueFollowOrder(ServerPlayer player) {
        return issueFollowOrderEx(player) == CommandResult.OK;
    }

    

    // --------------------
    // One battle instance = one active fight
    // --------------------
    private static final class BattleInstance {

        private enum OrderMode { FOLLOW, MOVE }

        private static final class SideOrders {
            CommandGroup selected = CommandGroup.FOOTMEN;

            OrderMode footMode = OrderMode.FOLLOW;
            BlockPos footMovePos = null;
            Vec3 footStableForward = new Vec3(1, 0, 0);

            OrderMode archerMode = OrderMode.FOLLOW;
            BlockPos archerMovePos = null;
            Vec3 archerStableForward = new Vec3(1, 0, 0);
        }

        final UUID battleId = UUID.randomUUID();

        final UUID playerKingdomId;
        final UUID enemyKingdomId;

        // commanders
        final UUID friendCommanderUuid;          // controls Side.FRIEND
        final @org.jetbrains.annotations.Nullable UUID enemyCommanderUuid; // controls Side.ENEMY (null = AI)

        final ServerLevel level;
        final BattleZone zone;

        final BlockPos enemyRally;
        final BlockPos friendRally;

        final int ticketsStartFriend;
        final int ticketsStartEnemy;

        int ticketsFriend;
        int ticketsEnemy;

        float moraleFriend = START_MORALE;
        float moraleEnemy  = START_MORALE;

        UUID enemyKingEntity;
        final Map<UUID, UnitMeta> units = new HashMap<>();
        final ArrayDeque<PendingSpawn> pending = new ArrayDeque<>();
        final ArrayDeque<Long> friendDeathTicks = new ArrayDeque<>();
        final ArrayDeque<Long> enemyDeathTicks  = new ArrayDeque<>();

        long tick;

        // Orders per side:
        final SideOrders friendOrders = new SideOrders();
        final SideOrders enemyOrders  = new SideOrders();

        // Only used for AI-enemy battles:
        Vec3 enemyAdvancePos = null;

        BattleInstance(UUID playerKingdomId, UUID enemyKingdomId,
                    UUID friendCommanderUuid,
                    UUID enemyCommanderUuid,
                    ServerLevel level, BattleZone zone,
                    int ticketsFriend, int ticketsEnemy,
                    BlockPos friendRally, BlockPos enemyRally) {
            this.playerKingdomId = playerKingdomId;
            this.enemyKingdomId = enemyKingdomId;
            this.friendCommanderUuid = friendCommanderUuid;
            this.enemyCommanderUuid = enemyCommanderUuid;
            this.level = level;
            this.zone = zone;
            this.ticketsStartFriend = ticketsFriend;
            this.ticketsStartEnemy = ticketsEnemy;
            this.ticketsFriend = ticketsFriend;
            this.ticketsEnemy = ticketsEnemy;
            this.friendRally = friendRally;
            this.enemyRally = enemyRally;
        }

        boolean isPvP() { return enemyCommanderUuid != null; }
    }


    private record UnitMeta(Side side, boolean isKing, UnitRole role, int formationIndex) {}
    private record PendingSpawn(long atTick, Side side, boolean isKing, UnitRole role) {}

    // --------------------
    // Helpers
    // --------------------
    private record RulerInfo(String name, int skinId) {}

    private static RulerInfo resolveRulerInfo(MinecraftServer server, UUID kingdomId, Side side, ServerPlayer fallbackPlayer) {

        try {
            ServerLevel overworld = server.overworld();
            if (overworld != null) {
                Entity ent = overworld.getEntity(kingdomId);
                if (ent instanceof name.kingdoms.entity.aiKingdomEntity kingEnt) {
                    String nm0 = kingEnt.getKingName();
                    int skin0 = kingEnt.getSkinId();

                    String nm = (nm0 == null || nm0.isBlank()) ? "Enemy King" : nm0;
                    return new RulerInfo(nm, skin0);
                }
            }
        } catch (Exception ignored) {}

        Object kObj = null;
        try {
            var ks = kingdomState.get(server);
            kObj = ks.getKingdom(kingdomId);
        } catch (Exception ignored) {}

        Object aiObj = null;
        try {
            aiObj = aiKingdomState.get(server).getById(kingdomId);
        } catch (Exception ignored) {}

        String nm = tryReadString(kObj, "kingName", "rulerName", "leaderName");
        if (nm == null) nm = tryReadString(aiObj, "kingName", "rulerName", "leaderName");

        if (nm == null) {
            if (side == Side.FRIEND) {
         
            nm = fallbackPlayer.getName().getString();

            try {
                var ks = kingdomState.get(server);
                var k = ks.getKingdom(kingdomId);
                if (k != null) {
                    UUID owner = k.owner; // your Kingdom has owner
                    var online = server.getPlayerList().getPlayer(owner);
                    if (online != null) {
                        nm = online.getName().getString();
                    } else if (k.name != null && !k.name.isBlank()) {
                        // Offline owner: use kingdom name instead of profile lookup
                        nm = k.name;
                    }
                }
            } catch (Exception ignored) {}
        }else {
                nm = tryReadString(aiObj, "name");
                if (nm == null) {
                    try {
                        var ks = kingdomState.get(server);
                        var ek = ks.getKingdom(kingdomId);
                        if (ek != null && ek.name != null && !ek.name.isBlank()) nm = ek.name;
                    } catch (Exception ignored) {}
                }
                if (nm == null) nm = "Enemy King";
            }
        }

        Integer skin = tryReadInt(kObj, "skinId", "kingSkinId", "rulerSkinId", "skin");
        if (skin == null) skin = tryReadInt(aiObj, "skinId", "kingSkinId", "rulerSkinId", "skin");
        if (skin == null) skin = 0;

        return new RulerInfo(nm, Math.max(0, skin));
    }

    private static Integer tryReadInt(Object obj, String... names) {
        if (obj == null) return null;
        Class<?> c = obj.getClass();
        for (String n : names) {
            for (Class<?> cc = c; cc != null; cc = cc.getSuperclass()) {
                try {
                    var f = cc.getDeclaredField(n);
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v instanceof Number num) return num.intValue();
                } catch (Exception ignored) {}
            }
            String mname = "get" + Character.toUpperCase(n.charAt(0)) + n.substring(1);
            try {
                var m = c.getMethod(mname);
                Object v = m.invoke(obj);
                if (v instanceof Number num) return num.intValue();
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static String tryReadString(Object obj, String... names) {
        if (obj == null) return null;
        Class<?> c = obj.getClass();
        for (String n : names) {
            for (Class<?> cc = c; cc != null; cc = cc.getSuperclass()) {
                try {
                    var f = cc.getDeclaredField(n);
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v instanceof String s && !s.isBlank()) return s;
                } catch (Exception ignored) {}
            }
            String mname = "get" + Character.toUpperCase(n.charAt(0)) + n.substring(1);
            try {
                var m = c.getMethod(mname);
                Object v = m.invoke(obj);
                if (v instanceof String s && !s.isBlank()) return s;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static void advanceEnemyAnchor(BattleInstance battle, ServerPlayer player) {
        if (battle.enemyAdvancePos == null) {
            battle.enemyAdvancePos = Vec3.atCenterOf(battle.enemyRally);
        }

        Vec3 cur = battle.enemyAdvancePos;
        Vec3 tgt = player.position();

        Vec3 to = new Vec3(tgt.x - cur.x, 0, tgt.z - cur.z);
        double dist = Math.sqrt(to.x * to.x + to.z * to.z);
        if (dist < 1e-4) return;

        if (dist <= ENEMY_ADVANCE_STOP_DIST) return;

        double move = Math.min(ENEMY_ADVANCE_STEP, dist - ENEMY_ADVANCE_STOP_DIST);
        Vec3 dir = to.scale(1.0 / dist);
        Vec3 next = cur.add(dir.scale(move));

        int nx = clampWithMargin(Mth.floor(next.x), battle.zone.minX(), battle.zone.maxX(), ENEMY_ADVANCE_MARGIN);
        int nz = clampWithMargin(Mth.floor(next.z), battle.zone.minZ(), battle.zone.maxZ(), ENEMY_ADVANCE_MARGIN);
        int ny = battle.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz);

        battle.enemyAdvancePos = new Vec3(nx + 0.5, ny, nz + 0.5);
    }

    private static Vec3 centerOfSideRole(BattleInstance battle, Side side, UnitRole role, Vec3 fallback) {
        Vec3 sum = Vec3.ZERO;
        int n = 0;

        for (var e : battle.units.entrySet()) {
            UnitMeta meta = e.getValue();
            if (meta.side() != side) continue;
            if (meta.isKing()) continue;
            if (meta.role() != role) continue;

            Entity ent = battle.level.getEntity(e.getKey());
            if (!(ent instanceof LivingEntity le) || !le.isAlive()) continue;

            sum = sum.add(le.position());
            n++;
        }

        if (n == 0) return fallback;
        return sum.scale(1.0 / (double) n);
    }

    private static Vec3 stepTowardInZone(BattleInstance battle, Vec3 from, Vec3 to, double step, int margin) {
        Vec3 delta = new Vec3(to.x - from.x, 0, to.z - from.z);
        double dist = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (dist < 1e-4) return from;

        double move = Math.min(step, dist);
        Vec3 dir = delta.scale(1.0 / dist);
        Vec3 next = from.add(dir.scale(move));

        int nx = clampWithMargin(Mth.floor(next.x), battle.zone.minX(), battle.zone.maxX(), margin);
        int nz = clampWithMargin(Mth.floor(next.z), battle.zone.minZ(), battle.zone.maxZ(), margin);
        int ny = battle.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz);

        return new Vec3(nx + 0.5, ny, nz + 0.5);
    }

    private static void moveEnemyKingWithFootmen(BattleInstance battle, Vec3 footLeaderPos) {
        if (battle.enemyKingEntity == null) return;

        Entity ent = battle.level.getEntity(battle.enemyKingEntity);
        if (!(ent instanceof Mob king) || !king.isAlive()) return;

        // Keep king close to the footmen leader (slightly behind so it doesn't front-run)
        Vec3 tgt = footLeaderPos;

        double d2 = king.position().distanceToSqr(tgt);
        if (d2 <= (2.5 * 2.5)) return;

        double spd = (d2 > (16.0 * 16.0)) ? 1.35 : 1.15;
        king.getNavigation().moveTo(tgt.x, tgt.y, tgt.z, spd);

        // If your SoldierEntity teleport assist is enabled, keep its formation target updated too
        if (king instanceof SoldierEntity se) {
            se.setFormationTarget(new BlockPos(Mth.floor(tgt.x), Mth.floor(tgt.y), Mth.floor(tgt.z)));
        }
    }


    private static int getAiAliveSoldiers(MinecraftServer server, UUID aiKingdomId) {
        var ai = aiKingdomState.get(server).getById(aiKingdomId);
        if (ai == null) return 0;
        return Math.max(0, ai.aliveSoldiers);
    }

    private static int computeTotalSoldiers(MinecraftServer server, UUID kingdomId) {
        // AI kingdom?
        var ai = aiKingdomState.get(server).getById(kingdomId);
        if (ai != null) return Math.max(0, ai.aliveSoldiers);

        // Player kingdom (tickets based on garrisons)
        return computePlayerTickets(server, kingdomId);
    }


    private static int clampWithMargin(int v, int min, int max, int margin) {
        int lo = min + margin;
        int hi = max - margin;
        if (lo > hi) {
            lo = min;
            hi = max;
        }
        return Mth.clamp(v, lo, hi);
    }

    private static int slotsFor(UnitRole role) {
        return (role == UnitRole.ARCHER) ? ARCH_SLOTS : FOOT_SLOTS;
    }

    private static int allocateFormationSlot(BattleInstance b, Side side, UnitRole role) {
        int max = slotsFor(role);
        boolean[] used = new boolean[max];

        for (UnitMeta m : b.units.values()) {
            if (m.side() != side || m.isKing() || m.role() != role) continue;
            int idx = m.formationIndex();
            if (idx >= 0 && idx < max) used[idx] = true;
        }

        for (int i = 0; i < max; i++) if (!used[i]) return i;
        return max - 1;
    }

    private static void trackUnit(BattleInstance b, SoldierEntity soldier, Side side, boolean isKing, UnitRole role) {
        int idx = isKing ? -1 : allocateFormationSlot(b, side, role);
        b.units.put(soldier.getUUID(), new UnitMeta(side, isKing, role, idx));
    }

    private static BlockPos clampToZone(BattleZone zone, int x, int z, int margin, int y) {
        int cx = clampWithMargin(x, zone.minX(), zone.maxX(), margin);
        int cz = clampWithMargin(z, zone.minZ(), zone.maxZ(), margin);
        return new BlockPos(cx, y, cz);
    }

    private static BlockPos oppositePointInZone(BattleZone zone, BlockPos playerPos, int margin) {
        int cx = (zone.minX() + zone.maxX()) / 2;
        int cz = (zone.minZ() + zone.maxZ()) / 2;

        int ox = cx - (playerPos.getX() - cx);
        int oz = cz - (playerPos.getZ() - cz);

        ox = clampWithMargin(ox, zone.minX(), zone.maxX(), margin);
        oz = clampWithMargin(oz, zone.minZ(), zone.maxZ(), margin);

        return new BlockPos(ox, playerPos.getY(), oz);
    }

    private static Vec3 flatLook(ServerPlayer p) {
        Vec3 look = p.getLookAngle();
        Vec3 f = new Vec3(look.x, 0, look.z);
        if (f.lengthSqr() < 1e-4) f = new Vec3(1, 0, 0);
        return f.normalize();
    }

    private static BlockPos groundAt(ServerLevel level, Vec3 pos) {
        int x = Mth.floor(pos.x);
        int z = Mth.floor(pos.z);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    private static boolean withinOrderRange(ServerPlayer p, Vec3 target, double max) {
        double dx = target.x - p.getX();
        double dz = target.z - p.getZ();
        return (dx * dx + dz * dz) <= (max * max);
    }

    // --------------------
    // Scoreboard teams (fix allied/damage rules)
    // --------------------
    private static final String TEAM_FRIEND = "kingdoms_friend";
    private static final String TEAM_ENEMY  = "kingdoms_enemy";

    private static PlayerTeam getOrCreateTeam(Scoreboard sb, String name) {
        PlayerTeam t = sb.getPlayerTeam(name);
        if (t == null) t = sb.addPlayerTeam(name);

        // Friendly fire within same side off (optional but recommended)
        t.setAllowFriendlyFire(false);

        return t;
    }

    private static void assignSoldierTeam(MinecraftServer server, SoldierEntity soldier) {
        Scoreboard sb = server.getScoreboard();

        PlayerTeam friend = getOrCreateTeam(sb, TEAM_FRIEND);
        PlayerTeam enemy  = getOrCreateTeam(sb, TEAM_ENEMY);

        // IMPORTANT: entities are keyed by UUID string in the scoreboard
        String entry = soldier.getStringUUID();

        PlayerTeam cur = sb.getPlayersTeam(entry);
        if (cur != null) sb.removePlayerFromTeam(entry);

        if (soldier.getSide() == SoldierEntity.Side.FRIEND) {
            sb.addPlayerToTeam(entry, friend);
        } else {
            sb.addPlayerToTeam(entry, enemy);
        }
    }


    // --------------------
    // Formation math
    // --------------------
    private static void applyRectFormation(
            BattleInstance battle,
            Side side,
            UnitRole role,
            Vec3 leaderPos,
            Vec3 leaderForward,
            double behindDist,
            int cols,
            int rows,
            double spacing
    ) {
        Vec3 f = new Vec3(leaderForward.x, 0, leaderForward.z);
        if (f.lengthSqr() < 1e-4) f = new Vec3(1, 0, 0);
        f = f.normalize();

        Vec3 back = f.scale(-1.0);
        Vec3 right = new Vec3(-f.z, 0, f.x);

        Vec3 base = leaderPos.add(back.scale(behindDist));
        int maxSlots = cols * rows;

        for (var e : battle.units.entrySet()) {
            UnitMeta meta = e.getValue();
            if (meta.side() != side || meta.isKing() || meta.role() != role) continue;

            int slotIndex = meta.formationIndex();
            if (slotIndex < 0) continue;
            if (slotIndex >= maxSlots) slotIndex = maxSlots - 1;

            Entity ent = battle.level.getEntity(e.getKey());
            if (!(ent instanceof Mob mob) || !mob.isAlive()) continue;

            int col = slotIndex % cols;
            int row = slotIndex / cols;

            double xOff = (col - (cols - 1) * 0.5) * spacing;
            double zOff = row * spacing;

            Vec3 slot = base.add(right.scale(xOff)).add(back.scale(zOff));

            int sx = Mth.floor(slot.x);
            int sz = Mth.floor(slot.z);
            int sy = battle.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz);

            // Always update formation target (even if already in place)
            if (ent instanceof SoldierEntity se) {
                se.setFormationTarget(new BlockPos(sx, sy, sz));
            }

            double d2 = mob.distanceToSqr(slot.x, sy, slot.z);
            if (d2 <= (2.0 * 2.0)) continue;

            double spd = (d2 > (16.0 * 16.0)) ? 1.6 : 1.25;
            mob.getNavigation().moveTo(slot.x, sy, slot.z, spd);
        }

    }

    private static Vec3 slotPosFor(
            BattleInstance battle,
            Vec3 leaderPos,
            Vec3 leaderForward,
            double behindDist,
            int cols,
            double spacing,
            int slotIndex
    ) {
        Vec3 f = new Vec3(leaderForward.x, 0, leaderForward.z);
        if (f.lengthSqr() < 1e-4) f = new Vec3(1, 0, 0);
        f = f.normalize();

        Vec3 back = f.scale(-1.0);
        Vec3 right = new Vec3(-f.z, 0, f.x);

        int col = slotIndex % cols;
        int row = slotIndex / cols;

        double xOff = (col - (cols - 1) * 0.5) * spacing;
        double zOff = row * spacing;

        Vec3 base = leaderPos.add(back.scale(behindDist));
        Vec3 desired = base.add(right.scale(xOff)).add(back.scale(zOff));

        int sx = clampWithMargin(Mth.floor(desired.x), battle.zone.minX(), battle.zone.maxX(), 1);
        int sz = clampWithMargin(Mth.floor(desired.z), battle.zone.minZ(), battle.zone.maxZ(), 1);
        int sy = battle.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz);

        return new Vec3(sx + 0.5, sy, sz + 0.5);
    }

    // --------------------
    // Tick
    // --------------------
    private static void tick(MinecraftServer server) {
        tryStartBattles(server);

        if (ACTIVE.isEmpty()) return;

        var it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            var battle = it.next().getValue();
            battle.tick++;

            ServerPlayer friendCommander = server.getPlayerList().getPlayer(battle.friendCommanderUuid);
            ServerPlayer enemyCommander = (battle.enemyCommanderUuid != null)
                    ? server.getPlayerList().getPlayer(battle.enemyCommanderUuid)
                    : null;

            if (friendCommander == null || !friendCommander.isAlive()) {
                if (friendCommander != null) clearHud(friendCommander);
                if (enemyCommander != null) clearHud(enemyCommander);
                finishBattle(server, battle, Side.ENEMY, "Battle ended: friendly commander died/disconnected.");
                it.remove();
                continue;
            }

            // PvP: if enemy commander dies/disconnects, friend wins immediately
            if (battle.enemyCommanderUuid != null && (enemyCommander == null || !enemyCommander.isAlive())) {
                if (enemyCommander != null) clearHud(enemyCommander);
                finishBattle(server, battle, Side.FRIEND, "Battle ended: enemy commander died/disconnected.");
                it.remove();
                continue;
            }


            pruneOld(battle.friendDeathTicks, battle.tick - LOSS_WINDOW_TICKS);
            pruneOld(battle.enemyDeathTicks, battle.tick - LOSS_WINDOW_TICKS);

            scanTrackedUnits(server, battle);
            processRespawns(battle);

           if (battle.tick % FORMATION_REFRESH_TICKS == 0) {
                keepFormation(server, battle);
            }

            if (battle.tick % TARGET_REFRESH_TICKS == 0) {
                refreshTargets(server, battle, friendCommander, enemyCommander);
            }

            if (battle.moraleFriend <= 0f) {
                finishBattle(server, battle, Side.ENEMY, "Battle ended: friendly morale collapsed.");
                it.remove();
                continue;
            }
            if (battle.moraleEnemy <= 0f) {
                finishBattle(server, battle, Side.FRIEND, "Battle ended: enemy morale collapsed.");
                it.remove();
                continue;
            }

            if (battle.ticketsFriend <= 0 && countAlive(battle, Side.FRIEND) == 0) {
                finishBattle(server, battle, Side.ENEMY, "Battle ended: friend tickets exhausted and no units left.");
                it.remove();
                continue;
            }
            if (battle.ticketsEnemy <= 0 && countAlive(battle, Side.ENEMY) == 0) {
                finishBattle(server, battle, Side.FRIEND, "Battle ended: enemy tickets exhausted and no units left.");
                it.remove();
                continue;
            }

            if (battle.tick % HUD_SYNC_TICKS == 0) {
                sendHudToCommander(friendCommander, battle, Side.FRIEND);
                if (enemyCommander != null) sendHudToCommander(enemyCommander, battle, Side.ENEMY);
            }

        }
    }

    private static void pruneOld(ArrayDeque<Long> q, long minTickInclusive) {
        while (!q.isEmpty() && q.peekFirst() < minTickInclusive) q.removeFirst();
    }

    private static void clearHud(ServerPlayer player) {
        ServerPlayNetworking.send(player, new warBattleHudSyncPayload(false, 0, 0, 0f, 0f));
    }

    // --------------------
    // Battle start
    // --------------------
    private static void tryStartBattles(MinecraftServer server) {
        var ws = name.kingdoms.war.WarState.get(server);
        if (ws.wars().isEmpty()) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID playerKingdomId = findPlayersKingdomId(server, player);
            if (playerKingdomId == null) continue;

            var ks = kingdomState.get(server);
            var pk = ks.getKingdom(playerKingdomId);
            if (pk != null && pk.owner != null && !pk.owner.equals(player.getUUID())) {
                continue; // only ruler can trigger the battle start
            }

            for (String key : ws.wars()) {
                UUID enemyId = otherFromWarKey(key, playerKingdomId);
                if (enemyId == null) continue;

                var zoneOpt = ws.getZone(playerKingdomId, enemyId);
                if (zoneOpt.isEmpty()) continue;

                BattleZone zone = zoneOpt.get();
                if (!zone.contains(player.blockPosition())) continue;

                if (isBattleActiveForPair(playerKingdomId, enemyId)) continue;

                startBattle(server, player, playerKingdomId, enemyId, zone);
            }
        }
    }

    /** Parses "u1|u2" and returns the OTHER uuid if self matches either side, else null. */
    private static UUID otherFromWarKey(String key, UUID self) {
        int bar = key.indexOf('|');
        if (bar < 0) return null;

        UUID a, b;
        try {
            a = UUID.fromString(key.substring(0, bar));
            b = UUID.fromString(key.substring(bar + 1));
        } catch (Exception ignored) {
            return null;
        }

        if (self.equals(a)) return b;
        if (self.equals(b)) return a;
        return null;
    }

    private static boolean isBattleActiveForPair(UUID a, UUID b) {
        for (var bi : ACTIVE.values()) {
            if ((bi.playerKingdomId.equals(a) && bi.enemyKingdomId.equals(b)) ||
                (bi.playerKingdomId.equals(b) && bi.enemyKingdomId.equals(a))) return true;
        }
        return false;
    }

    private static void startBattle(MinecraftServer server, ServerPlayer player, UUID playerKingdomId, UUID enemyKingdomId, BattleZone zone) {
        ServerLevel level = server.overworld();

        var ks = kingdomState.get(server);
        var aiState = aiKingdomState.get(server);

        boolean enemyIsAi = (aiState.getById(enemyKingdomId) != null);

        // Friend commander = owner of the friend kingdom if present, else whoever triggered
        UUID friendCommander = player.getUUID();
        var fk = ks.getKingdom(playerKingdomId);
        if (fk != null && fk.owner != null) friendCommander = fk.owner;

        // Enemy commander if PvP (null = AI)
        UUID enemyCommander = null;
        if (!enemyIsAi) {
            var ek = ks.getKingdom(enemyKingdomId);
            if (ek != null && ek.owner != null) {
                // require online for PvP battle creation (recommended)
                ServerPlayer onlineEnemy = server.getPlayerList().getPlayer(ek.owner);
                if (onlineEnemy != null) {
                    enemyCommander = ek.owner;
                } else {

                    return;
                }
            } else {
                return; 
            }
        }


        int friendTotal = computeTotalSoldiers(server, playerKingdomId);
        int enemyTotal  = computeTotalSoldiers(server, enemyKingdomId);


        int friendDeploy = Math.min(SOLDIERS_PER_SIDE, Math.max(0, friendTotal));
        int enemyDeploy  = Math.min(SOLDIERS_PER_SIDE, Math.max(0, enemyTotal));

        int friendTickets = Math.max(0, friendTotal - friendDeploy);
        int enemyTickets  = Math.max(0, enemyTotal  - enemyDeploy);

        int friendArch = (int) Math.round(friendDeploy * FRIEND_ARCHER_RATIO);
        friendArch = Mth.clamp(friendArch, 0, friendDeploy);
        int friendFoot = friendDeploy - friendArch;

        int enemyArch = (int) Math.round(enemyDeploy * ENEMY_ARCHER_RATIO);
        enemyArch = Mth.clamp(enemyArch, 0, enemyDeploy);
        int enemyFoot = enemyDeploy - enemyArch;

        Vec3 look = player.getLookAngle();
        Vec3 dir = new Vec3(look.x, 0, look.z);
        if (dir.lengthSqr() < 1e-4) dir = new Vec3(1, 0, 0);
        dir = dir.normalize();

        final int MARGIN = 6;
        final double FRIEND_BACK = 12;

        int fx = Mth.floor(player.getX() - dir.x * FRIEND_BACK);
        int fz = Mth.floor(player.getZ() - dir.z * FRIEND_BACK);
        BlockPos friend2D = clampToZone(zone, fx, fz, MARGIN, player.blockPosition().getY());

        BlockPos enemy2D = oppositePointInZone(zone, player.blockPosition(), MARGIN);

        int yFriend = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, friend2D.getX(), friend2D.getZ());
        int yEnemy  = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, enemy2D.getX(), enemy2D.getZ());

        BlockPos friendRally = new BlockPos(friend2D.getX(), yFriend, friend2D.getZ());
        BlockPos enemyRally  = new BlockPos(enemy2D.getX(),  yEnemy,  enemy2D.getZ());

        Vec3 enemyForward = player.position().subtract(enemyRally.getX() + 0.5, player.getY(), enemyRally.getZ() + 0.5);
        enemyForward = new Vec3(enemyForward.x, 0, enemyForward.z);
        if (enemyForward.lengthSqr() < 1e-4) enemyForward = dir.scale(-1);
        enemyForward = enemyForward.normalize();

        Vec3 friendForward = new Vec3(-enemyForward.x, 0, -enemyForward.z);

        var battle = new BattleInstance(
                playerKingdomId, enemyKingdomId,
                friendCommander,
                enemyCommander, // null if AI
                level, zone,
                friendTickets, enemyTickets,
                friendRally, enemyRally
        );


        battle.enemyAdvancePos = Vec3.atCenterOf(enemyRally);
        ACTIVE.put(battle.battleId, battle);

        COMMANDER_INDEX.put(battle.friendCommanderUuid, new CommanderCtx(battle, Side.FRIEND));
        if (battle.enemyCommanderUuid != null) {
            COMMANDER_INDEX.put(battle.enemyCommanderUuid, new CommanderCtx(battle, Side.ENEMY));
        }

        battle.friendOrders.footStableForward = friendForward;
        battle.friendOrders.archerStableForward = friendForward;

        // useful defaults for PvP (and harmless for AI)
        battle.enemyOrders.footStableForward = enemyForward;
        battle.enemyOrders.archerStableForward = enemyForward;

        player.displayClientMessage(Component.literal(
                "[WAR] friendTotal=" + friendTotal + " enemyTotal=" + enemyTotal
                        + " | friendDeploy=" + friendDeploy + " (foot " + friendFoot + ", arch " + friendArch + ")"
                        + " | enemyDeploy=" + enemyDeploy + " (foot " + enemyFoot + ", arch " + enemyArch + ")"
                        + " | friendRally=" + friendRally + " enemyRally=" + enemyRally
        ), false);

        // --------------------
        // Captains / Kings
        // --------------------
        RulerInfo friendRuler = resolveRulerInfo(server, playerKingdomId, Side.FRIEND, player);
        RulerInfo enemyRuler  = resolveRulerInfo(server, enemyKingdomId, Side.ENEMY,  player);

        boolean friendCommanderIsPlayer = (server.getPlayerList().getPlayer(friendCommander) != null);

        if (!friendCommanderIsPlayer) {
            var friendCaptain = spawnSoldier(
                    level,
                    friendRally,
                    Side.FRIEND,
                    UnitRole.FOOTMAN,
                    false,            // bannerman
                    true,             // captain/king
                    friendRuler.skinId(),
                    Component.literal(friendRuler.name())
            );
            if (friendCaptain != null) trackUnit(battle, friendCaptain, Side.FRIEND, true, UnitRole.FOOTMAN);
        }

        // ENEMY king entity: always spawn (AI king, or PvP enemy "king" soldier if you want a visible king avatar)
        var enemyCaptain = spawnSoldier(
                level,
                enemyRally,
                Side.ENEMY,
                UnitRole.FOOTMAN,
                true,            // bannerman
                true,             // captain/king
                enemyRuler.skinId(),
                Component.literal(enemyRuler.name())
        );

        if (enemyCaptain != null) {
            battle.enemyKingEntity = enemyCaptain.getUUID();
            trackUnit(battle, enemyCaptain, Side.ENEMY, true, UnitRole.FOOTMAN);
        }


        // Spawn formations (1/10 are bannermen; banner color derived from side in SoldierEntity)
        if (enemyFoot > 0) spawnFormation(level, battle, Side.ENEMY, UnitRole.FOOTMAN, enemyRally, enemyForward, +4.0, enemyFoot);
        if (enemyArch > 0) spawnFormation(level, battle, Side.ENEMY, UnitRole.ARCHER,  enemyRally, enemyForward, -3.0, enemyArch);

        if (friendFoot > 0) spawnFormation(level, battle, Side.FRIEND, UnitRole.FOOTMAN, friendRally, friendForward, +4.0, friendFoot);
        if (friendArch > 0) spawnFormation(level, battle, Side.FRIEND, UnitRole.ARCHER,  friendRally, friendForward, -3.0, friendArch);

        player.displayClientMessage(Component.literal(
                "Battle started! Friend total=" + friendTotal + " (deploy " + friendDeploy + ", tickets " + friendTickets + ")"
                        + " | Enemy total=" + enemyTotal + " (deploy " + enemyDeploy + ", tickets " + enemyTickets + ")"
        ), false);

        sendHudToCommander(player, battle, Side.FRIEND);

        // if enemy commander exists, they will start receiving HUD via tick anyway,
        // but you can “wake” it instantly:
        if (battle.enemyCommanderUuid != null) {
            var ep = server.getPlayerList().getPlayer(battle.enemyCommanderUuid);
            if (ep != null) sendHudToCommander(ep, battle, Side.ENEMY);
        }
    }

    // --------------------
    // Unit tracking + deaths
    // --------------------
    private static void scanTrackedUnits(MinecraftServer server, BattleInstance battle) {
        var it = battle.units.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            UUID id = e.getKey();
            UnitMeta meta = e.getValue();

            Entity ent = battle.level.getEntity(id);
            if (ent instanceof LivingEntity le && le.isAlive()) continue;

            it.remove();
            onUnitDeath(battle, meta.side(), meta.isKing(), meta.role());
        }
    }

    private static void onUnitDeath(BattleInstance battle, Side side, boolean isKing, UnitRole role) {
        float extraRateHit;
        if (side == Side.FRIEND) {
            battle.friendDeathTicks.addLast(battle.tick);
            extraRateHit = (float) battle.friendDeathTicks.size() * 0.15f;
            battle.moraleFriend -= (DEATH_MORALE_HIT + extraRateHit);
            if (isKing) battle.moraleFriend -= KING_DEATH_HIT;
        } else {
            battle.enemyDeathTicks.addLast(battle.tick);
            extraRateHit = (float) battle.enemyDeathTicks.size() * 0.15f;
            battle.moraleEnemy -= (DEATH_MORALE_HIT + extraRateHit);
            if (isKing) battle.moraleEnemy -= KING_DEATH_HIT;
        }

        if (isKing) return;

        if (battle.enemyCommanderUuid == null && side == Side.ENEMY) {
            var server = battle.level.getServer();
            if (server != null) {
                var aiState = aiKingdomState.get(server);
                var ai = aiState.getById(battle.enemyKingdomId);
                if (ai != null) {
                    ai.aliveSoldiers = Math.max(0, ai.aliveSoldiers - 1);
                    aiState.setDirty();
                }
            }
        }

        if (side == Side.FRIEND) {
            if (battle.ticketsFriend > 0) {
                battle.ticketsFriend--;
                battle.pending.addLast(new PendingSpawn(battle.tick + RESPAWN_DELAY_TICKS, side, false, role));
            }
        } else {
            if (battle.ticketsEnemy > 0) {
                battle.ticketsEnemy--;
                battle.pending.addLast(new PendingSpawn(battle.tick + RESPAWN_DELAY_TICKS, side, false, role));
            }
        }
    }

    private static void sendHud(ServerPlayer player, boolean active, BattleInstance b) {
        ServerPlayNetworking.send(player, new warBattleHudSyncPayload(
                active,
                b.ticketsFriend,
                b.ticketsEnemy,
                b.moraleFriend,
                b.moraleEnemy
        ));
    }

    private static void sendHudToCommander(ServerPlayer player, BattleInstance b, Side commanderSide) {
        int ownTickets   = (commanderSide == Side.FRIEND) ? b.ticketsFriend : b.ticketsEnemy;
        int theirTickets = (commanderSide == Side.FRIEND) ? b.ticketsEnemy  : b.ticketsFriend;

        float ownMorale   = (commanderSide == Side.FRIEND) ? b.moraleFriend : b.moraleEnemy;
        float theirMorale = (commanderSide == Side.FRIEND) ? b.moraleEnemy  : b.moraleFriend;

        ServerPlayNetworking.send(player, new warBattleHudSyncPayload(true, ownTickets, theirTickets, ownMorale, theirMorale));
    }


    private static int countAlive(BattleInstance battle, Side side) {
        int c = 0;
        for (var meta : battle.units.values()) if (meta.side() == side) c++;
        return c;
    }

    // --------------------
    // Respawning
    // --------------------
    private static void processRespawns(BattleInstance battle) {
        while (!battle.pending.isEmpty() && battle.pending.peekFirst().atTick() <= battle.tick) {
            var ps = battle.pending.removeFirst();
            if (ps.isKing()) continue;

            BlockPos rally = (ps.side() == Side.ENEMY) ? battle.enemyRally : battle.friendRally;

            boolean bannerman = (battle.level.random.nextInt(10) == 0);

            var mob = spawnSoldier(
                    battle.level,
                    rally,
                    ps.side(),
                    ps.role(),
                    bannerman,
                    false,      // captain
                    0,          // skinId for normal troops
                    Component.literal(ps.side() == Side.ENEMY ? "Enemy Soldier" : "Friendly Soldier")
            );

            if (mob != null) {
                trackUnit(battle, mob, ps.side(), false, ps.role());
            }
        }
    }

    // --------------------
    // Formation + targeting
    // --------------------
    private static void keepFormation(MinecraftServer server, BattleInstance battle) {

        ServerPlayer friendCommander = server.getPlayerList().getPlayer(battle.friendCommanderUuid);
        ServerPlayer enemyCommander  = (battle.enemyCommanderUuid != null)
                ? server.getPlayerList().getPlayer(battle.enemyCommanderUuid)
                : null;

        // FRIEND side always exists
        applySideFormation(battle, Side.FRIEND, friendCommander, battle.friendOrders);

        // ENEMY side: PvP uses enemy commander orders; AI keeps your existing advance anchor logic
        if (battle.enemyCommanderUuid != null) {
            applySideFormation(battle, Side.ENEMY, enemyCommander, battle.enemyOrders);
        } else {
            // AI ENEMY behavior:
            if (friendCommander != null) {

                // 1) Figure out where the FRIEND footmen actually are (fallback: commander position)
                Vec3 friendFootCenter = centerOfSideRole(
                        battle,
                        Side.FRIEND,
                        UnitRole.FOOTMAN,
                        friendCommander.position()
                );

                // 2) Move enemy footmen leader toward that point (not toward the player directly)
                if (battle.enemyAdvancePos == null) {
                    battle.enemyAdvancePos = Vec3.atCenterOf(battle.enemyRally);
                }

                battle.enemyAdvancePos = stepTowardInZone(
                        battle,
                        battle.enemyAdvancePos,
                        friendFootCenter,
                        ENEMY_ADVANCE_STEP,      // same tuning knob you already had
                        ENEMY_ADVANCE_MARGIN
                );

                Vec3 enemyFootLeaderPos = battle.enemyAdvancePos;

                // 3) Forward direction = from enemy leader toward friend foot center
                Vec3 eFwd = friendFootCenter.subtract(enemyFootLeaderPos);
                eFwd = new Vec3(eFwd.x, 0, eFwd.z);
                if (eFwd.lengthSqr() < 1e-4) eFwd = new Vec3(1, 0, 0);
                eFwd = eFwd.normalize();

                // 4) Enemy FOOTMEN formation: advances toward friendly FOOTMEN
                applyRectFormation(battle, Side.ENEMY, UnitRole.FOOTMAN,
                        enemyFootLeaderPos, eFwd,
                        3.0, FOOT_COLS, FOOT_ROWS, 1.1);

                // 5) Enemy ARCHERS: stay behind enemy footmen so they can shoot
                // (just increase behindDist; tune 8–12 depending on how wide your formations are)
                applyRectFormation(battle, Side.ENEMY, UnitRole.ARCHER,
                        enemyFootLeaderPos, eFwd,
                        10.0, ARCH_COLS, ARCH_ROWS, 1.2);

                // 6) Enemy KING follows the footmen leader (previously excluded from formations)
                moveEnemyKingWithFootmen(battle, enemyFootLeaderPos);
            }
        }

    }

    private static void applySideFormation(BattleInstance battle, Side side, ServerPlayer commander, BattleInstance.SideOrders orders) {
        if (commander == null || !commander.isAlive()) return;

        // update stable forward while FOLLOW and moving
        Vec3 vel = commander.getDeltaMovement();
        Vec3 moveDir = new Vec3(vel.x, 0, vel.z);
        boolean moving = moveDir.lengthSqr() > 0.001;

        if (orders.footMode == BattleInstance.OrderMode.FOLLOW && moving) {
            orders.footStableForward = moveDir.normalize();
        }
        if (orders.archerMode == BattleInstance.OrderMode.FOLLOW && moving) {
            orders.archerStableForward = moveDir.normalize();
        }

        Vec3 footLeaderPos = (orders.footMode == BattleInstance.OrderMode.MOVE && orders.footMovePos != null)
                ? Vec3.atCenterOf(orders.footMovePos)
                : commander.position();
        Vec3 footForward = orders.footStableForward;

        Vec3 archLeaderPos = (orders.archerMode == BattleInstance.OrderMode.MOVE && orders.archerMovePos != null)
                ? Vec3.atCenterOf(orders.archerMovePos)
                : commander.position();
        Vec3 archForward = orders.archerStableForward;

        applyRectFormation(battle, side, UnitRole.FOOTMAN, footLeaderPos, footForward, 3.0, FOOT_COLS, FOOT_ROWS, 1.1);
        applyRectFormation(battle, side, UnitRole.ARCHER,  archLeaderPos, archForward, 7.0, ARCH_COLS, ARCH_ROWS, 1.2);
    }


    private static void refreshTargets(MinecraftServer server, BattleInstance battle,
                                    ServerPlayer friendCommander,
                                    ServerPlayer enemyCommander) {
        if (friendCommander == null || !friendCommander.isAlive()) return;

        List<LivingEntity> friend = new ArrayList<>();
        List<LivingEntity> enemy = new ArrayList<>();

        for (var e : battle.units.entrySet()) {
            Entity ent = battle.level.getEntity(e.getKey());
            if (!(ent instanceof LivingEntity le) || !le.isAlive()) continue;

            if (e.getValue().side() == Side.FRIEND) friend.add(le);
            else enemy.add(le);
        }

        // commanders are valid targets too
        friend.add(friendCommander);
        if (enemyCommander != null && enemyCommander.isAlive()) enemy.add(enemyCommander);

        // --- FRIEND leader positions/forward (from friendOrders) ---
        var fO = battle.friendOrders;

        Vec3 footLeaderPosF = (fO.footMode == BattleInstance.OrderMode.MOVE && fO.footMovePos != null)
                ? Vec3.atCenterOf(fO.footMovePos)
                : friendCommander.position();
        Vec3 footForwardF = fO.footStableForward;

        Vec3 archLeaderPosF = (fO.archerMode == BattleInstance.OrderMode.MOVE && fO.archerMovePos != null)
                ? Vec3.atCenterOf(fO.archerMovePos)
                : friendCommander.position();
        Vec3 archForwardF = fO.archerStableForward;

        // --- ENEMY leader positions/forward ---
        // PvP: use enemyOrders + enemy commander
        // AI: use advance anchor + forward toward friend commander
        Vec3 enemyLeaderPosAI = null;
        Vec3 enemyForwardAI = null;

        Vec3 footLeaderPosE, footForwardE, archLeaderPosE, archForwardE;

        if (battle.enemyCommanderUuid != null && enemyCommander != null && enemyCommander.isAlive()) {
            var eO = battle.enemyOrders;

            footLeaderPosE = (eO.footMode == BattleInstance.OrderMode.MOVE && eO.footMovePos != null)
                    ? Vec3.atCenterOf(eO.footMovePos)
                    : enemyCommander.position();
            footForwardE = eO.footStableForward;

            archLeaderPosE = (eO.archerMode == BattleInstance.OrderMode.MOVE && eO.archerMovePos != null)
                    ? Vec3.atCenterOf(eO.archerMovePos)
                    : enemyCommander.position();
            archForwardE = eO.archerStableForward;

            // enemy should also consider friend commander as a target pool (already in friend list)
        } else {
            // AI case
            enemyLeaderPosAI = (battle.enemyAdvancePos != null) ? battle.enemyAdvancePos : Vec3.atCenterOf(battle.enemyRally);
            enemyForwardAI = friendCommander.position().subtract(enemyLeaderPosAI);
            enemyForwardAI = new Vec3(enemyForwardAI.x, 0, enemyForwardAI.z);
            if (enemyForwardAI.lengthSqr() < 1e-4) enemyForwardAI = new Vec3(1, 0, 0);
            enemyForwardAI = enemyForwardAI.normalize();

            footLeaderPosE = enemyLeaderPosAI;
            footForwardE = enemyForwardAI;
            archLeaderPosE = enemyLeaderPosAI;
            archForwardE = enemyForwardAI;

        }

        double reform2 = REFORM_FIRST_DIST * REFORM_FIRST_DIST;
        double drop2   = DROP_TARGET_DIST * DROP_TARGET_DIST;

        // --- FRIENDS ---
        for (var entry : battle.units.entrySet()) {
            UnitMeta meta = entry.getValue();
            if (meta.side() != Side.FRIEND || meta.isKing()) continue;

            Entity ent = battle.level.getEntity(entry.getKey());
            if (!(ent instanceof Mob mob) || !mob.isAlive()) continue;

            // drop dead / far targets
            LivingEntity cur = mob.getTarget();
            if (cur != null && (!cur.isAlive() || mob.distanceToSqr(cur) > drop2)) {
                cur = null;
                mob.setTarget(null);
            }

            double engageDist = (meta.role() == UnitRole.ARCHER) ? ARCHER_ENGAGE_DIST : ENGAGE_DIST;
            double engage2 = engageDist * engageDist;

            // 1) choose a candidate target FIRST
            LivingEntity candidate = null;

            // PvP preference: enemy commander
            if (enemyCommander != null && enemyCommander.isAlive() && mob.distanceToSqr(enemyCommander) <= engage2) {
                candidate = enemyCommander;
            } else {
                LivingEntity best = nearestLiving(mob.position(), enemy);
                if (best != null && mob.distanceToSqr(best) <= engage2) candidate = best;
            }

            if (candidate != null) {
                mob.setTarget(candidate);
                continue;
            }

            // 2) no candidate → only then enforce reform
            Vec3 leaderPos = (meta.role() == UnitRole.ARCHER) ? archLeaderPosF : footLeaderPosF;
            Vec3 leaderFwd = (meta.role() == UnitRole.ARCHER) ? archForwardF   : footForwardF;
            double behind  = (meta.role() == UnitRole.ARCHER) ? 7.0 : 3.0;
            int cols       = (meta.role() == UnitRole.ARCHER) ? ARCH_COLS : FOOT_COLS;
            double spacing = (meta.role() == UnitRole.ARCHER) ? 1.2 : 1.1;

            Vec3 slot = slotPosFor(battle, leaderPos, leaderFwd, behind, cols, spacing, meta.formationIndex());
            if (mob.position().distanceToSqr(slot) > reform2) {
                mob.setTarget(null);
                continue;
            }

            mob.setTarget(null);
        }


        // --- ENEMIES ---
        for (var entry : battle.units.entrySet()) {
            UnitMeta meta = entry.getValue();
            if (meta.side() != Side.ENEMY || meta.isKing()) continue;

            Entity ent = battle.level.getEntity(entry.getKey());
            if (!(ent instanceof Mob mob) || !mob.isAlive()) continue;

            LivingEntity cur = mob.getTarget();
            if (cur != null && (!cur.isAlive() || mob.distanceToSqr(cur) > drop2)) {
                cur = null;
                mob.setTarget(null);
            }

            double engageDist = (meta.role() == UnitRole.ARCHER) ? ARCHER_ENGAGE_DIST : ENGAGE_DIST;
            double engage2 = engageDist * engageDist;

            LivingEntity candidate = null;

            // always prefer friend commander if close
            if (friendCommander.isAlive() && mob.distanceToSqr(friendCommander) <= engage2) {
                candidate = friendCommander;
            } else {
                LivingEntity best = nearestLiving(mob.position(), friend);
                if (best != null && mob.distanceToSqr(best) <= engage2) candidate = best;
            }

            if (candidate != null) {
                mob.setTarget(candidate);
                continue;
            }

            Vec3 leaderPos = (meta.role() == UnitRole.ARCHER) ? archLeaderPosE : footLeaderPosE;
            Vec3 leaderFwd = (meta.role() == UnitRole.ARCHER) ? archForwardE   : footForwardE;
            double behind  = (meta.role() == UnitRole.ARCHER) ? 7.0 : 3.0;
            int cols       = (meta.role() == UnitRole.ARCHER) ? ARCH_COLS : FOOT_COLS;
            double spacing = (meta.role() == UnitRole.ARCHER) ? 1.2 : 1.1;

            Vec3 slot = slotPosFor(battle, leaderPos, leaderFwd, behind, cols, spacing, meta.formationIndex());
            if (mob.position().distanceToSqr(slot) > reform2) {
                mob.setTarget(null);
                continue;
            }

            mob.setTarget(null);
        }

    }


    private static LivingEntity nearestLiving(Vec3 pos, List<LivingEntity> list) {
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (var e : list) {
            double d = e.position().distanceToSqr(pos);
            if (d < bestD) { bestD = d; best = e; }
        }
        return best;
    }

    // --------------------
    // Spawning helpers (CUSTOM SOLDIER)
    // --------------------
    private static void spawnFormation(ServerLevel level, BattleInstance battle, Side side, UnitRole role,
                                       BlockPos anchorPos, Vec3 forward, double forwardOffset, int count) {
        Vec3 anchor = new Vec3(anchorPos.getX() + 0.5, anchorPos.getY(), anchorPos.getZ() + 0.5);
        Vec3 f = new Vec3(forward.x, 0, forward.z).normalize();
        Vec3 r = new Vec3(-f.z, 0, f.x);

        int width = 6;
        double spacing = 1.6;

        for (int i = 0; i < count; i++) {
            int col = i % width;
            int row = i / width;

            double xOff = (col - (width - 1) * 0.5) * spacing;
            double zOff = row * spacing;

            Vec3 pos = anchor
                    .add(f.scale(forwardOffset))
                    .add(r.scale(xOff))
                    .add(f.scale(zOff));

            BlockPos bp = new BlockPos(Mth.floor(pos.x), anchorPos.getY(), Mth.floor(pos.z));

            boolean bannerman = (level.random.nextInt(10) == 0);

            var mob = spawnSoldier(
                    level,
                    bp,
                    side,
                    role,
                    bannerman,
                    false, // captain
                    0,     // skinId for normal troops
                    Component.literal(side == Side.ENEMY ? "Enemy Soldier" : "Friendly Soldier")
            );

            if (mob != null) {
                trackUnit(battle, mob, side, false, role);
            }
        }
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private static SoldierEntity spawnSoldier(
            ServerLevel level,
            BlockPos near,
            Side side,
            UnitRole role,
            boolean bannerman,
            boolean captain,
            int skinId,
            Component name
    ) {
        Entity ent = ((EntityType) modEntities.SOLDIER).create(
                level,
                (Consumer) (e -> {}),
                near,
                EntitySpawnReason.EVENT,
                false,
                false
        );

        if (!(ent instanceof SoldierEntity soldier)) return null;

        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, near.getX(), near.getZ());
        soldier.setPos(near.getX() + 0.5, y, near.getZ() + 0.5);

        float yaw = level.random.nextFloat() * 360.0F;
        soldier.setYRot(yaw);
        soldier.setYHeadRot(yaw);

        soldier.setRole(role == UnitRole.ARCHER ? SoldierEntity.Role.ARCHER : SoldierEntity.Role.FOOTMAN);
        soldier.setSide(side == Side.ENEMY ? SoldierEntity.Side.ENEMY : SoldierEntity.Side.FRIEND);

        soldier.setBannerman(bannerman);
        soldier.setCaptain(captain);
        soldier.setSkinId(skinId);

        soldier.setCustomName(name);
        if (captain) soldier.setCustomNameVisible(true);

        soldier.setPersistenceRequired();
        level.addFreshEntity(soldier);
        
        var srv = level.getServer();
        if (srv != null) {
            assignSoldierTeam(srv, soldier);
        }
                
        return soldier;
    }

    // --------------------
    // Finish
    // --------------------
    private static void finishBattle(MinecraftServer server, BattleInstance battle, Side winner, String msg) {
        
        COMMANDER_INDEX.remove(battle.friendCommanderUuid);
        if (battle.enemyCommanderUuid != null) COMMANDER_INDEX.remove(battle.enemyCommanderUuid);

        // optional: clear HUD for both commanders
        var fp = server.getPlayerList().getPlayer(battle.friendCommanderUuid);
        if (fp != null) clearHud(fp);
        if (battle.enemyCommanderUuid != null) {
            var ep = server.getPlayerList().getPlayer(battle.enemyCommanderUuid);
            if (ep != null) clearHud(ep);
        }

        for (UUID id : new ArrayList<>(battle.units.keySet())) {
            Entity e = battle.level.getEntity(id);
            if (e != null) e.discard();
        }
        battle.units.clear();

        boolean playerWon = (winner == Side.FRIEND);

        WarState ws = WarState.get(server);
        ws.makePeace(battle.playerKingdomId, battle.enemyKingdomId);

        String enemyName = null;
        var ks = kingdomState.get(server);
        var ek = ks.getKingdom(battle.enemyKingdomId);
        if (ek != null && ek.name != null && !ek.name.isBlank()) {
            enemyName = ek.name;
        } else {
            var ai = aiKingdomState.get(server).getById(battle.enemyKingdomId);
            if (ai != null) enemyName = ai.name;
        }
        if (enemyName == null) enemyName = "the enemy";

        var friendCommander = server.getPlayerList().getPlayer(battle.friendCommanderUuid);
        var enemyCommander = (battle.enemyCommanderUuid != null)
                ? server.getPlayerList().getPlayer(battle.enemyCommanderUuid)
                : null;

        if (friendCommander != null) {
            friendCommander.displayClientMessage(Component.literal(msg), false);
            friendCommander.displayClientMessage(Component.literal(
                    "You " + ((winner == Side.FRIEND) ? "won" : "lost") + " the battle vs " + enemyName + "."
            ), false);
        }

        if (enemyCommander != null) {
            enemyCommander.displayClientMessage(Component.literal(msg), false);
            enemyCommander.displayClientMessage(Component.literal(
                    "You " + ((winner == Side.ENEMY) ? "won" : "lost") + " the battle vs " + enemyName + "."
            ), false);
        }

    }

    // --------------------
    // Ticket computation
    // --------------------
    private static int computePlayerTickets(MinecraftServer server, UUID playerKingdomId) {
        var ks = kingdomState.get(server);
        var k = ks.getKingdom(playerKingdomId);
        if (k == null) return 0;

        int garrisons = k.getActive("garrison");
        return Math.max(0, garrisons * 50);
    }

    @SuppressWarnings("unused")
    private static int computeAiTickets(MinecraftServer server, UUID aiKingdomId) {
        var ai = name.kingdoms.aiKingdomState.get(server).getById(aiKingdomId);
        if (ai == null) return 250;

        int alive = Mth.clamp(ai.aliveSoldiers, 0, ai.maxSoldiers);
        return Math.max(0, alive - SOLDIERS_PER_SIDE);
    }

    // --------------------
    // Kingdom lookup
    // --------------------
    private static UUID findPlayersKingdomId(MinecraftServer server, ServerPlayer player) {
        return kingdomState.get(server).getKingdomIdFor(player.getUUID());
    }
}
