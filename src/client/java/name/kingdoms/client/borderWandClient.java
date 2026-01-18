package name.kingdoms.client;

import name.kingdoms.kingdomsClient;
import name.kingdoms.modItem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

public final class borderWandClient {
    private borderWandClient() {}

    private static BlockPos a = null;
    private static BlockPos b = null;

    public static void clearSelection() {
        a = null;
        b = null;
    }
    
    private static void spawnNoBorderHint(Minecraft mc, ParticleOptions particle) {
        if (mc.player == null || mc.level == null) return;

        BlockPos p = mc.player.blockPosition();
        int baseY = p.getY() + 1;

        // light ring around feet (cheap + readable)
        for (int i = 0; i < 12; i++) {
            double ang = mc.level.random.nextDouble() * Math.PI * 2.0;
            double r = 1.2 + mc.level.random.nextDouble() * 0.8;

            double x = p.getX() + 0.5 + Math.cos(ang) * r;
            double z = p.getZ() + 0.5 + Math.sin(ang) * r;
            double y = baseY + mc.level.random.nextDouble() * 1.2;

            mc.level.addParticle(particle, x, y, z, 0.0, 0.01, 0.0);
        }
    }



    // throttle particle spam
    private static int tick = 0;

    public static void init() {
        // Left click: set A
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (!level.isClientSide()) return InteractionResult.PASS;

            ItemStack held = player.getItemInHand(hand);
            if (!held.is(modItem.BORDER_WAND)) return InteractionResult.PASS;

            a = pos.immutable();
            return InteractionResult.PASS;
        });

        // Right click: set B; shift-right-click clears selection (client visuals only)
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.isClientSide()) return InteractionResult.PASS;

            ItemStack held = player.getItemInHand(hand);
            if (!held.is(modItem.BORDER_WAND)) return InteractionResult.PASS;

            if (player.isShiftKeyDown()) {
                a = null;
                b = null;
                return InteractionResult.PASS;
            }

            b = hit.getBlockPos().immutable();
            return InteractionResult.PASS;
        });

       ClientTickEvents.END_CLIENT_TICK.register(client -> {
    if (client.player == null || client.level == null) return;
    if (!client.player.getMainHandItem().is(modItem.BORDER_WAND)) return;

    // Local selection state (instant; doesn't depend on server sync)
    boolean selecting = (a != null || b != null);

    // "Do I have a kingdom?" can be briefly false before sync arrives
    boolean haveKingdom = kingdomsClient.hasKingdomClient();
    boolean hasBorder   = kingdomsClient.hasBorderClient();

    // If client thinks you DON'T have a kingdom:
    // - never show the "no border hint" ring (the thing that follows you)
    // - BUT still show selection preview if you're selecting (A/B set)
    if (!haveKingdom && !selecting) {
        return;
    }

    // If you're not selecting, only show hint smoke when your kingdom has NO border
    // (but only when haveKingdom is true, because we returned above otherwise)
    if (!selecting && hasBorder) return;

    tick++;
    if ((tick % 2) != 0) return;

    ParticleOptions particle = ParticleTypes.LARGE_SMOKE;

    if (!selecting) {
        // This is the ring that follows the player.
        // It will now only appear when haveKingdom == true.
        spawnNoBorderHint(client, particle);
        return;
    }

    // selection visuals
    if (a != null && b == null) {
        spawnEdgeBox(client, a, a, particle, 20);
    } else if (a != null && b != null) {
        spawnEdgeBox(client, a, b, particle, 60);
    }
});



    }

    /**
     * Spawns particles along the outline of the rectangle defined by A/B in XZ,
     * at a few Y bands so it reads as a vertical “box”.
     */
    private static void spawnEdgeBox(Minecraft mc, BlockPos a, BlockPos b, ParticleOptions particle, int count) {
        int minX = Math.min(a.getX(), b.getX());
        int maxX = Math.max(a.getX(), b.getX()) + 1;
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxZ = Math.max(a.getZ(), b.getZ()) + 1;

        int baseY = mc.player.blockPosition().getY();
        int[] ys = new int[]{ baseY + 1, baseY + 6, baseY + 12 };

        for (int i = 0; i < count; i++) {
            boolean alongX = mc.level.random.nextBoolean();

            double x, z;
            if (alongX) {
                x = Mth.lerp(mc.level.random.nextDouble(), minX, maxX);
                z = (mc.level.random.nextBoolean() ? minZ : maxZ);
            } else {
                z = Mth.lerp(mc.level.random.nextDouble(), minZ, maxZ);
                x = (mc.level.random.nextBoolean() ? minX : maxX);
            }

            int y = ys[mc.level.random.nextInt(ys.length)];
            mc.level.addParticle(
                    particle,
                    x + 0.5, y + 0.1, z + 0.5,
                    0.0, 0.02, 0.0
            );
        }
    }

    public static BlockPos getA() { return a; }
    public static BlockPos getB() { return b; }
}
