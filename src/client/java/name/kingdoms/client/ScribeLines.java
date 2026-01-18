package name.kingdoms.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ScribeLines {
    private static final Gson GSON = new Gson();
    private static final ResourceLocation FILE =
        ResourceLocation.fromNamespaceAndPath("kingdoms", "scribe_lines.json");


    private static List<String> single = List.of(
            "My liege, a sealed letter has arrived."
    );
    private static List<String> multi = List.of(
            "My liege, %d new letters have arrived."
    );

    private ScribeLines() {}

    public static void load(ResourceManager rm) {
        try {
            Resource res = rm.getResourceOrThrow(FILE);
            try (var reader = new InputStreamReader(res.open(), StandardCharsets.UTF_8)) {
                JsonObject obj = GSON.fromJson(reader, JsonObject.class);

                single = readArray(obj, "single", single);
                multi   = readArray(obj, "multi", multi);
            }
        } catch (Throwable t) {
            // Keep defaults; don't crash the client if the JSON is missing/bad
        }
    }

    private static List<String> readArray(JsonObject obj, String key, List<String> fallback) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) return fallback;
        var arr = obj.getAsJsonArray(key);
        var out = new ArrayList<String>(arr.size());
        for (var el : arr) {
            if (el != null && el.isJsonPrimitive()) out.add(el.getAsString());
        }
        return out.isEmpty() ? fallback : out;
    }

    public static String pickLine(int delta) {
        var mc = Minecraft.getInstance();
        var r = (mc.player != null) ? mc.player.getRandom() : mc.level != null ? mc.level.random : null;

        if (delta > 1) {
            String template = pick(multi, r);
            return String.format(template, delta);
        } else {
            return pick(single, r);
        }
    }

    private static String pick(List<String> list, net.minecraft.util.RandomSource r) {
        if (list == null || list.isEmpty()) return "";
        if (r == null) return list.get(0);
        return list.get(r.nextInt(list.size()));
    }
}
