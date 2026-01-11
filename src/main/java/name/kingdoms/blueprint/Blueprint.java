package name.kingdoms.blueprint;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class Blueprint {
    private static final Gson GSON = new Gson();

    public final String id;

    public final int sectionSize;
    public final int sectionsX, sectionsY, sectionsZ;

    // NEW: actual blueprint footprint (from meta.json), used for grading/placement checks
    public final int sizeX, sizeY, sizeZ;

    public final int airId;
    public final List<String> palette;

    private Blueprint(String id,
                      int sectionSize,
                      int sectionsX, int sectionsY, int sectionsZ,
                      int sizeX, int sizeY, int sizeZ,
                      int airId,
                      List<String> palette) {
        this.id = id;

        this.sectionSize = sectionSize;
        this.sectionsX = sectionsX;
        this.sectionsY = sectionsY;
        this.sectionsZ = sectionsZ;

        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;

        this.airId = airId;
        this.palette = palette;
    }

    // Loads: assets/<modId>/blueprints/<blueprintId>/meta.json
    public static Blueprint load(MinecraftServer server, String modId, String blueprintId) throws IOException {
        var rm = server.getResourceManager();
        ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(modId, "blueprints/" + blueprintId + "/meta.json");

        try (InputStream in = rm.open(rl);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            Meta m = GSON.fromJson(br, Meta.class);
            if (m == null) throw new IOException("meta.json parsed to null: " + rl);
            if (m.palette == null) throw new IOException("meta.json missing palette[]: " + rl);

            // support BOTH:
            // 1) nested sections {x,y,z}
            // 2) flat sectionsX/Y/Z
            int sx, sy, sz;
            if (m.sections != null) {
                sx = m.sections.x;
                sy = m.sections.y;
                sz = m.sections.z;
            } else {
                sx = m.sectionsX;
                sy = m.sectionsY;
                sz = m.sectionsZ;
                if (sx <= 0 || sy <= 0 || sz <= 0) {
                    throw new IOException("meta.json missing sections (need sections{} or sectionsX/Y/Z): " + rl);
                }
            }

            // Fallback size if meta doesn't include explicit sizeX/Y/Z
            int fallbackX = sx * m.sectionSize;
            int fallbackY = sy * m.sectionSize;
            int fallbackZ = sz * m.sectionSize;

            int sizeX = (m.sizeX > 0) ? m.sizeX : fallbackX;
            int sizeY = (m.sizeY > 0) ? m.sizeY : fallbackY;
            int sizeZ = (m.sizeZ > 0) ? m.sizeZ : fallbackZ;

            return new Blueprint(
                    blueprintId,
                    m.sectionSize,
                    sx, sy, sz,
                    sizeX, sizeY, sizeZ,
                    m.airId,
                    m.palette
            );

        } catch (Exception e) {
            System.out.println("[Kingdoms] FAILED to read blueprint meta: " + rl);
            System.out.println("[Kingdoms] Reason: " + e);

            // List what resources the game *can* see under your blueprints folder
            String prefix = "blueprints/" + blueprintId + "/";
            try {
                var found = rm.listResources(prefix, r -> true).keySet();
                System.out.println("[Kingdoms] Resources visible under " + modId + ":" + prefix);
                for (ResourceLocation r : found) {
                    System.out.println("  - " + r);
                }
            } catch (Exception ignored) {}

            if (e instanceof IOException io) throw io;
            throw new IOException(e);
        }
    }

    // Opens: assets/<modId>/blueprints/<id>/sec_<sx>_<sy>_<sz>.bin
    public InputStream openSection(MinecraftServer server, String modId, int sx, int sy, int sz) throws IOException {
        ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(
                modId,
                "blueprints/" + id + "/sec_" + sx + "_" + sy + "_" + sz + ".bin"
        );
        return server.getResourceManager().open(rl);
    }

    private static final class Meta {
        // accept either "section_size" or "sectionSize"
        @SerializedName(value = "section_size", alternate = {"sectionSize"})
        public int sectionSize = 16;

        // accept either "air_id" or "airId"
        @SerializedName(value = "air_id", alternate = {"airId"})
        public int airId = -1;

        @SerializedName("palette")
        public List<String> palette;

        // Flat sectionsX/Y/Z
        @SerializedName(value = "sectionsX", alternate = {"sections_x"})
        public int sectionsX;

        @SerializedName(value = "sectionsY", alternate = {"sections_y"})
        public int sectionsY;

        @SerializedName(value = "sectionsZ", alternate = {"sections_z"})
        public int sectionsZ;

        // NEW: explicit blueprint size (recommended)
        @SerializedName(value = "sizeX", alternate = {"size_x"})
        public int sizeX;

        @SerializedName(value = "sizeY", alternate = {"size_y"})
        public int sizeY;

        @SerializedName(value = "sizeZ", alternate = {"size_z"})
        public int sizeZ;

        // Old nested format still supported
        @SerializedName("sections")
        public Sections sections;
    }

    private static final class Sections {
        @SerializedName("x") public int x;
        @SerializedName("y") public int y;
        @SerializedName("z") public int z;
    }
}
