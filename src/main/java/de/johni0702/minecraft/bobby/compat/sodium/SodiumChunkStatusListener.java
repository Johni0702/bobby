package de.johni0702.minecraft.bobby.compat.sodium;

import de.johni0702.minecraft.bobby.compat.IChunkStatusListener;
import me.jellysquid.mods.sodium.client.world.ChunkStatusListener;

public class SodiumChunkStatusListener implements IChunkStatusListener {
    public final ChunkStatusListener delegate;

    public SodiumChunkStatusListener(ChunkStatusListener delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onChunkAdded(int x, int z) {
        this.delegate.onChunkAdded(x, z);
    }

    @Override
    public void onChunkRemoved(int x, int z) {
        this.delegate.onChunkRemoved(x, z);
    }
}
