package de.johni0702.minecraft.bobby.ext;

import de.johni0702.minecraft.bobby.FakeChunkManager;
import de.johni0702.minecraft.bobby.VisibleChunksTracker;

public interface ClientChunkManagerExt {
    FakeChunkManager bobby_getFakeChunkManager();
    VisibleChunksTracker bobby_getRealChunksTracker();
    void bobby_onFakeChunkAdded(int x, int z);
    void bobby_onFakeChunkRemoved(int x, int z, boolean willBeReplaced);
}
