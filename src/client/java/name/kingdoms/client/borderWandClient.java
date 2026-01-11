package name.kingdoms.client;

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

        // Spawn particles while wand is held
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) return;
            if (!client.player.getMainHandItem().is(modItem.BORDER_WAND)) return;

            // don't render anything until at least A or B is set
            if (a == null && b == null) return;

            tick++;
            if ((tick % 2) != 0) return; // every 2 ticks

            ParticleOptions particle = ParticleTypes.LARGE_SMOKE;

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
