package de.johni0702.minecraft.bobby.mixin.sodium;

import de.johni0702.minecraft.bobby.ext.ClientChunkManagerExt;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.jellysquid.mods.sodium.client.world.ChunkStatusListener;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = ClientChunkManager.class, priority = 1010) // higher than our normal one
public abstract class SodiumChunkManagerMixin implements ClientChunkManagerExt {
    /* Shadows the one in Sodium's Mixin */
    private LongOpenHashSet loadedChunks;
    /* Shadows the one in Sodium's Mixin */
    private ChunkStatusListener listener;

    @Override
    public void bobby_onFakeChunkAdded(int x, int z) {
        if (this.listener != null && this.loadedChunks.add(ChunkPos.toLong(x, z))) {
            this.listener.onChunkAdded(x, z);
        }
    }

    @Override
    public void bobby_onFakeChunkRemoved(int x, int z) {
        if (this.listener != null && this.loadedChunks.remove(ChunkPos.toLong(x, z))) {
            this.listener.onChunkRemoved(x, z);
        }
    }
}
