package name.kingdoms.ambient;

import name.kingdoms.Kingdoms;
import name.kingdoms.entity.ai.aiKingdomNPCEntity;
import name.kingdoms.kingdomState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntSupplier;

public final class ScriptedAmbientEvent implements AmbientEvent {

    private static final float MOUNT_CHANCE = 0.18f; // tune
    private static final float WALK_BY_CHANCE = 0.85f; // tune

    // “train” formation (optional)
    private static final int GROUP_SPACING_MIN = 2;   // blocks behind leader
    private static final int GROUP_SPACING_MAX = 4;   // blocks behind leader
    private static final int GROUP_LATERAL_JITTER = 1; // +/- blocks sideways

    public interface Predicate { boolean test(AmbientContext ctx); }
    public interface KingdomSelector { UUID pick(AmbientContext ctx); } // can return null

    public record SpawnPlan(
            String role,
            IntSupplier count,
            int minR,
            int maxR,
            boolean mustBeInKingdom,
            boolean mustBeWilderness,
            boolean mustBeNearBorder,
            boolean mustBeNearWarEdge,
            boolean mustBeInSameKingdomAsPlayer
    ) {}

    public enum SpawnAnchor {
        PLAYER_RING,
        NEAREST_WAR_EDGE
    }

    /** Variant = alternate cast/loadout for same event, chosen by context. */
    public record SpawnVariant(Predicate gate, List<SpawnPlan> spawns, String variantId) {}

    public record Def(
            String id,
            int baseWeight,
            Predicate gate,
            String dialogueRole,
            String dialogueTag,
            List<SpawnVariant> variants,
            List<AmbientEffect> effects,
            KingdomSelector kingdomSelector,
            SpawnAnchor anchor,
            PropSpec propSpec
    ) {
        // keep old call sites working (no prop)
        public Def(String id, int baseWeight, Predicate gate, String dialogueRole, String dialogueTag,
                List<SpawnVariant> variants, List<AmbientEffect> effects,
                KingdomSelector kingdomSelector, SpawnAnchor anchor) {
            this(id, baseWeight, gate, dialogueRole, dialogueTag, variants, effects, kingdomSelector, anchor, null);
        }

        // keep your older “extra args” AmbientEvents entries working
        public Def(String id, int baseWeight, Predicate gate, String dialogueRole, String dialogueTag,
                List<SpawnVariant> variants, List<AmbientEffect> effects,
                KingdomSelector kingdomSelector, SpawnAnchor anchor,
                String propId, int ttlTicks, int loiterRadius, boolean required) {
            this(id, baseWeight, gate, dialogueRole, dialogueTag, variants, effects, kingdomSelector, anchor,
                    new PropSpec(propId, ttlTicks, loiterRadius, required));
        }

    }

    private final Def def;

    public ScriptedAmbientEvent(Def def) { this.def = def; }

    @Override public String id() { return def.id(); }

    @Override
    public int weight(AmbientContext ctx) {
        if (def.baseWeight() <= 0) return 0;
        if (def.gate() != null && !def.gate().test(ctx)) return 0;
        return def.baseWeight();
    }

    @Override
    public void run(AmbientContext ctx) {
        SpawnVariant chosen = chooseVariant(ctx);
        if (chosen == null) return;
        runInternal(ctx, chosen, false);
        
    }

    /**
     * Force a specific variant id.
     * ignoreGate: bypass event + variant gates (still respects prop placement failure etc.)
     * relaxPlanFilters: bypass SpawnPlan filters (mustBeInKingdom/mustBeWilderness/etc.)
     */
    public boolean runForced(AmbientContext ctx, String variantId, boolean ignoreGate, boolean relaxPlanFilters) {
        if (variantId == null || variantId.isBlank()) return false;

        if (!ignoreGate && def.gate() != null && !def.gate().test(ctx)) return false;

        SpawnVariant chosen = null;
        if (def.variants() != null) {
            for (var v : def.variants()) {
                if (v == null || v.variantId() == null) continue;
                if (!v.variantId().equalsIgnoreCase(variantId)) continue;

                if (!ignoreGate && v.gate() != null && !v.gate().test(ctx)) return false;
                chosen = v;
                break;
            }
        }
        if (chosen == null) return false;

        runInternal(ctx, chosen, relaxPlanFilters);
        return true;
    }

    // Back-compat overload used by older commands
    public boolean runForced(AmbientContext ctx, String variantId, boolean ignoreGate) {
        return runForced(ctx, variantId, ignoreGate, false);
    }

    public List<String> variantIds() {
        if (def.variants() == null) return List.of();
        ArrayList<String> out = new ArrayList<>();
        for (var v : def.variants()) {
            if (v != null && v.variantId() != null && !v.variantId().isBlank()) out.add(v.variantId());
        }
        return out;
    }

    @Override
    public List<AmbientEffect> effects(AmbientContext ctx) {
        return def.effects() == null ? List.of() : def.effects();
    }

    // -----------------------
    // Internal
    // -----------------------

    private SpawnVariant chooseVariant(AmbientContext ctx) {
        if (def.variants() == null) return null;
        for (var v : def.variants()) {
            if (v != null && (v.gate() == null || v.gate().test(ctx))) return v;
        }
        return null;
    }

    private void runInternal(AmbientContext ctx, SpawnVariant chosen, boolean relaxPlanFilters) {
        var level = ctx.level();
        var sp = ctx.player();
        var ks = kingdomState.get(ctx.server());

        List<SpawnPlan> spawns = chosen.spawns();
        if (spawns == null || spawns.isEmpty()) return;

        // detect peasant (used by mountedScene rule)
        boolean hasPeasant = false;
        for (SpawnPlan spn : spawns) {
            if (spn != null && "peasant".equals(spn.role())) { hasPeasant = true; break; }
        }

        boolean mountedScene = !hasPeasant && level.random.nextFloat() < MOUNT_CHANCE;
        boolean walkByScene  = level.random.nextFloat() < WALK_BY_CHANCE;

        // ---- choose anchor base ----
        BlockPos base = pickBase(ctx);
        if (base == null) return;

        // ---- optional prop placement (camp/shrine/cart/tents) ----
       AmbientProps.PropPlacement placement = null;
        if (def.propSpec() != null && def.propSpec().propId() != null && !def.propSpec().propId().isBlank()) {
            placement = AmbientProps.place(def.propSpec().propId(), level, base, def.propSpec().ttlTicks());
            if (placement == null && def.propSpec().required()) return;

        }

        BlockPos npcAnchor = (placement != null) ? placement.anchor() : base;

        // one group direction for the whole scene
        Direction groupDir = Direction.Plane.HORIZONTAL.getRandomDirection(level.random);
        int groupSpacing = GROUP_SPACING_MIN + level.random.nextInt(GROUP_SPACING_MAX - GROUP_SPACING_MIN + 1);

        UUID bindKid = null;
        if (def.kingdomSelector() != null) {
            try { bindKid = def.kingdomSelector().pick(ctx); }
            catch (Throwable ignored) { bindKid = null; }
        }

        UUID playerKid = (ctx.playerKingdom() == null) ? null : ctx.playerKingdom().id;

        List<aiKingdomNPCEntity> spawned = new ArrayList<>();

        for (SpawnPlan plan : spawns) {
            if (plan == null) continue;

            int n = Math.max(0, plan.count().getAsInt());
            for (int i = 0; i < n; i++) {
                // compact “train” around anchor (works well for caravans / groups approaching player)
                int slot = spawned.size();

                BlockPos desired = npcAnchor
                        .relative(groupDir, -slot * groupSpacing)
                        .offset(
                                level.random.nextInt(GROUP_LATERAL_JITTER * 2 + 1) - GROUP_LATERAL_JITTER,
                                0,
                                level.random.nextInt(GROUP_LATERAL_JITTER * 2 + 1) - GROUP_LATERAL_JITTER
                        );

                BlockPos p;

                if (placement != null) {
                    // We already graded + cleared air. Spawn on the flattened “floor”.
                    int flatY = npcAnchor.getY() - 1; // npcAnchor is centerGround.above(1)
                     BlockPos desiredOnPlane = new BlockPos(desired.getX(), flatY + 1, desired.getZ());

                        p = SpawnUtil.findNearbyValidGround(level, desiredOnPlane, 6, 8);
                } else {
                    int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, desired.getX(), desired.getZ());
                    desired = new BlockPos(desired.getX(), gy, desired.getZ());

                    p = SpawnUtil.findNearbyValidGround(level, desired, 8, 12);
                    if (p == null) continue;
                }


                var at = ks.getKingdomAt(level, p);
                boolean inKingdom = at != null;
                boolean inWilderness = at == null;

                if (!relaxPlanFilters) {
                    if (plan.mustBeInKingdom() && !inKingdom) continue;
                    if (plan.mustBeWilderness() && !inWilderness) continue;

                    if (plan.mustBeInSameKingdomAsPlayer()) {
                        if (playerKid == null || at == null || !playerKid.equals(at.id)) continue;
                    }

                    if (plan.mustBeNearWarEdge() && !ctx.nearWarZone()) continue;

                    if (plan.mustBeNearBorder()) {
                        if (at == null || !at.hasBorder) continue;
                        if (!nearBorder(at, p, 28)) continue;
                    }
                }

                aiKingdomNPCEntity npc = Kingdoms.AI_KINGDOM_NPC_ENTITY_TYPE.create(level, EntitySpawnReason.EVENT);
                if (npc == null) continue;

                npc.teleportTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
                npc.setYRot(level.random.nextFloat() * 360f);
                npc.initFromSpawner(plan.role(), -1);

                UUID kidToBind = (bindKid != null) ? bindKid : (at != null ? at.id : null);
                if (kidToBind != null) {
                    npc.setKingdomUUID(kidToBind);
                    npc.applyKingdomMilitarySkin();
                }

                if (placement != null && def.propSpec() != null) {
                    int r = Math.max(2, def.propSpec().loiterRadius());
                    int ttl = Math.max(20, def.propSpec().ttlTicks());
                    npc.setAmbientLoiter(npcAnchor, r, ttl);
                }

                npc.setAmbientTtl(20 * 60 * 3);

                boolean thisNpcMounted = mountedScene && !"peasant".equals(plan.role());

                if (thisNpcMounted) {
                    var horse = SpawnUtil.spawnAmbientHorse(level, p);
                    if (horse != null) {
                        level.addFreshEntity(horse);
                        level.addFreshEntity(npc);
                        npc.startRiding(horse, true, true);
                    } else {
                        level.addFreshEntity(npc);
                    }
                } else {
                    level.addFreshEntity(npc);
                }

                spawned.add(npc);

                // follower chain (train)
                if (spawned.size() > 1) {
                    aiKingdomNPCEntity leader = spawned.get(0);
                    if (npc != leader) npc.setAmbientLeader(leader.getUUID(), groupSpacing);
                }

                if (walkByScene) SpawnUtil.walkPastPlayer(npc, sp);
                else if (level.random.nextFloat() < 0.25f) SpawnUtil.walkTowardPlayer(npc, sp);
            }
        }

        if (spawned.isEmpty()) return;

        // ---- pick a speaker ----
        aiKingdomNPCEntity speaker = spawned.get(level.random.nextInt(spawned.size()));
        if (walkByScene) SpawnUtil.walkPastPlayer(speaker, sp);
        else SpawnUtil.walkTowardPlayer(speaker, sp);

        String role = speaker.getAiTypeId();
        if (role == null || role.isBlank()) role = def.dialogueRole();

        String line = AmbientDialogue.pickLine(ctx, role, def.dialogueTag());

        String title = switch (role) {
            case "guard" -> "Guard";
            case "soldier" -> "Soldier";
            case "scout" -> "Scout";
            case "noble" -> "Noble";
            case "peasant" -> "Peasant";
            case "trader" -> "Trader";
            case "envoy" -> "Envoy";
            case "refugee" -> "Refugee";
            case "scholar" -> "Scholar";
            default -> "Villager";
        };

        // Delay speech so client tracks entity
        AmbientManager.queueSpeech(ctx.server(), speaker.getUUID(), sp.getUUID(), title, line, 20 * 60, 15);
    }

    private BlockPos pickBase(AmbientContext ctx) {
        var level = ctx.level();

        BlockPos base;
        if (def.anchor() == SpawnAnchor.NEAREST_WAR_EDGE) {
            var near = WarZoneUtil.findNearestWarEdge(ctx.server(), ctx.pos(), 96);
            if (near == null) return null;
            base = near.edgePos();
        } else {
            base = SpawnUtil.findRingSpawn(level, ctx.pos(), 14, 38, 16);
            if (base == null) return null;
        }

        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base.getX(), base.getZ());
        return new BlockPos(base.getX(), y, base.getZ());
    }

    private static boolean nearBorder(kingdomState.Kingdom k, BlockPos pos, int dist) {
        if (k == null || !k.hasBorder) return false;
        int x = pos.getX(), z = pos.getZ();
        if (x < k.borderMinX || x > k.borderMaxX || z < k.borderMinZ || z > k.borderMaxZ) return false;
        int dx = Math.min(x - k.borderMinX, k.borderMaxX - x);
        int dz = Math.min(z - k.borderMinZ, k.borderMaxZ - z);
        return Math.min(dx, dz) <= dist;
    }

    public record PropSpec(String propId, int ttlTicks, int loiterRadius, boolean required) {
        public PropSpec(String propId, int ttlTicks, int loiterRadius) {
            this(propId, ttlTicks, loiterRadius, false);
        }
    }

}
