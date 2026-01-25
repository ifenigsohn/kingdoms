package name.kingdoms.ambient;

import name.kingdoms.Kingdoms;
import name.kingdoms.entity.ai.aiKingdomNPCEntity;
import name.kingdoms.kingdomState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntSupplier;

public final class ScriptedAmbientEvent implements AmbientEvent {

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
            SpawnAnchor anchor
    ) {}

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
        var level = ctx.level();
        var sp = ctx.player();
        var ks = kingdomState.get(ctx.server());

        // ---- choose variant ----
        SpawnVariant chosen = null;
        if (def.variants() != null) {
            for (var v : def.variants()) {
                if (v != null && (v.gate() == null || v.gate().test(ctx))) {
                    chosen = v;
                    break;
                }
            }
        }
        if (chosen == null) return; // no suitable cast

        List<SpawnPlan> spawns = chosen.spawns();
        if (spawns == null || spawns.isEmpty()) return;

        // ---- choose anchor base ----
        BlockPos base;
        if (def.anchor() == SpawnAnchor.NEAREST_WAR_EDGE) {
            var near = WarZoneUtil.findNearestWarEdge(ctx.server(), ctx.pos(), 96);
            if (near == null) return;
            base = near.edgePos();
        } else {
            base = SpawnUtil.findRingSpawn(level, ctx.pos(), 14, 38, 16);
            if (base == null) return;
        }

        // snap to ground
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base.getX(), base.getZ());
        base = new BlockPos(base.getX(), y, base.getZ());

        // optional selector override (for envoys, foreign patrols, etc)
        UUID bindKid = null;
        if (def.kingdomSelector() != null) {
            try { bindKid = def.kingdomSelector().pick(ctx); }
            catch (Throwable ignored) { bindKid = null; }
        }

        UUID playerKid = (ctx.playerKingdom() == null) ? null : ctx.playerKingdom().id;

        List<aiKingdomNPCEntity> spawned = new ArrayList<>();

        // ---- spawn plans (from chosen variant!) ----
        for (SpawnPlan plan : spawns) {
            int n = Math.max(0, plan.count().getAsInt());
            for (int i = 0; i < n; i++) {
                BlockPos p = SpawnUtil.findRingSpawn(level, base, plan.minR(), plan.maxR(), 10);
                if (p == null) continue;

                var at = ks.getKingdomAt(level, p);
                boolean inKingdom = at != null;
                boolean inWilderness = at == null;

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

                npc.setAmbientTtl(20 * 60 * 3);
                level.addFreshEntity(npc);
                spawned.add(npc);

                if (level.random.nextFloat() < 0.25f) {
                    SpawnUtil.walkTowardPlayer(npc, sp);
                }
            }
        }

        if (spawned.isEmpty()) return;

        // ---- pick a speaker ----
        aiKingdomNPCEntity speaker = spawned.get(level.random.nextInt(spawned.size()));
        SpawnUtil.walkTowardPlayer(speaker, sp);

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

    @Override
    public List<AmbientEffect> effects(AmbientContext ctx) {
        return def.effects() == null ? List.of() : def.effects();
    }

    private static boolean nearBorder(kingdomState.Kingdom k, BlockPos pos, int dist) {
        if (k == null || !k.hasBorder) return false;
        int x = pos.getX(), z = pos.getZ();
        if (x < k.borderMinX || x > k.borderMaxX || z < k.borderMinZ || z > k.borderMaxZ) return false;
        int dx = Math.min(x - k.borderMinX, k.borderMaxX - x);
        int dz = Math.min(z - k.borderMinZ, k.borderMaxZ - z);
        return Math.min(dx, dz) <= dist;
    }
}
