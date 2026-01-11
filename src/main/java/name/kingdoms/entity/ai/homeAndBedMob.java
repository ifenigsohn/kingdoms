package name.kingdoms.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public interface homeAndBedMob {
    Level level();
    BlockPos blockPosition();
    PathNavigation getNavigation();

    boolean isSleeping();
    void startSleeping(BlockPos headPos);
    void stopSleeping();

    @Nullable BlockPos getHomePos();
    void setHomePos(@Nullable BlockPos pos);

    @Nullable BlockPos getAssignedBedPos();
    void setAssignedBedPos(@Nullable BlockPos pos);

    boolean isBedClaimedByOther(BlockPos bedHeadPos, int checkRadius);
}
