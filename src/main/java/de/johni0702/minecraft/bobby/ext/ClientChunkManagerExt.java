package de.johni0702.minecraft.bobby.ext;

import de.johni0702.minecraft.bobby.FakeChunkManager;

public interface ClientChunkManagerExt {
    FakeChunkManager bobby_getFakeChunkManager();
    void bobby_onFakeChunkAdded(int x, int z);
    void bobby_onFakeChunkRemoved(int x, int z, boolean willBeReplaced);
}
