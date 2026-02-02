package name.kingdoms.pressure;

import net.minecraft.server.MinecraftServer;
import java.util.UUID;

public final class KingdomModifiers {
    private KingdomModifiers() {}

    public record All(
        double econMult,
        double happinessDelta,
        double securityDelta,
        int globalRelDelta,
        double guardGoldCostMult,
        double tavernGoldInMult,
        double shopGoldOutMult,
        double soldierRegenMult
    ) {}

    public static All compute(MinecraftServer server, UUID kingdomId, UUID otherIdOrNull) {
        long now = server.getTickCount();

        var ps = KingdomPressureState.get(server);
        var mods = ps.getMods(kingdomId, now);

        // base “stat” mods
        double econMult = mods.economyMult();
        double hap = mods.happinessDelta();
        double sec = mods.securityDelta();

        // relations: global + pair
        int rel = mods.relationsDelta();

        // policy-only modifiers by scanning events once
        var pol = PolicyModifiers.compute(server, kingdomId);

        return new All(
            econMult,
            hap,
            sec,
            rel,
            pol.guardGoldCostMult(),
            pol.tavernGoldInMult(),
            pol.shopGoldOutMult(),
            pol.soldierRegenMult()
        );
    }
}
