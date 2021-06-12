package de.johni0702.minecraft.bobby.compat;

public interface IChunkStatusListener {
    void onChunkAdded(int x, int z);
    void onChunkRemoved(int x, int z);
}
