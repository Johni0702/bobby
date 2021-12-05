package de.johni0702.minecraft.bobby.sodium;

public interface ChunkStatusListener {
    void onChunkAdded(int x, int z);
    void onChunkRemoved(int x, int z);
}
