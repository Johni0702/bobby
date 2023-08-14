package de.johni0702.minecraft.bobby.mixin.sodium;

import de.johni0702.minecraft.bobby.ext.ClientChunkManagerExt;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkStatus;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.ChunkSectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = ClientChunkManager.class, priority = 1010) // higher than our normal one
public abstract class SodiumChunkManagerMixin implements ClientChunkManagerExt {

    @Shadow @Final
    ClientWorld world;

    @Override
    public void bobby_onFakeChunkAdded(int x, int z) {
        // Fake chunks always have light data included, so we use ALL rather than just HAS_BLOCK_DATA
        ChunkTrackerHolder.get(world).onChunkStatusAdded(x, z, ChunkStatus.FLAG_ALL);
    }

    @Override
    public void bobby_onFakeChunkRemoved(int x, int z, boolean willBeReplaced) {
        // If we know the chunk will be replaced by a real one, then we can pretend like light data is already
        // available, otherwise Sodium will unload the chunk for a few frames until MC's delayed light update gets
        // around to actually inserting the real light.
        boolean stillHasLight = willBeReplaced || world.getLightingProvider().isLightingEnabled(ChunkSectionPos.from(x, 0, z));
        ChunkTrackerHolder.get(world).onChunkStatusRemoved(x, z, stillHasLight ? ChunkStatus.FLAG_HAS_BLOCK_DATA : ChunkStatus.FLAG_ALL);
    }
}
