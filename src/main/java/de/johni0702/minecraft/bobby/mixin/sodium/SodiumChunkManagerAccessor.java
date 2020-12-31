package de.johni0702.minecraft.bobby.mixin.sodium;

import me.jellysquid.mods.sodium.client.world.ChunkStatusListener;
import me.jellysquid.mods.sodium.client.world.SodiumChunkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SodiumChunkManager.class)
public interface SodiumChunkManagerAccessor {
    @Accessor(remap = false)
    ChunkStatusListener getListener();
}
