package name.kingdoms.entity.ai;

import name.kingdoms.IKingdomSpawnerBlock;
import name.kingdoms.namePool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

public class aiKingdomNPCEntity extends PathfinderMob {

    // --- Synched data ---
    private static final EntityDataAccessor<String> AI_TYPE_ID =
            SynchedEntityData.defineId(aiKingdomNPCEntity.class, EntityDataSerializers.STRING);

    private static final EntityDataAccessor<Integer> SKIN_ID =
            SynchedEntityData.defineId(aiKingdomNPCEntity.class, EntityDataSerializers.INT);

    // ✅ NEW: name persisted + synced
    private static final EntityDataAccessor<String> NPC_NAME =
            SynchedEntityData.defineId(aiKingdomNPCEntity.class, EntityDataSerializers.STRING);

    // Spawner binding
    private static final EntityDataAccessor<Boolean> HAS_SPAWNER =
            SynchedEntityData.defineId(aiKingdomNPCEntity.class, EntityDataSerializers.BOOLEAN);

    private static final EntityDataAccessor<BlockPos> SPAWNER_POS =
            SynchedEntityData.defineId(aiKingdomNPCEntity.class, EntityDataSerializers.BLOCK_POS);

    // --- Positions ---
    @Nullable private BlockPos homePos;
    @Nullable private BlockPos assignedBedPos;

    // --- Teleport safety ---
    private static final int HARD_TELEPORT_RADIUS = 70;
    private static final long MORNING_TIME = 1000L;
    private static final long NOON_TIME = 6000L;
    private static final int NOON_TELEPORT_RADIUS = 48;
    private static final int NOON_TELEPORT_COOLDOWN_TICKS = 20 * 60;
    private int noonTeleportCooldown = 0;

    private int panicTicks = 0;

    // --- Sleep poof rate (optional flair) ---
    private static final int SLEEP_POOF_INTERVAL_TICKS = 60;

    public aiKingdomNPCEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.22D)
                .add(Attributes.FOLLOW_RANGE, 24.0D);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        GroundPathNavigation nav = new GroundPathNavigation(this, level);
        nav.setCanOpenDoors(true);
        return nav;
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new PanicGoal(this, 1.35D));
        this.goalSelector.addGoal(4, new OpenDoorGoal(this, true));

        // ✅ WORKING NPC goals (do not depend on king-only goal constructors)
        this.goalSelector.addGoal(2, new FindBedAtNightGoalNPC(this, 1.05D, 30));
        this.goalSelector.addGoal(3, new ReturnHomeDayGoalNPC(this, 1.05D));

        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.9D) {
            @Override public boolean canUse() { return !aiKingdomNPCEntity.this.isSleeping() && super.canUse(); }
            @Override public boolean canContinueToUse() { return !aiKingdomNPCEntity.this.isSleeping() && super.canContinueToUse(); }
        });

        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 6.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(AI_TYPE_ID, "villager");
        builder.define(SKIN_ID, 0);
        builder.define(NPC_NAME, ""); // ✅ NEW
        builder.define(HAS_SPAWNER, false);
        builder.define(SPAWNER_POS, BlockPos.ZERO);
    }

    // --- Type / skin / name ---
    public String getAiTypeId() { return this.entityData.get(AI_TYPE_ID); }

    public int getSkinId() { return this.entityData.get(SKIN_ID); }

    public void setSkinId(int skinId) {
        this.entityData.set(SKIN_ID, Mth.clamp(skinId, 0, Integer.MAX_VALUE));
    }

    public String getNpcName() { return this.entityData.get(NPC_NAME); }

    public void setNpcName(String name) {
        this.entityData.set(NPC_NAME, (name == null) ? "" : name);
    }

    private static String titleForType(String type) {
        return switch (type) {
            case "guard" -> "Guard";
            case "noble" -> "Noble";
            default -> "Villager";
        };
    }

    private void refreshNametag() {
        String type = getAiTypeId();
        String job = titleForType(type);

        String nm = getNpcName();
        if (nm == null || nm.isBlank()) nm = "Aelfric";

        this.setCustomName(Component.literal(nm + " [" + job + "]"));
        this.setCustomNameVisible(true);
    }

    // Spawner binding API
    public void setSpawnerPos(BlockPos pos) {
        this.entityData.set(HAS_SPAWNER, true);
        this.entityData.set(SPAWNER_POS, pos);
    }

    public boolean hasSpawnerPos() { return this.entityData.get(HAS_SPAWNER); }

    public BlockPos getSpawnerPos() { return this.entityData.get(SPAWNER_POS); }

    @Nullable public BlockPos getHomePos() { return homePos; }

    public void setHomePos(@Nullable BlockPos pos) { homePos = pos; }

    @Nullable public BlockPos getAssignedBedPos() { return assignedBedPos; }

    public void setAssignedBedPos(@Nullable BlockPos pos) { assignedBedPos = pos; }

    public boolean isBedClaimedByOther(BlockPos bedHeadPos, int checkRadius) {
        if (!(this.level() instanceof ServerLevel sl)) return false;

        return !sl.getEntitiesOfClass(
                aiKingdomNPCEntity.class,
                this.getBoundingBox().inflate(checkRadius),
                w -> w != this
                        && w.getAssignedBedPos() != null
                        && w.getAssignedBedPos().equals(bedHeadPos)
        ).isEmpty();
    }

    /** Called by spawner blocks to configure role + skin (+ name). */
    public void initFromSpawner(@Nullable String aiTypeId, int skinId) {
        String type = (aiTypeId == null || aiTypeId.isBlank()) ? "villager" : aiTypeId;
        this.entityData.set(AI_TYPE_ID, type);

        // --- Pick skin (random per type if -1) ---
        int chosen = skinId;

        if (chosen < 0) {
            int maxExclusive = switch (type) {
                case "guard" -> 1;   // number of skins
                case "noble" -> 1;   // 
                default -> 12;       // 
            };
            if (maxExclusive < 1) maxExclusive = 1;
            chosen = this.random.nextInt(maxExclusive);
        }

        this.entityData.set(SKIN_ID, Mth.clamp(chosen, 0, Integer.MAX_VALUE));

        if (this.homePos == null) this.homePos = this.blockPosition();

        // --- Pick medieval name (server) only if not already set ---
        if (this.level() instanceof ServerLevel sl) {
            if (this.entityData.get(NPC_NAME).isBlank()) {
                this.entityData.set(NPC_NAME, namePool.randomMedieval(sl.getServer(), sl.random));
            }
        } else {
            // client fallback safety (shouldn't be needed for spawner init)
            if (this.entityData.get(NPC_NAME).isBlank()) {
                this.entityData.set(NPC_NAME, "Aelfric");
            }
        }

        // --- Nametag: "<MedievalName> <JobTitle>" ---
        refreshNametag();
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public @Nullable SpawnGroupData finalizeSpawn(
            ServerLevelAccessor level,
            DifficultyInstance difficulty,
            EntitySpawnReason reason,
            @Nullable SpawnGroupData spawnData
    ) {
        SpawnGroupData data = super.finalizeSpawn(level, difficulty, reason, spawnData);

        // If something spawned this without initFromSpawner, still give it a medieval name + tag.
        if (level instanceof ServerLevel sl) {
            if (this.entityData.get(NPC_NAME).isBlank()) {
                this.entityData.set(NPC_NAME, namePool.randomMedieval(sl.getServer(), sl.random));
            }
        } else {
            if (this.entityData.get(NPC_NAME).isBlank()) this.entityData.set(NPC_NAME, "Aelfric");
        }

        // Ensure a consistent nametag exists
        refreshNametag();
        return data;
    }

    @Override
    public void tick() {
        super.tick();
        if (!(this.level() instanceof ServerLevel level)) return;

        // Despawn if spawner destroyed (accept ANY kingdom spawner type)
        if (this.hasSpawnerPos()) {
            BlockPos sp = this.getSpawnerPos();
            BlockState bs = level.getBlockState(sp);
            if (!(bs.getBlock() instanceof IKingdomSpawnerBlock)) {
                this.discard();
                return;
            }
        }

        // Wake logic runs even while sleeping
        if (homePos != null) {
            long time = level.getDayTime() % 24000L;
            if (this.isSleeping() && time >= MORNING_TIME && time < 6000L) {
                this.stopSleeping();
            }
        }

        // Sleep freeze + poof
        if (this.isSleeping()) {
            if (this.tickCount % SLEEP_POOF_INTERVAL_TICKS == 0) {
                level.sendParticles(
                        ParticleTypes.POOF,
                        this.getX(), this.getY() + 0.25, this.getZ(),
                        6,
                        0.25, 0.10, 0.25,
                        0.01
                );
            }
            this.getNavigation().stop();
            this.setDeltaMovement(0, this.getDeltaMovement().y, 0);
            this.setSprinting(false);
            return;
        }

        // panic / sprinting
        if (this.isOnFire() || this.hurtTime > 0) {
            panicTicks = 80;
        } else if (panicTicks > 0) {
            panicTicks--;
        }
        this.setSprinting(panicTicks > 0);

        if (homePos == null) return;

        // teleport safety
        double distSq = this.blockPosition().distSqr(homePos);
        if (distSq > HARD_TELEPORT_RADIUS * HARD_TELEPORT_RADIUS) {
            teleportHome(level);
            return;
        }

        if (noonTeleportCooldown > 0) noonTeleportCooldown--;

        long time = level.getDayTime() % 24000L;
        if (time >= NOON_TIME && panicTicks == 0 && noonTeleportCooldown == 0) {
            double distSqToHome = this.blockPosition().distSqr(homePos);
            if (distSqToHome > (NOON_TELEPORT_RADIUS * NOON_TELEPORT_RADIUS)) {
                teleportHome(level);
                noonTeleportCooldown = NOON_TELEPORT_COOLDOWN_TICKS;
            }
        }
    }

    private void teleportHome(ServerLevel level) {
        if (this.isSleeping()) this.stopSleeping();
        this.getNavigation().stop();
        this.teleportTo(homePos.getX() + 0.5, homePos.getY(), homePos.getZ() + 0.5);
    }

    // --- Save/load ---
    @Override
    public void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);

        out.putString("AiTypeId", getAiTypeId());
        out.putInt("SkinId", getSkinId());

        // ✅ NEW: persist name
        out.putString("NpcName", getNpcName());

        if (homePos != null) out.putLong("HomePos", homePos.asLong());
        if (assignedBedPos != null) out.putLong("BedPos", assignedBedPos.asLong());

        out.putBoolean("HasSpawner", this.hasSpawnerPos());
        if (this.hasSpawnerPos()) out.putLong("SpawnerPos", this.getSpawnerPos().asLong());
    }

    @Override
    public void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);

        this.entityData.set(AI_TYPE_ID, in.getString("AiTypeId").orElse("villager"));
        this.entityData.set(SKIN_ID, in.getInt("SkinId").orElse(0));

        // ✅ NEW: restore name
        this.entityData.set(NPC_NAME, in.getString("NpcName").orElse(""));

        homePos = in.getLong("HomePos").map(BlockPos::of).orElse(null);
        assignedBedPos = in.getLong("BedPos").map(BlockPos::of).orElse(null);

        boolean has = in.getBooleanOr("HasSpawner", false);
        this.entityData.set(HAS_SPAWNER, has);
        this.entityData.set(SPAWNER_POS, has
                ? BlockPos.of(in.getLongOr("SpawnerPos", BlockPos.ZERO.asLong()))
                : BlockPos.ZERO);

        // Rebuild nametag after load
        refreshNametag();
    }

    // -------------------------------------------------------------------------
    // Goals (NPC-specific, constructor-safe)
    // -------------------------------------------------------------------------

    /** During the day, try to return to homePos if we drift far. */
    private static final class ReturnHomeDayGoalNPC extends Goal {
        private final aiKingdomNPCEntity mob;
        private final double speed;

        // Tune these
        private static final int START_DIST = 14;   // start walking home if farther than this
        private static final int STOP_DIST  = 5;    // stop when within this distance

        ReturnHomeDayGoalNPC(aiKingdomNPCEntity mob, double speed) {
            this.mob = mob;
            this.speed = speed;
        }

        @Override
        public boolean canUse() {
            if (mob.isSleeping()) return false;
            if (mob.homePos == null) return false;

            long time = mob.level().getDayTime() % 24000L;
            boolean isDay = time >= 0 && time < 12000L;
            if (!isDay) return false;

            double d2 = mob.blockPosition().distSqr(mob.homePos);
            return d2 > (START_DIST * START_DIST);
        }

        @Override
        public boolean canContinueToUse() {
            if (mob.isSleeping()) return false;
            if (mob.homePos == null) return false;

            long time = mob.level().getDayTime() % 24000L;
            boolean isDay = time >= 0 && time < 12000L;
            if (!isDay) return false;

            double d2 = mob.blockPosition().distSqr(mob.homePos);
            return d2 > (STOP_DIST * STOP_DIST) && !mob.getNavigation().isDone();
        }

        @Override
        public void start() {
            if (mob.homePos != null) {
                mob.getNavigation().moveTo(
                        mob.homePos.getX() + 0.5,
                        mob.homePos.getY(),
                        mob.homePos.getZ() + 0.5,
                        speed
                );
            }
        }

        @Override
        public void stop() {
            mob.getNavigation().stop();
        }
    }

    /** At night, find/claim a bed nearby and go sleep in it. */
    private static final class FindBedAtNightGoalNPC extends Goal {
        private final aiKingdomNPCEntity mob;
        private final double speed;
        private final int searchRadius;

        private int recheckCooldown = 0;

        FindBedAtNightGoalNPC(aiKingdomNPCEntity mob, double speed, int searchRadius) {
            this.mob = mob;
            this.speed = speed;
            this.searchRadius = Math.max(4, searchRadius);
        }

        @Override
        public boolean canUse() {
            if (!(mob.level() instanceof ServerLevel sl)) return false;
            if (mob.isSleeping()) return false;

            long time = mob.level().getDayTime() % 24000L;
            boolean isNight = time >= 12500L && time <= 23500L;
            if (!isNight) return false;

            if (recheckCooldown > 0) {
                recheckCooldown--;
                return false;
            }

            if (mob.homePos == null) mob.homePos = mob.blockPosition();

            // If we have a bed, try to go sleep.
            if (mob.assignedBedPos != null) return true;

            // Find a bed nearby
            BlockPos found = findNearestBed(sl, mob.blockPosition(), searchRadius);
            if (found == null) {
                recheckCooldown = 40; // 2 seconds
                return false;
            }

            // Don’t steal someone else’s bed
            if (mob.isBedClaimedByOther(found, searchRadius)) {
                recheckCooldown = 40;
                return false;
            }

            mob.assignedBedPos = found;
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            if (mob.isSleeping()) return false;
            if (mob.assignedBedPos == null) return false;

            long time = mob.level().getDayTime() % 24000L;
            boolean isNight = time >= 12500L && time <= 23500L;
            if (!isNight) return false;

            // continue while navigating OR until we are close enough to sleep
            double d2 = mob.blockPosition().distSqr(mob.assignedBedPos);
            return d2 > 2.5 && !mob.getNavigation().isDone();
        }

        @Override
        public void start() {
            if (mob.assignedBedPos != null) {
                mob.getNavigation().moveTo(
                        mob.assignedBedPos.getX() + 0.5,
                        mob.assignedBedPos.getY(),
                        mob.assignedBedPos.getZ() + 0.5,
                        speed
                );
            }
        }

        @Override
        public void tick() {
            if (!(mob.level() instanceof ServerLevel sl)) return;
            if (mob.assignedBedPos == null) return;

            // If bed got broken, forget it
            BlockState bs = sl.getBlockState(mob.assignedBedPos);
            if (!(bs.getBlock() instanceof BedBlock)) {
                mob.assignedBedPos = null;
                mob.getNavigation().stop();
                recheckCooldown = 40;
                return;
            }

            double d2 = mob.blockPosition().distSqr(mob.assignedBedPos);
            if (d2 <= 2.5) {
                // Try to sleep
                mob.getNavigation().stop();
                mob.startSleeping(mob.assignedBedPos);
            }
        }

        @Override
        public void stop() {
            // If we stopped because it became day, leave bed claimed (so they go back tonight).
            mob.getNavigation().stop();
        }

        @Nullable
        private static BlockPos findNearestBed(ServerLevel sl, BlockPos origin, int r) {
            BlockPos best = null;
            int bestD2 = Integer.MAX_VALUE;

            int ox = origin.getX();
            int oy = origin.getY();
            int oz = origin.getZ();

            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    // stay near entity's vertical level to keep scan cheap
                    for (int dy = -3; dy <= 3; dy++) {
                        BlockPos p = new BlockPos(ox + dx, oy + dy, oz + dz);
                        BlockState bs = sl.getBlockState(p);
                        if (!(bs.getBlock() instanceof BedBlock)) continue;

                        int d2 = dx * dx + dz * dz + dy * dy;
                        if (d2 < bestD2) {
                            bestD2 = d2;
                            best = p;
                        }
                    }
                }
            }

            return best;
        }
    }
}
