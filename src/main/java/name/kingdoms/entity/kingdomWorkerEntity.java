package name.kingdoms.entity;

import name.kingdoms.entity.ai.FindBedAtNightGoal;
import name.kingdoms.entity.ai.ReturnHomeDayGoal;
import name.kingdoms.entity.ai.FollowOwnerGoal;
import name.kingdoms.entity.ai.RetinueSeparationGoal;
import net.minecraft.network.chat.Component;
import name.kingdoms.RetinueRespawnManager;
import name.kingdoms.kingdomState;
import name.kingdoms.kingdomsClientProxy;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.EntitySpawnReason;

import java.util.List;
import java.util.ArrayList;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import name.kingdoms.payload.OpenWorkerActionsS2CPayload;


import java.util.UUID;

public class kingdomWorkerEntity extends PathfinderMob {

        private boolean equippedCombatSword = false;

        // ---- Routine state ----
        @org.jetbrains.annotations.Nullable private net.minecraft.core.BlockPos routineTarget = null;
        private int routineLoiterTicks = 0;

        // ---- TP / stuck detection ----
        private int stuckTicks = 0;
        private double lastDistToTargetSq = Double.MAX_VALUE;
        private int tpCooldown = 0;

        // Tune
        private static final int ROUTINE_SEARCH_RADIUS = 40;
        private static final int ROUTINE_SAMPLES = 220;      // cheap sampling instead of full cube scan
        private static final int STUCK_MAX_TICKS = 20 * 8;   // 8 seconds
        private static final int TP_COOLDOWN_TICKS = 20 * 20; // 20 seconds

        // ---- Orphan cleanup ----
        private int unboundTicks = 0;
        private static final int UNBOUND_GRACE_TICKS = 20 * 12; // 12 seconds grace



        private boolean isCombatant() {
            if (this.isRetinue()) return true; // retinue always combat-capable
            String job = this.getJobId();
            return "guard".equals(job) || "soldier".equals(job);
        }


        private void updateDisplayName() {
            if (!this.isRetinue()) return;

            String job = getJobId();
            if (job == null || job.isBlank()) return;

            String base = getRetinueBaseName();
            if (base == null) base = "";
            base = base.trim();

            Component prettyJob = Component.translatable("job.kingdoms." + job);

            Component name = base.isBlank()
            ? prettyJob
            : Component.literal(base + " (").append(prettyJob).append(Component.literal(")"));

            this.setCustomName(blue(name));
            this.setCustomNameVisible(true);


        }


        @Nullable
        private static BlockPos findNearestPoiBlock(ServerLevel sl, BlockPos origin, int r, net.minecraft.world.level.block.Block poiBlock) {
            BlockPos best = null;
            int bestD2 = Integer.MAX_VALUE;

            int ox = origin.getX();
            int oy = origin.getY();
            int oz = origin.getZ();

            var rand = sl.random;

            for (int i = 0; i < ROUTINE_SAMPLES; i++) {
                int dx = rand.nextInt(r * 2 + 1) - r;
                int dz = rand.nextInt(r * 2 + 1) - r;
                int dy = rand.nextInt(9) - 4;

                BlockPos p = new BlockPos(ox + dx, oy + dy, oz + dz);
                var bs = sl.getBlockState(p);

                if (bs.getBlock() != poiBlock) continue;

                // If it’s a jobBlock, require ENABLED true
                if (bs.getBlock() instanceof name.kingdoms.jobBlock) {
                    try {
                        if (!bs.getValue(name.kingdoms.jobBlock.ENABLED)) continue;
                    } catch (Throwable ignored) {}
                }

                // must be enabled if it’s a jobBlock with ENABLED property
                if (bs.getBlock() instanceof name.kingdoms.jobBlock) {
                    try {
                        if (!bs.getValue(name.kingdoms.jobBlock.ENABLED)) continue;
                    } catch (Throwable ignored) {}
                }

                int d2 = dx * dx + dz * dz + dy * dy;
                if (d2 < bestD2) {
                    bestD2 = d2;
                    best = p;
                }
            }

            return best;
        }



        


    @Override
    public boolean doHurtTarget(ServerLevel level, Entity target) {
        if (target instanceof LivingEntity) {
            this.swing(InteractionHand.MAIN_HAND, true);
            this.setAggressive(true);
        }
        return super.doHurtTarget(level, target);
    }



    // -----------------------
    // Synced data
    // -----------------------
    private static final EntityDataAccessor<String> JOB_ID =
            SynchedEntityData.defineId(kingdomWorkerEntity.class, EntityDataSerializers.STRING);

    private static final EntityDataAccessor<String> RETINUE_BASE_NAME =
        SynchedEntityData.defineId(kingdomWorkerEntity.class, EntityDataSerializers.STRING);

    private static final EntityDataAccessor<String> KINGDOM_UUID =
        SynchedEntityData.defineId(kingdomWorkerEntity.class, EntityDataSerializers.STRING);

    private static final EntityDataAccessor<Integer> SKIN_ID =
            SynchedEntityData.defineId(kingdomWorkerEntity.class, EntityDataSerializers.INT);

    // Retinue binding
    private static final EntityDataAccessor<String> OWNER_UUID =
            SynchedEntityData.defineId(kingdomWorkerEntity.class, EntityDataSerializers.STRING);

    private static final EntityDataAccessor<Boolean> IS_RETINUE =
            SynchedEntityData.defineId(kingdomWorkerEntity.class, EntityDataSerializers.BOOLEAN);

    private static final EntityDataAccessor<BlockPos> BIND_POS =
        SynchedEntityData.defineId(kingdomWorkerEntity.class, EntityDataSerializers.BLOCK_POS);


    // -----------------------
    // State
    // -----------------------
    @Nullable private BlockPos homePos;
    @Nullable private BlockPos assignedBedPos;

    // Teleport safety (retinue uses HARD_TELEPORT_RADIUS too)
    private static final int HARD_TELEPORT_RADIUS = 70;

    // Retinue teleport gating
    private static final double RETINUE_OWNER_MAX_TP_SPEED = 0.18; // blocks/tick (tune)


    private static final long MORNING_TIME = 1000L;
    private static final long NOON_TIME = 6000L;
    private static final int NOON_TELEPORT_RADIUS = 48;              // far from job? teleport
    private static final int NOON_TELEPORT_COOLDOWN_TICKS = 20 * 60; // once per minute max
    private int noonTeleportCooldown = 0;

    private int panicTicks = 0;
    private long lastTaxDay = -1;
    private int bindValidateCd = 0;

    // Retinue emergency teleport tuning
    private static final int RETINUE_EMERGENCY_TP_RADIUS = 90;      // should be >= FollowOwnerGoal range
    private static final int RETINUE_TP_COOLDOWN_TICKS = 40;        // 2 seconds
    private long lastRetinueTeleportTick = -999999L;
    


    // -----------------------
    // Retinue horse mount state
    // -----------------------
    @Nullable private UUID retinueHorseUuid = null;
    private int horseCooldown = 0;
    private static final int HORSE_COOLDOWN_TICKS = 40; // 2s anti-spam

    public kingdomWorkerEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.20D)
                .add(Attributes.FOLLOW_RANGE, 24.0D)
                .add(Attributes.ATTACK_DAMAGE, 3.0D);
    }

    private void dropStack(ServerLevel sl, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        ItemEntity it = new ItemEntity(sl, this.getX(), this.getY() + 0.5, this.getZ(), stack);
        it.setPickUpDelay(10);
        sl.addFreshEntity(it);
    }


    @Override
    protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(Level level) {
        return new name.kingdoms.entity.TrapdoorBlockingGroundNavigation(this, level);
    }


    @Override
protected void registerGoals() {
    this.goalSelector.addGoal(0, new FloatGoal(this));

    this.goalSelector.addGoal(1, new PanicGoal(this, 1.35D) {
        @Override public boolean canUse() { return !kingdomWorkerEntity.this.isCombatant() && super.canUse(); }
        @Override public boolean canContinueToUse() { return !kingdomWorkerEntity.this.isCombatant() && super.canContinueToUse(); }
    });

    this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.15D, true) {
        @Override public boolean canUse() { return kingdomWorkerEntity.this.isCombatant() && !kingdomWorkerEntity.this.isSleeping() && super.canUse(); }
        @Override public boolean canContinueToUse() { return kingdomWorkerEntity.this.isCombatant() && !kingdomWorkerEntity.this.isSleeping() && super.canContinueToUse(); }
    });

    // Retinue follow/separation
    this.goalSelector.addGoal(3, new FollowOwnerGoal(this, 2.0D, 8.0F, 8.0F));
    this.goalSelector.addGoal(4, new RetinueSeparationGoal(this, 3.5, 1.0));
    this.goalSelector.addGoal(5, new OpenDoorGoal(this, true));

    // Worker POI routines (they will self-gate retinue internally)
    this.goalSelector.addGoal(6, new VisitMorningPoiGoal(this, 1.05D));
    this.goalSelector.addGoal(7, new VisitTavernAtNightGoal(this, 1.05D));

    // Bed/home already gated with anonymous override in your code (keep that)
    this.goalSelector.addGoal(8, new FindBedAtNightGoal(this, 1.05D, 30) {
        @Override public boolean canUse() { return !kingdomWorkerEntity.this.isRetinue() && super.canUse(); }
        @Override public boolean canContinueToUse() { return !kingdomWorkerEntity.this.isRetinue() && super.canContinueToUse(); }
    });

    this.goalSelector.addGoal(9, new ReturnHomeDayGoal(this, 1.05D) {
        @Override public boolean canUse() { return !kingdomWorkerEntity.this.isRetinue() && super.canUse(); }
        @Override public boolean canContinueToUse() { return !kingdomWorkerEntity.this.isRetinue() && super.canContinueToUse(); }
    });

    this.goalSelector.addGoal(10, new WaterAvoidingRandomStrollGoal(this, 0.9D) {
        @Override public boolean canUse() { return !kingdomWorkerEntity.this.isRetinue() && !kingdomWorkerEntity.this.isSleeping() && super.canUse(); }
        @Override public boolean canContinueToUse() { return !kingdomWorkerEntity.this.isRetinue() && !kingdomWorkerEntity.this.isSleeping() && super.canContinueToUse(); }
    });

    this.goalSelector.addGoal(11, new LookAtPlayerGoal(this, Player.class, 6.0F));
    this.goalSelector.addGoal(12, new RandomLookAroundGoal(this));



        // --------------------
        // Target goals
        // --------------------

        // Retaliate vs mobs, but never retaliate vs players
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this) {
            @Override public boolean canUse() {
                if (!kingdomWorkerEntity.this.isCombatant()) return false;
                if (!super.canUse()) return false;
                return !(this.mob.getLastHurtByMob() instanceof Player);
            }

            @Override protected void alertOther(Mob ally, LivingEntity target) {
                if (target instanceof Player) return;
                super.alertOther(ally, target);
            }
        });

        // Retinue attacks players only when owner hits them
        this.targetSelector.addGoal(2, new OwnerHurtTargetGoal(this));

                // Combatants attack hostile mobs
                this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(
                this,
                Monster.class,
                true,
                (TargetingConditions.Selector) (LivingEntity e, ServerLevel lvl) -> !(e instanceof Creeper)
        ) {
            @Override public boolean canUse() {
                return kingdomWorkerEntity.this.isCombatant()
                        && !kingdomWorkerEntity.this.isSleeping()
                        && super.canUse();
            }
        });
    }

    public boolean tryPayTaxTo(ServerLevel sl, ServerPlayer sp, kingdomState.Kingdom kingdom) {
        if (sl == null || sp == null || kingdom == null) return false;

        // Only the KING can collect (owner)
        if (kingdom.owner == null || !sp.getUUID().equals(kingdom.owner)) {
            return false;
        }

        long today = sl.getDayTime() / 24000L;

        // If we've already collected today, they can't pay again.
        if (lastTaxDay == today) {
            sp.sendSystemMessage(name.kingdoms.KingdomTaxTables.rollPayLine(this.getRandom(), ItemStack.EMPTY));
            return false;
        }

        // Otherwise, they can pay now.
        String job = this.getJobId();
        ItemStack tax = name.kingdoms.KingdomTaxTables.rollTax(job, this.getRandom());

        boolean added = sp.getInventory().add(tax.copy());
        if (!added) dropStack(sl, tax.copy());

        lastTaxDay = today;

        sp.sendSystemMessage(name.kingdoms.KingdomTaxTables.rollNoPayLine(this.getRandom()));
        return true;
    }

    


    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(JOB_ID, "unknown");
        builder.define(SKIN_ID, 0);

        // retinue defaults
        builder.define(OWNER_UUID, "");
        builder.define(IS_RETINUE, false);
        builder.define(RETINUE_BASE_NAME, "");
        builder.define(KINGDOM_UUID, "");
        builder.define(BIND_POS, BlockPos.ZERO);


    }

    @Nullable
    public BlockPos getBindPosOrNull() {
        BlockPos p = this.entityData.get(BIND_POS);
        return (p == null || p.equals(BlockPos.ZERO)) ? null : p;
    }

    public void setBindPos(@Nullable BlockPos p) {
        this.entityData.set(BIND_POS, p == null ? BlockPos.ZERO : p);
    }


    @Nullable
    public UUID getKingdomUUID() {
        String s = this.entityData.get(KINGDOM_UUID);
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    public void setKingdomUUID(@Nullable UUID id) {
        this.entityData.set(KINGDOM_UUID, id == null ? "" : id.toString());
    }

    

    public String getRetinueBaseName() { return this.entityData.get(RETINUE_BASE_NAME); }

    public void setRetinueBaseName(@Nullable String name) {
        this.entityData.set(RETINUE_BASE_NAME, name == null ? "" : name);
        updateDisplayName(); // recompose
    }

    @Override
    public void aiStep() {
        this.updateSwingTime(); // <-- makes swing() actually animate reliably
        super.aiStep();
    }

    private static Component blue(Component c) {
        return c.copy().withStyle(s -> s.withColor(ChatFormatting.BLUE));
    }
    // -----------------------
    // MENUS
    // -----------------------
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (this.level().isClientSide()) return InteractionResult.SUCCESS;
        if (!(this.level() instanceof ServerLevel sl)) return InteractionResult.CONSUME;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.CONSUME;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;

        // -------------------------
        // RETINUE: existing behavior
        // -------------------------
        if (this.isRetinue()) {
            UUID ownerId = this.getOwnerUUID();
            if (ownerId == null || !ownerId.equals(sp.getUUID())) {
                sp.sendSystemMessage(Component.literal("They ignore you."));
                return InteractionResult.CONSUME;
            }

            var ks = kingdomState.get(sl.getServer());
            var k = ks.getPlayerKingdom(sp.getUUID());
            if (k == null) {
                sp.sendSystemMessage(Component.literal("Not connected to a kingdom."));
                return InteractionResult.CONSUME;
            }

            String job = this.getJobId();

            if ("treasurer".equals(job)) {
                kingdomsClientProxy.openTreasury(sp, k.terminalPos);
                return InteractionResult.CONSUME;
            }

            if ("general".equals(job)) {
                name.kingdoms.network.networkInit.sendWarOverviewTo(sp);
                return InteractionResult.CONSUME;
            }

            if ("scribe".equals(job)) {
                kingdomsClientProxy.openMail(sp, this.getId(), this.getUUID());
                return InteractionResult.CONSUME;
            }

            return InteractionResult.PASS;
        }

        

        // -------------------------
        // NON-RETINUE: OPEN ACTION UI
        // -------------------------
        UUID workerKingdomId = this.getKingdomUUID();
        if (workerKingdomId == null) {
            var ks2 = kingdomState.get(sl.getServer());
            var at = ks2.getKingdomAt(sl, this.blockPosition());
            if (at != null) {
                workerKingdomId = at.id;
                this.setKingdomUUID(workerKingdomId);
            } else {
                sp.sendSystemMessage(Component.literal("This worker is not assigned to a kingdom."));
                return InteractionResult.CONSUME;
            }
        }

        var ks = kingdomState.get(sl.getServer());
        var workerKingdom = ks.getKingdom(workerKingdomId);
        if (workerKingdom == null) {
            sp.sendSystemMessage(Component.literal("This worker's kingdom no longer exists."));
            return InteractionResult.CONSUME;
        }

        // Only the king of THIS kingdom can manage actions/taxes
        boolean isOwner = (workerKingdom.owner != null && workerKingdom.owner.equals(sp.getUUID()));
        if (!isOwner) {
            return InteractionResult.PASS;
        }

        // Decide if TAX is available today
        long today = sl.getDayTime() / 24000L;
        boolean canTax = (lastTaxDay != today);

        // Build action list based on job type
        List<String> actionIds = new ArrayList<>();
        String job = this.getJobId();

        // Pace policy (productive jobs)
        if ("butcher".equals(job) || "farm".equals(job) || "fishing".equals(job)
                || "wood".equals(job) || "metal".equals(job) || "gem".equals(job)
                || "alchemy".equals(job) || "weapon".equals(job) || "armor".equals(job)) {
            actionIds.add("DOUBLE_PACE");
            actionIds.add("LEISURELY_PACE");
        }



        // Guard patrol policy
        if ("guard".equals(job)) {
            actionIds.add("INCREASE_PATROLS");
            actionIds.add("DECREASE_PATROLS");
        }

        // Garrison / soldier rations policy
        if ("garrison".equals(job) || "training".equals(job)) {
            actionIds.add("DOUBLE_RATIONS");
            actionIds.add("HALVE_RATIONS");
        }

        // Tavern policy
        if ("tavern".equals(job)) {
            actionIds.add("ALCOHOL_SUBSIDIES");
            actionIds.add("DRUNK_CRACKDOWNS");
        }


        // Chapel / priest policy
        if ("chapel".equals(job)) {
            actionIds.add("FREQUENT_SERVICES");
            actionIds.add("PAPAL_AUTHORITY");
        }


        // Noble / nobility policy
        if ("nobility".equals(job)) {
            actionIds.add("DIPLOMATIC_ENVOYS");
            actionIds.add("VASSAL_CONTRIBUTIONS");
        }


        // Shop policy
        if ("shop".equals(job)) {
            actionIds.add("MARKET_SUBSIDIES");
            actionIds.add("CONTRABAND_CRACKDOWNS");
        }


        // Retinue buttons are false here (non-retinue)
        ServerPlayNetworking.send(sp, new OpenWorkerActionsS2CPayload(
                this.getId(),
                this.getUUID(),
                workerKingdomId,
                job,
                false,
                canTax,
                false,
                false,
                false,
                actionIds
        ));

        return InteractionResult.CONSUME;


    }
    

    // -----------------------
    // Job / skin
    // -----------------------
    public String getJobId() { return this.entityData.get(JOB_ID); }

    public void setJobId(@Nullable String jobId) {
        this.entityData.set(JOB_ID, jobId == null ? "unknown" : jobId);
    }


    public int getSkinId() { return this.entityData.get(SKIN_ID); }

    public void setSkinId(int skinId) {
        this.entityData.set(SKIN_ID, Mth.clamp(skinId, 0, Integer.MAX_VALUE));
    }

    // -----------------------
    // Retinue API
    // -----------------------
    public boolean isRetinue() { return this.entityData.get(IS_RETINUE); }

    public void setRetinue(boolean v) { this.entityData.set(IS_RETINUE, v); }

    @Nullable
    public UUID getOwnerUUID() {
        String s = this.entityData.get(OWNER_UUID);
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    public void setOwnerUUID(@Nullable UUID id) {
        this.entityData.set(OWNER_UUID, id == null ? "" : id.toString());
    }

    // -----------------------
    // Home / bed
    // -----------------------
    @Nullable public BlockPos getHomePos() { return homePos; }
    public void setHomePos(@Nullable BlockPos pos) { homePos = pos; }

    @Nullable public BlockPos getAssignedBedPos() { return assignedBedPos; }
    public void setAssignedBedPos(@Nullable BlockPos pos) { assignedBedPos = pos; }

    public boolean isBedClaimedByOther(BlockPos bedHeadPos, int checkRadius) {
        if (!(this.level() instanceof ServerLevel sl)) return false;

        return !sl.getEntitiesOfClass(
                kingdomWorkerEntity.class,
                this.getBoundingBox().inflate(checkRadius),
                w -> w != this
                        && w.getAssignedBedPos() != null
                        && w.getAssignedBedPos().equals(bedHeadPos)
        ).isEmpty();
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    private BlockPos pickSafeTeleportNearOwner(ServerLevel level, ServerPlayer owner) {
        BlockPos base = owner.blockPosition();

        // Try a bunch of offsets around owner
        for (int attempt = 0; attempt < 32; attempt++) {
            double ang = level.random.nextDouble() * Math.PI * 2.0;
            double r = 1.5 + level.random.nextDouble() * 5.0; // 1.5..6.5

            int dx = (int) Math.round(Math.cos(ang) * r);
            int dz = (int) Math.round(Math.sin(ang) * r);

            BlockPos p = base.offset(dx, 0, dz);

            for (int dy = 2; dy >= -2; dy--) {
                BlockPos feet = p.offset(0, dy, 0);
                BlockPos head = feet.above();
                BlockPos below = feet.below();

                // avoid fluids
                if (!level.getFluidState(feet).isEmpty()) continue;
                if (!level.getFluidState(head).isEmpty()) continue;

                // need space for feet/head
                if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()) continue;
                if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()) continue;

                // need support below
                if (level.getBlockState(below).getCollisionShape(level, below).isEmpty()) continue;

                // full bbox collision check
                this.setPos(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                if (!level.noCollision(this)) continue;

                return feet;
            }
        }

        // fallback: next to owner
        return base.offset(1, 0, 1);
    }


    private boolean canTeleportToOwner(ServerPlayer owner) {
        // Disallow creative flight / spectator-style flying
        if (owner.getAbilities().flying) return false;

        // Disallow elytra “fall flying”
        if (owner.isFallFlying()) return false;

        // Must be grounded (or swimming), NOT airborne
        Entity vehicle = owner.getVehicle();
        boolean grounded =
                owner.onGround()
                || owner.isInWater()
                || (vehicle != null && (vehicle.onGround() || vehicle.isInWater()));

        if (!grounded) return false;

        // Must be moving slowly enough (horizontal speed)
        Vec3 v = owner.getDeltaMovement();
        double h2 = v.x * v.x + v.z * v.z;
        return h2 <= (RETINUE_OWNER_MAX_TP_SPEED * RETINUE_OWNER_MAX_TP_SPEED);
    }

    private boolean isRoyalGuard() {
        return this.isRetinue() && "royal_guard".equals(this.getJobId());
    }


    // -----------------------
    // Retinue horse helpers
    // -----------------------
    private @Nullable ServerPlayer getOwnerPlayer(ServerLevel sl) {
        UUID id = this.getOwnerUUID();
        if (id == null) return null;
        return sl.getServer().getPlayerList().getPlayer(id);
    }

    private boolean ownerIsOnHorse(ServerLevel sl) {
        if (!this.isRetinue()) return false;
        ServerPlayer owner = getOwnerPlayer(sl);
        if (owner == null) return false;

        Entity vehicle = owner.getVehicle();
        return vehicle instanceof Horse; // only match actual horses (not donkey/camel)
    }

    @Nullable
    private Horse getRetinueHorseIfAlive(ServerLevel sl) {
        if (retinueHorseUuid == null) return null;
        Entity e = sl.getEntity(retinueHorseUuid);
        return (e instanceof Horse h && h.isAlive()) ? h : null;
    }

    private void ensureRetinueHorse(ServerLevel sl) {
        if (horseCooldown > 0) return;

        // already on our spawned horse
        Horse existing = getRetinueHorseIfAlive(sl);
        if (existing != null && this.getVehicle() == existing) return;

        if (existing == null) retinueHorseUuid = null;

        ServerPlayer owner = getOwnerPlayer(sl);
        if (owner == null) return;

        Horse horse = EntityType.HORSE.create(sl, EntitySpawnReason.EVENT);
        // If this overload errors in your mappings, swap to:
        // Horse horse = EntityType.HORSE.create(sl);

        if (horse == null) return;

        horse.tameWithName(owner);
        horse.addTag("kingdoms_retinue_mount");
        horse.addTag("kingdoms_owner:" + owner.getUUID());

        // place + face
        horse.teleportTo(this.getX(), this.getY(), this.getZ());
        horse.setYRot(this.getYRot());
        horse.setYHeadRot(this.getYRot());

        
        horse.setInvulnerable(true);

        // optional but safe if present
        try { horse.setCanPickUpLoot(false); } catch (Throwable ignored) {}

        sl.addFreshEntity(horse);

        // mount (correct overload)
        this.startRiding(horse, true, true);


        retinueHorseUuid = horse.getUUID();
        horseCooldown = HORSE_COOLDOWN_TICKS;
    }

    public void forceDespawnRetinueHorse(ServerLevel sl) {
        Horse h = getRetinueHorseIfAlive(sl);
        if (h != null) {
            if (this.getVehicle() == h) this.stopRiding();
            h.discard();
        }
        retinueHorseUuid = null;
        horseCooldown = HORSE_COOLDOWN_TICKS; // optional anti-spam if owner relogs instantly
    }



    @Override
    public Vec3 getVehicleAttachmentPoint(Entity vehicle) {
        Vec3 base = super.getVehicleAttachmentPoint(vehicle);

        if (vehicle instanceof net.minecraft.world.entity.animal.horse.AbstractHorse) {
            return base.add(0.0, 0.75, 0.0);
        }

        return base;
    }

    private void despawnRetinueHorse(ServerLevel sl) {
        if (horseCooldown > 0) return;

        Horse h = getRetinueHorseIfAlive(sl);
        if (h != null) {
            if (this.getVehicle() == h) this.stopRiding();
            h.discard();
        }
        retinueHorseUuid = null;
        horseCooldown = HORSE_COOLDOWN_TICKS;
    }

    @Override
    public void tick() {
        super.tick();

        if (this.level() instanceof ServerLevel sl && !this.isRetinue()) {
            if (bindValidateCd-- <= 0) {
                bindValidateCd = 100;
                validateBinding(sl);
                if (!this.isAlive()) return;
            }

            orphanCleanupTick(sl);
            if (!this.isAlive()) return;
        }


        if (this.level() instanceof ServerLevel sl) {
            // Retinue only; workers don't need this map
            if (!this.isRetinue()) {
                retinueCollide.clear();
            } else if ((sl.getGameTime() % 20L) == 0L) { // once per second
                long now = sl.getGameTime();
                retinueCollide.entrySet().removeIf(e -> (now - e.getValue().lastSeenTick) > 5L);
                // (if they haven't collided in ~0.25s, forget the timer)
            }
        }

        if (this.level() instanceof ServerLevel sl && !this.isRetinue()) {
            if ((sl.getGameTime() % name.kingdoms.pressure.PressureBarks.ATTEMPT_PERIOD_TICKS) == 0L) {
                name.kingdoms.pressure.PressureBarks.tryBark(sl, this);
            }
        }


        // --- Wake logic must run even while sleeping ---
        if (this.level() instanceof ServerLevel level && homePos != null) {
            long time = level.getDayTime() % 24000L;

            // wake up shortly after dawn
            if (this.isSleeping() && time >= MORNING_TIME && time < 6000L) {
                this.stopSleeping();
            }
        }


        // --- Retinue leash: don't chase too far from owner ---
        if (this.level() instanceof ServerLevel sl && this.isRetinue()) {
            UUID ownerId = getOwnerUUID();
            if (ownerId != null) {
                var owner = sl.getServer().getPlayerList().getPlayer(ownerId);
                if (owner != null) {

                    LivingEntity t = this.getTarget();
                    if (t != null && t.isAlive()) {
                        double max = 18.0; // blocks, tune (16–24 feels good)
                        double d2 = this.distanceToSqr(owner);
                        if (d2 > max * max) {
                            // Drop target and snap back to guard behavior
                            this.setTarget(null);
                            this.getNavigation().stop();
                        }
                    }
                }
            }
        }


        // --- If still sleeping after the wake check, freeze & return ---
        if (this.isSleeping()) {
            this.getNavigation().stop();
            this.setDeltaMovement(0, this.getDeltaMovement().y, 0);
            this.setSprinting(false);

            if (this.level() instanceof ServerLevel sl) {
                if ((sl.getGameTime() % 20L) == 0L) {
                    sl.sendParticles(
                            ParticleTypes.CLOUD,
                            this.getX(), this.getY() + 1.2, this.getZ(),
                            1,
                            0.1, 0.05, 0.1,
                            0.0
                    );
                }
            }
            return;
        }

        if (this.level() instanceof ServerLevel sl && !this.isRetinue() && !this.isSleeping()) {

            long t = sl.getDayTime() % 24000L;

            // If we’re routing to a POI, routineTarget is set -> assist it
            if (this.routineTarget != null) {
                tpHelper_tick(sl, this.routineTarget, 2);
            }
            // If it’s night and we have a bed assignment, assist bed pathing
            else if (t >= 12500L && t <= 23500L && this.assignedBedPos != null) {
                tpHelper_tick(sl, this.assignedBedPos, 2);
            }
            // If it’s day and we have homePos, assist returning home (optional)
            else if (t < 12000L && this.homePos != null) {
                // only assist if we’re notably away
                if (this.blockPosition().distSqr(this.homePos) > (14 * 14)) {
                    tpHelper_tick(sl, this.homePos, 3);
                } else {
                    tpHelper_clear();
                }
            } else {
                tpHelper_clear();
            }
        }


        // panic / running
        if (this.isOnFire() || this.hurtTime > 0) {
            panicTicks = 80;
        } else if (panicTicks > 0) {
            panicTicks--;
        }
        this.setSprinting(panicTicks > 0);

        
        // --- Combatants equip sword while fighting ---
        if (this.level() instanceof ServerLevel && this.isCombatant()) {
               boolean fighting = this.getTarget() != null && this.getTarget().isAlive();
                setCombatSword(this.isRoyalGuard() || fighting);
        }

        boolean fighting = this.getTarget() != null
        && this.getTarget().isAlive()
        && this.distanceToSqr(this.getTarget()) < 64; // 8 blocks

        // --- Retinue follow/teleport overrides (SERVER) ---
        if (this.level() instanceof ServerLevel sl && this.isRetinue()) {

            // horse cooldown tick
            if (horseCooldown > 0) horseCooldown--;

            // 1) If owner mounts a horse -> spawn horse under us + mount
            // 2) If owner dismounts -> despawn our horse (and dismount)
            if (ownerIsOnHorse(sl)) {
                ensureRetinueHorse(sl);
            } else if (retinueHorseUuid != null) {
                despawnRetinueHorse(sl);
            }

            UUID ownerId = getOwnerUUID();
            ServerPlayer owner = (ownerId == null) ? null : sl.getServer().getPlayerList().getPlayer(ownerId);

            // If owner is offline, we should NOT keep a retinue horse around.
            if (owner == null) {
                if (retinueHorseUuid != null) {
                    forceDespawnRetinueHorse(sl);
                }
                return; // still skip worker logic
            }

           // If owner is in a different dimension, don't try to teleport to their XYZ.
            // Let the respawn manager handle restoring near the owner.
            if (owner.level() != sl) {
                if (retinueHorseUuid != null) {
                    forceDespawnRetinueHorse(sl);
                }
                // Prevent a stranded copy from lingering in the old dimension
                this.discard();
                return;
            }

            // Emergency teleport (entity-side backup) with cooldown + safe landing
            double d2 = this.distanceToSqr(owner);
            if (d2 > (RETINUE_EMERGENCY_TP_RADIUS * RETINUE_EMERGENCY_TP_RADIUS)) {
                long now = sl.getGameTime();
                if (canTeleportToOwner(owner) && (now - lastRetinueTeleportTick) >= RETINUE_TP_COOLDOWN_TICKS) {
                    this.getNavigation().stop();
                    BlockPos safe = pickSafeTeleportNearOwner(sl, owner);
                    this.teleportTo(safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5);
                    lastRetinueTeleportTick = now;
                }
            }


            return; // IMPORTANT: don't run home/job logic while retinue
        }



        // --- Normal worker (job/home) logic ---
        if (!(this.level() instanceof ServerLevel level)) return;
        if (homePos == null) return;

        double distSq = this.blockPosition().distSqr(homePos);

        if (distSq > HARD_TELEPORT_RADIUS * HARD_TELEPORT_RADIUS) {
            teleportHome(level);
            return;
        }

        // noon teleport (server-side only)
        if (noonTeleportCooldown > 0) noonTeleportCooldown--;

        long time = level.getDayTime() % 24000L;

        if (time >= NOON_TIME
                && !this.isSleeping()
                && panicTicks == 0
                && noonTeleportCooldown == 0) {

            double distSqToJob = this.blockPosition().distSqr(homePos);
            if (distSqToJob > (NOON_TELEPORT_RADIUS * NOON_TELEPORT_RADIUS)) {
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

    // -----------------------
    // Save/load
    // -----------------------
    @Override
    public void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);

        out.putString("JobId", getJobId());
        out.putInt("SkinId", getSkinId());

        if (homePos != null) out.putLong("HomePos", homePos.asLong());
        if (assignedBedPos != null) out.putLong("BedPos", assignedBedPos.asLong());

        BlockPos bp = getBindPosOrNull();
        if (bp != null) out.putLong("BindPos", bp.asLong());


        out.putLong("LastTaxDay", lastTaxDay);
        String kid = this.entityData.get(KINGDOM_UUID);
        if (kid != null && !kid.isBlank()) out.putString("KingdomUUID", kid);


        // retinue fields
        out.putBoolean("IsRetinue", isRetinue());
        String ou = this.entityData.get(OWNER_UUID);
        if (ou != null && !ou.isBlank()) out.putString("OwnerUUID", ou);
        
        String base = getRetinueBaseName();
        if (base != null && !base.isBlank()) out.putString("RetinueBaseName", base);

        // retinue horse (optional)
        if (retinueHorseUuid != null) out.putString("RetinueHorse", retinueHorseUuid.toString());
        out.putInt("HorseCooldown", horseCooldown);
    }

    @Override
    public void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);

        setJobId(in.getString("JobId").orElse("unknown"));
        setSkinId(in.getInt("SkinId").orElse(0));

        homePos = in.getLong("HomePos").map(BlockPos::of).orElse(null);
        assignedBedPos = in.getLong("BedPos").map(BlockPos::of).orElse(null);
        this.entityData.set(BIND_POS, BlockPos.of(in.getLongOr("BindPos", BlockPos.ZERO.asLong())));


        // retinue fields
        this.entityData.set(IS_RETINUE, in.getBooleanOr("IsRetinue", false));
        this.entityData.set(OWNER_UUID, in.getString("OwnerUUID").orElse(""));
        this.entityData.set(RETINUE_BASE_NAME, in.getString("RetinueBaseName").orElse(""));

        lastTaxDay = in.getLong("LastTaxDay").orElse((long) -1);
            this.entityData.set(KINGDOM_UUID, in.getString("KingdomUUID").orElse(""));



        // horse
        retinueHorseUuid = in.getString("RetinueHorse").map(UUID::fromString).orElse(null);
        horseCooldown = in.getInt("HorseCooldown").orElse(0);

        // safest: don’t trust saved entity UUIDs across reloads
        retinueHorseUuid = null;
    }

    @Override
    public void die(DamageSource source) {
        // Capture info BEFORE super.die() in case anything gets cleared
        boolean wasRetinue = this.isRetinue();
        UUID ownerId = this.getOwnerUUID();
        String job = this.getJobId();
        int skin = this.getSkinId();

        // Pretty name for respawned NPC
        String pretty = switch (job) {
            case "general" -> "General";
            case "scribe" -> "Scribe";
            case "treasurer" -> "Treasurer";
            default -> {
                if (job == null || job.isBlank()) yield "";
                yield job.substring(0, 1).toUpperCase() + job.substring(1);
            }
        };

        super.die(source);

        // Only retinue members trigger respawn logic
        if (!wasRetinue) return;
        if (!(this.level() instanceof ServerLevel sl)) return;
        if (ownerId == null) return;

        RetinueRespawnManager.onRetinueDied(sl, ownerId, job, skin, pretty);
    }

    private void setCombatSword(boolean equip) {
        if (this.level().isClientSide()) return;

        if (equip) {
            if (!equippedCombatSword) {
                this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                equippedCombatSword = true;
            }
        } else {
            if (equippedCombatSword) {
                this.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                equippedCombatSword = false;
            }
        }
    }

    private void tpHelper_clear() {
        stuckTicks = 0;
        lastDistToTargetSq = Double.MAX_VALUE;
    }

    private void tpHelper_tick(ServerLevel sl, BlockPos target, int arriveRadius) {
        if (target == null) return;

        // don’t spam teleports
        if (tpCooldown > 0) {
            tpCooldown--;
            return;
        }

        double d2 = this.blockPosition().distSqr(target);
        if (d2 <= (arriveRadius * arriveRadius)) {
            tpHelper_clear();
            return;
        }

        // If navigation isn't active, treat as stuck
        boolean navActive = !this.getNavigation().isDone();

        // progress check
        boolean progressed = d2 < (lastDistToTargetSq - 0.25);
        lastDistToTargetSq = d2;

        // If we’re not even navigating right now, don’t accumulate “stuck”.
        // (Goals decide when to navigate; this avoids false positives.)
        if (!navActive) {
            stuckTicks = 0;
            return;
        }

        if (!progressed) stuckTicks++;
        else stuckTicks = 0;

        if (stuckTicks < STUCK_MAX_TICKS) return;

        // teleport near target safely
        BlockPos tp = findSafeTeleportNear(sl, target, 3);
        if (tp != null) {
            this.getNavigation().stop();
            this.teleportTo(tp.getX() + 0.5, tp.getY(), tp.getZ() + 0.5);
            tpCooldown = TP_COOLDOWN_TICKS;
        }

        stuckTicks = 0;
    }

    @org.jetbrains.annotations.Nullable
    private static BlockPos findSafeTeleportNear(ServerLevel sl, BlockPos target, int r) {
        // try a few random offsets near target, pick first safe 2-high air spot
        var rand = sl.random;

        for (int i = 0; i < 24; i++) {
            int dx = rand.nextInt(r * 2 + 1) - r;
            int dz = rand.nextInt(r * 2 + 1) - r;

            BlockPos base = target.offset(dx, 0, dz);

            // snap to surface
            BlockPos top = sl.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base);

            // need 2 blocks of air
            if (!sl.getBlockState(top).isAir()) continue;
            if (!sl.getBlockState(top.above()).isAir()) continue;

            // and solid ground below
            if (!sl.getBlockState(top.below()).isSolid()) continue;

            return top;
        }
        return null;
    }


    // Retinue-only "unstick" collision tracking
    private final java.util.Map<java.util.UUID, CollideInfo> retinueCollide = new java.util.HashMap<>();
    private static final int RETINUE_STUCK_TICKS = 40; // 2 seconds @ 20 TPS

    private static final class CollideInfo {
        int ticks;
        long lastSeenTick;
    }

    @Override
    public void push(Entity other) {
        // Only retinue-vs-retinue gets special handling
        if (this.isRetinue() && other instanceof kingdomWorkerEntity kw && kw.isRetinue()) {
            // Only track on server to avoid client-side weirdness
            if (this.level() instanceof ServerLevel sl) {
                UUID oid = other.getUUID();
                CollideInfo info = retinueCollide.computeIfAbsent(oid, k -> new CollideInfo());
                info.lastSeenTick = sl.getGameTime();

                // count consecutive "push calls" as collision time
                info.ticks = Math.min(RETINUE_STUCK_TICKS, info.ticks + 1);

                // If they've been colliding for >= 2 seconds, stop pushing to let them pass through
                if (info.ticks >= RETINUE_STUCK_TICKS) {
                    return;
                }
            }

            // Before 2 seconds, allow normal pushing so they try to separate
            super.push(other);
            return;
        }

        super.push(other);
    }

    
    public static class OwnerHurtTargetGoal extends TargetGoal {
        private final kingdomWorkerEntity guard;
        private LivingEntity lastOwnerTarget;
        private int lastTimestamp;

        public OwnerHurtTargetGoal(kingdomWorkerEntity guard) {
            super(guard, false);
            this.guard = guard;
        }

        @Override
        public boolean canUse() {
            if (!guard.isRetinue()) return false;

            UUID ownerId = guard.getOwnerUUID();
            if (ownerId == null) return false;

            if (!(guard.level().getPlayerByUUID(ownerId) instanceof Player owner)) return false;

            LivingEntity ownerTarget = owner.getLastHurtMob();
            if (ownerTarget == null || !ownerTarget.isAlive()) return false;

            // Only care about player-vs-player aggression
            if (!(ownerTarget instanceof Player)) return false;

            int ts = owner.getLastHurtMobTimestamp();
            if (ts == this.lastTimestamp) return false;

            this.lastOwnerTarget = ownerTarget;
            return true;
        }

        @Override
        public void start() {
            this.mob.setTarget(this.lastOwnerTarget);
            UUID ownerId = guard.getOwnerUUID();
            if (ownerId != null) {
                var owner = guard.level().getPlayerByUUID(ownerId);
                if (owner instanceof Player p) this.lastTimestamp = p.getLastHurtMobTimestamp();
            }
            super.start();
        }
        
    }

    private void validateBinding(ServerLevel sl) {
        BlockPos bp = getBindPosOrNull();
        if (bp == null) return;            // not bound => can't validate
        if (!sl.isLoaded(bp)) return;      // don't force chunk loads

        // If the block is gone, this worker should go too (prevents orphans after world edits)
        var state = sl.getBlockState(bp);
        if (!(state.getBlock() instanceof name.kingdoms.jobBlock)) { // <-- change if your spawner block type differs
            this.discard();
            return;
        }

        // If the block entity tracks an assigned worker UUID, enforce it
        var be = sl.getBlockEntity(bp);
        if (be instanceof name.kingdoms.jobBlockEntity jbe) {         // <-- rename to your actual BE class
            UUID assigned = jbe.getAssignedWorkerUuid();              // <-- add this method in Step 3
            if (assigned == null) {
                // claim if empty
                jbe.setAssignedWorkerUuid(this.getUUID());
                jbe.setChanged();
            } else if (!assigned.equals(this.getUUID())) {
                // another worker owns this slot -> WE are a duplicate
                this.discard();
            }
        }
    }

    private void orphanCleanupTick(ServerLevel sl) {
        if (this.isRetinue()) return;

        // If we already have a bind pos, reset orphan counter
        BlockPos bp = getBindPosOrNull();
        if (bp != null) {
            unboundTicks = 0;
            return;
        }

        // No bind pos -> try to recover using homePos (which is jobPos in your setup)
        if (homePos != null && sl.isLoaded(homePos)) {
            var state = sl.getBlockState(homePos);
            if (state.getBlock() instanceof name.kingdoms.jobBlock) {
                // bind to it
                this.setBindPos(homePos);
                unboundTicks = 0;

                // Optional: immediately validate slot ownership so duplicates die quickly
                validateBinding(sl);

                return;
            }
        }

        // Still unbound; count up and delete after grace
        unboundTicks++;

        if (unboundTicks >= UNBOUND_GRACE_TICKS) {
            this.discard();
        }
    }


    private final class VisitTavernAtNightGoal extends net.minecraft.world.entity.ai.goal.Goal {
        private final kingdomWorkerEntity mob;
        private final double speed;
        private int recheckCd = 0;

        // Tuning
        private static final float CHANCE = 0.35f;    // 35% nights go tavern
        private static final int LOITER_MIN = 20 * 10; // 10s
        private static final int LOITER_MAX = 20 * 40; // 40s

        

        VisitTavernAtNightGoal(kingdomWorkerEntity mob, double speed) {
            this.mob = mob;
            this.speed = speed;
        }

        @Override
        public boolean canUse() {
            if (!(mob.level() instanceof ServerLevel sl)) return false;
            if (mob.isSleeping()) return false;
            if (mob.isRetinue()) return false;

 

            long time = sl.getDayTime() % 24000L;
            boolean isNight = time >= 12500L && time <= 23500L;
            if (!isNight) return false;

            if (recheckCd-- > 0) return false;

            // already doing a routine target
            if (mob.routineTarget != null) return true;

            // roll chance (don’t do this every tick)
            if (sl.random.nextFloat() > CHANCE) {
                recheckCd = 20 * 20; // 20s
                return false;
            }

            BlockPos tav = findNearestPoiBlock(sl, mob.blockPosition(), ROUTINE_SEARCH_RADIUS, name.kingdoms.modBlock.tavern_block);
            if (tav == null) {
                recheckCd = 20 * 30; // 30s backoff
                return false;
            }

            mob.routineTarget = tav;
            mob.routineLoiterTicks = LOITER_MIN + sl.random.nextInt(Math.max(1, LOITER_MAX - LOITER_MIN));
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            if (!(mob.level() instanceof ServerLevel sl)) return false;
            if (mob.isSleeping()) return false;
            if (mob.routineTarget == null) return false;
            if (mob.isRetinue()) return false;


            long time = sl.getDayTime() % 24000L;
            boolean isNight = time >= 12500L && time <= 23500L;
            if (!isNight) return false;

            return mob.routineLoiterTicks > 0 || !mob.getNavigation().isDone();
        }

        @Override
        public void start() {
            if (mob.routineTarget != null) {
                mob.getNavigation().moveTo(
                        mob.routineTarget.getX() + 0.5,
                        mob.routineTarget.getY(),
                        mob.routineTarget.getZ() + 0.5,
                        speed
                );
            }
        }

        @Override
        public void tick() {
            if (!(mob.level() instanceof ServerLevel sl)) return;

            if (mob.routineTarget == null) return;

            double d2 = mob.blockPosition().distSqr(mob.routineTarget);

            if (d2 <= 3.5 * 3.5) {
                mob.getNavigation().stop();
                mob.routineLoiterTicks--;

                mob.tpHelper_clear();
                return;
            }

            mob.tpHelper_tick(sl, mob.routineTarget, 4);

        }

        @Override
        public void stop() {
            mob.getNavigation().stop();
            mob.routineTarget = null;
            mob.routineLoiterTicks = 0;
            recheckCd = 20 * 10; // small backoff
            mob.tpHelper_clear();
        }
    }

    private final class VisitMorningPoiGoal extends net.minecraft.world.entity.ai.goal.Goal {
        private final kingdomWorkerEntity mob;
        private final double speed;

        private int recheckCd = 0;

        private static final float CHANCE = 0.30f;       // 30% mornings do POI
        private static final int LOITER = 20 * 20;        // 20 seconds
        private static final long START = 1000L;          // your existing “morning”
        private static final long END   = 3500L;

        VisitMorningPoiGoal(kingdomWorkerEntity mob, double speed) {
            this.mob = mob;
            this.speed = speed;
        }

        @Override
        public boolean canUse() {
            if (!(mob.level() instanceof ServerLevel sl)) return false;
            if (mob.isSleeping()) return false;
            if (mob.isRetinue()) return false;


            long t = sl.getDayTime() % 24000L;
            if (t < START || t > END) return false;

            if (recheckCd-- > 0) return false;

            if (mob.routineTarget != null) return true;

            if (sl.random.nextFloat() > CHANCE) {
                recheckCd = 20 * 10; // 10s
                return false;
            }

            // Prefer chapel sometimes, shop other times (and only if enabled)
            BlockPos poi = null;

            boolean preferChapel = sl.random.nextBoolean();
            if (preferChapel) {
                poi = findNearestPoiBlock(sl, mob.blockPosition(), ROUTINE_SEARCH_RADIUS, name.kingdoms.modBlock.chapel_block);
            }
            if (poi == null) {
                poi = findNearestPoiBlock(sl, mob.blockPosition(), ROUTINE_SEARCH_RADIUS, name.kingdoms.modBlock.shop_block);
            }
            if (poi == null) {
                poi = findNearestPoiBlock(sl, mob.blockPosition(), ROUTINE_SEARCH_RADIUS, name.kingdoms.modBlock.chapel_block);
            }

            if (poi == null) {
                recheckCd = 20 * 15;
                return false;
            }

            mob.routineTarget = poi;
            mob.routineLoiterTicks = LOITER;
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            if (!(mob.level() instanceof ServerLevel sl)) return false;
            if (mob.routineTarget == null) return false;
            if (mob.isRetinue()) return false;


            long t = sl.getDayTime() % 24000L;
            if (t < START || t > END) return false;

            return mob.routineLoiterTicks > 0 || !mob.getNavigation().isDone();
        }

        @Override
        public void start() {
            if (mob.routineTarget != null) {
                mob.getNavigation().moveTo(
                        mob.routineTarget.getX() + 0.5,
                        mob.routineTarget.getY(),
                        mob.routineTarget.getZ() + 0.5,
                        speed
                );
            }
        }

        @Override
        public void tick() {
            if (!(mob.level() instanceof ServerLevel sl)) return;

            if (mob.routineTarget == null) return;

            double d2 = mob.blockPosition().distSqr(mob.routineTarget);
            if (d2 <= 5 * 5) {
                mob.getNavigation().stop();
                mob.routineLoiterTicks--;

   
                mob.tpHelper_clear();
                return;
            }

            mob.tpHelper_tick(sl, mob.routineTarget, 4); 

        }

        @Override
        public void stop() {
            mob.getNavigation().stop();
            mob.routineTarget = null;
            mob.routineLoiterTicks = 0;
            recheckCd = 20 * 20; // don’t instantly re-trigger
            mob.tpHelper_clear();
        }
    }


}


