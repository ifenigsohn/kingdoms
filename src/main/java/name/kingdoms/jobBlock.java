package name.kingdoms;

import name.kingdoms.payload.jobReqsS2CPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

public class jobBlock extends Block implements EntityBlock {

    public static final int REQUIRE_RADIUS = 7;

    /** Minimum spacing between job blocks (X/Z only). */
    public static final int MIN_JOB_SPACING = 5;

    /** How far vertically to scan while enforcing X/Z spacing (keeps “ignore Y”, but bounded). */
    private static final int IGNORE_Y_SCAN = 16; // +/- 16 blocks

    /** Production enabled toggle (blockstate). */
    public static final BooleanProperty ENABLED = BooleanProperty.create("enabled");

    private final String jobId;
    private final jobDefinition job;

    public jobBlock(jobDefinition job, Properties props) {
        super(props);
        this.job = job;
        this.jobId = job.getId();

        // default state: enabled
        this.registerDefaultState(this.stateDefinition.any().setValue(ENABLED, true));
    }

    public String getJobId() {
        return jobId;
    }

    /* -----------------------------
       BLOCKSTATE
     ----------------------------- */

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(ENABLED);
    }

    /* -----------------------------
       PLACEMENT RULE: spacing (X/Z)
     ----------------------------- */

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockPos pos = ctx.getClickedPos();
        Level level = ctx.getLevel();
        Player player = ctx.getPlayer();

        // Cancel placement on server, and message the placer
        if (!level.isClientSide() && hasNearbyJobBlockXZ(level, pos)) {
            if (player instanceof ServerPlayer sp) {
                sp.displayClientMessage(
                        Component.literal("Job blocks must be at least " + MIN_JOB_SPACING + " blocks apart."),
                        false
                );
            }
            return null;
        }

        BlockState st = super.getStateForPlacement(ctx);
        if (st == null) return null;

        // ensure enabled=true on placement
        if (st.hasProperty(ENABLED)) st = st.setValue(ENABLED, true);
        return st;
    }

    private static boolean hasNearbyJobBlockXZ(LevelReader level, BlockPos pos) {
        int baseY = pos.getY();

        for (int dx = -MIN_JOB_SPACING; dx <= MIN_JOB_SPACING; dx++) {
            for (int dz = -MIN_JOB_SPACING; dz <= MIN_JOB_SPACING; dz++) {
                if (dx == 0 && dz == 0) continue;

                int x = pos.getX() + dx;
                int z = pos.getZ() + dz;

                // “Ignore Y” by scanning a vertical band (bounded for performance)
                for (int dy = -IGNORE_Y_SCAN; dy <= IGNORE_Y_SCAN; dy++) {
                    BlockPos p = new BlockPos(x, baseY + dy, z);
                    if (level.getBlockState(p).getBlock() instanceof jobBlock) return true;
                }
            }
        }
        return false;
    }

    /* -----------------------------
       RIGHT-CLICK: open requirements UI
       (toggle happens via UI button + C2S payload)
     ----------------------------- */

    @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (!(level instanceof ServerLevel sl)) return InteractionResult.SUCCESS;
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;

            Map<String, Integer> required = buildRequiredMap(job);
            Map<String, Integer> have = required.isEmpty()
                    ? Map.of()
                    : countNearby(sl, pos, REQUIRE_RADIUS, job);

            boolean enabled = state.getValue(ENABLED);

            ServerPlayNetworking.send(
                    sp,
                    new jobReqsS2CPayload(
                            pos,
                            jobId,
                            REQUIRE_RADIUS,
                            required,
                            have,
                            enabled
                    )
            );

            return InteractionResult.SUCCESS;
        }

    private static Map<String, Integer> buildRequiredMap(jobDefinition job) {
        Map<String, Integer> required = new HashMap<>();

        for (var e : job.getRequiredBlocks().entrySet()) {
            required.put(e.getKey().toString(), e.getValue());
        }
        for (var e : job.getRequiredBlockTags().entrySet()) {
            required.put("#" + e.getKey().location().toString(), e.getValue());
        }

        return required;
    }

    private static Map<String, Integer> countNearby(ServerLevel level, BlockPos origin, int radius, jobDefinition job) {
        Map<String, Integer> out = new HashMap<>();

        // pre-fill so UI always shows 0/N
        for (var e : job.getRequiredBlocks().entrySet()) {
            out.put(e.getKey().toString(), 0);
        }
        for (var e : job.getRequiredBlockTags().entrySet()) {
            out.put("#" + e.getKey().location().toString(), 0);
        }

        var reqBlocks = job.getRequiredBlocks();
        var reqTags = job.getRequiredBlockTags();

        BlockPos min = origin.offset(-radius, -radius, -radius);
        BlockPos max = origin.offset(radius, radius, radius);

        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            BlockState st = level.getBlockState(p);
            Block b = st.getBlock();

            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(b);
            if (reqBlocks.containsKey(id)) {
                out.merge(id.toString(), 1, Integer::sum);
            }

            for (TagKey<Block> tag : reqTags.keySet()) {
                if (st.is(tag)) {
                    out.merge("#" + tag.location().toString(), 1, Integer::sum);
                }
            }
        }

        return out;
    }

    /* -----------------------------
       BLOCK ENTITY
     ----------------------------- */

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new jobBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        if (type != Kingdoms.JOB_BLOCK_ENTITY) return null;
        return (lvl, p, st, be) -> jobBlockEntity.tick(lvl, p, st, (jobBlockEntity) be);
    }
}
