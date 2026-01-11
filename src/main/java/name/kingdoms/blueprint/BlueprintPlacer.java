package name.kingdoms.blueprint;

import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Places blueprints over multiple ticks to avoid freezing the server.
 *
 * Section file format:
 *   byte dx, byte dy, byte dz
 *   then dx*dy*dz little-endian uint16 palette indices (x-fastest, then z, then y)
 */
public final class BlueprintPlacer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Deque<PlaceTask> TASKS = new ArrayDeque<>();

    // Start conservative. Raise later once stable.
    public static int BLOCKS_PER_TICK = 200;

    private BlueprintPlacer() {}

    public static boolean hasPendingTasks() {
        return !TASKS.isEmpty();
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(BlueprintPlacer::tick);
    }

    public static void enqueue(ServerLevel level, Blueprint bp, BlockPos origin, String modId, boolean includeAir) {
        TASKS.add(new PlaceTask(level, bp, origin, modId, includeAir));
    }

    private static void tick(MinecraftServer server) {
        if (TASKS.isEmpty()) return;

        PlaceTask task = TASKS.peek();
        if (task == null) return;

        try {
            boolean done = task.step(server, BLOCKS_PER_TICK);
            if (done) TASKS.poll();
        } catch (Exception e) {
            LOGGER.error("Blueprint placement failed", e);
            TASKS.poll();
        }
    }

    private static final class PlaceTask {
        private final ServerLevel level;
        private final Blueprint bp;
        private final BlockPos origin;
        private final String modId;
        private final boolean includeAir;

        private final Map<Integer, BlockState> paletteCache = new HashMap<>();

        // Reuse ONE mutable pos to avoid GC spikes
        private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();

        // section coords
        private int secX = 0, secY = 0, secZ = 0;

        // current section stream + dims
        private DataInputStream secIn = null;
        private int dx, dy, dz;

        // cell cursor within section (x-fastest, then z, then y)
        private int cx = 0, cy = 0, cz = 0;

        private PlaceTask(ServerLevel level, Blueprint bp, BlockPos origin, String modId, boolean includeAir) {
            this.level = level;
            this.bp = bp;
            this.origin = origin;
            this.modId = modId;
            this.includeAir = includeAir;
        }

        private boolean step(MinecraftServer server, int budget) throws IOException {
            int placed = 0;

            while (placed < budget) {
                if (secIn == null) {
                    if (secY >= bp.sectionsY) return true; // all done
                    openCurrentSectionOrSkip(server);
                    continue;
                }

                // finished section?
                if (cy >= dy) {
                    closeSection();
                    advanceSectionCoords();
                    continue;
                }

                int paletteIndex = readU16LE(secIn);

                int worldX = origin.getX() + secX * bp.sectionSize + cx;
                int worldY = origin.getY() + secY * bp.sectionSize + cy;
                int worldZ = origin.getZ() + secZ * bp.sectionSize + cz;

                // advance cursor
                cx++;
                if (cx >= dx) {
                    cx = 0;
                    cz++;
                    if (cz >= dz) {
                        cz = 0;
                        cy++;
                    }
                }

                if (!includeAir && bp.airId != -1 && paletteIndex == bp.airId) continue;

                BlockState state = paletteState(paletteIndex);
                if (state == null) continue;
                if (!includeAir && state.isAir()) continue;

                // IMPORTANT: do NOT force-generate chunks while placing.
                // If the chunk isn't loaded yet, stop for this tick and resume later.
                int cX = worldX >> 4;
                int cZ = worldZ >> 4;
                if (!level.getChunkSource().hasChunk(cX, cZ)) {
                    return false;
                }

                scratchPos.set(worldX, worldY, worldZ);

                // flag 2 = send to clients, avoids heavy neighbor updates
                level.setBlock(scratchPos, state, 2);
                placed++;
            }

            return false;
        }

        private void openCurrentSectionOrSkip(MinecraftServer server) throws IOException {
            try (InputStream raw = bp.openSection(server, modId, secX, secY, secZ)) {
                byte[] bytes = raw.readAllBytes();
                this.secIn = new DataInputStream(new ByteArrayInputStream(bytes));
            } catch (IOException missing) {
                // missing section => treat as empty and move on
                this.secIn = null;
                advanceSectionCoords();
                return;
            }

            this.dx = Byte.toUnsignedInt(secIn.readByte());
            this.dy = Byte.toUnsignedInt(secIn.readByte());
            this.dz = Byte.toUnsignedInt(secIn.readByte());

            this.cx = 0;
            this.cy = 0;
            this.cz = 0;
        }

        private void closeSection() {
            if (secIn != null) {
                try { secIn.close(); } catch (IOException ignored) {}
                secIn = null;
            }
        }

        private void advanceSectionCoords() {
            secX++;
            if (secX >= bp.sectionsX) {
                secX = 0;
                secZ++;
                if (secZ >= bp.sectionsZ) {
                    secZ = 0;
                    secY++;
                }
            }
        }

        private BlockState paletteState(int idx) {
            return paletteCache.computeIfAbsent(idx, this::parseBlockState);
        }

        private BlockState parseBlockState(int idx) {
            if (idx < 0 || idx >= bp.palette.size()) return null;

            String s = bp.palette.get(idx);

            // 1) Try full state string
            try {
                return net.minecraft.commands.arguments.blocks.BlockStateParser
                        .parseForBlock(BuiltInRegistries.BLOCK, s, true)
                        .blockState();
            } catch (Exception ignored) {}

            // 2) Strip properties and try again
            try {
                String idStr = s;
                int props = idStr.indexOf('[');
                if (props >= 0) idStr = idStr.substring(0, props);

                return net.minecraft.commands.arguments.blocks.BlockStateParser
                        .parseForBlock(BuiltInRegistries.BLOCK, idStr, true)
                        .blockState();
            } catch (Exception ignored) {}

            return Blocks.AIR.defaultBlockState();
        }

        private static int readU16LE(DataInputStream in) throws IOException {
            int lo = in.readUnsignedByte();
            int hi = in.readUnsignedByte();
            return (hi << 8) | lo;
        }
    }
}
