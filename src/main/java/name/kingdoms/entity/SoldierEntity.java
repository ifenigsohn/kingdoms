package name.kingdoms.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.EnumSet;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public class SoldierEntity extends PathfinderMob implements RangedAttackMob {

    public enum Side {
        FRIEND, ENEMY;
        public static Side fromOrdinal(int v) { return (v <= 0) ? FRIEND : ENEMY; }
    }

    public enum Role {
        FOOTMAN, ARCHER;
        public static Role fromOrdinal(int v) { return (v <= 0) ? FOOTMAN : ARCHER; }
    }

    private static final EntityDataAccessor<Integer> DATA_SIDE =
            SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_BANNERMAN =
            SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_ROLE =
            SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_SKIN =
            SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_CAPTAIN =
            SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.BOOLEAN);

    public SoldierEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setCanPickUpLoot(false);
    }

    // -------------------------
    // Attributes
    // -------------------------
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 22.0)
                .add(Attributes.MOVEMENT_SPEED, 0.23)
                .add(Attributes.ATTACK_DAMAGE, 4.0)
                .add(Attributes.FOLLOW_RANGE, 30.0)
                .add(Attributes.ARMOR, 3.0);
    }

    // -------------------------
    // Synched data
    // -------------------------
    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SIDE, 0);
        builder.define(DATA_BANNERMAN, false);
        builder.define(DATA_ROLE, 0);
        builder.define(DATA_SKIN, 0);
        builder.define(DATA_CAPTAIN, false);
    }

    public Side getSide() { return Side.fromOrdinal(this.entityData.get(DATA_SIDE)); }
    public void setSide(Side side) { this.entityData.set(DATA_SIDE, side.ordinal()); applyLoadout(); }

    public boolean isBannerman() { return this.entityData.get(DATA_BANNERMAN); }
    public void setBannerman(boolean bannerman) { this.entityData.set(DATA_BANNERMAN, bannerman); applyLoadout(); }

    public Role getRole() { return Role.fromOrdinal(this.entityData.get(DATA_ROLE)); }
    public void setRole(Role role) { this.entityData.set(DATA_ROLE, role.ordinal()); applyLoadout(); }

    public int getSkinId() { return this.entityData.get(DATA_SKIN); }
    public void setSkinId(int id) { this.entityData.set(DATA_SKIN, Math.max(0, id)); }

    public boolean isCaptain() { return this.entityData.get(DATA_CAPTAIN); }
    public void setCaptain(boolean captain) { this.entityData.set(DATA_CAPTAIN, captain); }

    // -------------------------
    // Friendly-fire prevention
    // -------------------------
    @Override
    public boolean canAttack(LivingEntity target) {
        if (target instanceof SoldierEntity se && se.getSide() == this.getSide()) return false;
        return super.canAttack(target);
    }

    @Override
    public void setTarget(LivingEntity target) {
        if (target instanceof SoldierEntity se && se.getSide() == this.getSide()) {
            super.setTarget(null);
            return;
        }
        super.setTarget(target);
    }


    // Optional retaliation-only (does NOT seek targets; just responds if hit)
    private static final class SoldierHurtByTargetGoal extends HurtByTargetGoal {
        private final SoldierEntity self;
        SoldierHurtByTargetGoal(SoldierEntity self) { super(self); this.self = self; }

        @Override
        public boolean canUse() {
            LivingEntity attacker = self.getLastHurtByMob();
            if (attacker instanceof SoldierEntity se && se.getSide() == self.getSide()) return false;
            return super.canUse();
        }
    }

    // -------------------------
    // HOLD-LINE melee (no chasing)
    // -------------------------
    private static final class HoldLineMeleeGoal extends Goal {
        private final SoldierEntity self;
        private int cooldown;

        HoldLineMeleeGoal(SoldierEntity self) {
            this.self = self;
            this.setFlags(EnumSet.of(Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (self.getRole() != Role.FOOTMAN) return false;
            LivingEntity t = self.getTarget();
            return t != null && t.isAlive();
        }

        @Override
        public boolean canContinueToUse() { return canUse(); }

        @Override
        public void start() { cooldown = 0; }

        @Override
        public void tick() {
            LivingEntity t = self.getTarget();
            if (t == null) return;

            self.getLookControl().setLookAt(t, 30.0F, 30.0F);

            if (cooldown > 0) cooldown--;

            // “Stay in formation”: do NOT move to target, just attack if in reach
            double d2 = self.distanceToSqr(t);

            // slightly generous reach so two tight formations actually fight
            double reach = (self.getBbWidth() * 2.0) + 1.25 + t.getBbWidth();
            double reach2 = reach * reach;

            if (cooldown <= 0 && d2 <= reach2 && self.getSensing().hasLineOfSight(t)) {
                if (self.level() instanceof ServerLevel sl) {
                    self.doHurtTarget(sl, t); // doHurtTarget handles swing
                }
                cooldown = 12;
            }

        }
    }

    // -------------------------
    // Formation target (server-side only)
    // -------------------------
    private net.minecraft.core.BlockPos formationTarget = null;

    public void setFormationTarget(@org.jetbrains.annotations.Nullable net.minecraft.core.BlockPos pos) {
        this.formationTarget = pos;
    }

    public @org.jetbrains.annotations.Nullable net.minecraft.core.BlockPos getFormationTarget() {
        return this.formationTarget;
    }

    // -------------------------
    // Formation teleport assist (stuck recovery)
    // -------------------------
    private int formationCheckTicker = 0;
    private int formationStuckTicks = 0;
    private int formationTeleportCooldownTicks = 0;

    private double lastDistToFormation = Double.NaN;
    private net.minecraft.world.phys.Vec3 lastFormationSamplePos = null;

    private static final int FORMATION_CHECK_INTERVAL = 10; // ticks
    private static final int STUCK_TRIGGER_TICKS = 80;       // ~4s
    private static final double MIN_MOVE_PER_SAMPLE = 0.15;
    private static final double PROGRESS_EPS = 0.35;
    private static final double MAX_ASSIST_DIST = 96.0;
    private static final int TP_COOLDOWN = 200;              // 10s

    private void tickFormationTeleportAssist(ServerLevel level, net.minecraft.core.BlockPos formationTarget) {
        if (formationTarget == null) return;

        if (formationTeleportCooldownTicks > 0)
            formationTeleportCooldownTicks--;

        // Run occasionally
        if (++formationCheckTicker < FORMATION_CHECK_INTERVAL) return;
        formationCheckTicker = 0;

        // Don't teleport if actively fighting or riding
        if (this.getTarget() != null) return;
        if (this.hurtTime > 0) return;
        if (this.isPassenger()) return;

        double tx = formationTarget.getX() + 0.5;
        double ty = formationTarget.getY();
        double tz = formationTarget.getZ() + 0.5;

        var pos = this.position();
        double dist = pos.distanceTo(new net.minecraft.world.phys.Vec3(tx, ty, tz));

        // Respawn run-back protection
        if (dist > MAX_ASSIST_DIST) {
            sampleFormationProgress(dist);
            formationStuckTicks = 0;
            return;
        }

        if (formationTeleportCooldownTicks > 0) {
            sampleFormationProgress(dist);
            formationStuckTicks = 0;
            return;
        }

        boolean movedEnough = true;
        if (lastFormationSamplePos != null) {
            double moved = pos.distanceTo(lastFormationSamplePos);
            movedEnough = moved >= MIN_MOVE_PER_SAMPLE;
        }

        boolean makingProgress = true;
        if (!Double.isNaN(lastDistToFormation)) {
            makingProgress = (lastDistToFormation - dist) >= PROGRESS_EPS;
        }

        if (!movedEnough && !makingProgress) {
            formationStuckTicks += FORMATION_CHECK_INTERVAL;
        } else {
            formationStuckTicks = 0;
        }

        sampleFormationProgress(dist);

        if (formationStuckTicks >= STUCK_TRIGGER_TICKS) {
            net.minecraft.core.BlockPos safe = findSafeTeleportNear(level, formationTarget, 6);
            if (safe != null) {
                this.getNavigation().stop();
                this.teleportTo(safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5);
                this.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);

                formationTeleportCooldownTicks = TP_COOLDOWN;
                formationStuckTicks = 0;
            } else {
                formationTeleportCooldownTicks = 40;
                formationStuckTicks = 0;
            }
        }
    }

    private void sampleFormationProgress(double dist) {
        this.lastDistToFormation = dist;
        this.lastFormationSamplePos = this.position();
    }

    private static net.minecraft.core.BlockPos findSafeTeleportNear(ServerLevel level,
                                                                net.minecraft.core.BlockPos center,
                                                                int radius) {
        for (int r = 0; r <= radius; r++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;

                    int x = center.getX() + dx;
                    int z = center.getZ() + dz;

                    int y = level.getHeight(
                            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            x, z
                    );

                    var feet = new net.minecraft.core.BlockPos(x, y, z);

                    if (!level.getFluidState(feet).isEmpty()) continue;

                    var below = feet.below();
                    if (!level.getBlockState(below).isSolidRender()) continue;

                    if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()) continue;
                    if (!level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) continue;

                    return feet;
                }
            }
        }
        return null;
    }



    // -------------------------
    // HOLD-LINE bow draw + shoot (no chasing)
    // -------------------------
    private static final class HoldLineBowGoal extends Goal {
        private final SoldierEntity self;
        private final int intervalTicks;
        private final float attackRadius;

        private int cooldown;
        private int seeTime;

        HoldLineBowGoal(SoldierEntity self, int intervalTicks, float attackRadius) {
            this.self = self;
            this.intervalTicks = intervalTicks;
            this.attackRadius = attackRadius;
            this.setFlags(EnumSet.of(Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (self.getRole() != Role.ARCHER) return false;
            if (!self.isHolding(Items.BOW)) return false;
            LivingEntity t = self.getTarget();
            return t != null && t.isAlive();
        }

        @Override
        public boolean canContinueToUse() { return canUse(); }

        @Override
        public void start() {
            cooldown = 0;
            seeTime = 0;
        }

        @Override
        public void stop() {
            if (self.isUsingItem()) self.stopUsingItem();
        }

        @Override
        public void tick() {
            LivingEntity t = self.getTarget();
            if (t == null) return;

            double d2 = self.distanceToSqr(t);
            double r2 = (double) attackRadius * (double) attackRadius;

            boolean canSee = self.getSensing().hasLineOfSight(t);
            seeTime = canSee ? (seeTime + 1) : 0;

            self.getLookControl().setLookAt(t, 30.0F, 30.0F);

            if (cooldown > 0) cooldown--;

            // Don’t chase. Only shoot if target is in range + visible.
            if (d2 > r2 || seeTime < 4) {
                if (self.isUsingItem()) self.stopUsingItem();
                return;
            }

            if (!self.isUsingItem()) {
                if (cooldown <= 0) {
                    self.startUsingItem(InteractionHand.MAIN_HAND); // bow draw anim
                }
                return;
            }

            int use = self.getTicksUsingItem();
            if (use >= 20) {
                float power = BowItem.getPowerForTime(use);
                self.stopUsingItem();
                self.performRangedAttack(t, power);
                cooldown = intervalTicks;
            }
        }
    }

    // -------------------------
    // Goals
    // -------------------------
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new FloatGoal(this));

        // IMPORTANT: these do not navigate to target — WarBattleManager handles movement.
        this.goalSelector.addGoal(2, new HoldLineBowGoal(this, 20, 18.0F));
        this.goalSelector.addGoal(2, new HoldLineMeleeGoal(this));

        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 10.0f));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));

        // Optional (retaliation only). If you want *pure* battle-manager targets, remove this line.
        this.targetSelector.addGoal(1, new SoldierHurtByTargetGoal(this));
    }

    // -------------------------
    // RangedAttackMob
    // -------------------------
   @Override
    public void performRangedAttack(LivingEntity target, float pullProgress) {
        if (!this.isHolding(Items.BOW)) return;
        if (!(this.level() instanceof ServerLevel sl)) return;

        Arrow arrow = EntityType.ARROW.create(sl, EntitySpawnReason.EVENT);
        if (arrow == null) return;

        arrow.setOwner(this);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;

        if (target instanceof ServerPlayer sp && this.level() instanceof ServerLevel srv) {
            var sb = srv.getServer().getScoreboard();

            var shooterTeam = sb.getPlayersTeam(this.getStringUUID());
            var playerTeam  = sb.getPlayersTeam(sp.getScoreboardName()); // players use NAME entry, not UUID

            System.out.println("[WAR][ARROW] shooterSide=" + this.getSide()
                    + " shooterTeam=" + (shooterTeam == null ? "null" : shooterTeam.getName())
                    + " playerTeam=" + (playerTeam == null ? "null" : playerTeam.getName())
                    + " target.isAlliedTo(shooter)=" + sp.isAlliedTo(this)
                    + " shooter.isAlliedTo(target)=" + this.isAlliedTo(sp));
        }


        // Spawn arrow at shooter eye height FIRST
        double ax = this.getX();
        double ay = this.getEyeY() - 0.1;
        double az = this.getZ();
        arrow.setPos(ax, ay, az);

        // Damage tuning (optional but recommended)
        arrow.setBaseDamage(3.5 + (double) pullProgress * 4.0);

        // Now compute trajectory using the arrow's *actual* starting Y
        double dx = target.getX() - ax;
        double dz = target.getZ() - az;
        double dy = target.getY(0.3333333333333333) - arrow.getY();

        double horiz = Math.sqrt(dx * dx + dz * dz);

        arrow.shoot(dx, dy + horiz * 0.2, dz, 1.6F, 14.0F);
        sl.addFreshEntity(arrow);

        this.swing(InteractionHand.MAIN_HAND, true);
    }

    // -------------------------
    // Equipment / visuals
    // -------------------------
    private void applyLoadout() {
        if (this.level() == null) return;

        // Mainhand
        if (this.getRole() == Role.ARCHER) {
            this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        } else {
            this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        }

        // Offhand: shields ONLY for footmen
        if (this.getRole() == Role.FOOTMAN) {
            DyeColor base = (this.getSide() == Side.FRIEND) ? DyeColor.BLUE : DyeColor.RED;
            this.setItemSlot(EquipmentSlot.OFFHAND, makeBannerShield(base));
        } else {
            this.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        }

        // Bannerman helmet banner (your existing behavior)
        if (this.isBannerman()) {
            ItemStack banner = (this.getSide() == Side.FRIEND)
                    ? new ItemStack(Items.BLUE_BANNER)
                    : new ItemStack(Items.RED_BANNER);
            this.setItemSlot(EquipmentSlot.HEAD, banner);
        } else {
            this.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        }
    }


    private static ItemStack makeBannerShield(DyeColor baseColor) {
        ItemStack shield = new ItemStack(Items.SHIELD);
        shield.set(DataComponents.BASE_COLOR, baseColor); // solid colored banner overlay
        return shield;
    }




    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }


    @Override
    public boolean doHurtTarget(ServerLevel level, Entity target) {
        this.swing(InteractionHand.MAIN_HAND, true); // broadcast to clients
        this.setAggressive(true);
        return super.doHurtTarget(level, target);
    }

    @Override
    public void aiStep() {
        super.aiStep();

        if (!this.level().isClientSide() && this.level() instanceof ServerLevel sl) {
            tickFormationTeleportAssist(sl, this.getFormationTarget());
        }
    }


    // -------------------------
    // Saving
    // -------------------------
    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putInt("KSide", this.entityData.get(DATA_SIDE));
        output.putInt("KBanner", this.entityData.get(DATA_BANNERMAN) ? 1 : 0);
        output.putInt("KRole", this.entityData.get(DATA_ROLE));
        output.putInt("KSkin", this.entityData.get(DATA_SKIN));
        output.putInt("KCaptain", this.entityData.get(DATA_CAPTAIN) ? 1 : 0);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);

        int side = input.getIntOr("KSide", 0);
        int banner = input.getIntOr("KBanner", 0);
        int role = input.getIntOr("KRole", 0);
        int skin = input.getIntOr("KSkin", 0);
        int captain = input.getIntOr("KCaptain", 0);

        this.entityData.set(DATA_SIDE, Mth.clamp(side, 0, 1));
        this.entityData.set(DATA_BANNERMAN, banner != 0);
        this.entityData.set(DATA_ROLE, Mth.clamp(role, 0, 1));
        this.entityData.set(DATA_SKIN, Math.max(0, skin));
        this.entityData.set(DATA_CAPTAIN, captain != 0);

        applyLoadout();
    }
}
