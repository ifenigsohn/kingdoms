package name.kingdoms.entity.ai;

import name.kingdoms.namePool;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

public class RoadAmbientNPCEntity extends PathfinderMob {

    private static final EntityDataAccessor<String> AI_TYPE_ID =
            SynchedEntityData.defineId(RoadAmbientNPCEntity.class, EntityDataSerializers.STRING);

    private static final EntityDataAccessor<Integer> SKIN_ID =
            SynchedEntityData.defineId(RoadAmbientNPCEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<String> NPC_NAME =
            SynchedEntityData.defineId(RoadAmbientNPCEntity.class, EntityDataSerializers.STRING);

    private static final EntityDataAccessor<String> KINGDOM_UUID =
            SynchedEntityData.defineId(RoadAmbientNPCEntity.class, EntityDataSerializers.STRING);

    // lightweight TTL so they clean up naturally
    private static final EntityDataAccessor<Integer> TTL =
            SynchedEntityData.defineId(RoadAmbientNPCEntity.class, EntityDataSerializers.INT);

    public RoadAmbientNPCEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.24D)
                .add(Attributes.FOLLOW_RANGE, 16.0D);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        // if you want the same “trapdoor blocking” nav as your other NPCs:
        return new name.kingdoms.entity.TrapdoorBlockingGroundNavigation(this, level);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));

        // Road-walking “patrol” behavior (defined below)
        this.goalSelector.addGoal(2, new name.kingdoms.entity.ambient.RoadTravelGoal(this, 1.10D));

        // NOTE: intentionally no LookAtPlayerGoal / RandomLookAroundGoal / speech goals
        // These are meant to “pass through” the world.
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(AI_TYPE_ID, "soldier");
        b.define(SKIN_ID, 0);
        b.define(NPC_NAME, "");
        b.define(KINGDOM_UUID, "");
        b.define(TTL, 20 * 60 * 6); // default 6 minutes
    }

    // --- Nametag hover-only ---
    @Override
    public boolean isCustomNameVisible() {
        // false = hover-only display (when crosshair is on them)
        return false;
    }

    private static String titleForType(String type) {
        return switch (type) {
            case "guard", "soldier", "scout" -> "Guard";   // <-- road walkers: all military called Guard
            case "peasant" -> "Peasant";
            case "villager" -> "Villager";
            case "noble" -> "Noble";
            case "trader" -> "Trader";
            case "envoy" -> "Envoy";
            case "refugee" -> "Refugee";
            case "scholar" -> "Scholar";
            default -> "Traveler";
        };
    }

    public void refreshNametag() {
        String type = getAiTypeId();
        String nm = getNpcName();
        if (nm == null || nm.isBlank()) nm = "Aelfric";

        // Keep it simple: name + role (you can append kingdom ruler later if you want)
        String built = nm + " [" + titleForType(type) + "]";
        this.setCustomName(Component.literal(built));
        this.setCustomNameVisible(false); // hover-only
    }

    // --- accessors ---
    public String getAiTypeId() { return this.entityData.get(AI_TYPE_ID); }
    public int getSkinId() { return this.entityData.get(SKIN_ID); }
    public String getNpcName() { return this.entityData.get(NPC_NAME); }

    public void setAiTypeId(String v) {
        this.entityData.set(AI_TYPE_ID, (v == null || v.isBlank()) ? "soldier" : v.toLowerCase(Locale.ROOT));
    }

    public void setSkinId(int id) { this.entityData.set(SKIN_ID, Mth.clamp(id, 0, Integer.MAX_VALUE)); }

    public void setNpcName(String v) { this.entityData.set(NPC_NAME, v == null ? "" : v); }

    @Nullable
    public UUID getKingdomUUID() {
        String s = this.entityData.get(KINGDOM_UUID);
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    public void setKingdomUUID(@Nullable UUID id) {
        this.entityData.set(KINGDOM_UUID, id == null ? "" : id.toString());
    }

    public void setTtlTicks(int ticks) { this.entityData.set(TTL, Math.max(0, ticks)); }

    @Override
    public void tick() {
        super.tick();
        if (!(this.level() instanceof ServerLevel sl)) return;

        int ttl = this.entityData.get(TTL);
        if (ttl <= 0) {
            // if mounted, also discard mount if it was tagged ambient
            Entity v = this.getVehicle();
            if (v != null && v.getTags().contains(name.kingdoms.ambient.SpawnUtil.AMBIENT_MOUNT_TAG)) {
                v.discard();
            }
            this.discard();
            return;
        }
        this.entityData.set(TTL, ttl - 1);
    }

    // --- Spawn finalize: ensure name exists + nametag built ---
    @Override
    public @Nullable SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance diff, EntitySpawnReason reason, @Nullable SpawnGroupData data) {
        SpawnGroupData d = super.finalizeSpawn(level, diff, reason, data);

        // If spawned by command/egg/etc and still default, randomize a visual type + skin
        if ("soldier".equals(getAiTypeId()) && getSkinId() == 0) {
            String[] types = new String[]{"peasant","villager","noble","soldier"};
            String t = types[this.random.nextInt(types.length)];
            setAiTypeId(t);

            int maxExclusive = switch (t) {
                case "peasant" -> 46;
                case "villager" -> 12;
                case "noble" -> 25;
                case "soldier" -> 29;
                default -> 12;
            };
            setSkinId(this.random.nextInt(Math.max(1, maxExclusive)));
        }


        if (level instanceof ServerLevel sl) {
            if (this.entityData.get(NPC_NAME).isBlank()) {
                this.entityData.set(NPC_NAME, namePool.randomMedieval(sl.getServer(), sl.random));
            }
        } else {
            if (this.entityData.get(NPC_NAME).isBlank()) this.entityData.set(NPC_NAME, "Aelfric");
        }
        refreshNametag();
        return d;
    }

    @Override
    public void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putString("AiTypeId", getAiTypeId());
        out.putInt("SkinId", getSkinId());
        out.putString("NpcName", getNpcName());
        UUID kid = getKingdomUUID();
        if (kid != null) out.putString("KingdomUUID", kid.toString());
        out.putInt("Ttl", this.entityData.get(TTL));
    }

    @Override
    public void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        setAiTypeId(in.getString("AiTypeId").orElse("soldier"));
        setSkinId(in.getInt("SkinId").orElse(0));
        setNpcName(in.getString("NpcName").orElse(""));
        this.entityData.set(KINGDOM_UUID, in.getString("KingdomUUID").orElse(""));
        this.entityData.set(TTL, in.getInt("Ttl").orElse(20 * 60 * 6));
        refreshNametag();
    }
}
