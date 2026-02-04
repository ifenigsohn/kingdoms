package name.kingdoms;

import java.util.UUID;

import name.kingdoms.entity.aiKingdomEntity;
import name.kingdoms.entity.ai.aiKingdomNPCEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public class AIkingdomNPCSpawnerBlock extends Block implements IKingdomSpawnerBlock {

    private final String aiTypeId;   // "villager", "guard", "noble", etc.
    private final int skinId;        // 0 or -1 for random
    private final int checkRadius;   // how far we count "ours"
    private final int checkTicks;    // how often to check
    private static final String ROAD_AMBIENT_INFRA_KEY = "road_ambient_infra";


    public AIkingdomNPCSpawnerBlock(
            Properties props,
            String aiTypeId,
            int skinId,
            int checkRadius,
            int checkTicks, int i
    ) {
        super(props);
        this.aiTypeId = aiTypeId;
        this.skinId = skinId;
        this.checkRadius = Math.max(4, checkRadius);
        this.checkTicks = Math.max(1, checkTicks);
    }

   @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!(level instanceof ServerLevel sl)) return;
        if (oldState.is(state.getBlock())) return;

        bumpInfra(sl, pos, +1);

        // Don't spawn immediately during structure paste; do it next tick
        sl.scheduleTick(pos, this, 1);
    }

    private static void bumpInfra(ServerLevel sl, BlockPos pos, int delta) {
        var ks = kingdomState.get(sl.getServer());
        var k = ks.getKingdomAt(sl, pos);
        if (k == null) return;

        // Treat spawner blocks like job blocks: +1 infra point each
        k.placed.merge(ROAD_AMBIENT_INFRA_KEY, delta, Integer::sum);

        // Clean up negatives (just in case)
        if (k.placed.getOrDefault(ROAD_AMBIENT_INFRA_KEY, 0) <= 0) {
            k.placed.remove(ROAD_AMBIENT_INFRA_KEY);
        }

        ks.markDirty();
    }


    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {

        if (!(level.getBlockState(pos).getBlock() instanceof AIkingdomNPCSpawnerBlock)) {
            bumpInfra(level, pos, -1);
            return;
        }


        ensureOne(level, pos);
        level.scheduleTick(pos, this, checkTicks);
    }

    private void ensureOne(ServerLevel sl, BlockPos pos) {
        var list = sl.getEntitiesOfClass(
                aiKingdomNPCEntity.class,
                new AABB(pos).inflate(checkRadius),
                e -> e.hasSpawnerPos() && e.getSpawnerPos().equals(pos)
        );

        // If we somehow got duplicates, keep exactly one.
        if (list.size() > 1) {
            list.sort(java.util.Comparator.comparingInt(Entity::getId));

            // keep oldest-ish
            aiKingdomNPCEntity keep = list.get(0);

            for (int i = 1; i < list.size(); i++) {
                list.get(i).discard();
            }

            // NEW: ensure the kept NPC is properly bound
            ensureBound(sl, pos, keep);
            return;
        }


        if (list.size() == 1) {
            // NEW: self-heal binding in case the NPC lost its kingdom UUID
            ensureBound(sl, pos, list.get(0));
            return;
        }


        spawnOne(sl, pos);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof ServerLevel sl) {
            bumpInfra(sl, pos, -1);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }



    private void spawnOne(ServerLevel sl, BlockPos pos) {
        aiKingdomNPCEntity npc = Kingdoms.AI_KINGDOM_NPC_ENTITY_TYPE.create(sl, EntitySpawnReason.SPAWN_ITEM_USE);
        if (npc == null) return;

        npc.teleportTo(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        npc.setYRot(sl.random.nextFloat() * 360f);
        npc.setXRot(0f);

        npc.initFromSpawner(aiTypeId, skinId);

        // bind to spawner so we can count "ours" and so it despawns if block is broken
        npc.setSpawnerPos(pos);

        // ----------------------------
        // Bind NPC to an AI kingdom id
        // ----------------------------
        UUID bindKid = null;

        // A) Prefer: nearest AI king entity -> ensures AI kingdom exists + claims border
        var kings = sl.getEntitiesOfClass(
                aiKingdomEntity.class,
                new AABB(pos).inflate(128),
                e -> e != null && e.isAlive()
        );

        aiKingdomState ai = aiKingdomState.get(sl.getServer());

        aiKingdomEntity nearestKing = null;
        double bestD2 = Double.MAX_VALUE;
        for (var k : kings) {
            double d2 = k.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (d2 < bestD2) { bestD2 = d2; nearestKing = k; }
        }

        if (nearestKing != null) {
            // This call also links AI -> kingdomState (ensureAiKingdom + claimRect) inside aiKingdomState
            var aiK = ai.getOrCreateForKing(sl, nearestKing);
            if (aiK != null && aiK.id != null) bindKid = aiK.id;
        }

        // B) Fallback: if territory is already claimed in kingdomState
        if (bindKid == null) {
            var ks = kingdomState.get(sl.getServer());
            var at = ks.getKingdomAt(sl, pos);
            if (at != null && at.id != null) bindKid = at.id;
        }

        // C) Apply binding if we found something
        if (bindKid != null) {
            npc.setKingdomUUID(bindKid);
            try { name.kingdoms.pressure.KingdomPressureState.get(sl.getServer()).markKnownAi(bindKid); }
            catch (Throwable ignored) {}
        }



        // --------------------------------------------
        // NEW: bind this NPC to the kingdom at this pos
        // --------------------------------------------
        var ks = kingdomState.get(sl.getServer());
        var k = ks.getKingdomAt(sl, pos);
        if (k != null && k.id != null) {
            npc.setKingdomUUID(k.id);

            // Optional: if this is an AI kingdom, mark discovered for pressure systems
            // (harmless even if it's a player kingdom)
            try {
                name.kingdoms.pressure.KingdomPressureState.get(sl.getServer()).markKnownAi(k.id);
            } catch (Throwable ignored) {}
        } else {
            // Debug if you want: spawner placed outside any kingdom region
            // System.out.println("[AI NPC Spawner] No kingdomAt for pos=" + pos);
            npc.setKingdomUUID(null);
        }

        sl.addFreshEntity(npc);
        if (npc instanceof Mob mob) mob.setPersistenceRequired();
    }

    private UUID inferKingdomId(ServerLevel sl, BlockPos spawnerPos) {
        if (sl == null) return null;

        UUID bindKid = null;

        // A) Prefer: nearest AI king -> ensures AI kingdom exists/claims
        var kings = sl.getEntitiesOfClass(
                name.kingdoms.entity.aiKingdomEntity.class,
                new AABB(spawnerPos).inflate(128),
                e -> e != null && e.isAlive()
        );

        var ai = aiKingdomState.get(sl.getServer());

        name.kingdoms.entity.aiKingdomEntity nearest = null;
        double bestD2 = Double.MAX_VALUE;
        for (var k : kings) {
            double d2 = k.distanceToSqr(spawnerPos.getX() + 0.5, spawnerPos.getY() + 0.5, spawnerPos.getZ() + 0.5);
            if (d2 < bestD2) { bestD2 = d2; nearest = k; }
        }

        if (nearest != null) {
            var aiK = ai.getOrCreateForKing(sl, nearest);
            if (aiK != null && aiK.id != null) bindKid = aiK.id;
        }

        // B) Fallback: claimed kingdom at the spawner position
        if (bindKid == null) {
            var ks = kingdomState.get(sl.getServer());
            var at = ks.getKingdomAt(sl, spawnerPos);
            if (at != null && at.id != null) bindKid = at.id;
        }

        return bindKid;
    }

    private void ensureBound(ServerLevel sl, BlockPos spawnerPos, aiKingdomNPCEntity npc) {
        if (sl == null || npc == null) return;

        UUID kid = npc.getKingdomUUID();
        if (kid != null) return; // already bound

        UUID inferred = inferKingdomId(sl, spawnerPos);
        if (inferred != null) {
            npc.setKingdomUUID(inferred);
            try { name.kingdoms.pressure.KingdomPressureState.get(sl.getServer()).markKnownAi(inferred); }
            catch (Throwable ignored) {}
        }
    }


}
