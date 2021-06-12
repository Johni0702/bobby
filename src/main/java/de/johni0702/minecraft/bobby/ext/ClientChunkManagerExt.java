package de.johni0702.minecraft.bobby.ext;

import de.johni0702.minecraft.bobby.compat.IChunkStatusListener;

public interface ClientChunkManagerExt {
    IChunkStatusListener bobby_getListener();
    void bobby_suppressListener();
    IChunkStatusListener bobby_restoreListener();
}
