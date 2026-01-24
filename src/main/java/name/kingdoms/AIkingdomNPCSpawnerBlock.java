package name.kingdoms;

import name.kingdoms.entity.ai.aiKingdomNPCEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public class AIkingdomNPCSpawnerBlock extends Block implements IKingdomSpawnerBlock {

    private final String aiTypeId;   // "villager", "guard", "noble", etc.
    private final int skinId;        // 0 or -1 for random
    private final int checkRadius;   // how far we count "ours"
    private final int checkTicks;    // how often to check

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

        // Don't spawn immediately during structure paste; do it next tick
        sl.scheduleTick(pos, this, 1);
    }


    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        // stop if replaced
        if (!(level.getBlockState(pos).getBlock() instanceof AIkingdomNPCSpawnerBlock)) return;

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
            // Deterministic: keep the one with the smallest entity id (oldest-ish)
            list.sort(java.util.Comparator.comparingInt(Entity::getId));
            for (int i = 1; i < list.size(); i++) {
                list.get(i).discard();
            }
            return; // after cleanup, do NOT spawn
        }

        if (list.size() == 1) return;

        spawnOne(sl, pos);
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

        sl.addFreshEntity(npc);
        if (npc instanceof Mob mob) mob.setPersistenceRequired();
    }
}
