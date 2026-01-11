package name.kingdoms.war;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

public record BattleZone(int minX, int minZ, int maxX, int maxZ) {

    public static final Codec<BattleZone> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.INT.fieldOf("minX").forGetter(BattleZone::minX),
            Codec.INT.fieldOf("minZ").forGetter(BattleZone::minZ),
            Codec.INT.fieldOf("maxX").forGetter(BattleZone::maxX),
            Codec.INT.fieldOf("maxZ").forGetter(BattleZone::maxZ)
    ).apply(inst, BattleZone::new));

    public static BattleZone of(int x1, int z1, int x2, int z2) {
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);
        return new BattleZone(minX, minZ, maxX, maxZ);
    }

    public boolean contains(BlockPos pos) {
        int x = pos.getX(), z = pos.getZ();
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public AABB toTallAabb() {
        return new AABB(minX, -64, minZ, maxX + 1, 400, maxZ + 1);
    }
}
