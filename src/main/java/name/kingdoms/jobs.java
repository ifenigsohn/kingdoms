//TODO: TEST DELETE
package name.kingdoms;
import java.util.HashMap;
import java.util.Map;

public final class jobs {
    private jobs() {}

    public static final Map<String, jobDefinition> BY_ID = new HashMap<>();

    static {
        register(jobDefinition.FARM_JOB);
        register(jobDefinition.BUTCHER_JOB);
        register(jobDefinition.FISHING_JOB);
        register(jobDefinition.WOOD_JOB);
        register(jobDefinition.METAL_JOB);
        register(jobDefinition.GEM_JOB);
        register(jobDefinition.ALCHEMY_JOB);
        register(jobDefinition.ARMOR_JOB);
        register(jobDefinition.WEAPON_JOB);
        // add the rest when you define them:
        // register(jobDefinition.STABLE_JOB);
        // register(jobDefinition.GUARD_JOB);
        // etc...
    }

    private static void register(jobDefinition job) {
        BY_ID.put(job.getId(), job);
    }
}