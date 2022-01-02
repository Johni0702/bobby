package de.johni0702.minecraft.bobby.ext;

import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.light.ChunkLightingView;

public interface ChunkLightProviderExt {
    void bobby_addSectionData(long pos, ChunkNibbleArray data);
    void bobby_removeSectionData(long pos);

    void bobby_setTainted(long pos, int delta);

    static ChunkLightProviderExt get(ChunkLightingView view) {
        return (view instanceof ChunkLightProviderExt) ? (ChunkLightProviderExt) view : null;
    }
}
