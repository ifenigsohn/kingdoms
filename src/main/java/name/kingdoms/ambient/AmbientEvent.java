package name.kingdoms.ambient;

import java.util.List;

public interface AmbientEvent {
    int weight(AmbientContext ctx);
    void run(AmbientContext ctx);

    default int minGroup(AmbientContext ctx) { return 1; }
    default int maxGroup(AmbientContext ctx) { return 1; }


    default List<AmbientEffect> effects(AmbientContext ctx) {
        return List.of();
    }

    default String id() { return this.getClass().getSimpleName(); }
}
