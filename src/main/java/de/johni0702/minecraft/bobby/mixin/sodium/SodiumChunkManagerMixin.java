package de.johni0702.minecraft.bobby.mixin.sodium;

import de.johni0702.minecraft.bobby.ext.ClientChunkManagerExt;
import de.johni0702.minecraft.bobby.sodium.BufferedChunkStatusListener;
import de.johni0702.minecraft.bobby.sodium.ChunkStatusListener;
import de.johni0702.minecraft.bobby.sodium.SodiumChunkStatusListenerImpl;
import net.minecraft.client.world.ClientChunkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = ClientChunkManager.class, priority = 1010) // higher than our normal one
public abstract class SodiumChunkManagerMixin implements ClientChunkManagerExt {
    @Unique
    private ChunkStatusListener listener = new SodiumChunkStatusListenerImpl();

    @Unique
    private BufferedChunkStatusListener bufferedListener;

    @Override
    public void bobby_onFakeChunkAdded(int x, int z) {
        if (this.listener != null) {
            this.listener.onChunkAdded(x, z);
        }
    }

    @Override
    public void bobby_onFakeChunkRemoved(int x, int z) {
        if (this.listener != null) {
            this.listener.onChunkRemoved(x, z);
        }
    }

    @Override
    public void bobby_pauseChunkStatusListener() {
        if (this.listener == this.bufferedListener) {
            return;
        }

        if (this.bufferedListener == null || this.bufferedListener.delegate != this.listener) {
            this.bufferedListener = new BufferedChunkStatusListener(this.listener);
        }

        this.listener = this.bufferedListener;
    }

    @Override
    public void bobby_resumeChunkStatusListener() {
        if (this.listener != this.bufferedListener) {
            return;
        }

        this.bufferedListener.flush();
        this.listener = this.bufferedListener.delegate;
    }
}
