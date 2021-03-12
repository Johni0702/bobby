package de.johni0702.minecraft.bobby.mixin;

import net.minecraft.world.chunk.light.ChunkLightProvider;
import net.minecraft.world.chunk.light.LightingProvider;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LightingProvider.class)
public interface LightingProviderAccessor {
    @Accessor
    @Nullable
    ChunkLightProvider<?, ?> getBlockLightProvider();
    @Accessor
    @Nullable
    ChunkLightProvider<?, ?> getSkyLightProvider();
}
