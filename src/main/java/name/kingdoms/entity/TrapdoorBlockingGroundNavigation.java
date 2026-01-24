package name.kingdoms.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.level.block.state.BlockState;

public class TrapdoorBlockingGroundNavigation extends GroundPathNavigation {

    public TrapdoorBlockingGroundNavigation(Mob mob, Level level) {
        super(mob, level);
    }

   @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        // IMPORTANT: assign to the field used by PathNavigation#setCanOpenDoors
        this.nodeEvaluator = new WalkNodeEvaluator() {

            @Override
            public PathType getPathType(PathfindingContext ctx, int x, int y, int z) {
                BlockState state = ctx.getBlockState(new BlockPos(x, y, z));

                // Treat trapdoors as blocked so mobs won't path onto/through them
                if (state.is(BlockTags.TRAPDOORS)) {
                    return PathType.BLOCKED;
                }

                return super.getPathType(ctx, x, y, z);
            }
        };

        // Configure evaluator here (safe now)
        this.nodeEvaluator.setCanPassDoors(true);
        this.nodeEvaluator.setCanOpenDoors(true);
        this.nodeEvaluator.setCanFloat(true);

        return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
    }

}
