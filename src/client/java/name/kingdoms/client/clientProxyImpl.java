package name.kingdoms.client;


import name.kingdoms.kingdomsClientProxy;
import name.kingdoms.payload.bordersRequestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

public class clientProxyImpl extends kingdomsClientProxy {

    @Override
    public void openKingdomBordersMap() {
        ClientPlayNetworking.send(new bordersRequestPayload());
        Minecraft.getInstance().setScreen(new kingdomBordersMapScreen());
    }
}
