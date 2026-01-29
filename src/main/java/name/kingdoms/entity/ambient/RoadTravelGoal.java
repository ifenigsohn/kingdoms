package name.kingdoms.entity.ambient;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;

import java.util.EnumSet;

public class RoadTravelGoal extends Goal {

    private final PathfinderMob mob;
    private final double speed;

    private BlockPos currentTarget = null;
    private Direction lastDir = null;

    // --- Tuning ---
    private static final int FIND_ROAD_R = 12;          // larger so "snap to road" works reliably
    private static final int STEP_MIN = 28;             // longer legs so it doesn’t look like “a few blocks”
    private static final int STEP_MAX = 64;
    private static final int ARRIVE_DIST_SQ = 4 * 4;    // consider "arrived" when within 4 blocks
    private static final int STUCK_TICKS = 40;          // ~2 seconds
    private static final int REPICK_DELAY_MIN = 2;      // tiny delay between picks to avoid thrash
    private static final int REPICK_DELAY_MAX = 8;

    private int repickDelay = 0;
    private int stuckCounter = 0;

    public RoadTravelGoal(PathfinderMob mob, double speed) {
        this.mob = mob;
        this.speed = speed;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (mob.level().isClientSide()) return false;
        // If a road is nearby, we want this goal to own movement forever.
        return RoadUtil.findNearestPathableRoad(mob.level(), mob.blockPosition(), FIND_ROAD_R) != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (mob.level().isClientSide()) return false;
        // Keep running as long as there's still a road nearby.
        return RoadUtil.findNearestPathableRoad(mob.level(), mob.blockPosition(), FIND_ROAD_R) != null;
    }

    @Override
    public void start() {
        repickDelay = 0;
        stuckCounter = 0;
        pickAndMove(); // immediately start moving
    }

    @Override
    public void tick() {
        if (repickDelay > 0) repickDelay--;

        PathNavigation nav = mob.getNavigation();

        // If path finished, we want to chain immediately.
        boolean done = !nav.isInProgress();

        // Track “stuck”
        if (done) stuckCounter++;
        else stuckCounter = 0;

        // If we have a target and we're close enough -> chain
        if (currentTarget != null) {
            double d2 = mob.blockPosition().distSqr(currentTarget);
            if (d2 <= ARRIVE_DIST_SQ) {
                if (repickDelay == 0) {
                    scheduleDelay();
                    pickAndMove();
                }
                return;
            }
        }

        // If nav ended but we aren't near the target (path failed mid-way) -> repick
        if (done && repickDelay == 0) {
            scheduleDelay();
            pickAndMove();
            return;
        }

        // If stuck for a while -> repick
        if (stuckCounter > STUCK_TICKS && repickDelay == 0) {
            stuckCounter = 0;
            scheduleDelay();
            pickAndMove();
        }
    }

    @Override
    public void stop() {
        currentTarget = null;
        stuckCounter = 0;
        repickDelay = 0;
    }

    private void scheduleDelay() {
        RandomSource r = mob.getRandom();
        repickDelay = REPICK_DELAY_MIN + r.nextInt(REPICK_DELAY_MAX - REPICK_DELAY_MIN + 1);
    }

    private void pickAndMove() {
        Level level = mob.level();
        RandomSource r = mob.getRandom();

        BlockPos roadFeet = RoadUtil.findNearestPathableRoad(level, mob.blockPosition(), FIND_ROAD_R);
        if (roadFeet == null) return;

        Direction preferred = pickPreferredDirection(level, roadFeet, r);
        if (preferred == null) return;

        Direction[] tries = orderedTries(preferred);

        for (Direction d : tries) {
            BlockPos target = chooseTargetAlong(level, roadFeet, d, r);
            if (target == null) continue;
            if (target.equals(roadFeet)) continue;

            // Try to path to it. If path is null/invalid, try next direction.
            var path = mob.getNavigation().createPath(target, 0);
            if (path == null) continue;

            currentTarget = target;
            lastDir = d;

            mob.getNavigation().moveTo(path, speed);
            return;
        }

        // If nothing worked, clear target so we can try again soon
        currentTarget = null;
    }

    /**
     * Order: straight, left/right (random order), reverse LAST.
     */
    private Direction[] orderedTries(Direction straight) {
        Direction left = rotateLeft(straight);
        Direction right = rotateRight(straight);
        Direction back = straight.getOpposite();

        boolean leftFirst = mob.getRandom().nextBoolean();
        return leftFirst
                ? new Direction[]{straight, left, right, back}
                : new Direction[]{straight, right, left, back};
    }

    private Direction pickPreferredDirection(Level level, BlockPos roadFeet, RandomSource r) {
        // Prefer continuing previous heading.
        if (lastDir != null && hasRoadNeighbor(level, roadFeet, lastDir)) {
            return lastDir;
        }

        // Otherwise pick any direction that has a road neighbor.
        Direction[] candidates = new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

        // Random scan first
        for (int i = 0; i < 8; i++) {
            Direction d = candidates[r.nextInt(candidates.length)];
            if (hasRoadNeighbor(level, roadFeet, d)) return d;
        }
        // Deterministic fallback
        for (Direction d : candidates) {
            if (hasRoadNeighbor(level, roadFeet, d)) return d;
        }

        return null;
    }

    private boolean hasRoadNeighbor(Level level, BlockPos roadFeet, Direction d) {
        BlockPos nextFeet = roadFeet.relative(d);

        for (int dy = -1; dy <= 1; dy++) {
            BlockPos cand = nextFeet.offset(0, dy, 0);
            if (RoadUtil.isStandingOnPathableRoad(level, cand)) return true;
        }
        return false;
    }

    private BlockPos chooseTargetAlong(Level level, BlockPos roadFeet, Direction d, RandomSource r) {
        int steps = STEP_MIN + r.nextInt(STEP_MAX - STEP_MIN + 1);
        return RoadUtil.walkPathable(level, roadFeet, d, steps);
    }

    private static Direction rotateLeft(Direction d) {
        return switch (d) {
            case NORTH -> Direction.WEST;
            case WEST -> Direction.SOUTH;
            case SOUTH -> Direction.EAST;
            case EAST -> Direction.NORTH;
            default -> d;
        };
    }

    private static Direction rotateRight(Direction d) {
        return switch (d) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            default -> d;
        };
    }
}
