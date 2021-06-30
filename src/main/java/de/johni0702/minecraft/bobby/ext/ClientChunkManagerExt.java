package de.johni0702.minecraft.bobby.ext;

import de.johni0702.minecraft.bobby.FakeChunkManager;
import de.johni0702.minecraft.bobby.compat.IChunkStatusListener;

public interface ClientChunkManagerExt {
    FakeChunkManager bobby_getFakeChunkManager();
    IChunkStatusListener bobby_getListener();
    void bobby_suppressListener();
    IChunkStatusListener bobby_restoreListener();
}
