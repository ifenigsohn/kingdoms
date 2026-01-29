package name.kingdoms.entity;

import name.kingdoms.kingSkinPoolState;
import name.kingdoms.kingdomKingSpawnerBlock;
import name.kingdoms.kingdomState;
import name.kingdoms.namePool;
import name.kingdoms.entity.ai.FindBedAtNightGoalAI;
import name.kingdoms.entity.ai.ReturnHomeDayGoalAI;
import name.kingdoms.payload.opendiplomacyS2CPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import name.kingdoms.aiKingdomState;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class aiKingdomEntity extends PathfinderMob {

    // --- Synched data ---
    private static final EntityDataAccessor<String> AI_TYPE_ID =
            SynchedEntityData.defineId(aiKingdomEntity.class, EntityDataSerializers.STRING);

    private static final EntityDataAccessor<String> KING_NAME =
            SynchedEntityData.defineId(aiKingdomEntity.class, EntityDataSerializers.STRING);

    private static final EntityDataAccessor<Integer> SKIN_ID =
            SynchedEntityData.defineId(aiKingdomEntity.class, EntityDataSerializers.INT);

    // Spawner binding
    private static final EntityDataAccessor<Boolean> HAS_SPAWNER =
            SynchedEntityData.defineId(aiKingdomEntity.class, EntityDataSerializers.BOOLEAN);

    private static final EntityDataAccessor<BlockPos> SPAWNER_POS =
            SynchedEntityData.defineId(aiKingdomEntity.class, EntityDataSerializers.BLOCK_POS);

    private static final EntityDataAccessor<String> KINGDOM_UUID =
        SynchedEntityData.defineId(aiKingdomEntity.class, EntityDataSerializers.STRING);


    // --- Positions ---
    @Nullable private BlockPos homePos;
    @Nullable private BlockPos assignedBedPos;

    // --- Teleport safety ---
    private static final int HARD_TELEPORT_RADIUS = 50;
    private static final long MORNING_TIME = 1000L;
    private static final long NOON_TIME = 6000L;
    private static final int NOON_TELEPORT_RADIUS = 48;
    private static final int NOON_TELEPORT_COOLDOWN_TICKS = 20 * 60;
    private int noonTeleportCooldown = 0;

    // ---- ambient scene behavior ----
private java.util.UUID ambientLeaderId = null;
private net.minecraft.core.BlockPos ambientAnchorPos = null;
private int ambientAnchorRadius = 0;


    private int panicTicks = 0;

    // --- Diplomacy freeze (server timer; refreshed by C2S keepalive) ---
    private int diplomacyFreezeTicks = 0;

    public void freezeForDiplomacy(int ticks) {
        this.diplomacyFreezeTicks = Math.max(this.diplomacyFreezeTicks, ticks);
    }

    public void clearDiplomacyFreeze() {
        this.diplomacyFreezeTicks = 0;
    }

    // --- Sleep poof rate ---
    private static final int SLEEP_POOF_INTERVAL_TICKS = 60; // 3 seconds

    // --- Greeting logic ---
    private static final double GREET_RADIUS = 5.0;
    private static final double RESET_RADIUS = 50.0;

    // first-time greeting memory
    private final Map<UUID, Boolean> greetedEver = new HashMap<>();
    // “entered greet radius” memory (to prevent spam)
    private final Map<UUID, Boolean> greetedNear = new HashMap<>();
    private int greetScanTimer = 0;

    // --- Player-specific relations ---
    private final Map<UUID, Integer> relationByPlayer = new HashMap<>();

    public int getRelationFor(UUID playerId) {
        return relationByPlayer.getOrDefault(playerId, 5); // default
    }

    public void setRelationFor(UUID playerId, int v) {
        relationByPlayer.put(playerId, Mth.clamp(v, -10, 10));
    }

    // --- Variant text pools ---
    private static final String[] INTRO_LINES = {
            "Greetings, {P}. I am King {K}.",
            "Hail, {P}. You stand before King {K}.",
            "Well met, {P}. I am {K}, sovereign of these lands.",
            "{P}… you have my audience. I am King {K}.",
            "Welcome, {P}. I am King {K}; speak freely.",
            "Ah, {P}. I am King {K}. What brings you here?",
            "You approach my court, {P}. I am King {K}.",
            "State your name and business, {P}. I am King {K}.",
            "Behold—King {K}. Speak, {P}.",
            "{P}, you stand in the presence of King {K}."
    };

    private static final String[] PEASANTRY_LINES = {
            "{P}, I don't speak with peasantry.",
            "Begone, {P}. My court is not for peasants.",
            "{P}, you have no standing here. Leave.",
            "No kingdom, no audience, {P}.",
            "I will not waste words on peasants, {P}.",
            "{P}, find yourself a lord before you address a king."
    };

    private static final String[] GREET_10_8 = {
            "{P}! It gladdens me to see you again, friend!",
            "{P}! Ah—my friend returns! Speak, and I shall listen.",
            "Well met, {P}! You honor my hall with your presence.",
            "{P}! Come, join me—what news do you bring?",
            "Ha! {P}! Fortune smiles upon us to meet again."
    };

    private static final String[] GREET_7_6 = {
            "Good day, {P}. What can I do for you?",
            "Well met, {P}. Speak your business.",
            "A fair day to you, {P}. How may I help?",
            "Greetings, {P}. What brings you here?",
            "{P}. You have my attention—state your need."
    };

    private static final String[] GREET_5_4 = {
            "Hello, {P}. What is it you need?",
            "{P}. Speak quickly—what do you require?",
            "Yes, {P}? What matter is this?",
            "Hello, {P}. State your request.",
            "{P}. What is it you seek from me?"
    };

    private static final String[] GREET_3_2 = {
            "Oh, {P}. State your request and begone.",
            "{P}. Make it brief. I have little patience.",
            "You again, {P}. Say what you must, then leave me.",
            "Speak, {P}. And be quick about it.",
            "{P}. I am not in the mood—what is it?"
    };

    private static final String[] GREET_1 = {
            "{P}. Do you wish death?!",
            "Back away, {P}, lest you regret it!",
            "{P}. One wrong word and you’ll answer for it!",
            "Tread carefully, {P}. You stand on thin ice.",
            "{P}. Say your piece—if you dare."
    };

    private String pick(String[] arr, String playerName) {
        String s = arr[this.random.nextInt(arr.length)];
        return s.replace("{P}", playerName);
    }

    private String pickIntro(String playerName) {
        String s = INTRO_LINES[this.random.nextInt(INTRO_LINES.length)];
        return s.replace("{P}", playerName).replace("{K}", getKingName());
    }

    private boolean isPlayerInKingdom(ServerPlayer sp) {
        if (!(sp.level() instanceof ServerLevel sl)) return false;
        // If your API differs, change this line:
        return kingdomState.get(sl.getServer()).getPlayerKingdom(sp.getUUID()) != null;
    }

    private Component buildGreeting(ServerPlayer sp) {
        UUID id = sp.getUUID();
        String name = sp.getName().getString();
        int r = getRelationFor(id);

        if (!greetedEver.getOrDefault(id, false)) {
            greetedEver.put(id, true);
            return Component.literal(pickIntro(name));
        }

        if (r >= 8) return Component.literal(pick(GREET_10_8, name));
        if (r >= 6) return Component.literal(pick(GREET_7_6, name));
        if (r >= 4) return Component.literal(pick(GREET_5_4, name));
        if (r >= 2) return Component.literal(pick(GREET_3_2, name));
        return Component.literal(pick(GREET_1, name));
    }

    // --- Constructor / attributes ---
    public aiKingdomEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 30.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.20D)
                .add(Attributes.FOLLOW_RANGE, 24.0D);
    }

    // --- Navigation ---
    @Override
    protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(Level level) {
        return new name.kingdoms.entity.TrapdoorBlockingGroundNavigation(this, level);
    }


    // --- AI goals ---
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new PanicGoal(this, 1.35D));
        this.goalSelector.addGoal(4, new OpenDoorGoal(this, true));

        this.goalSelector.addGoal(2, new FindBedAtNightGoalAI(this, 1.05D, 30));
        this.goalSelector.addGoal(3, new ReturnHomeDayGoalAI(this, 1.05D));

        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.9D) {
            @Override public boolean canUse() { return !aiKingdomEntity.this.isSleeping() && super.canUse(); }
            @Override public boolean canContinueToUse() { return !aiKingdomEntity.this.isSleeping() && super.canContinueToUse(); }
        });

        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 6.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    // --- Synched data defaults ---
    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(AI_TYPE_ID, "king");
        builder.define(KING_NAME, "");
        builder.define(SKIN_ID, 0);
        builder.define(KINGDOM_UUID, "");
        builder.define(HAS_SPAWNER, false);
        builder.define(SPAWNER_POS, BlockPos.ZERO);
    }

    // --- Public getters/setters ---
    public String getAiTypeId() { return this.entityData.get(AI_TYPE_ID); }

    public String getKingName() { return this.entityData.get(KING_NAME); }

    public void setKingName(String name) {
        this.entityData.set(KING_NAME, (name == null || name.isBlank()) ? "Aelfric" : name);
        this.setCustomName(Component.literal("King " + getKingName()));
        this.setCustomNameVisible(true); 
    }

    public int getSkinId() { return this.entityData.get(SKIN_ID); }

    public void setSkinId(int skinId) {
        this.entityData.set(SKIN_ID, Mth.clamp(skinId, 0, Integer.MAX_VALUE));
    }

    @Nullable public BlockPos getHomePos() { return homePos; }

    public void setHomePos(@Nullable BlockPos pos) { homePos = pos; }

    @Nullable public BlockPos getAssignedBedPos() { return assignedBedPos; }

    public void setAssignedBedPos(@Nullable BlockPos pos) { assignedBedPos = pos; }

    // Spawner binding API
    public void setSpawnerPos(BlockPos pos) {
        this.entityData.set(HAS_SPAWNER, true);
        this.entityData.set(SPAWNER_POS, pos);
    }

    public boolean hasSpawnerPos() { return this.entityData.get(HAS_SPAWNER); }

    public BlockPos getSpawnerPos() { return this.entityData.get(SPAWNER_POS); }

    public boolean isBedClaimedByOther(BlockPos bedHeadPos, int checkRadius) {
        if (!(this.level() instanceof ServerLevel sl)) return false;

        return !sl.getEntitiesOfClass(
                aiKingdomEntity.class,
                this.getBoundingBox().inflate(checkRadius),
                w -> w != this
                        && w.getAssignedBedPos() != null
                        && w.getAssignedBedPos().equals(bedHeadPos)
        ).isEmpty();
    }

    /** Called by the spawner block so the block controls “what kind” this ruler is. */
    public void initFromSpawner(@Nullable String aiTypeId, int skinId) {
        if (!(this.level() instanceof ServerLevel sl)) return;

        this.entityData.set(AI_TYPE_ID, (aiTypeId == null || aiTypeId.isBlank()) ? "king" : aiTypeId);
        this.entityData.set(KING_NAME, namePool.randomMedieval(sl.getServer(), sl.random));

        int chosen = skinId;
        if (chosen < 0) {
            chosen = kingSkinPoolState.get(sl.getServer()).nextSkinId(sl.random);
        }
        this.entityData.set(SKIN_ID, Mth.clamp(chosen, 0, kingSkinPoolState.MAX_SKIN_ID));

        if (this.homePos == null) this.homePos = this.blockPosition();
        this.setCustomName(Component.literal("King " + getKingName()));
        this.setCustomNameVisible(true);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (this.level().isClientSide()) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.CONSUME;

        // Peasants get nothing but an insult; do NOT open diplomacy
        if (!isPlayerInKingdom(sp)) {
            sp.sendSystemMessage(Component.literal(pick(PEASANTRY_LINES, sp.getName().getString())));
            return InteractionResult.CONSUME;
        }

        // Freeze long enough to cover UI opening; C2S keepalive will extend while open
        this.freezeForDiplomacy(40);

        ServerLevel sl = (ServerLevel) this.level();

        // relations now come from DiplomacyRelationsState (server-wide)
        int rel = name.kingdoms.diplomacy.DiplomacyRelationsState
                .get(sl.getServer())
                .getRelation(sp.getUUID(), this.getUUID());

        // Ensure this king has an AI kingdom entry (creates it if missing)
        aiKingdomState ai = aiKingdomState.get(sl.getServer());
        ai.getOrCreateForKing(sl, this);

        UUID kingdomId = this.getUUID();

        ServerPlayNetworking.send(sp, new opendiplomacyS2CPayload(
                this.getId(),
                this.getUUID(),
                kingdomId,
                getKingName(),
                rel
        ));

        return InteractionResult.CONSUME;
    }


    @Override
    public @Nullable SpawnGroupData finalizeSpawn(
            ServerLevelAccessor level,
            DifficultyInstance difficulty,
            EntitySpawnReason reason,
            @Nullable SpawnGroupData spawnData
    ) {
        SpawnGroupData data = super.finalizeSpawn(level, difficulty, reason, spawnData);

        if (level instanceof ServerLevel sl) {
            if (this.entityData.get(KING_NAME).isBlank()) {
                this.entityData.set(KING_NAME, namePool.randomMedieval(sl.getServer(), sl.random));
            }
            this.setCustomName(Component.literal("King " + getKingName()));
            this.setCustomNameVisible(true);
        }

        return data;
    }


    // Leader-follow (train)
    public void setAmbientLeader(java.util.UUID leaderId, int spacingBlocks) {
        this.ambientLeaderId = leaderId;
        // spacingBlocks currently used by spawner; goal uses distances, but keep this hook anyway.
    }

    public java.util.UUID getAmbientLeaderId() {
        return this.ambientLeaderId;
    }





    public net.minecraft.core.BlockPos getAmbientAnchorPos() {
        return this.ambientAnchorPos;
    }



    public boolean hasAmbientAnchor() {
        return this.ambientAnchorPos != null && this.ambientAnchorRadius > 0;
    }

    
    // --- Tick ---
    @Override
    public void tick() {
        super.tick();

        if (!(this.level() instanceof ServerLevel level)) return;

        // Despawn if spawner destroyed
        if (this.hasSpawnerPos()) {
            BlockPos sp = this.getSpawnerPos();
            BlockState bs = level.getBlockState(sp);
            if (!(bs.getBlock() instanceof kingdomKingSpawnerBlock)) {
                this.discard();
                return;
            }
        }

        // Freeze while diplomacy is active
        if (diplomacyFreezeTicks > 0) {
            diplomacyFreezeTicks--;
            this.getNavigation().stop();
            this.setDeltaMovement(0, this.getDeltaMovement().y, 0);
            this.setSprinting(false);
            return;
        }

        // Wake logic runs even while sleeping
        if (homePos != null) {
            long time = level.getDayTime() % 24000L;
            if (this.isSleeping() && time >= MORNING_TIME && time < 6000L) {
                this.stopSleeping();
            }
        }

        // Sleep freeze + POOF every 3 seconds
        if (this.isSleeping()) {
            if (this.tickCount % SLEEP_POOF_INTERVAL_TICKS == 0) {
                level.sendParticles(
                        ParticleTypes.POOF,
                        this.getX(), this.getY() + 0.25, this.getZ(),
                        8,
                        0.25, 0.10, 0.25,
                        0.01
                );
            }

            this.getNavigation().stop();
            this.setDeltaMovement(0, this.getDeltaMovement().y, 0);
            this.setSprinting(false);
            return;
        }

        // panic / running
        if (this.isOnFire() || this.hurtTime > 0) {
            panicTicks = 80;
        } else if (panicTicks > 0) {
            panicTicks--;
        }
        this.setSprinting(panicTicks > 0);

        // Greeting system (server): only for players in kingdoms
        if (++greetScanTimer >= 20) {
            greetScanTimer = 0;

            // clear greetedNear once player is far away
            greetedNear.entrySet().removeIf(e -> {
                ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
                if (p == null) return true;
                return p.distanceToSqr(this) > (RESET_RADIUS * RESET_RADIUS);
            });

            Player nearest = level.getNearestPlayer(this, GREET_RADIUS);
            if (nearest instanceof ServerPlayer sp) {
                if (!isPlayerInKingdom(sp)) {
                    // No greeting at all for peasants (as requested)
                } else {
                    UUID id = sp.getUUID();
                    if (!greetedNear.getOrDefault(id, false)) {
                        sp.sendSystemMessage(buildGreeting(sp));
                        greetedNear.put(id, true);
                    }
                }
            }
        }

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
        out.putString("KingName", getKingName());
        out.putInt("SkinId", getSkinId());

        if (homePos != null) out.putLong("HomePos", homePos.asLong());
        if (assignedBedPos != null) out.putLong("BedPos", assignedBedPos.asLong());

        out.putBoolean("HasSpawner", this.hasSpawnerPos());
        if (this.hasSpawnerPos()) out.putLong("SpawnerPos", this.getSpawnerPos().asLong());

        // greetedEver (UUID strings)
        out.putInt("GreetedCount", greetedEver.size());
        int gi = 0;
        for (UUID id : greetedEver.keySet()) {
            out.putString("Greeted_" + gi++, id.toString());
        }

        // per-player relations
        out.putInt("RelCount", relationByPlayer.size());
        int ri = 0;
        for (var e : relationByPlayer.entrySet()) {
            out.putString("RelU_" + ri, e.getKey().toString());
            out.putInt("RelV_" + ri, e.getValue());
            ri++;
        }
    }

    @Override
    public void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);

        this.entityData.set(AI_TYPE_ID, in.getString("AiTypeId").orElse("king"));
        this.entityData.set(KING_NAME, in.getString("KingName").orElse("Aelfric"));
        this.entityData.set(SKIN_ID, in.getInt("SkinId").orElse(0));

        homePos = in.getLong("HomePos").map(BlockPos::of).orElse(null);
        assignedBedPos = in.getLong("BedPos").map(BlockPos::of).orElse(null);

        boolean has = in.getBooleanOr("HasSpawner", false);
        this.entityData.set(HAS_SPAWNER, has);
        this.entityData.set(SPAWNER_POS, has
                ? BlockPos.of(in.getLongOr("SpawnerPos", BlockPos.ZERO.asLong()))
                : BlockPos.ZERO);

        // restore nametag
        this.setCustomName(Component.literal("King " + getKingName()));
        this.setCustomNameVisible(true);
        
        // greetedEver
        greetedEver.clear();
        int gcount = in.getInt("GreetedCount").orElse(0);
        for (int i = 0; i < gcount; i++) {
            in.getString("Greeted_" + i).ifPresent(s -> {
                try { greetedEver.put(UUID.fromString(s), true); }
                catch (IllegalArgumentException ignored) {}
            });
        }

        // relations
        relationByPlayer.clear();
        int rcount = in.getInt("RelCount").orElse(0);
        for (int i = 0; i < rcount; i++) {
            String u = in.getString("RelU_" + i).orElse(null);
            int v = in.getInt("RelV_" + i).orElse(5);
            if (u == null) continue;
            try { relationByPlayer.put(UUID.fromString(u), Mth.clamp(v, -10, 10)); }
            catch (IllegalArgumentException ignored) {}
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
    }
}
