package de.johni0702.minecraft.bobby.ext;

import net.minecraft.world.chunk.ChunkNibbleArray;

public interface ChunkLightProviderExt {
    void bobby_addSectionData(long pos, ChunkNibbleArray data);
    void bobby_removeSectionData(long pos);
}
