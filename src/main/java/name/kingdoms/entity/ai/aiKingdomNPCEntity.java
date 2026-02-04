package name.kingdoms.entity.ai;

import name.kingdoms.IKingdomSpawnerBlock;
import name.kingdoms.aiKingdomState;
import name.kingdoms.kingdomState;
import name.kingdoms.namePool;
import name.kingdoms.diplomacy.DiplomacyRelationsState;
import name.kingdoms.entity.SoldierEntity;
import name.kingdoms.payload.KingSpeakActionsRequestC2SPayload;
import name.kingdoms.pressure.ForeignPressureActions;
import name.kingdoms.war.WarState;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

public class aiKingdomNPCEntity extends PathfinderMob {

    // --- Synched data ---
    private static final EntityDataAccessor<String> AI_TYPE_ID =
            SynchedEntityData.defineId(aiKingdomNPCEntity.class, EntityDataSerializers.STRING);

    private static final EntityDataAccessor<Integer> SKIN_ID =
            SynchedEntityData.defineId(aiKingdomNPCEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<String> NPC_NAME =
            SynchedEntityData.defineId(aiKingdomNPCEntity.class, EntityDataSerializers.STRING);

    private static final EntityDataAccessor<Boolean> HAS_SPAWNER =
            SynchedEntityData.defineId(aiKingdomNPCEntity.class, EntityDataSerializers.BOOLEAN);

    private static final EntityDataAccessor<BlockPos> SPAWNER_POS =
            SynchedEntityData.defineId(aiKingdomNPCEntity.class, EntityDataSerializers.BLOCK_POS);

    private static final EntityDataAccessor<String> KINGDOM_UUID =
            SynchedEntityData.defineId(aiKingdomNPCEntity.class, EntityDataSerializers.STRING);

    private static final EntityDataAccessor<Boolean> IS_AMBIENT =
        SynchedEntityData.defineId(aiKingdomNPCEntity.class, EntityDataSerializers.BOOLEAN);

    private static final EntityDataAccessor<Integer> AMBIENT_TTL =
            SynchedEntityData.defineId(aiKingdomNPCEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<String> AMBIENT_EVENT_ID =
        SynchedEntityData.defineId(aiKingdomNPCEntity.class, EntityDataSerializers.STRING);

    private static final EntityDataAccessor<String> AMBIENT_OTHER_KINGDOM_UUID =
            SynchedEntityData.defineId(aiKingdomNPCEntity.class, EntityDataSerializers.STRING);




    // --- Positions ---
    @Nullable private BlockPos homePos;
    @Nullable private BlockPos assignedBedPos;

    @Nullable private BlockPos ambientLoiterCenter = null;
    private int ambientLoiterRadius = 0;
    private long ambientLoiterUntilTick = 0;

    public void setAmbientLoiter(BlockPos center, int radius, int durationTicks) {
        this.ambientLoiterCenter = center;
        this.ambientLoiterRadius = Math.max(2, radius);
        this.ambientLoiterUntilTick = this.level().getServer().getTickCount() + Math.max(20, durationTicks);
    }


    // --- Teleport safety ---
    private static final int HARD_TELEPORT_RADIUS = 70;
    private static final long MORNING_TIME = 1000L;
    private static final long NOON_TIME = 6000L;
    private static final int NOON_TELEPORT_RADIUS = 48;
    private static final int NOON_TELEPORT_COOLDOWN_TICKS = 20 * 60;
    private int noonTeleportCooldown = 0;
    private int combatEquipLinger = 0;

    private int panicTicks = 0;

    // --- Ambient queued speech (server-only, no need to sync) ---
    private UUID ambientTalkPlayer = null;
    private String ambientTalkLine = null;
    private String ambientTalkTitle = null;
    private int ambientTalkTtl = 0;

    private java.util.UUID ambientLeaderId = null;
    private int ambientFollowDist = 3;

    public void setAmbientLeader(java.util.UUID leader, int followDist) {
        this.ambientLeaderId = leader;
        this.ambientFollowDist = followDist;
    }

    public java.util.UUID getAmbientLeaderId() { return ambientLeaderId; }
    public int getAmbientFollowDist() { return ambientFollowDist; }



    // --- Sleep poof rate (optional flair) ---
    private static final int SLEEP_POOF_INTERVAL_TICKS = 60;

    public aiKingdomNPCEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
    }

    // --- AI throttling ---
    private int aiInterval = 1;          // ticks between AI updates
    private int aiSkip = 0;              // countdown
    private int nearPlayerCheckCd = 0;   // how often to recompute nearest player
    private boolean hasNearbyPlayer = true;
    private double nearestPlayerD2 = 0.0;
    private int ambientTargetCheckCd = 0;

    // --- Hibernate (skip expensive movement tick when far) ---
    private int hibernateCheckCd = 0;
    private boolean hibernating = false;

    // --- Soft throttle (still ticks, but much less often) ---
    private int softTickSkip = 0;
    private static final int SOFT_DIST = 48;         // start soft-throttling beyond this
    private static final int SOFT_TICK_RATE = 5;     // only do full tick 1/5 ticks when soft


    // Tune these
    private static final int HIBERNATE_DIST = 96;      // start hibernating when farther than this
    private static final int WAKE_DIST = 80;           // wake when closer than this
    private static final int HIBERNATE_CHECK_TICKS = 20; // check once per second



    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.22D)
                .add(Attributes.FOLLOW_RANGE, 24.0D)
                .add(Attributes.ATTACK_DAMAGE, 4.0D);   
    }
    
    @Override
    public boolean isCustomNameVisible() {
        // Ambient soldiers ALWAYS show their nametag
        if (this.isAmbient()) {
            return true;
        }
        return super.isCustomNameVisible();
    }

    public BlockPos getAmbientLoiterCenter() { return ambientLoiterCenter; }
    public int getAmbientLoiterRadius() { return ambientLoiterRadius; }
    public boolean isAmbientLoiterActive() {
        if (this.level().isClientSide()) return false;
        var srv = this.level().getServer();
        return ambientLoiterCenter != null && srv != null && srv.getTickCount() < ambientLoiterUntilTick;
    }



    @Override
    protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(Level level) {
        return new name.kingdoms.entity.TrapdoorBlockingGroundNavigation(this, level);
    }

    private boolean isFarInactive() {
        return !hasNearbyPlayer || nearestPlayerD2 > (96 * 96);
    }

    private void updateHibernateState(ServerLevel level) {
        // Never hibernate ambient scenes or combatants (they're the “interesting” ones)
        if (this.isAmbient() || this.isCombatant() || this.isSleeping()) {
            hibernating = false;
            return;
        }

        if (hibernateCheckCd-- > 0) return;
        hibernateCheckCd = HIBERNATE_CHECK_TICKS;

        if (nearPlayerCheckCd > 0) {
            // customServerAiStep will refresh soon; use last-known values
        } else {
            Player p = level.getNearestPlayer(this, 160.0);
            hasNearbyPlayer = (p != null);
            nearestPlayerD2 = (p == null) ? Double.MAX_VALUE : this.distanceToSqr(p);
        }
        double d2 = nearestPlayerD2;

        // Hard unload ONLY spawner-bound non-ambient non-combatants (town clutter)
        if (this.hasSpawnerPos() && !this.isAmbient() && !this.isCombatant()) {
            if (d2 > (192.0 * 192.0)) {
                this.discard();
                return;
            }
        }


        // Hysteresis so it doesn't flicker
        if (!hibernating) {
            if (d2 > (HIBERNATE_DIST * HIBERNATE_DIST)) hibernating = true;
        } else {
            if (d2 < (WAKE_DIST * WAKE_DIST)) hibernating = false;
        }
    }

    private boolean shouldSoftThrottle() {
        // don’t soft-throttle ambient scenes or combatants
        if (this.isAmbient() || this.isCombatant() || this.isSleeping()) return false;
        return hasNearbyPlayer && nearestPlayerD2 > (SOFT_DIST * SOFT_DIST) && nearestPlayerD2 <= (HIBERNATE_DIST * HIBERNATE_DIST);
    }





    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        
        // Panic only for non-combatants
        this.goalSelector.addGoal(1, new PanicGoal(this, 1.35D) {
            @Override public boolean canUse() { return !aiKingdomNPCEntity.this.isCombatant() && super.canUse(); }
            @Override public boolean canContinueToUse() { return !aiKingdomNPCEntity.this.isCombatant() && super.canContinueToUse(); }
        });

        // Combatants: melee
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.15D, true) {
            @Override public boolean canUse() { return aiKingdomNPCEntity.this.isCombatant() && !aiKingdomNPCEntity.this.isSleeping() && super.canUse(); }
            @Override public boolean canContinueToUse() { return aiKingdomNPCEntity.this.isCombatant() && !aiKingdomNPCEntity.this.isSleeping() && super.canContinueToUse(); }
        });

        this.goalSelector.addGoal(3, new AmbientSeparationGoal(this, 2.0, 1.05));
        this.goalSelector.addGoal(4, new AmbientFollowLeaderGoal(this, 1.15, 6.0f, 3.0f));

        this.goalSelector.addGoal(5, new OpenDoorGoal(this, true));

        // Existing NPC goals
        this.goalSelector.addGoal(6, new FindBedAtNightGoalNPC(this, 1.05D, 30));
        this.goalSelector.addGoal(7, new ReturnHomeDayGoalNPC(this, 1.05D));

        this.goalSelector.addGoal(8, new AmbientLoiterGoal(this, new AmbientLoiterGoal.LoiterAccess() {
            @Override public BlockPos getAmbientLoiterCenter() { return aiKingdomNPCEntity.this.getAmbientLoiterCenter(); }
            @Override public int getAmbientLoiterRadius() { return aiKingdomNPCEntity.this.getAmbientLoiterRadius(); }
            @Override public boolean isAmbientLoiterActive() { return aiKingdomNPCEntity.this.isAmbientLoiterActive(); }
        }, 0.9D));



        this.goalSelector.addGoal(9, new WaterAvoidingRandomStrollGoal(this, 0.9D) {
            @Override public boolean canUse() {
                return !aiKingdomNPCEntity.this.isSleeping()
                        && !aiKingdomNPCEntity.this.isFarInactive()
                        && super.canUse();
            }
            @Override public boolean canContinueToUse() {
                return !aiKingdomNPCEntity.this.isSleeping()
                        && !aiKingdomNPCEntity.this.isFarInactive()
                        && super.canContinueToUse();
            }
        });


        this.goalSelector.addGoal(10, new LookAtPlayerGoal(this, Player.class, 6.0F));
        this.goalSelector.addGoal(11, new RandomLookAroundGoal(this));

        // -----------------
        // Targets (combatants only)
        // -----------------

        // Retaliate if hit
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this) {
            @Override public boolean canUse() { return aiKingdomNPCEntity.this.isCombatant() && super.canUse(); }
        });

        // Attack hostile mobs
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(
                this,
                Monster.class,
                true,
                (TargetingConditions.Selector) (LivingEntity e, ServerLevel lvl) -> !(e instanceof Creeper)
        ) {
            @Override public boolean canUse() { return aiKingdomNPCEntity.this.isCombatant() && super.canUse(); }
        });

        // Bandits attack players (ambush behavior)
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(
                this,
                Player.class,
                true,
                (TargetingConditions.Selector) (LivingEntity e, ServerLevel lvl) -> aiKingdomNPCEntity.this.isBandit()
        ) {
            @Override public boolean canUse() { return aiKingdomNPCEntity.this.isBandit() && super.canUse(); }
        });



        // Attack enemy players (ONLY when at war)
        this.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(
        this,
        Player.class,
        true,
                (TargetingConditions.Selector) (LivingEntity e, ServerLevel lvl) ->
                        (e instanceof Player p) && aiKingdomNPCEntity.this.isEnemyPlayer(p)
        ) {
            @Override public boolean canUse() { return aiKingdomNPCEntity.this.isCombatant() && super.canUse(); }
        });

        // Attack enemy soldiers (your war soldiers)
        this.targetSelector.addGoal(5, new NearestAttackableTargetGoal<>(
                this,
                SoldierEntity.class,
                true,
                (TargetingConditions.Selector) (LivingEntity e, ServerLevel lvl) ->
                        (e instanceof SoldierEntity s) && aiKingdomNPCEntity.this.isEnemySoldier(s)
        ) {
            @Override public boolean canUse() { return aiKingdomNPCEntity.this.isCombatant() && super.canUse(); }
        });

        // Attack enemy AI NPC combatants (ambient-only)
        this.targetSelector.addGoal(6, new NearestAttackableTargetGoal<>(
                this,
                aiKingdomNPCEntity.class,
                true,
                (TargetingConditions.Selector) (LivingEntity e, ServerLevel lvl) ->
                        (e instanceof aiKingdomNPCEntity other) && aiKingdomNPCEntity.this.isEnemyAiNpc(other)
        ) {
            @Override public boolean canUse() { return aiKingdomNPCEntity.this.isCombatant() && super.canUse(); }
        });


    }


    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(AI_TYPE_ID, "villager");
        builder.define(SKIN_ID, 0);
        builder.define(NPC_NAME, ""); 
        builder.define(HAS_SPAWNER, false);
        builder.define(SPAWNER_POS, BlockPos.ZERO);
        builder.define(KINGDOM_UUID, "");
        builder.define(IS_AMBIENT, false);
        builder.define(AMBIENT_TTL, 0);
        builder.define(AMBIENT_EVENT_ID, "");
        builder.define(AMBIENT_OTHER_KINGDOM_UUID, "");


    }

    public boolean isAmbient() {
        return this.entityData.get(IS_AMBIENT);
    }

    public void setAmbient(boolean v) {
        this.entityData.set(IS_AMBIENT, v);
    }

    public void setAmbientTtl(int ticks) {
        this.entityData.set(IS_AMBIENT, true);
        this.entityData.set(AMBIENT_TTL, Math.max(0, ticks));
    }


    @Override
    public boolean isPushable() {
        // Only ambient NPCs should behave like real bodies in crowds
        if (this.isAmbient()) return true;
        return super.isPushable();
    }

    @Override
    public boolean canBeCollidedWith(Entity other) {
        // Needed so other entities can collide with them
        if (this.isAmbient()) return true;
        return super.canBeCollidedWith(other);
    }

    @Override
    protected void doPush(Entity other) {
        // Keep normal mob pushing behavior, but only “enabled” for ambient
        if (this.isAmbient()) {
            super.doPush(other);
        }
        // non-ambient: leave vanilla/super behavior (or no-op if you previously changed it elsewhere)
        else {
            super.doPush(other);
        }
    }

    @Override
    public Vec3 getVehicleAttachmentPoint(Entity vehicle) {
        Vec3 base = super.getVehicleAttachmentPoint(vehicle);

        if (vehicle instanceof net.minecraft.world.entity.animal.horse.AbstractHorse) {
                 return base.add(0.0, 0.75, 0.0);
        }

        return base;
    }


    private boolean isEnemyAiNpc(aiKingdomNPCEntity other) {
        if (other == null || other == this) return false;
        if (!other.isCombatant()) return false;

        // Keep it ambient-only so normal town pop doesn't fight
        if (!(this.isAmbient() || other.isAmbient())) return false;

        // --- Bandit hostility: bandits fight any non-bandit combatant ---
        boolean meBandit = this.isBandit();
        boolean themBandit = other.isBandit();
        if (meBandit != themBandit) {
            // exactly one is bandit -> hostile
            return true;
        }
        if (meBandit) {
            // bandit vs bandit -> not hostile
            return false;
        }

        // --- Original war-based hostility for kingdom combatants ---
        if (!(this.level() instanceof ServerLevel sl)) return false;

        UUID myKid = this.getKingdomUUID();
        UUID theirKid = other.getKingdomUUID();
        if (myKid == null || theirKid == null) return false;
        if (myKid.equals(theirKid)) return false;

        var war = WarState.get(sl.getServer());
        return war.isAtWar(myKid, theirKid);
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
            case "soldier" -> "Soldier";
            case "scout" -> "Scout";
            case "noble" -> "Noble";

            case "peasant" -> "Peasant";
            case "trader" -> "Trader";
            case "envoy" -> "Envoy";
            case "refugee" -> "Refugee";
            case "scholar" -> "Scholar";
            case "bandit" -> "Bandit";


            default -> "Villager";
        };
    }


    private void refreshNametag() {
        String type = getAiTypeId();
        String job = titleForType(type);

        String nm = getNpcName();
        if (nm == null || nm.isBlank()) nm = "Aelfric";

        // Bandits use comma format
        String built;
        if ("bandit".equals(type)) {
            built = nm + ", Bandit";
        } else {
            built = nm + " [" + job + "]";
        }

        // Only append [KING_NAME] for ambient combatants (soldier/scout/guard), NOT bandits
        if (!this.level().isClientSide() && this.isAmbient() && this.isCombatant() && !this.isBandit()) {
            String king = resolveKingNameForTag((ServerLevel) this.level(), this.getKingdomUUID());
            if (king != null && !king.isBlank()) {
                built = built + " [" + king + "]";
            }
        }

        this.setCustomName(Component.literal(built));
        this.setCustomNameVisible(this.entityData.get(IS_AMBIENT));
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

        var box = new net.minecraft.world.phys.AABB(bedHeadPos).inflate(checkRadius);

        return !sl.getEntitiesOfClass(
                aiKingdomNPCEntity.class,
                box,
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
                case "guard" -> 1;

                case "noble" -> 25;

                // New roles (tune these to whatever skin counts you actually have)
                case "peasant" -> 46;
                case "trader" -> 4;
                case "envoy" -> 8;
                case "refugee" -> 3;
                case "villager" -> 12;
                case "scholar" -> 22; // tune to your actual skin count
                case "soldier" -> 29;
                case "scout" -> 29;
                case "bandit" -> 6;
                default -> 12;
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

    public void queueAmbientSpeech(ServerPlayer player, String title, String line, int ttlTicks) {
        if (player == null || line == null || line.isBlank()) return;

        this.ambientTalkPlayer = player.getUUID();
        this.ambientTalkTitle = (title == null) ? "" : title;
        this.ambientTalkLine = line;
        this.ambientTalkTtl = Math.max(20, ttlTicks); // at least 1s
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;

        // Let client handle sending the request packet.
        // Returning SUCCESS on client makes the hand swing and triggers client logic cleanly.
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        // If sleeping, don't run AI at all.
        if (this.isSleeping()) return;
        if (hibernating) return;

        // If in combat / panic, always run full AI.
        boolean urgent = (this.getTarget() != null && this.getTarget().isAlive())
                || this.hurtTime > 0
                || panicTicks > 0;

        if (!urgent) {
            // Only recompute nearest player every 20 ticks (1 second)
            if (nearPlayerCheckCd-- <= 0) {
                nearPlayerCheckCd = 20;

                Player p = level.getNearestPlayer(this, 160.0);
                hasNearbyPlayer = (p != null);
                nearestPlayerD2 = (p == null) ? Double.MAX_VALUE : this.distanceToSqr(p);

                // Decide AI rate by distance
                if (!hasNearbyPlayer) aiInterval = 80;                // 4s
                else if (nearestPlayerD2 < (48 * 48)) aiInterval = 1;  // full
                else if (nearestPlayerD2 < (96 * 96)) aiInterval = 5;  // cheaper
                else aiInterval = 20;                                  // far
            }

            // When far from players, stop navigation so we don't repath constantly
            if ((!hasNearbyPlayer || nearestPlayerD2 > (96 * 96)) && !this.getNavigation().isDone()) {
                this.getNavigation().stop();
            }

            // Stagger updates so all NPCs don't think on the same tick
            int stagger = (this.getId() & 15);

            // Skip AI most ticks
            if (((this.tickCount + stagger) % aiInterval) != 0) {
                return;
            }
        }

        // Run vanilla AI on allowed ticks
        super.customServerAiStep(level);
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

        if (level instanceof ServerLevel sl) {
            if (this.isAmbient() && this.isBandit()) {
                applyBanditLoadoutFromNearestPlayer(sl);
            }
        }
        return data;
    }

    public void applyKingdomMilitarySkin() {
        if (!(this.level() instanceof ServerLevel sl)) return;

        UUID kid = getKingdomUUID();
        if (kid == null) return;

        String t = getAiTypeId();
        boolean military = "soldier".equals(t) || "scout".equals(t) || "guard".equals(t);
        if (!military) return;

        this.setSkinId(militarySkinForKingdom(sl, kid));
    }



    @Override
    public void tick() {
        // Client keeps normal behavior (rendering/anim expectations)
        if (this.level().isClientSide()) {
            super.tick();
            return;
        }

        if (!(this.level() instanceof ServerLevel level)) {
            super.tick();
            return;
        }

        // Decide if we should hibernate (server-side only)
        updateHibernateState(level);

        // Soft throttle band: only do full tick sometimes
        if (!hibernating && shouldSoftThrottle()) {
            if (softTickSkip-- > 0) {
                // minimal upkeep
                this.baseTick();
                // stop navigation so we don't repath on skipped ticks
                if (!this.getNavigation().isDone()) this.getNavigation().stop();
                return;
            }
            softTickSkip = SOFT_TICK_RATE - 1;
        } else {
            softTickSkip = 0;
        }


        // If hibernating, DO NOT call super.tick() (that’s where tickMovement/travel cost lives)
        if (hibernating) {
            // Minimal “keep alive” tick
            this.baseTick();

            // Freeze any motion/pathing and targets
            this.getNavigation().stop();
            this.setDeltaMovement(Vec3.ZERO);
            this.setSprinting(false);
            this.setTarget(null);

            // Optional: keep them from drifting due to pushes
            // (If you *want* crowds to still push them around, remove this)
            // this.setNoGravity(true);

            return;
        }

        // Normal full tick when not hibernating
        super.tick();

        // -------------------------
        // Everything below is your existing logic, unchanged
        // -------------------------

        // Deliver queued ambient speech only when close enough
        if (ambientTalkTtl > 0) {
            ambientTalkTtl--;

            var server = level.getServer();
            if (server != null && ambientTalkPlayer != null && ambientTalkLine != null) {
                ServerPlayer sp = server.getPlayerList().getPlayer(ambientTalkPlayer);

                if (sp != null) {
                    double d2 = this.distanceToSqr(sp);
                    if (d2 <= (10.0 * 10.0)) {
                        String nm = getNpcName();
                        if (nm == null || nm.isBlank()) nm = "Aelfric";

                        String title = ambientTalkTitle;
                        if (title == null || title.isBlank()) title = titleForType(getAiTypeId());

                        sp.sendSystemMessage(Component.literal(nm + " [" + title + "]: " + ambientTalkLine));

                        // clear so it only happens once
                        ambientTalkTtl = 0;
                        ambientTalkPlayer = null;
                        ambientTalkLine = null;
                        ambientTalkTitle = null;
                    }
                } else {
                    // player left -> drop it
                    ambientTalkTtl = 0;
                    ambientTalkPlayer = null;
                    ambientTalkLine = null;
                    ambientTalkTitle = null;
                }
            }
        }

        // TTL despawn for ambient event NPCs
        if (this.entityData.get(IS_AMBIENT)) {
            int ttl = this.entityData.get(AMBIENT_TTL);
            if (ttl <= 0) {
                Entity v = this.getVehicle();
                if (v != null && v.getTags().contains(name.kingdoms.ambient.SpawnUtil.AMBIENT_MOUNT_TAG)) {
                    v.discard();
                }
                this.discard();
                return;
            }
            this.entityData.set(AMBIENT_TTL, ttl - 1);
        }

        if (isCombatant() && !this.isSleeping()) {
            boolean fightingNow = this.getTarget() != null && this.getTarget().isAlive();
            if (fightingNow) combatEquipLinger = 40;
            else if (combatEquipLinger > 0) combatEquipLinger--;

            setCombatSword(fightingNow || combatEquipLinger > 0);
        } else {
            combatEquipLinger = 0;
            setCombatSword(false);
        }

        // Ambient combat auto-engage (throttled)
        if (this.isAmbient() && this.isCombatant() && !this.isSleeping()) {
            if (this.getTarget() == null || !this.getTarget().isAlive()) {

                if (ambientTargetCheckCd-- <= 0) {
                    ambientTargetCheckCd = 10 + (this.getId() & 7);

                    double r = 20.0;

                    var nearby = level.getEntitiesOfClass(
                            aiKingdomNPCEntity.class,
                            this.getBoundingBox().inflate(r),
                            e -> e != this && e.isAlive() && e.isAmbient() && e.isCombatant() && this.isEnemyAiNpc(e)
                    );

                    aiKingdomNPCEntity best = null;
                    double bestD2 = Double.MAX_VALUE;
                    for (var e : nearby) {
                        double d2 = this.distanceToSqr(e);
                        if (d2 < bestD2) { bestD2 = d2; best = e; }
                    }
                    if (best != null) this.setTarget(best);
                }
            } else {
                ambientTargetCheckCd = 0;
            }
        }

        // Despawn if spawner destroyed
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

        out.putString("NpcName", getNpcName());

        out.putBoolean("IsAmbient", this.entityData.get(IS_AMBIENT));
        out.putInt("AmbientTtl", this.entityData.get(AMBIENT_TTL));
        out.putString("AmbientEventId", this.entityData.get(AMBIENT_EVENT_ID));
        out.putString("AmbientOtherKid", this.entityData.get(AMBIENT_OTHER_KINGDOM_UUID));


        UUID kid = getKingdomUUID();
        if (kid != null) out.putString("KingdomUUID", kid.toString());

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
        this.entityData.set(KINGDOM_UUID, in.getString("KingdomUUID").orElse(""));
        this.entityData.set(NPC_NAME, in.getString("NpcName").orElse(""));

        boolean isAmb = in.getBooleanOr("IsAmbient", false);
        int ttl = in.getInt("AmbientTtl").orElse(0);
        this.entityData.set(IS_AMBIENT, isAmb);
        this.entityData.set(AMBIENT_TTL, ttl);

        this.entityData.set(AMBIENT_EVENT_ID, in.getString("AmbientEventId").orElse(""));
        this.entityData.set(AMBIENT_OTHER_KINGDOM_UUID, in.getString("AmbientOtherKid").orElse(""));


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
            if (mob.isAmbient()) return false; 
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
            if (mob.isAmbient()) return false;    
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
        private int pathCooldown = 0;
        private static final int PATH_RECALC_COOLDOWN_TICKS = 60; // 3 seconds


        FindBedAtNightGoalNPC(aiKingdomNPCEntity mob, double speed, int searchRadius) {
            this.mob = mob;
            this.speed = speed;
            this.searchRadius = Math.max(4, searchRadius);
        }

        @Override
        public boolean canUse() {
            if (mob.isAmbient()) return false; 
            if (!(mob.level() instanceof ServerLevel sl)) return false;
            if (mob.isSleeping()) return false;

            long time = mob.level().getDayTime() % 24000L;
            boolean isNight = time >= 12500L && time <= 23500L;
            if (!isNight) return false;

            if (recheckCooldown > 0) {
                recheckCooldown--;
                return false;
            }

            // If nobody is near, don't do expensive bed logic
            Player p = sl.getNearestPlayer(mob, 96.0);
            if (p == null) return false;

            if (mob.homePos == null) mob.homePos = mob.blockPosition();

            // If we have a bed, try to go sleep.
            if (mob.assignedBedPos != null) return true;

            // Find a bed nearby
            BlockPos found = findNearestBed(sl, mob.blockPosition(), searchRadius);
            if (found == null) {
                // No bed found: back off much harder (10s) so 50 NPCs don't scan constantly
                recheckCooldown = 200; // 10 seconds
                return false;
            }


            // Don’t steal someone else’s bed
            if (mob.isBedClaimedByOther(found, searchRadius)) {
                // Bed taken: medium backoff
                recheckCooldown = 100; // 5 seconds
                return false;
            }


            mob.assignedBedPos = found;
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            if (mob.isAmbient()) return false;  
            if (mob.isSleeping()) return false;
            if (mob.assignedBedPos == null) return false;

            long time = mob.level().getDayTime() % 24000L;
            boolean isNight = time >= 12500L && time <= 23500L;
            if (!isNight) return false;

            // continue while navigating OR until we are close enough to sleep
            double d2 = mob.blockPosition().distSqr(mob.assignedBedPos);
            return d2 > 2.5;
        }

        @Override
        public void start() {
            if (mob.assignedBedPos == null) return;

            // Don't pathfind too often
            if (pathCooldown > 0) return;
            pathCooldown = PATH_RECALC_COOLDOWN_TICKS;

            boolean ok = mob.getNavigation().moveTo(
                    mob.assignedBedPos.getX() + 0.5,
                    mob.assignedBedPos.getY(),
                    mob.assignedBedPos.getZ() + 0.5,
                    speed
            );

            // If pathfinding failed, back off HARD so we don't spam A*
            if (!ok) {
                recheckCooldown = 100; // 5 seconds
            }
        }

        @Override
        public void tick() {
            if (!(mob.level() instanceof ServerLevel sl)) return;
            if (mob.assignedBedPos == null) return;

            // cooldown tick
            if (pathCooldown > 0) pathCooldown--;

            // If bed got broken, forget it
            BlockState bs = sl.getBlockState(mob.assignedBedPos);
            if (!(bs.getBlock() instanceof BedBlock)) {
                mob.assignedBedPos = null;
                mob.getNavigation().stop();
                recheckCooldown = 40; // 2 seconds
                return;
            }

            // If we're far but navigation is done (failed or completed), only try again occasionally
            double d2 = mob.blockPosition().distSqr(mob.assignedBedPos);
            if (d2 > 2.5 && mob.getNavigation().isDone()) {
                if (pathCooldown <= 0) {
                    pathCooldown = PATH_RECALC_COOLDOWN_TICKS;

                    boolean ok = mob.getNavigation().moveTo(
                            mob.assignedBedPos.getX() + 0.5,
                            mob.assignedBedPos.getY(),
                            mob.assignedBedPos.getZ() + 0.5,
                            speed
                    );

                    if (!ok) {
                        recheckCooldown = 100; // 5 seconds backoff
                        return;
                    }
                }
            }

            // If close enough, sleep
            if (d2 <= 2.5) {
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

            // Sample count (tune): 200 checks instead of ~26,000
            int samples = 200;
            var rand = sl.random;

            for (int i = 0; i < samples; i++) {
                int dx = rand.nextInt(r * 2 + 1) - r;
                int dz = rand.nextInt(r * 2 + 1) - r;
                int dy = rand.nextInt(7) - 3;

                BlockPos p = new BlockPos(ox + dx, oy + dy, oz + dz);
                BlockState bs = sl.getBlockState(p);
                if (!(bs.getBlock() instanceof BedBlock)) continue;

                int d2 = dx * dx + dz * dz + dy * dy;
                if (d2 < bestD2) {
                    bestD2 = d2;
                    best = p;
                }
            }

            return best;
        }

    }

    @Nullable
    public UUID getKingdomUUID() {
        String s = this.entityData.get(KINGDOM_UUID);
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    public void setKingdomUUID(@Nullable UUID id) {
        this.entityData.set(KINGDOM_UUID, id == null ? "" : id.toString());
        if (!this.level().isClientSide() && id != null) {
            applyKingdomMilitarySkin();
        }
    }

    
    private boolean equippedCombatSword = false;

    private boolean isBandit() {
        return "bandit".equals(getAiTypeId());
    }

    private boolean isCombatant() {
        String t = getAiTypeId();
        return "guard".equals(t) || "soldier".equals(t) || "scout".equals(t) || "bandit".equals(t);
    }


    private void setCombatSword(boolean equip) {
        if (this.level().isClientSide()) return;

        if (equip) {
            if (!equippedCombatSword) {
                if (!this.isBandit()) {
                    this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                } else {
                    // bandits keep assigned weapon; do nothing
                }
                equippedCombatSword = true;
            }
        } else {
            if (equippedCombatSword) {
                if (!this.isBandit()) {
                    this.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                }
                equippedCombatSword = false;
            }
        }
    }



        private boolean isEnemyPlayer(Player p) {
            if (!(this.level() instanceof ServerLevel sl)) return false;

            UUID myKid = getKingdomUUID();
            if (myKid == null) return false; // not bound -> don't aggro anyone

            var ks = kingdomState.get(sl.getServer());
            var pk = ks.getPlayerKingdom(p.getUUID());
            if (pk == null) return false;

            var war = WarState.get(sl.getServer());
            return war.isAtWar(myKid, pk.id); // war-gated aggression
        }
    
        private boolean isEnemySoldier(SoldierEntity s) {
           
            return s.getSide() == SoldierEntity.Side.ENEMY;
        }

    public String getAmbientEventId() {
        String s = this.entityData.get(AMBIENT_EVENT_ID);
        return (s == null) ? "" : s;
    }

    @Nullable
    public UUID getAmbientOtherKingdomUUID() {
        String s = this.entityData.get(AMBIENT_OTHER_KINGDOM_UUID);
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    public void setAmbientScene(String eventId, @Nullable UUID otherKid, int ttlTicks) {
        this.setAmbientTtl(ttlTicks);
        this.entityData.set(AMBIENT_EVENT_ID, eventId == null ? "" : eventId);
        this.entityData.set(AMBIENT_OTHER_KINGDOM_UUID, otherKid == null ? "" : otherKid.toString());
    }

    private static final java.util.Map<java.util.UUID, Integer> LAST_AMBIENT_PAYOUT_TICK = new java.util.HashMap<>();

    @Override
    public void die(DamageSource source) {
        super.die(source);

        Entity v = this.getVehicle();
        if (v != null && v.getTags().contains(name.kingdoms.ambient.SpawnUtil.AMBIENT_MOUNT_TAG)) {
            v.discard();
        }



        if (!(this.level() instanceof ServerLevel sl)) return;
        if (!this.isAmbient()) return;

        // find the "real" killer
        Entity killer = source.getEntity();
        if (killer == null && source.getDirectEntity() instanceof Projectile proj) {
            killer = proj.getOwner();
        }
        if (!(killer instanceof net.minecraft.server.level.ServerPlayer sp)) return;

        // anti-farm: one payout per 15s per player
        int now = sl.getServer().getTickCount();
        int last = LAST_AMBIENT_PAYOUT_TICK.getOrDefault(sp.getUUID(), -999999);
        if (now - last < 20 * 15) return;
        LAST_AMBIENT_PAYOUT_TICK.put(sp.getUUID(), now);

        String ev = getAmbientEventId();
        UUID myKid = getKingdomUUID();
        UUID otherKid = getAmbientOtherKingdomUUID();

        // Only do "positive" payouts for specific events (so killing random ambient villagers isn't rewarded)
        if ("war_edge_skirmish".equals(ev)) {
            // player helped the OTHER side (the one this NPC was opposed to)
            if (otherKid != null) {
                DiplomacyRelationsState.get(sl.getServer()).addRelation(sp.getUUID(), otherKid, +1);
            }
            // optionally: the victim's kingdom dislikes you a bit
            if (myKid != null) {
                DiplomacyRelationsState.get(sl.getServer()).addRelation(sp.getUUID(), myKid, -1);
            }

            // tiny economy drip (rare)
            if (sl.random.nextFloat() < 0.25f) {
                var ks = kingdomState.get(sl.getServer());
                var pk = ks.getPlayerKingdom(sp.getUUID());
                if (pk != null) {
                    pk.gold = Math.max(0, pk.gold + 10);
                    ks.markDirty();
                }
            }
        }
    }

    private static String resolveKingNameForTag(ServerLevel sl, @Nullable UUID kid) {
        if (kid == null) return null;

        // ---- 1) Player kingdoms (kingdomState) ----
        try {
            var ks = kingdomState.get(sl.getServer());
            var k = ks.getKingdom(kid);
            if (k != null) {
                // If the owner is online, use their player name
                ServerPlayer owner = sl.getServer().getPlayerList().getPlayer(k.owner);
                if (owner != null) {
                    String n = owner.getGameProfile().name();
                    if (n != null && !n.isBlank()) return n;
                }

                // Fallback: kingdom name
                if (k.name != null && !k.name.isBlank()) return k.name;

                return "Unknown";
            }
        } catch (Throwable ignored) {}

        // ---- 2) AI kingdoms (aiKingdomState) via reflection ----
        try {
            Class<?> cls = Class.forName("name.kingdoms.aiKingdomState");

            Object aiState = cls.getMethod("get", net.minecraft.server.MinecraftServer.class)
                    .invoke(null, sl.getServer());

            Object mapObj = cls.getField("kingdoms").get(aiState);

            @SuppressWarnings("unchecked")
            java.util.Map<java.util.UUID, Object> map = (java.util.Map<java.util.UUID, Object>) mapObj;

            Object aiK = map.get(kid);
            if (aiK != null) {
                // Try common accessor names on a record/class
                for (String method : new String[]{
                        "kingName", "rulerName", "king", "ruler", "name"
                }) {
                    try {
                        Object val = aiK.getClass().getMethod(method).invoke(aiK);
                        if (val instanceof String s && !s.isBlank()) return s;
                    } catch (Throwable ignored2) {}
                }
            }
        } catch (Throwable ignored) {}

        return "Unknown";
    }

    public void setAmbientAnchor(@Nullable BlockPos center, int radius) {
        this.ambientLoiterCenter = center;
        this.ambientLoiterRadius = Math.max(0, radius);
        // keep it effectively “on” (TTL despawns them anyway)
        var srv = this.level().getServer();
        this.ambientLoiterUntilTick = (srv == null) ? 0 : srv.getTickCount() + (20L * 60L * 60L); // 1 hour
    }

    @Nullable
    public BlockPos getAmbientAnchor() { return ambientLoiterCenter; }

    public int getAmbientAnchorRadius() { return ambientLoiterRadius; }

    private static int militarySkinForKingdom(ServerLevel sl, UUID kingdomId) {
        var srv = sl.getServer();
        if (srv == null || kingdomId == null) return 0;

        // Player kingdom + (optionally) mirrored AI kingdoms stored in kingdomState
        var ks = kingdomState.get(srv);
        var k = ks.getKingdom(kingdomId);
        if (k != null) {
            return name.kingdoms.entity.SoldierSkins.clamp(k.soldierSkinId);
        }

        // AI fallback from aiKingdomState
        var ai = name.kingdoms.aiKingdomState.get(srv).getById(kingdomId);
        if (ai != null) {
            try {
                return name.kingdoms.entity.SoldierSkins.clamp(ai.soldierSkinId);
            } catch (Throwable ignored) {
                return 0;
            }
        }

        return 0;
    }

    private void applyBanditLoadoutFromNearestPlayer(ServerLevel level) {
        if (!this.isBandit()) return;

        Player nearestP = level.getNearestPlayer(this, 32.0);
        ServerPlayer nearest = (nearestP instanceof ServerPlayer sp) ? sp : null;

        int playerTier = (nearest == null) ? 0 : bestWeaponTierInInventory(nearest);

        // Clear equipment first
        this.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        this.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        this.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        this.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        this.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
        this.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);

        // Tier 0 (no sword/axe or only wood): bandits have NO melee weapon
        if (playerTier <= 0) {
            // Optional: tiny chance to have a bow so they're not totally harmless
            if (level.random.nextFloat() < 0.08f) {
                this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
                this.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.ARROW, 6 + level.random.nextInt(7)));
            }

            // Light “ragged” armor
            if (level.random.nextFloat() < 0.50f) this.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
            if (level.random.nextFloat() < 0.35f) this.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.LEATHER_LEGGINGS));
            return;
        }

        // Choose bandit bracket based on playerTier:
        // 1 (stone) => bandits = wood
        // 2 (iron) => bandits = stone with rare iron
        // 3 (diamond+) => bandits = iron with rare stone
        int banditTier;
        if (playerTier == 1) banditTier = 0;
        else if (playerTier == 2) banditTier = 1;
        else banditTier = 2; // playerTier 3 => iron-ish bandits

        // Clear armor first
        this.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        this.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        this.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
        this.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);

        // Decide if this bandit uses bow
        boolean archer = level.random.nextFloat() < (playerTier >= 2 ? 0.25f : 0.15f);

        if (archer) {
            this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            // Optional: a couple arrows so it feels real
            this.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.ARROW, 8 + level.random.nextInt(9)));
        } else {
            // Sword OR axe
            boolean axe = level.random.nextFloat() < 0.35f;

            // Pick melee weapon based on banditTier + rare roll rules
            ItemStack weapon = switch (banditTier) {
                case 0 -> new ItemStack(axe ? Items.WOODEN_AXE : Items.WOODEN_SWORD);

                case 1 -> { // mostly stone, rare iron
                    boolean rareIron = level.random.nextFloat() < 0.10f;
                    yield new ItemStack(rareIron
                            ? (axe ? Items.IRON_AXE : Items.IRON_SWORD)
                            : (axe ? Items.STONE_AXE : Items.STONE_SWORD));
                }

                default -> { // banditTier 2: mostly iron, rare stone
                    boolean rareStone = level.random.nextFloat() < 0.12f;
                    yield new ItemStack(rareStone
                            ? (axe ? Items.STONE_AXE : Items.STONE_SWORD)
                            : (axe ? Items.IRON_AXE : Items.IRON_SWORD));
                }
            };

            this.setItemSlot(EquipmentSlot.MAINHAND, weapon);
            this.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        }

        // Armor to match tier
        switch (banditTier) {
            case 0 -> { // wood-tier bandit
                this.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
                if (level.random.nextFloat() < 0.50f) this.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.LEATHER_LEGGINGS));
            }
            case 1 -> { // stone-tier bandit
                this.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.CHAINMAIL_CHESTPLATE));
                if (level.random.nextFloat() < 0.60f) this.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.LEATHER_LEGGINGS));
                if (level.random.nextFloat() < 0.25f) this.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
            }
            default -> { // iron-tier bandit
                this.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                this.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
                if (level.random.nextFloat() < 0.40f) this.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            }
        }
    }

    public void finalizeKingdomVisuals() {
        if (!(this.level() instanceof ServerLevel sl)) {
            refreshNametag();
            return;
        }

        // If this is a kingdom combatant (not bandit) and bound, force kingdom soldier skin.
        if (this.isCombatant() && !this.isBandit() && this.getKingdomUUID() != null) {
            applyKingdomMilitarySkin();
        }

        // Rebuild tag after everything else (ambient flag + kingdom id) is final.
        refreshNametag();
    }

    public static int bestWeaponTierInInventory(ServerPlayer p) {
        // 0 = wood/none, 1 = stone, 2 = iron, 3 = diamond+, (netherite counts as 3)
        int best = 0;

        // Main inventory (includes hotbar)
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            int t = weaponTier(s);
            if (t > best) best = t;
            if (best >= 3) return 3; // early out: diamond/netherite bracket
        }

        // Offhand counts too (optional, but nice)
        best = Math.max(best, weaponTier(p.getOffhandItem()));
        return Math.min(best, 3);
    }

    private static int weaponTier(ItemStack s) {
        if (s == null || s.isEmpty()) return 0;

        // Sword tiers
        if (s.is(Items.NETHERITE_SWORD) || s.is(Items.DIAMOND_SWORD)) return 3;
        if (s.is(Items.IRON_SWORD)) return 2;
        if (s.is(Items.STONE_SWORD)) return 1;
        if (s.is(Items.WOODEN_SWORD)) return 0;

        // Axe tiers
        if (s.is(Items.NETHERITE_AXE) || s.is(Items.DIAMOND_AXE)) return 3;
        if (s.is(Items.IRON_AXE)) return 2;
        if (s.is(Items.STONE_AXE)) return 1;
        if (s.is(Items.WOODEN_AXE)) return 0;

        return 0;
    }


    // rough “how geared is the player” tier estimator
    private static int estimatePlayerTier(ServerPlayer p) {
        float armor = p.getArmorValue();
        float tough = (float) p.getAttributeValue(Attributes.ARMOR_TOUGHNESS);

        ItemStack main = p.getMainHandItem();

        // very stable “weapon tier” heuristic
        int weaponTier = 0;
        if (main.is(Items.NETHERITE_SWORD) || main.is(Items.NETHERITE_AXE)) weaponTier = 3;
        else if (main.is(Items.DIAMOND_SWORD) || main.is(Items.DIAMOND_AXE)) weaponTier = 2;
        else if (main.is(Items.IRON_SWORD) || main.is(Items.IRON_AXE)) weaponTier = 1;
        else weaponTier = 0;

        // enchant bonus (works in 1.21.x)
        int ench = 0;
        try {
           
        } catch (Throwable ignored) {}

        // score
        float score = armor + (tough * 0.75f) + (weaponTier * 6.0f);

        if (score < 14f) return 0;
        if (score < 22f) return 1;
        if (score < 30f) return 2;
        return 3;
    }





}
