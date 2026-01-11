package name.kingdoms.payload;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.HashMap;
import java.util.Map;

public record jobReqsS2CPayload(
        BlockPos pos,
        String jobId,
        int radius,
        Map<String, Integer> required,
        Map<String, Integer> have,
        boolean enabled
) implements CustomPacketPayload {

   public static final Type<jobReqsS2CPayload> TYPE =
        new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("kingdoms", "job_reqs_s2c"));


    public static final StreamCodec<RegistryFriendlyByteBuf, jobReqsS2CPayload> CODEC =
            StreamCodec.of(jobReqsS2CPayload::write, jobReqsS2CPayload::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(RegistryFriendlyByteBuf buf, jobReqsS2CPayload p) {
        buf.writeBlockPos(p.pos());
        buf.writeUtf(p.jobId());
        buf.writeVarInt(p.radius());
        writeMap(buf, p.required());
        writeMap(buf, p.have());
        buf.writeBoolean(p.enabled());
    }

    private static jobReqsS2CPayload read(RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String jobId = buf.readUtf();
        int radius = buf.readVarInt();
        Map<String, Integer> required = readMap(buf);
        Map<String, Integer> have = readMap(buf);
        boolean enabled = buf.readBoolean();
        return new jobReqsS2CPayload(pos, jobId, radius, required, have, enabled);
    }

    private static void writeMap(RegistryFriendlyByteBuf buf, Map<String, Integer> map) {
        if (map == null) {
            buf.writeVarInt(0);
            return;
        }
        buf.writeVarInt(map.size());
        for (var e : map.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeVarInt(e.getValue());
        }
    }

    private static Map<String, Integer> readMap(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Map<String, Integer> map = new HashMap<>(Math.max(0, size));
        for (int i = 0; i < size; i++) {
            String key = buf.readUtf();
            int val = buf.readVarInt();
            map.put(key, val);
        }
        return map;
    }
}
