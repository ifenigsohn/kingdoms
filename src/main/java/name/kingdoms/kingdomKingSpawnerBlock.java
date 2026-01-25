package name.kingdoms;

import name.kingdoms.entity.aiKingdomEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public class kingdomKingSpawnerBlock extends Block implements IKingSpawnerBlock {


    private final String aiTypeId;
    
    public kingdomKingSpawnerBlock(Properties props, String aiTypeId, int skinId) {
        super(props);
        this.aiTypeId = aiTypeId;
    }

    private static final int CHECK_RADIUS = 256;
    private static final int RESPAWN_CHECK_TICKS = 20; // 1 second

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!(level instanceof ServerLevel sl)) return;
        if (oldState.is(state.getBlock())) return;

        // IMPORTANT: don't spawn immediately; structure placement can call onPlace multiple times
        sl.scheduleTick(pos, this, 1);
    }


    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        // if replaced, stop scheduling (king will self-discard from its tick() check)
        if (!(level.getBlockState(pos).getBlock() instanceof kingdomKingSpawnerBlock)) return;

        ensureKing(level, pos);
        level.scheduleTick(pos, this, RESPAWN_CHECK_TICKS);
    }

    private void ensureKing(ServerLevel sl, BlockPos pos) {
        boolean exists = !sl.getEntitiesOfClass(
                aiKingdomEntity.class,
                new AABB(pos).inflate(CHECK_RADIUS),
                k -> k.hasSpawnerPos() && k.getSpawnerPos().equals(pos)
        ).isEmpty();
        if (exists) return;

        aiKingdomEntity king = Kingdoms.AI_KINGDOM_ENTITY_TYPE.create(sl, EntitySpawnReason.SPAWN_ITEM_USE);
        if (king == null) return;

        king.teleportTo(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        king.setYRot(sl.random.nextFloat() * 360f);
        king.setXRot(0f);

        king.initFromSpawner(aiTypeId, -1);

        // IMPORTANT: bind to spawner so king despawns if block is destroyed
        king.setSpawnerPos(pos);

        sl.addFreshEntity(king);
        if (king instanceof Mob mob) mob.setPersistenceRequired();

        //  create/claim the AI kingdom right now
        aiKingdomState.get(sl.getServer()).getOrCreateForKing(sl, king);
    }
}
