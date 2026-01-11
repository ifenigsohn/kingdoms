package name.kingdoms.client;

import java.util.List;

public final class ClientNewsCache {
    private static volatile List<String> LINES = List.of();

    private ClientNewsCache() {}

    public static void set(List<String> lines) {
        LINES = (lines == null) ? List.of() : lines;
    }

    public static List<String> get() {
        return LINES;
    }
}
