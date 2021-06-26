package de.johni0702.minecraft.bobby.compat.sodium;

import me.jellysquid.mods.sodium.client.world.ChunkStatusListener;

public class SuppressingChunkStatusListener implements ChunkStatusListener {
    @Override
    public void onChunkAdded(int x, int z) {
    }

    @Override
    public void onChunkRemoved(int x, int z) {
    }
}
