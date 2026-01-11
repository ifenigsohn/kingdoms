package name.kingdoms;


import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class namePool {
    private namePool() {}

    private static final ResourceLocation MEDIEVAL =
    ResourceLocation.fromNamespaceAndPath(Kingdoms.MOD_ID, "names/medieval_names.json");


    private static volatile List<String> cached = List.of();

    public static String randomMedieval(MinecraftServer server, RandomSource r) {
        List<String> names = getMedieval(server);
        return names.isEmpty() ? "Aelfric" : names.get(r.nextInt(names.size()));
    }

    public static List<String> getMedieval(MinecraftServer server) {
        List<String> local = cached;
        if (!local.isEmpty()) return local;

        try {
            var opt = server.getResourceManager().getResource(MEDIEVAL);
            if (opt.isEmpty()) return cached = List.of("Aelfric");

            try (var in = opt.get().open()) {
                JsonObject obj = JsonParser.parseReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8)
                ).getAsJsonObject();

                ArrayList<String> out = new ArrayList<>();

                // matches your medieval_names.json format :contentReference[oaicite:0]{index=0}
                if (obj.has("male")) for (JsonElement e : obj.getAsJsonArray("male")) out.add(e.getAsString());
                if (obj.has("female")) for (JsonElement e : obj.getAsJsonArray("female")) out.add(e.getAsString());

                if (out.isEmpty()) out.add("Aelfric");
                cached = List.copyOf(out);
                return cached;
            }
        } catch (Exception e) {
            cached = List.of("Aelfric");
            return cached;
        }
    }
}
