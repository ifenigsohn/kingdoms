package name.kingdoms.client;

import name.kingdoms.clientWarZoneCache;
import name.kingdoms.kingdomsClient;
import name.kingdoms.payload.bordersSyncPayload;
import name.kingdoms.payload.warZonesRequestPayload;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;


public class kingdomBordersMapScreen extends Screen {

    private java.util.UUID hoveredKingdomId = null;
    private long nextHoverReqMs = 0L;

    // UI zoom: blocks per screen pixel (bigger = more zoomed out)
    private double scale = 4.0;

    // -------------------------
    // Persistent explored cache (CPU-side)
    // -------------------------
    private static final int CACHE_W = 4096;
    private static final int CACHE_H = 4096;

    // Diplomatic range overlay (client-side)
    private boolean showDiploRange = false;

    // if you already have a constant server-side, mirror it here
    private static final int DIPLO_RANGE_BLOCKS = 2500; // <-- set this to your actual range

    // Cache pixels represent this many blocks (fixed, independent of UI zoom)
    private static final int CACHE_SCALE_BLOCKS = 4;

    private static NativeImage terrainCache;
    private static boolean cacheInit = false;
    private static int cacheCenterX;
    private static int cacheCenterZ;

    // Fog color (we skip drawing these)
    private static final int FOG = 0xFF101010;

    // Reusable mutable pos to reduce allocations
    private final BlockPos.MutableBlockPos tmpPos = new BlockPos.MutableBlockPos();

    // -------------------------
    // PERF knobs
    // -------------------------
    private int frameCounter = 0;

    // Update terrain only every N frames
    private int updateEveryNFrames = 2;

    // How many samples per update call (total)
    private int samplesPerUpdate = 2500;

    // Update radius around player in world blocks
    private int updateRadiusBlocks = 400;

    // Draw coarseness on screen
    private int drawStepPx = 5;

    private static final int FOG_DRAW = 0xFF0B0F14;

    // -------------------------
    // Closest-to-farthest scan state (ring walker)
    // -------------------------
    private static int scanR = 0;       // current ring radius
    private static int scanEdge = 0;    // edge 0..3
    private static int scanT = 0;       // step along edge

    // -------------------------
    // Per-world persistence
    // -------------------------
    private static String worldKey = null;
    private static boolean cacheDirty = false;
    private static int dirtyWrites = 0;

    private DynamicTexture mapTex;
    private ResourceLocation mapTexId;
    private NativeImage mapImg;

    private int mapImgW = 0;
    private int mapImgH = 0;

    // how often to rebuild the panel image (separate from terrain sampling)
    private int redrawEveryNFrames = 2;
    private boolean forceRedraw = true;

    public kingdomBordersMapScreen() {
        super(Component.literal("Kingdom Borders"));
    }

    public void onBordersUpdated() {
        // no-op
    }

    private void ensureMapTexture(int panelW, int panelH, int stepPx) {
        int w = Math.max(1, panelW / stepPx);
        int h = Math.max(1, panelH / stepPx);

        if (mapTex != null && w == mapImgW && h == mapImgH) return;

        var tm = Minecraft.getInstance().getTextureManager();

        // cleanup old
        if (mapTex != null) {
            mapTex.close();   // closes pixels too
            mapTex = null;
        }
        mapImg = null; // DO NOT close separately


        mapImgW = w;
        mapImgH = h;

        mapImg = new NativeImage(mapImgW, mapImgH, false);

        // ✅ your version supports this:
        mapTex = new DynamicTexture(() -> "kingdoms_map_panel", mapImg);

        // register (ResourceLocation is correct for your TextureManager)
        mapTexId = ResourceLocation.fromNamespaceAndPath("kingdoms", "map_panel");
        tm.register(mapTexId, mapTex);

        forceRedraw = true;
    }





    @Override
    protected void init() {
        super.init();

        ClientPlayNetworking.send(new name.kingdoms.payload.bordersRequestPayload());
        ClientPlayNetworking.send(new warZonesRequestPayload());
    }

    

    private boolean resolveSurfaceBlock(ClientLevel level, int wx, int wz, int surfaceY) {
        int y = surfaceY - 1;
        int minY = level.getMinY();

        // Start at surface block
        tmpPos.set(wx, y, wz);
        BlockState s = level.getBlockState(tmpPos);

        // Walk down a bit if we hit air/none
        for (int step = 0; step < 12; step++) {
            if (y <= minY) return false;

            MapColor mc = s.getMapColor(level, tmpPos);
            boolean bad =
                    s.isAir()
                    || s.is(Blocks.CAVE_AIR)
                    || s.is(Blocks.VOID_AIR)
                    || mc == null
                    || mc == MapColor.NONE
                    || mc.col == 0;

            if (!bad) return true;

            y--;
            tmpPos.set(wx, y, wz);
            s = level.getBlockState(tmpPos);
        }

        return false;
    }


    // ------------------------------------
    // World key + disk IO
    // ------------------------------------
    private static String computeWorldKey(Minecraft mc, ClientLevel level) {
        String dim = level.dimension().location().toString();

        // Multiplayer: separate by server IP (only once it exists)
        if (mc.getCurrentServer() != null && mc.getCurrentServer().ip != null && !mc.getCurrentServer().ip.isBlank()) {
            String ip = mc.getCurrentServer().ip;
            return ("mp_" + ip + "_" + dim).toLowerCase(Locale.ROOT);
        }

        // Singleplayer: separate by save folder (only once integrated server exists)
        try {
            MinecraftServer srv = mc.getSingleplayerServer();
            if (srv != null) {
                Path root = srv.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
                String h = Integer.toHexString(root.toString().hashCode());
                return ("sp_" + h + "_" + dim).toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignored) {}

        // IMPORTANT: don't fallback to a shared key
        return null;
    }


    private static String sanitizeFileName(String s) {
        return s.replaceAll("[^a-z0-9._-]+", "_");
    }

    private static Path cacheDir(Minecraft mc) {
        // <gameDir>/kingdoms/map_cache/
        return mc.gameDirectory.toPath().resolve("kingdoms").resolve("map_cache");
    }

    private static Path cachePngPath(Minecraft mc, String key) {
        return cacheDir(mc).resolve(sanitizeFileName(key) + ".png");
    }

    private static Path cacheMetaPath(Minecraft mc, String key) {
        return cacheDir(mc).resolve(sanitizeFileName(key) + ".meta");
    }

    private static void fillFog() {
        if (terrainCache == null) return;
        for (int y = 0; y < CACHE_H; y++) {
            for (int x = 0; x < CACHE_W; x++) {           
                terrainCache.setPixel(x, y, FOG);
            }
        }
    }

    private static void ensureTerrainCacheSize() {
        if (terrainCache == null) return;

        int w = terrainCache.getWidth();
        int h = terrainCache.getHeight();
        if (w == CACHE_W && h == CACHE_H) return;

        // Old cache file (or old constants). Discard it and start fresh.
        try {
            terrainCache.close();
        } catch (Throwable ignored) {}

        terrainCache = new NativeImage(CACHE_W, CACHE_H, false);
        fillFog();

        // reset scan so reveal starts near player again
        scanR = 0;
        scanEdge = 0;
        scanT = 0;

        cacheDirty = true;
        dirtyWrites = 0;
    }


    private static void loadCacheIfExists(Minecraft mc, String key) {
        try {
            Files.createDirectories(cacheDir(mc));

            Path png = cachePngPath(mc, key);
            Path meta = cacheMetaPath(mc, key);

            if (Files.exists(png)) {
                terrainCache = NativeImage.read(Files.newInputStream(png));
            } else {
                terrainCache = new NativeImage(CACHE_W, CACHE_H, false);
                fillFog();
            }

            ensureTerrainCacheSize();

            if (Files.exists(meta)) {
                String txt = Files.readString(meta);
                String[] parts = txt.trim().split(",");
                if (parts.length >= 2) {
                    cacheCenterX = Integer.parseInt(parts[0].trim());
                    cacheCenterZ = Integer.parseInt(parts[1].trim());
                    cacheInit = true;
                } else {
                    cacheInit = false;
                }
            } else {
                cacheInit = false;
            }

            // Reset scan state so reveal is close-first again
            scanR = 0;
            scanEdge = 0;
            scanT = 0;

            cacheDirty = false;
            dirtyWrites = 0;

        } catch (Throwable t) {
            terrainCache = new NativeImage(CACHE_W, CACHE_H, false);
            fillFog();
            cacheInit = false;

            scanR = 0;
            scanEdge = 0;
            scanT = 0;

            cacheDirty = false;
            dirtyWrites = 0;
        }
    }

    private static void saveCache(Minecraft mc) {
        if (terrainCache == null || worldKey == null) return;
        if (!cacheDirty) return;

        try {
            Files.createDirectories(cacheDir(mc));

            Path png = cachePngPath(mc, worldKey);
            Path meta = cacheMetaPath(mc, worldKey);

            terrainCache.writeToFile(png);
            Files.writeString(meta, cacheCenterX + "," + cacheCenterZ);

            cacheDirty = false;
            dirtyWrites = 0;

        } catch (Throwable ignored) {}
    }

    private void ensureCache(int px, int pz) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        String keyNow = computeWorldKey(mc, level);

        // If the world isn't identifiable yet, do NOT reuse disk cache.
        if (keyNow == null) {
            // Keep a fresh temporary cache for this session
            if (terrainCache == null) {
                terrainCache = new NativeImage(CACHE_W, CACHE_H, false);
                fillFog();
                cacheInit = false;
                cacheDirty = false;
                dirtyWrites = 0;

                // reset reveal scan so it fills near player first
                scanR = 0;
                scanEdge = 0;
                scanT = 0;
            }

            // Still set a center so drawing isn't weird
            if (!cacheInit) {
                cacheCenterX = px;
                cacheCenterZ = pz;
                cacheInit = true;
            }

            ensureTerrainCacheSize();

            return;
        }

        // Normal per-world behavior once key exists
        if (worldKey == null || !worldKey.equals(keyNow)) {
            saveCache(mc);
            worldKey = keyNow;
            loadCacheIfExists(mc, worldKey);
        }

        if (terrainCache == null) {
            terrainCache = new NativeImage(CACHE_W, CACHE_H, false);
            fillFog();
            cacheInit = false;
        }

        if (!cacheInit) {
            cacheCenterX = px;
            cacheCenterZ = pz;
            cacheInit = true;
            cacheDirty = true;
        }
    }


    @Override
    public void removed() {
        saveCache(Minecraft.getInstance());

       if (mapTex != null) {
            mapTex.close();
            mapTex = null;
        }
        mapImg = null;

        super.removed();
    }



    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        double factor = (deltaY > 0) ? 0.85 : 1.15;
        scale = Math.max(0.5, Math.min(64.0, scale * factor));
        forceRedraw = true; 
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xCC000000);

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            g.drawCenteredString(this.font, Component.literal("No player."), this.width / 2, this.height / 2, 0xFFFFFFFF);
            return;
        }

        int px = mc.player.getBlockX();
        int pz = mc.player.getBlockZ();
        ensureCache(px, pz);

        // Panel
        int pad = 14;
        int left = pad, top = pad, right = this.width - pad, bottom = this.height - pad;
        g.fill(left, top, right, bottom, 0xAA111111);

        int cx = this.width / 2;
        int cy = this.height / 2;

        // Adaptive draw step (faster when zoomed out)
        if (scale <= 3) drawStepPx = 4;
        else if (scale <= 6) drawStepPx = 5;
        else if (scale <= 12) drawStepPx = 7;
        else drawStepPx = 9;

        // Update explored cache (closest->farthest, fog-only writes)
        updateExploredTerrainClosestFirst(px, pz);

        // Draw cached terrain
        drawCachedTerrainAsTexture(g, left, top, right, bottom, cx, cy, px, pz);
        drawDiploRangeOverlay(g, left, top, right, bottom, cx, cy, px, pz);

        // -------------------------
        // Borders overlay
        // -------------------------
        bordersSyncPayload borders = kingdomsClient.CLIENT_BORDERS;

        if (borders != null) {
            for (var e : borders.entries()) {

                
                if (!e.hasBorder()) continue;

                int x1 = (int) Math.round(cx + (e.minX() - px) / scale);
                int x2 = (int) Math.round(cx + (e.maxX() - px) / scale);
                int z1 = (int) Math.round(cy + (e.minZ() - pz) / scale);
                int z2 = (int) Math.round(cy + (e.maxZ() - pz) / scale);

                x1 = Math.max(left, Math.min(right, x1));
                x2 = Math.max(left, Math.min(right, x2));
                z1 = Math.max(top, Math.min(bottom, z1));
                z2 = Math.max(top, Math.min(bottom, z2));

                int color = e.isYours() ? 0xFFFFFFFF : e.colorARGB();
                g.hLine(x1, x2, z1, color);
                g.hLine(x1, x2, z2, color);
                g.vLine(x1, z1, z2, color);
                g.vLine(x2, z1, z2, color);
            }
        } else {
            g.drawCenteredString(this.font, Component.literal("Requesting borders..."), cx, top + 6, 0xFFFFFFFF);
        }


        // -------------------------
        // War zones overlay
        // -------------------------
        for (var z : clientWarZoneCache.ZONES) {
            int x1 = (int) Math.round(cx + (z.minX() - px) / scale);
            int x2 = (int) Math.round(cx + (z.maxX() - px) / scale);
            int z1 = (int) Math.round(cy + (z.minZ() - pz) / scale);
            int z2 = (int) Math.round(cy + (z.maxZ() - pz) / scale);

            // clamp to panel
            x1 = Math.max(left, Math.min(right, x1));
            x2 = Math.max(left, Math.min(right, x2));
            z1 = Math.max(top, Math.min(bottom, z1));
            z2 = Math.max(top, Math.min(bottom, z2));

            int leftX = Math.min(x1, x2);
            int rightX = Math.max(x1, x2);
            int topZ = Math.min(z1, z2);
            int botZ = Math.max(z1, z2);

            // translucent red outline
            int color = 0xA0FF0000;

            g.hLine(leftX, rightX, topZ, color);
            g.hLine(leftX, rightX, botZ, color);
            g.vLine(leftX, topZ, botZ, color);
            g.vLine(rightX, topZ, botZ, color);

            // label
            g.drawString(this.font,
                    Component.literal("Front: " + z.enemyName()),
                    leftX + 3, topZ + 3, 0xFFFFFFFF, false);
        }

        // -------------------------
        // Crosshair + bearing arrow
        // -------------------------
        g.hLine(left, right - 1, cy, 0xFF444444);
        g.vLine(cx, top, bottom - 1, 0xFF444444);
        g.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFFFFFFFF);

        float yaw = mc.player.getViewYRot(partialTick);
        drawBearingArrow(g, cx, cy, yaw);

        // -------------------------
        // Hover detect + tooltip (MUST be inside render)
        // -------------------------
        if (mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom) {

            java.util.UUID hoverNow = null;
            bordersSyncPayload borders2 = kingdomsClient.CLIENT_BORDERS;

            if (borders2 != null) {
                int worldX = (int) Math.round(px + (mouseX - cx) * scale);
                int worldZ = (int) Math.round(pz + (mouseY - cy) * scale);

                long bestArea = Long.MAX_VALUE;

                for (var e : borders2.entries()) {
                    if (!e.hasBorder()) continue;
                    int minX = Math.min(e.minX(), e.maxX());
                    int maxX = Math.max(e.minX(), e.maxX());
                    int minZ = Math.min(e.minZ(), e.maxZ());
                    int maxZ = Math.max(e.minZ(), e.maxZ());

                    if (worldX >= minX && worldX <= maxX && worldZ >= minZ && worldZ <= maxZ) {
                        long area = (long) (maxX - minX) * (long) (maxZ - minZ);
                        if (area < bestArea) {
                            bestArea = area;
                            hoverNow = e.id(); // requires bordersSyncPayload Entry has UUID id
                        }
                    }
                }
            }

            if (hoverNow == null) {
                hoveredKingdomId = null;
            } else {
                if (hoveredKingdomId == null || !hoverNow.equals(hoveredKingdomId)) {
                    hoveredKingdomId = hoverNow;
                    nextHoverReqMs = 0L; // allow immediate
                }

                long now = System.currentTimeMillis();

                // Optional: if already cached, don't keep requesting every 250ms
                boolean stale = name.kingdoms.client.clientHoverCardCache.ageMs(hoveredKingdomId) > 1000; // 1s TTL
                
                if (stale && now >= nextHoverReqMs) {
                    nextHoverReqMs = now + 250; // still throttle
                    ClientPlayNetworking.send(new name.kingdoms.payload.kingdomHoverRequestC2SPayload(hoveredKingdomId));
                }


                var card = name.kingdoms.client.clientHoverCardCache.get(hoveredKingdomId);
                if (card != null) {
                    drawHoverCard(g, mouseX, mouseY, card);
                }
            }
        } else {
            hoveredKingdomId = null;
        }


        // HUD text
        g.drawString(this.font,
                Component.literal("Scale: " + String.format("%.2f", scale) + " blocks/px  (scroll to zoom)"),
                left + 6, bottom - 16, 0xFFFFFFFF, false);

        super.render(g, mouseX, mouseY, partialTick);

        g.drawString(this.font,
        Component.literal("War zones: " + clientWarZoneCache.ZONES.size()),
        left + 6, top + 6, 0xFFFFFFFF, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }


    // ------------------------------------
    // Closest-to-farthest cache update (fog-only)
    // ------------------------------------
    private void updateExploredTerrainClosestFirst(int px, int pz) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || terrainCache == null) return;

        frameCounter++;
        if (frameCounter % updateEveryNFrames != 0) return;

        int wrote = 0;

        for (int i = 0; i < samplesPerUpdate; i++) {
            int[] off = nextOffsetOnRings(updateRadiusBlocks);
            int wx = px + off[0];
            int wz = pz + off[1];

            // only if chunk is loaded
            tmpPos.set(wx, 64, wz);
            if (!level.hasChunkAt(tmpPos)) continue;

            int tx = worldToCacheX(wx);
            int tz = worldToCacheZ(wz);
            if (tx < 0 || tx >= CACHE_W || tz < 0 || tz >= CACHE_H) continue;

            // Only write over fog, never overwrite explored pixels
            if (terrainCache.getPixel(tx, tz) != FOG) continue;

            int y = fastSurfaceY(level, wx, wz);
            if (y <= level.getMinY()) continue;

            // Resolve a real “top block” (avoid AIR / MapColor.NONE -> black speckles)
            if (!resolveSurfaceBlock(level, wx, wz, y)) continue;

            BlockState s = level.getBlockState(tmpPos);

            // Note: we pass y (heightmap surface) for shading, but color is based on tmpPos/s
            int color = colorForMap(level, tmpPos, s, y);

            terrainCache.setPixel(tx, tz, color);


            cacheDirty = true;
            dirtyWrites++;
            wrote++;

            // stop early if we actually filled a bunch
            if (wrote >= (samplesPerUpdate / 3)) break;
        }

        // autosave occasionally
        if (cacheDirty && dirtyWrites >= 8000) {
            saveCache(Minecraft.getInstance());
        }
    }

    /**
     * Expanding square rings (Chebyshev distance), close-first without sorting.
     */
    private static int[] nextOffsetOnRings(int maxR) {
        if (scanR == 0) {
            scanR = 1;
            scanEdge = 0;
            scanT = 0;
            return new int[]{0, 0};
        }

        if (scanR > maxR) {
            scanR = 1;
            scanEdge = 0;
            scanT = 0;
        }

        int r = scanR;
        int len = 2 * r;

        int dx, dz;
        switch (scanEdge) {
            case 0 -> { dx = -r + scanT; dz = -r; }        // top
            case 1 -> { dx = r; dz = -r + scanT; }         // right
            case 2 -> { dx = r - scanT; dz = r; }          // bottom
            default -> { dx = -r; dz = r - scanT; }        // left
        }

        scanT++;
        if (scanT >= len) {
            scanT = 0;
            scanEdge++;
            if (scanEdge >= 4) {
                scanEdge = 0;
                scanR++;
            }
        }

        return new int[]{dx, dz};
    }

    // ------------------------------------
    // Height lookup (fast via chunk heightmap)
    // ------------------------------------
    private int fastSurfaceY(ClientLevel level, int x, int z) {
        int ccx = x >> 4;
        int ccz = z >> 4;

        var chunk = level.getChunk(ccx, ccz);

        int lx = x & 15;
        int lz = z & 15;

        return chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
    }

    private int worldToCacheX(int worldX) {
        double dx = (worldX - cacheCenterX) / (double) CACHE_SCALE_BLOCKS;
        return (int) Math.floor(dx + (CACHE_W / 2.0));
    }

    private int worldToCacheZ(int worldZ) {
        double dz = (worldZ - cacheCenterZ) / (double) CACHE_SCALE_BLOCKS;
        return (int) Math.floor(dz + (CACHE_H / 2.0));
    }

    // ------------------------------------
    // Draw cached terrain
    // ------------------------------------
    private void drawCachedTerrainAsTexture(GuiGraphics g,
                                            int left, int top, int right, int bottom,
                                            int cx, int cy,
                                            int px, int pz) {
        if (terrainCache == null) return;

        int panelW = right - left;
        int panelH = bottom - top;

        ensureMapTexture(panelW, panelH, drawStepPx);
        if (mapTex == null || mapTexId == null) return;

        boolean doRedraw = forceRedraw || (frameCounter % redrawEveryNFrames == 0);
        if (doRedraw) {
            forceRedraw = false;

            if (mapImg == null) return;

            for (int iy = 0; iy < mapImgH; iy++) {
                int sy = top + iy * drawStepPx;
                int worldZ = (int) Math.round(pz + (sy - cy) * scale);
                int tz = worldToCacheZ(worldZ);

                for (int ix = 0; ix < mapImgW; ix++) {
                    int sx = left + ix * drawStepPx;
                    int worldX = (int) Math.round(px + (sx - cx) * scale);
                    int tx = worldToCacheX(worldX);

                    int color = FOG;
                    if (tx >= 0 && tx < CACHE_W && tz >= 0 && tz < CACHE_H) {
                        color = sampleCacheWithFallback(tx, tz);
                    }

                    if (color == FOG) color = FOG_DRAW;
                    mapImg.setPixel(ix, iy, color);
                }
            }

            mapTex.upload();
        }

        g.blit(
            net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
            mapTexId,
            left, top,
            0.0f, 0.0f,        // u,v (pixels in the texture)
            panelW, panelH,    // dest width/height on screen
            mapImgW, mapImgH,  // src width/height to sample from texture
            mapImgW, mapImgH   // full texture size (for normalization)
        );

    }



    private int sampleCacheWithFallback(int tx, int tz) {
        int c = terrainCache.getPixel(tx, tz);
        if (c != FOG) return c;

        // Look for nearest explored pixel in a small radius
        for (int r = 1; r <= 2; r++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    int xx = tx + dx;
                    int zz = tz + dz;
                    if (xx < 0 || xx >= CACHE_W || zz < 0 || zz >= CACHE_H) continue;
                    int cc = terrainCache.getPixel(xx, zz);
                    if (cc != FOG) return cc;
                }
            }
        }
        return FOG; // still unknown
    }


    // ------------------------------------
    // Better terrain coloring (biome tints + map color + slight height shading)
    // ------------------------------------
    private int colorForMap(ClientLevel level, BlockPos pos, BlockState s, int surfaceY) {
        // 1) Biome-tinted blocks (grass/leaves/water/etc)
        int rgb = Minecraft.getInstance().getBlockColors().getColor(s, level, pos, 0);
        if (rgb == -1) rgb = Minecraft.getInstance().getBlockColors().getColor(s, level, pos, 1);

        int argb;
        if (rgb != -1) {
            argb = 0xFF000000 | (rgb & 0xFFFFFF);
        } else {
            // 2) Vanilla map-color fallback (covers loads of blocks)
            MapColor mc = s.getMapColor(level, pos);
            if (mc != null && mc != MapColor.NONE && mc.col != 0) {
                argb = 0xFF000000 | (mc.col & 0xFFFFFF);
            } else {
                argb = colorForCheap(s);
            }

        }

        // Subtle height shading so hills read better
        int sea = level.getSeaLevel();
        float t = (surfaceY - sea) / 120.0f;
        t = Mth.clamp(t, -0.20f, 0.25f);
        return shade(argb, 1.0f + t);
    }

    private static int shade(int argb, float mul) {
        int r = (argb >> 16) & 255;
        int g = (argb >> 8) & 255;
        int b = (argb) & 255;

        r = Mth.clamp((int) (r * mul), 0, 255);
        g = Mth.clamp((int) (g * mul), 0, 255);
        b = Mth.clamp((int) (b * mul), 0, 255);

        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // Old fallback palette (kept as last resort)
    private int colorForCheap(BlockState s) {
        if (s.is(Blocks.WATER)) return 0xFF1E4DBB;
        if (s.is(Blocks.GRASS_BLOCK)) return 0xFF2E9B4C;
        if (s.is(Blocks.SAND) || s.is(Blocks.RED_SAND)) return 0xFFE0D08B;
        if (s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.SNOW) || s.is(Blocks.ICE) || s.is(Blocks.PACKED_ICE)) return 0xFFEAF3FF;
        if (s.is(Blocks.STONE) || s.is(Blocks.DEEPSLATE) || s.is(Blocks.COBBLESTONE)) return 0xFF6D6D6D;
        if (s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.PODZOL)) return 0xFF7A5A3A;
        return 0xFF4B4B4B;
    }

    // ------------------------------------
    // Bearing arrow (player facing)
    // ------------------------------------
    private void drawBearingArrow(GuiGraphics g, int cx, int cy, float yawDeg) {
        // Minecraft yaw: 0 = south(+Z), 180 = north(-Z)
        double yaw = Math.toRadians(yawDeg);
        double dx = -Math.sin(yaw);
        double dz =  Math.cos(yaw);

        int len = 28;
        int x2 = (int) Math.round(cx + dx * len);
        int y2 = (int) Math.round(cy + dz * len);

        int color = 0xFFFFFFFF;
        drawLineDots(g, cx, cy, x2, y2, color, 2);

        // Arrowhead
        double bx = -dx, bz = -dz;
        double ang = Math.toRadians(35);
        int headLen = 10;

        double lx = bx * Math.cos(ang) - bz * Math.sin(ang);
        double lz = bx * Math.sin(ang) + bz * Math.cos(ang);

        double rx = bx * Math.cos(-ang) - bz * Math.sin(-ang);
        double rz = bx * Math.sin(-ang) + bz * Math.cos(-ang);

        drawLineDots(g, x2, y2,
                (int) Math.round(x2 + lx * headLen),
                (int) Math.round(y2 + lz * headLen),
                color, 2);

        drawLineDots(g, x2, y2,
                (int) Math.round(x2 + rx * headLen),
                (int) Math.round(y2 + rz * headLen),
                color, 2);
    }

    private static void drawLineDots(GuiGraphics g, int x0, int y0, int x1, int y1, int color, int thickness) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int steps = Math.max(dx, dy);
        if (steps <= 0) {
            g.fill(x0, y0, x0 + thickness, y0 + thickness, color);
            return;
        }

        int half = thickness / 2;
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            int x = (int) Math.round(x0 + (x1 - x0) * t);
            int y = (int) Math.round(y0 + (y1 - y0) * t);
            g.fill(x - half, y - half, x - half + thickness, y - half + thickness, color);
        }
    }
    
    private void drawHoverCard(GuiGraphics g, int mouseX, int mouseY,
                            name.kingdoms.payload.kingdomHoverSyncS2CPayload card) {

        int x = mouseX + 12;
        int y = mouseY + 12;

        int w = 230;

        boolean war = card.atWar();

        boolean inRange = card.inDiplomaticRange();
        boolean dim = !inRange;

        // Height: header region + dynamic lines
        int base = 6 + 16 + 6;  // top padding + head box + spacing
        int lines = 0;

        // Lines we draw below name:
        lines += 1; // Allies
        boolean hasPuppets = card.puppets() != null && !card.puppets().isBlank();
        if (hasPuppets) lines += 1; // Puppets
        if (war) lines += 2; // "⚔ AT WAR" + Enemies
        lines += 1; // Relation
        lines += 1; // Happiness/Security (player scale)
        lines += 1; // Soldiers
        // (Tickets removed)
        lines += 1; // Gold/Food
        lines += 1; // Wood/Metal/Arms

        int h = base + 12 /*name*/ + (lines * 12) + 8; // +8 bottom padding


        // clamp to screen
        if (x + w > this.width) x = this.width - w - 6;
        if (y + h > this.height) y = this.height - h - 6;

        // background + border (war = red tint + red outline)
        int bg = war ? 0xCC2A0000 : 0xCC000000;        // semi-transparent dark red vs black
        int border = war ? 0xFFFF4444 : 0xFFAAAAAA;    // bright red vs gray

        if (dim) {
            bg = 0xCC1A1A1A;
            border = 0xFF666666;
        }

        int titleColor  = dim ? 0xFFB0B0B0 : 0xFFFFFFFF;
        int minorColor  = dim ? 0xFF909090 : 0xFFFFCCCC;
        int normalColor = dim ? 0xFF8A8A8A : 0xFFC0C0C0;
        int goldColor   = dim ? 0xFF9A8F60 : 0xFFFFD700;

        g.fill(x, y, x + w, y + h, bg);
        g.hLine(x, x + w, y, border);
        g.hLine(x, x + w, y + h, border);
        g.vLine(x, y, y + h, border);
        g.vLine(x + w, y, y + h, border);

        int headX = x + 6;
        int headY = y + 6;
        g.fill(headX, headY, headX + 16, headY + 16, 0xFF444444);

        if (card.isAi()) {
            drawAiKingHead(g, headX, headY, card.aiSkinId());
        } else {
            tryDrawPlayerSkull(g, headX, headY, card.rulerId(), card.rulerName());
        }

        // Heraldry icon (top-right)
        var heraldcard = card.heraldry();
        if (heraldcard != null && !heraldcard.isEmpty()) {
            int iconX = (x + w) - 6 - 16; // right padding 6, item size 16
            int iconY = y + 6;
            g.renderItem(heraldcard, iconX, iconY);
        }



        int tx = x + 28;
        int ty = y + 6;

        int titleMaxW = (x + w) - tx - 6 - 16 - 4; // leave space for icon
        String title = this.font.plainSubstrByWidth(card.kingdomName(), titleMaxW);
        g.drawString(this.font, Component.literal(title), tx, ty, titleColor, false);
        ty += 12;

        
        // Allies always visible
        String allies = card.allies();
        if (allies == null || allies.isBlank()) allies = "None";
        g.drawString(this.font, Component.literal("Allies: " + allies), tx, ty, minorColor, false);
        ty += 12;

        if (hasPuppets) {
            g.drawString(this.font, Component.literal("Puppets: " + card.puppets()), tx, ty, 0xFFFFCCCC, false);
            ty += 12;
        }

        // War banner + enemies only when at war
        if (war) {
            g.drawString(this.font, Component.literal("⚔ AT WAR"), tx, ty, 0xFFFF6666, false);
            ty += 12;

            String enemies = card.enemies();
            if (enemies == null || enemies.isBlank()) enemies = "None";
            g.drawString(this.font, Component.literal("Enemies: " + enemies), tx, ty, 0xFFFFCCCC, false);
            ty += 12;
        }


        g.drawString(this.font, Component.literal("Relation: " + card.relation()), tx, ty, normalColor, false);
        ty += 12;

        // Happiness and Security on PLAYER scale
        double hap = card.happinessValue();      // 0..10
        double sec = card.securityValue();       // 0..1

        int secPct = (int) Math.round(sec * 100.0);
        String hs = String.format(Locale.ROOT, "Happiness: %.1f/10  Security: %d%%", hap, secPct);

        // Color coding:
        // - normal: gray
        // - warning: light red (either stat low)
        // - danger: brighter red (either stat very low)
        int hsColor;
        if (dim) {
            hsColor = normalColor; 
        } else if (hap < 2.5 || sec < 0.20) {
            hsColor = 0xFFFF6666;
        } else if (hap < 4.0 || sec < 0.35) {
            hsColor = 0xFFFFAAAA;
        } else {
            hsColor = normalColor;
        }

        g.drawString(this.font, Component.literal(hs), tx, ty, hsColor, false);
        ty += 12;


        g.drawString(this.font, Component.literal("Soldiers: " + card.soldiersAlive() + "/" + card.soldiersMax()), tx, ty, 0xFFC0C0C0, false);
        ty += 12;

        // economy quick lines (keep it short in tooltip)
        g.drawString(this.font, Component.literal("Gold: " + (int)card.gold()
                + "  Food: " + (int)(card.meat() + card.grain() + card.fish())), tx, ty, goldColor, false);
        ty += 12;

        g.drawString(this.font, Component.literal("Wood " + (int)card.wood() + "  Metal " + (int)card.metal()
                + "  Arms " + (int)(card.armor() + card.weapons())), tx, ty, normalColor, false);
    }


    /*private void drawAiKingHead(GuiGraphics g, int x, int y, int skinId) {
        skinId = Mth.clamp(skinId, 0, name.kingdoms.kingSkinPoolState.MAX_SKIN_ID);

        ResourceLocation tex = ResourceLocation.fromNamespaceAndPath(
                "kingdoms",
                "textures/entity/king/king_" + skinId + ".png"
        );

        // draw 8x8 face centered inside the 16x16 head box
        int dx = x + 4;
        int dy = y + 4;

        // face front at (u=8,v=8) on a 64x64 texture
        g.blit(tex, dx, dy, 8, 8, 8, 8, 64, 64);

        // optional hat/overlay at (u=40,v=8)
        g.blit(tex, dx, dy, 40, 8, 8, 8, 64, 64);
    }*/

    private void drawAiKingHead(GuiGraphics g, int x, int y, int skinId) {
        skinId = Mth.clamp(skinId, 0, name.kingdoms.kingSkinPoolState.MAX_SKIN_ID);

        var item = name.kingdoms.modItem.KING_HEADS[skinId];
        if (item == null) return;

        var stack = new net.minecraft.world.item.ItemStack(item);
        g.renderItem(stack, x, y);
    }


    private void tryDrawPlayerSkull(GuiGraphics g, int x, int y, java.util.UUID id, String name) {
        try {
            ItemStack stack = new ItemStack(Items.PLAYER_HEAD);

            Minecraft mc = Minecraft.getInstance();

            // Best case: if the client knows this player (tab list), use that profile (includes textures)
            if (mc.getConnection() != null && id != null) {
                PlayerInfo info = mc.getConnection().getPlayerInfo(id);
                if (info != null) {
                    GameProfile gp = info.getProfile();
                    setPlayerHeadProfile(stack, gp);
                    g.renderItem(stack, x, y);
                    return;
                }
            }

            // Fallback: UUID/name only (may still be Steve if textures unknown)
            if (id != null || (name != null && !name.isBlank())) {
                GameProfile gp = new GameProfile(id, (name == null || name.isBlank()) ? null : name);
                setPlayerHeadProfile(stack, gp);
            }

            g.renderItem(stack, x, y);
        } catch (Throwable ignored) {
            // leave the gray box behind it
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setPlayerHeadProfile(ItemStack stack, GameProfile gp) {
        if (gp == null) return;

        try {
            // ResolvableProfile exists but is not "new"-able in your version.
            Class<?> rpClass = Class.forName("net.minecraft.world.item.component.ResolvableProfile");

            Object rp = null;

            // Try common static factories across versions/mappings
            for (String m : new String[]{"of", "create", "from", "fromProfile", "forProfile", "fromGameProfile"}) {
                try {
                    rp = rpClass.getMethod(m, GameProfile.class).invoke(null, gp);
                    break;
                } catch (Throwable ignored) {}
            }

            // Some versions might still have a ctor(GameProfile)
            if (rp == null) {
                try {
                    rp = rpClass.getConstructor(GameProfile.class).newInstance(gp);
                } catch (Throwable ignored) {}
            }

            if (rp != null) {
                // Bypass generic signature mismatch with raw cast
                stack.set((DataComponentType) DataComponents.PROFILE, rp);
            }
        } catch (Throwable ignored) {
            // If this fails, head will render as default (Steve). That's OK fallback.
        }
    }

    private static int[] uuidToIntArray(java.util.UUID id) {
        long msb = id.getMostSignificantBits();
        long lsb = id.getLeastSignificantBits();
        return new int[]{
                (int)(msb >> 32),
                (int) msb,
                (int)(lsb >> 32),
                (int) lsb
        };
    }

    private static void attachTagToItemStack(net.minecraft.world.item.ItemStack stack, net.minecraft.nbt.CompoundTag tag) {
        try {
            // Try common setter method names
            for (String m : new String[]{"setTag", "setNbt", "setNbtTag", "setCompoundTag"}) {
                try {
                    var mm = stack.getClass().getMethod(m, tag.getClass());
                    mm.invoke(stack, tag);
                    return;
                } catch (Throwable ignored) {}
            }

            // Try "getOrCreateTag" then merge/put into it
            for (String m : new String[]{"getOrCreateTag", "getOrCreateNbt", "getTag", "getNbt"}) {
                try {
                    var mm = stack.getClass().getMethod(m);
                    Object existing = mm.invoke(stack);
                    if (existing instanceof net.minecraft.nbt.CompoundTag ct) {
                        // merge our tag into existing
                        ct.merge(tag);
                        return;
                    }
                } catch (Throwable ignored) {}
            }

            // If none worked, we just render a plain player head (still fine)
        } catch (Throwable ignored) {}
    }

    private void drawDiploRangeOverlay(GuiGraphics g,
                                    int left, int top, int right, int bottom,
                                    int cx, int cy,
                                    int px, int pz) {
        if (!showDiploRange) return;

        int r = DIPLO_RANGE_BLOCKS;

        int x1 = (int) Math.round(cx + ((px - r) - px) / scale);
        int x2 = (int) Math.round(cx + ((px + r) - px) / scale);
        int z1 = (int) Math.round(cy + ((pz - r) - pz) / scale);
        int z2 = (int) Math.round(cy + ((pz + r) - pz) / scale);

        // clamp to panel
        x1 = Math.max(left, Math.min(right, x1));
        x2 = Math.max(left, Math.min(right, x2));
        z1 = Math.max(top, Math.min(bottom, z1));
        z2 = Math.max(top, Math.min(bottom, z2));

        int lx = Math.min(x1, x2), rx = Math.max(x1, x2);
        int tz = Math.min(z1, z2), bz = Math.max(z1, z2);

        int color = 0x66FFFFFF; // translucent white outline
        g.hLine(lx, rx, tz, color);
        g.hLine(lx, rx, bz, color);
        g.vLine(lx, tz, bz, color);
        g.vLine(rx, tz, bz, color);
    }



}
