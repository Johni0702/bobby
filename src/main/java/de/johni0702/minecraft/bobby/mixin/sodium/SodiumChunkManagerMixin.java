package de.johni0702.minecraft.bobby.mixin.sodium;

import de.johni0702.minecraft.bobby.compat.IChunkStatusListener;
import de.johni0702.minecraft.bobby.compat.sodium.SodiumChunkStatusListener;
import de.johni0702.minecraft.bobby.ext.ClientChunkManagerExt;
import me.jellysquid.mods.sodium.client.world.ChunkStatusListener;
import net.minecraft.client.world.ClientChunkManager;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = ClientChunkManager.class, priority = 1010) // higher than our normal one
public abstract class SodiumChunkManagerMixin implements ClientChunkManagerExt {
    /* Shadows the one in Sodium's Mixin */
    private ChunkStatusListener listener;

    private SodiumChunkStatusListener wrappedListener;
    private ChunkStatusListener suppressedListener;

    @Override
    public IChunkStatusListener bobby_getListener() {
        if (listener == null) {
            return null;
        }
        if (wrappedListener == null || wrappedListener.delegate != listener) {
            wrappedListener = new SodiumChunkStatusListener(listener);
        }
        return wrappedListener;
    }

    @Override
    public void bobby_suppressListener() {
        suppressedListener = listener;
        listener = null;
    }

    @Override
    public IChunkStatusListener bobby_restoreListener() {
        if (suppressedListener != null) {
            listener = suppressedListener;
            suppressedListener = null;
            return bobby_getListener();
        } else {
            return null;
        }
    }
}
