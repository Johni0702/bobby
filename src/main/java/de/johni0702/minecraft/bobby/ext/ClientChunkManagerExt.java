package de.johni0702.minecraft.bobby.ext;

import de.johni0702.minecraft.bobby.FakeChunkManager;
import de.johni0702.minecraft.bobby.sodium.ChunkStatusListener;

public interface ClientChunkManagerExt {
    FakeChunkManager bobby_getFakeChunkManager();
    void bobby_onFakeChunkAdded(int x, int z);
    void bobby_onFakeChunkRemoved(int x, int z);

    /**
     * Mark Sodium's {@link ChunkStatusListener} as paused.
     * This effectively delays all unload notifications until un-paused and most importantly removes redundant (as in
     * unload followed by a load) ones. Otherwise Sodium will unload the geometry and the chunk will be missing until
     * it is rebuilt.
     *
     * Has no effect on the vanilla renderer because it polls chunks every frame and gets no intermediate state.
     *
     * This method is idempotent.
     */
    void bobby_pauseChunkStatusListener();

    /**
     * Resumes Sodium's {@link ChunkStatusListener}, forwarding all updates which are still applicable.
     */
    void bobby_resumeChunkStatusListener();
}
