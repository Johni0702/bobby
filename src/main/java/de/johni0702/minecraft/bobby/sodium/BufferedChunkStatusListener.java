package de.johni0702.minecraft.bobby.sodium;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.jellysquid.mods.sodium.client.world.ChunkStatusListener;
import net.minecraft.util.math.ChunkPos;

public class BufferedChunkStatusListener implements ChunkStatusListener {
    public final ChunkStatusListener delegate;
    private final LongSet unloaded = new LongOpenHashSet();

    public BufferedChunkStatusListener(ChunkStatusListener delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onChunkAdded(int x, int z) {
        if (!this.unloaded.remove(ChunkPos.toLong(x, z))) {
            this.delegate.onChunkAdded(x, z);
        }
    }

    @Override
    public void onChunkRemoved(int x, int z) {
        this.unloaded.add(ChunkPos.toLong(x, z));
    }

    public void flush() {
        for (long pos : this.unloaded) {
            this.delegate.onChunkRemoved(ChunkPos.getPackedX(pos), ChunkPos.getPackedZ(pos));
        }
        this.unloaded.clear();
    }
}
