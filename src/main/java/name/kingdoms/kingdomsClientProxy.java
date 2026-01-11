package name.kingdoms;

import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import name.kingdoms.payload.OpenTreasuryS2CPayload;
import name.kingdoms.payload.OpenMailS2CPayload;

import java.lang.reflect.Method;
import java.util.UUID;

public class kingdomsClientProxy {
    public kingdomsClientProxy() {}

    // -------- CLIENT (reflection) --------

    public static void openTreasury(BlockPos pos) {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) return;

        try {
            Class<?> cls = Class.forName("name.kingdoms.kingdomsClient");
            Method m = cls.getMethod("openTreasury", BlockPos.class);
            m.invoke(null, pos);
        } catch (Throwable t) {
        }
    }

    public static void openKingdomMenu(BlockPos pos) {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) return;

        try {
            Class<?> cls = Class.forName("name.kingdoms.kingdomsClient");
            Method m = cls.getMethod("openKingdomMenu", BlockPos.class);
            m.invoke(null, pos);
        } catch (Throwable t) {
        }
    }

    public static void openMail(BlockPos pos) {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) return;

        try {
            Class<?> cls = Class.forName("name.kingdoms.kingdomsClient");
            Method m = cls.getMethod("openMail", BlockPos.class);
            m.invoke(null, pos);
        } catch (Throwable t) {
        }
    }

    public void openKingdomBordersMap() {
        // server/common: do nothing
    }

    // -------- SERVER → CLIENT --------

    public static void openTreasury(ServerPlayer player, BlockPos pos) {
        ServerPlayNetworking.send(player, new OpenTreasuryS2CPayload(pos));
    }

    /** Opens the mail UI for a specific NPC (scribe). */
    public static void openMail(ServerPlayer player, int entityId, UUID entityUuid) {
        ServerPlayNetworking.send(player, new OpenMailS2CPayload(entityId, entityUuid));
    }
}
