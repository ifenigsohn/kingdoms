// src/main/java/name/kingdoms/network/serverMail.java
package name.kingdoms.network;

import name.kingdoms.diplomacy.Letter;
import name.kingdoms.payload.mailInboxSyncPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public final class serverMail {
    private serverMail() {}

    public static void syncInbox(ServerPlayer player, List<Letter> inbox) {
        ServerPlayNetworking.send(player, new mailInboxSyncPayload(inbox));
    }
}
