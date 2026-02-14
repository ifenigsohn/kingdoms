package name.kingdoms;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import name.kingdoms.entity.kingdomWorkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public class jobBlockEntity extends BlockEntity {

    private jobDefinition job;
    private int tickCounter = 0;

    // Counts into kingdomState
    private boolean countedActive = false;
    private boolean countedPlaced = false;
    @Nullable private UUID countedKingdomId;

    private static final String ENVOY_ID = "envoy";
    private static final int ENVOY_RADIUS = 600; // or pull from your envoyBlockEntity constant if you want


    // Worker ownership
    @Nullable private UUID workerUuid;
    @Nullable private UUID ownerKingdomId;

    // Missing-requirements FX state (poof, not spam)
    private boolean wasMissingReqs = false;
    private long nextMissingReqPoofTick = 0L;

    // "became active" particles throttle (prevents spam when resources flicker)
    private long lastActivateParticlesTick = -999999L;
    private static final long ACTIVATE_PARTICLE_COOLDOWN_TICKS = 200L; // 10 seconds

    private void poofAtBlock(ServerLevel level) {
        BlockPos p = this.worldPosition;
        spawnEntityPoof(level, p.getX() + 0.5, p.getY() + 0.1, p.getZ() + 0.5);
    }

    // "no resources" chat throttle
    private long lastNoResourceMsgTick = 0L;
    private static final int NO_RESOURCE_MSG_COOLDOWN_TICKS = 6000; // 5 minutes
    private static final double NO_RESOURCE_MSG_RADIUS = 24.0;

    private static final int SKIN_COUNT = 8;
    @Nullable private UUID assignedWorkerUuid = null;

    @Nullable
    public UUID getAssignedWorkerUuid() { return assignedWorkerUuid; }

    public void setAssignedWorkerUuid(@Nullable UUID id) { assignedWorkerUuid = id; }

    // datapack path: resources/data/kingdoms/names/medieval_names.json
    private static final ResourceLocation NAMES_JSON =
            ResourceLocation.fromNamespaceAndPath("kingdoms", "names/medieval_names.json");

    private static final String[] FALLBACK_NAMES = {
            "Aldric","Bryn","Celia","Doran","Elowen","Fenn","Garrick","Hilda","Ivo","Jora"
    };

    private static volatile List<String> CACHED_NAMES = null;

    public jobBlockEntity(BlockPos pos, BlockState state) {
        super(Kingdoms.JOB_BLOCK_ENTITY, pos, state);
        inferJobFromBlock(state);
    }

    /* -----------------------------
       PARTICLES
     ----------------------------- */

    private static void spawnEntityPoof(ServerLevel level, double x, double y, double z) {
        level.sendParticles(
                ParticleTypes.POOF,
                x, y + 0.2, z,
                18,
                0.25, 0.35, 0.25,
                0.02
        );
    }

    private static void spawnActivateParticles(ServerLevel level, BlockPos pos) {
        level.sendParticles(
                ParticleTypes.HAPPY_VILLAGER,
                pos.getX() + 0.5,
                pos.getY() + 1.1,
                pos.getZ() + 0.5,
                12,
                0.25, 0.25, 0.25,
                0.0
        );
    }

    private static void spawnNoResourceParticles(ServerLevel level, BlockPos pos) {
        level.sendParticles(
                ParticleTypes.ANGRY_VILLAGER,
                pos.getX() + 0.5,
                pos.getY() + 1.1,
                pos.getZ() + 0.5,
                1,
                0.12, 0.05, 0.12,
                0.0
        );
    }

    private static void spawnDisabledSmoke(ServerLevel level, BlockPos pos) {
        level.sendParticles(
                ParticleTypes.SMOKE,
                pos.getX() + 0.5,
                pos.getY() + 1.05,
                pos.getZ() + 0.5,
                1,
                0.10, 0.02, 0.10,
                0.0
        );
    }

    /* -----------------------------
       JOB LOGIC
     ----------------------------- */

    private void inferJobFromBlock(BlockState state) {
        if (state.getBlock() instanceof jobBlock jb) {
            this.job = jobDefinition.byId(jb.getJobId());
        }
    }

    public static void tick(Level level, BlockPos pos, BlockState state, jobBlockEntity be) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        if (be.job == null) be.inferJobFromBlock(state);
        if (be.job == null) return;

        kingdomState ks = kingdomState.get(serverLevel.getServer());
        kingdomState.Kingdom kingdom = ks.getKingdomAt(serverLevel, pos);

        boolean isEnvoy = "envoy".equals(be.job.getId());

        if (isEnvoy && kingdom != null && be.ownerKingdomId == null) {
            be.setOwnerKingdomId(kingdom.id);
            ks.upsertEnvoyAnchor(kingdom.id, serverLevel.dimension(), pos, ENVOY_RADIUS);
            ks.markDirty();
        }


        if (isEnvoy && kingdom == null) {
            // allow envoy to run outside borders using explicit owner
            if (be.ownerKingdomId != null) {
                kingdom = ks.getKingdom(be.ownerKingdomId);
            }
        }


        // ---- Reconcile placed/active counts if borders changed ----
        UUID nowKid = (kingdom == null) ? null : kingdom.id;
        UUID prevKid = be.countedKingdomId;

        if (!Objects.equals(nowKid, prevKid)) {
            if (prevKid != null) {
                kingdomState.Kingdom prevK = ks.getKingdom(prevKid);
                if (prevK != null) {
                    if (be.countedPlaced) kingdomState.bumpPlacedCount(prevK, be.job, -1);
                    if (be.countedActive) kingdomState.bumpActiveCount(prevK, be.job, -1);
                    ks.markDirty();
                }
            }

            be.countedPlaced = false;
            be.countedActive = false;
            be.tickCounter = 0;
            be.countedKingdomId = nowKid;

            be.wasMissingReqs = false;
            be.nextMissingReqPoofTick = 0L;

            be.setChanged();
        }

        // Not in any kingdom: no worker, no counts.
        if (kingdom == null) {
            be.setCountedActive(serverLevel, ks, null, false);
            be.despawnWorker(serverLevel); // includes poof (fine)
            be.tickCounter = 0;
            be.wasMissingReqs = false;
            be.nextMissingReqPoofTick = 0L;
            be.setChanged();
            return;
        }

        // Count as placed once while inside a kingdom.
        if (!be.countedPlaced) {
            be.countedPlaced = true;
            be.countedKingdomId = kingdom.id;
            kingdomState.bumpPlacedCount(kingdom, be.job, +1);
            ks.markDirty();
            be.setChanged();
        }

        // Manual disable
        boolean enabled = true;
        try {
            enabled = state.getValue(jobBlock.ENABLED);
        } catch (Throwable ignored) {}

        if (!enabled) {
            if ((serverLevel.getGameTime() % 40L) == 0L) {
                spawnDisabledSmoke(serverLevel, pos);
                spawnNoResourceParticles(serverLevel, pos); // angry at block too
            }

            be.setCountedActive(serverLevel, ks, kingdom, false);

            // keep worker around idle
            be.ensureWorker(serverLevel, pos, kingdom, false);

            // angry villager on worker while disabled
            if ((serverLevel.getGameTime() % 40L) == 0L) {
                kingdomWorkerEntity w = be.getWorkerIfAlive(serverLevel);
                if (w != null) {
                    serverLevel.sendParticles(
                            ParticleTypes.ANGRY_VILLAGER,
                            w.getX(), w.getY() + 1.0, w.getZ(),
                            1,
                            0.12, 0.10, 0.12,
                            0.0
                    );
                }
            }

            be.tickCounter = 0;

            // reset missing-req state
            be.wasMissingReqs = false;
            be.nextMissingReqPoofTick = 0L;
            return;
        }


        // ---- IMPORTANT: check requirements BEFORE spawning worker ----
        boolean meetsReqs = be.job.meetsBlockRequirements(serverLevel, pos, jobBlock.REQUIRE_RADIUS);

        if (!meetsReqs) {
            // NOT active if requirements missing
            be.setCountedActive(serverLevel, ks, kingdom, false);

            long t = serverLevel.getGameTime();

            // poof once on transition to missing
            if (!be.wasMissingReqs) {
                be.poofAtBlock(serverLevel);
                be.wasMissingReqs = true;
                be.nextMissingReqPoofTick = t + 200L + serverLevel.random.nextInt(200); // 10–20s
                be.setChanged();
            } else if (t >= be.nextMissingReqPoofTick) {
                be.poofAtBlock(serverLevel);
                be.nextMissingReqPoofTick = t + 200L + serverLevel.random.nextInt(200); // 10–20s
                be.setChanged();
            }

            // worker should NOT exist if reqs missing (prevents spawn-poof loop)
            be.despawnWorkerSilent(serverLevel);

            be.tickCounter = 0;
            return;
        } else {
            if (be.wasMissingReqs) {
                be.wasMissingReqs = false;
                be.setChanged();
            }
        }

        // Now that requirements are met, ensure worker exists
        be.ensureWorker(serverLevel, pos, kingdom, true);
        be.pruneDuplicateWorkers(serverLevel, pos);


        boolean canWorkNow = be.job.canWork(serverLevel, kingdom);

        // Active ONLY if requirements met AND inputs available (for chapel/tavern happiness gating)
        be.setCountedActive(serverLevel, ks, kingdom, canWorkNow);

        if ("envoy".equals(be.job.getId())) {
            UUID okid = (be.ownerKingdomId != null) ? be.ownerKingdomId : (kingdom != null ? kingdom.id : null);

            if (okid != null) {
                if (canWorkNow) {
                    ks.upsertEnvoyAnchor(okid, serverLevel.dimension(), pos, ENVOY_RADIUS);
                } else {
                    ks.removeEnvoyAnchor(okid, serverLevel.dimension(), pos);
                }
                ks.markDirty();
            }
        }



       if (!canWorkNow) {
            if ((serverLevel.getGameTime() % 40L) == 0L) {
                spawnNoResourceParticles(serverLevel, pos);

                // ALSO angry particles on the worker
                kingdomWorkerEntity w = be.getWorkerIfAlive(serverLevel);
                if (w != null) {
                    serverLevel.sendParticles(
                        ParticleTypes.ANGRY_VILLAGER,
                        w.getX(), w.getY() + 1.0, w.getZ(),
                        1,
                        0.12, 0.10, 0.12,
                        0.0
                    );
                }
            }

            be.sayNoResources(serverLevel);
            be.tickCounter = 0;
            return;
        }


        be.tickCounter++;
        if (be.tickCounter < be.job.getWorkInterval()) return;
        be.tickCounter = 0;

        if (!be.job.consumeInputs(serverLevel, kingdom)) {
            // If inputs vanished between canWorkNow and consume, we should become inactive
            be.setCountedActive(serverLevel, ks, kingdom, false);

            if ((serverLevel.getGameTime() % 40L) == 0L) {
                spawnNoResourceParticles(serverLevel, pos);
            }
            be.sayNoResources(serverLevel);
            return;
        }

        be.job.applyOutputs(serverLevel, kingdom);
        ks.markDirty();
        be.setChanged();
    }

    private void setCountedActive(ServerLevel level, kingdomState ks, @Nullable kingdomState.Kingdom kingdom, boolean shouldBeActive) {
        if (kingdom == null || job == null) {
            countedActive = false;
            return;
        }

        if (shouldBeActive == countedActive) return;

        // Transition false -> true: spawn HAPPY_VILLAGER once (with cooldown)
        if (shouldBeActive) {
            long now = level.getGameTime();
            if (now - lastActivateParticlesTick >= ACTIVATE_PARTICLE_COOLDOWN_TICKS) {
                spawnActivateParticles(level, this.worldPosition);
                lastActivateParticlesTick = now;
            }
        }

        countedActive = shouldBeActive;
        kingdomState.bumpActiveCount(kingdom, job, shouldBeActive ? 1 : -1);
        ks.markDirty();
        this.setChanged();
    }

    /* -----------------------------
       "VANILLA CHAT" MESSAGE
     ----------------------------- */

    private void sayNoResources(ServerLevel level) {
        long now = level.getGameTime();
        if (now - lastNoResourceMsgTick < NO_RESOURCE_MSG_COOLDOWN_TICKS) return;
        lastNoResourceMsgTick = now;

        kingdomWorkerEntity w = getWorkerIfAlive(level);
        if (w == null) return;

        Component chatLine = Component.translatable(
                "chat.type.text",
                w.getDisplayName(),
                Component.literal("I don't have resources to work with!")
        );

        double r2 = NO_RESOURCE_MSG_RADIUS * NO_RESOURCE_MSG_RADIUS;
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(w) <= r2) {
                p.sendSystemMessage(chatLine);
            }
        }
    }

    /* -----------------------------
       WORKER MANAGEMENT
     ----------------------------- */

    private void ensureWorker(ServerLevel level, BlockPos jobPos, kingdomState.Kingdom kingdom, boolean enabled) {

        kingdomWorkerEntity worker = getWorkerIfAlive(level);

        if (worker == null) {
            var type = Kingdoms.KINGDOM_WORKER_ENTITY_TYPE;
            if (type == null) return;

            BlockPos spawn = jobPos.above();

            worker = type.create(
                    level,
                    (Consumer<kingdomWorkerEntity>) e -> {},
                    spawn,
                    EntitySpawnReason.EVENT,
                    false,
                    false
            );
            if (worker == null) return;

            worker.setPos(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);

            float yaw = level.random.nextFloat() * 360.0F;
            worker.setYRot(yaw);
            worker.setYHeadRot(yaw);

            worker.setHomePos(jobPos);
            worker.setBindPos(jobPos);
            worker.setJobId(job.getId());
            
            // Use kingdom-selected skin for combat jobs; random for others
            String jid = job.getId();
            boolean combat = "guard".equals(jid) || "soldier".equals(jid) || "royal_guard".equals(jid);

            if (combat) {
                worker.setSkinId(name.kingdoms.entity.SoldierSkins.clamp(kingdom.soldierSkinId));
            } else {
                worker.setSkinId(level.random.nextInt(SKIN_COUNT));
            }
            worker.setKingdomUUID(kingdom.id);

            String base = randomName(level, level.random);
            worker.setCustomName(makeWorkerName(base, job.getId(), enabled));
            worker.setCustomNameVisible(true);

            level.addFreshEntity(worker);
            spawnEntityPoof(level, worker.getX(), worker.getY(), worker.getZ());

            UUID id = worker.getUUID();
            this.workerUuid = id;
            this.assignedWorkerUuid = id;
            this.setChanged();

        } else {
            worker.setHomePos(jobPos);
            worker.setBindPos(jobPos);
            worker.setJobId(job.getId());
            worker.setKingdomUUID(kingdom.id);

            // Keep combat jobs synced to kingdom-selected skin
            String jid = job.getId();
            boolean combat = "guard".equals(jid) || "soldier".equals(jid) || "royal_guard".equals(jid);

            if (combat) {
                int desired = name.kingdoms.entity.SoldierSkins.clamp(kingdom.soldierSkinId);
                if (worker.getSkinId() != desired) {
                    worker.setSkinId(desired);
                }
            }


            if (worker.hasCustomName()) {
                String existing = worker.getCustomName().getString();
                if (existing.contains(" (")) {
                    String base = existing;
                    int idx = existing.indexOf(" (");
                    if (idx >= 0) base = existing.substring(0, idx);
                    worker.setCustomName(makeWorkerName(base, job.getId(), enabled));
                    worker.setCustomNameVisible(true);
                }
            }
        }
    }

    private static Component makeWorkerName(String baseName, String jobId, boolean enabled) {
        Component prettyJob = Component.translatable("job.kingdoms." + jobId);
        if (enabled) {
            return Component.literal(baseName + " (").append(prettyJob).append(Component.literal(")"));
        }
        return Component.literal(baseName + " (")
                .append(prettyJob)
                .append(Component.literal(", manually disabled)"));
    }

    private void despawnWorker(ServerLevel level) {
        kingdomWorkerEntity worker = getWorkerIfAlive(level);
        if (worker != null) {
            spawnEntityPoof(level, worker.getX(), worker.getY(), worker.getZ());
            worker.discard();
        }
        this.workerUuid = null;
        this.assignedWorkerUuid = null;
        this.setChanged();

    }

    // Used for missing-reqs case where we already poofed (avoid double-poof)
    private void despawnWorkerSilent(ServerLevel level) {
        kingdomWorkerEntity worker = getWorkerIfAlive(level);
        if (worker != null) {
            worker.discard();
        }
        this.workerUuid = null;
        this.assignedWorkerUuid = null;
        this.setChanged();

    }

    @Nullable
    private kingdomWorkerEntity getWorkerIfAlive(ServerLevel level) {
        // 1) Prefer assignedWorkerUuid (the "slot owner")
        if (this.assignedWorkerUuid != null) {
            Entity e = level.getEntity(this.assignedWorkerUuid);
            if (e instanceof kingdomWorkerEntity w && w.isAlive()) {
                // keep workerUuid in sync for older code paths
                if (this.workerUuid == null || !this.workerUuid.equals(this.assignedWorkerUuid)) {
                    this.workerUuid = this.assignedWorkerUuid;
                    this.setChanged();
                }
                return w;
            }
        }

        // 2) Fallback to workerUuid if assigned is missing
        if (this.workerUuid != null) {
            Entity e = level.getEntity(this.workerUuid);
            if (e instanceof kingdomWorkerEntity w && w.isAlive()) {
                // if assigned was null, claim it now
                if (this.assignedWorkerUuid == null) {
                    this.assignedWorkerUuid = this.workerUuid;
                    this.setChanged();
                }
                return w;
            }
        }

        return null;
    }


    private static String randomName(ServerLevel level, RandomSource r) {
        List<String> pool = getOrLoadNames(level);
        if (pool == null || pool.isEmpty()) {
            return FALLBACK_NAMES[r.nextInt(FALLBACK_NAMES.length)];
        }
        return pool.get(r.nextInt(pool.size()));
    }

    private static List<String> getOrLoadNames(ServerLevel level) {
        List<String> cached = CACHED_NAMES;
        if (cached != null) return cached;

        synchronized (jobBlockEntity.class) {
            if (CACHED_NAMES != null) return CACHED_NAMES;

            List<String> loaded = loadNamesFromJson(level);
            CACHED_NAMES = (loaded == null) ? List.of() : loaded;
            return CACHED_NAMES;
        }
    }

    private static List<String> loadNamesFromJson(ServerLevel level) {
        try {
            var rm = level.getServer().getResourceManager();
            var resOpt = rm.getResource(NAMES_JSON);
            if (resOpt.isEmpty()) return null;

            try (var in = resOpt.get().open();
                 var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

                List<String> out = new ArrayList<>(512);
                addAllStrings(root, "male", out);
                addAllStrings(root, "female", out);

                return out;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void addAllStrings(JsonObject root, String key, List<String> out) {
        if (!root.has(key)) return;
        JsonElement el = root.get(key);
        if (!(el instanceof JsonArray arr)) return;

        for (JsonElement it : arr) {
            if (it != null && it.isJsonPrimitive() && it.getAsJsonPrimitive().isString()) {
                String s = it.getAsString();
                if (!s.isBlank()) out.add(s);
            }
        }
    }

    /* -----------------------------
       SAVE / LOAD
     ----------------------------- */

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);

        if (ownerKingdomId != null) out.putString("OwnerKingdomId", ownerKingdomId.toString());
        out.putInt("CountedPlaced", countedPlaced ? 1 : 0);
        out.putInt("CountedActive", countedActive ? 1 : 0);
        out.putInt("TickCounter", tickCounter);

        out.putInt("WasMissingReqs", wasMissingReqs ? 1 : 0);
        out.putLong("NextMissingReqPoof", nextMissingReqPoofTick);

        out.putLong("LastActivateParticles", lastActivateParticlesTick);

        if (job != null) out.putString("JobId", job.getId());
        if (workerUuid != null) out.putString("WorkerUuid", workerUuid.toString());
        if (assignedWorkerUuid != null) out.putString("AssignedWorkerUuid", assignedWorkerUuid.toString());
        if (countedKingdomId != null) out.putString("CountedKingdomId", countedKingdomId.toString());

        out.putLong("LastNoResMsg", lastNoResourceMsgTick);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);

        countedPlaced = in.getInt("CountedPlaced").orElse(0) != 0;
        countedActive = in.getInt("CountedActive").orElse(0) != 0;
        tickCounter = in.getInt("TickCounter").orElse(0);
        ownerKingdomId = in.getString("OwnerKingdomId").map(UUID::fromString).orElse(null);

        wasMissingReqs = in.getInt("WasMissingReqs").orElse(0) != 0;
        nextMissingReqPoofTick = in.getLong("NextMissingReqPoof").orElse(0L);

        lastActivateParticlesTick = in.getLong("LastActivateParticles").orElse(-999999L);

        job = in.getString("JobId").map(jobDefinition::byId).orElse(null);
        workerUuid = in.getString("WorkerUuid").map(UUID::fromString).orElse(null);
        assignedWorkerUuid = in.getString("AssignedWorkerUuid").map(UUID::fromString).orElse(null);
        countedKingdomId = in.getString("CountedKingdomId").map(UUID::fromString).orElse(null);

        lastNoResourceMsgTick = in.getLong("LastNoResMsg").orElse(0L);
    }

    @Override
    public void setRemoved() {
        if (this.level instanceof ServerLevel sl) {
            despawnWorker(sl);

            if (job != null && ENVOY_ID.equals(job.getId()) && ownerKingdomId != null) {
                kingdomState.get(sl.getServer()).removeEnvoyAnchor(ownerKingdomId, sl.dimension(), worldPosition);
            }



            if (job != null) {
                kingdomState ks = kingdomState.get(sl.getServer());
                kingdomState.Kingdom k = ks.getKingdomAt(sl, worldPosition);

                // Prefer last known kingdom id if present (more correct if border moved)
                if (k == null && countedKingdomId != null) {
                    k = ks.getKingdom(countedKingdomId);
                }

                if (k != null) {
                    if (countedPlaced) kingdomState.bumpPlacedCount(k, job, -1);
                    if (countedActive) kingdomState.bumpActiveCount(k, job, -1);
                    ks.markDirty();
                }
            }
        }

        super.setRemoved();
    }

    private void pruneDuplicateWorkers(ServerLevel level, BlockPos jobPos) {
        // Only prune if we have an owner; otherwise we'd risk deleting the only valid one
        if (assignedWorkerUuid == null) return;
        if (!level.isLoaded(jobPos)) return;

        var box = new net.minecraft.world.phys.AABB(jobPos).inflate(16);

        List<kingdomWorkerEntity> workers = level.getEntitiesOfClass(
                kingdomWorkerEntity.class,
                box,
                w -> w != null
                        && w.isAlive()
                        && !w.isRetinue()
                        && jobPos.equals(w.getHomePos()) // homePos is jobPos in your code
                        && job != null
                        && job.getId().equals(w.getJobId())
        );

        for (kingdomWorkerEntity w : workers) {
            if (!w.getUUID().equals(assignedWorkerUuid)) {
                w.discard();
            }
        }
    }

    public void setOwnerKingdomId(@Nullable UUID id) {
        this.ownerKingdomId = id;
        this.setChanged();
    }

    @Nullable
    public UUID getOwnerKingdomId() { return ownerKingdomId; }


}
