package name.kingdoms;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

import java.util.List;
import java.util.Optional;

public final class AiHeraldryPool {
    private AiHeraldryPool() {}

    private static final DyeColor[] COLORS = DyeColor.values();

    // Banner pattern ids in the registry (Minecraft vanilla).
    // These are the “pattern names” used by banners.
    private static final List<ResourceLocation> PATTERNS = List.of(
            ResourceLocation.withDefaultNamespace("stripe_downright"),
            ResourceLocation.withDefaultNamespace("stripe_downleft"),
            ResourceLocation.withDefaultNamespace("stripe_center"),
            ResourceLocation.withDefaultNamespace("stripe_middle"),
            ResourceLocation.withDefaultNamespace("cross"),
            ResourceLocation.withDefaultNamespace("straight_cross"),
            ResourceLocation.withDefaultNamespace("border"),
            ResourceLocation.withDefaultNamespace("circle"),
            ResourceLocation.withDefaultNamespace("rhombus"),
            ResourceLocation.withDefaultNamespace("half_horizontal"),
            ResourceLocation.withDefaultNamespace("half_vertical"),
            ResourceLocation.withDefaultNamespace("diagonal_left"),
            ResourceLocation.withDefaultNamespace("diagonal_right"),
            ResourceLocation.withDefaultNamespace("gradient"),
            ResourceLocation.withDefaultNamespace("gradient_up")
    );

    /** Random banner with 1–3 layers. */
    public static ItemStack randomBanner(RegistryAccess registries, RandomSource r) {
        ItemStack banner = new ItemStack(Items.WHITE_BANNER);

        DyeColor base = pickColor(r, null);
        banner.set(DataComponents.BASE_COLOR, base);

        HolderGetter<BannerPattern> getter = registries.lookupOrThrow(Registries.BANNER_PATTERN);

        int layersN = 1 + r.nextInt(3);

        BannerPatternLayers.Builder b = new BannerPatternLayers.Builder();
        DyeColor last = base;

        for (int i = 0; i < layersN; i++) {
            ResourceLocation id = PATTERNS.get(r.nextInt(PATTERNS.size()));

            Optional<Holder.Reference<BannerPattern>> opt = getter.get(ResourceKeyUtil.bannerPatternKey(id));
            if (opt.isEmpty()) continue; // skip missing patterns safely

            DyeColor c = pickColor(r, last);
            b.add(opt.get(), c);
            last = c;
        }

        banner.set(DataComponents.BANNER_PATTERNS, b.build());
        return banner;
    }

    private static DyeColor pickColor(RandomSource r, DyeColor avoid) {
        for (int tries = 0; tries < 8; tries++) {
            DyeColor c = COLORS[r.nextInt(COLORS.length)];
            if (avoid == null || c != avoid) return c;
        }
        return DyeColor.WHITE;
    }

    /**
     * Tiny utility to make ResourceKey<BannerPattern> from a ResourceLocation
     * without depending on missing constants.
     */
    private static final class ResourceKeyUtil {
        private ResourceKeyUtil() {}

        static net.minecraft.resources.ResourceKey<BannerPattern> bannerPatternKey(ResourceLocation id) {
            return net.minecraft.resources.ResourceKey.create(Registries.BANNER_PATTERN, id);
        }
    }
}
