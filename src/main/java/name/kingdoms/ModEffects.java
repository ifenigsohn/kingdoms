package name.kingdoms;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

public final class ModEffects {
    private ModEffects() {}

    // Holder<MobEffect> is correct for 1.21.x registries
    public static Holder<MobEffect> RESPAWNING;

    public static void register() {
        ResourceLocation id = Kingdoms.id("respawning"); 
        RESPAWNING = Registry.registerForHolder(
                BuiltInRegistries.MOB_EFFECT,
                id,
                new RespawningEffect()
        );
    }

    private static final class RespawningEffect extends MobEffect {
        protected RespawningEffect() {
            super(MobEffectCategory.NEUTRAL, 0x66CCFF);
        }

        // No ticking behavior, purely UI/timer
        @Override
        public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
            return false;
        }
    }
}
