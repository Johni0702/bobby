package de.johni0702.minecraft.bobby.sodium;

import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import de.johni0702.minecraft.bobby.mixin.sodium.SodiumWorldRendererMixin;

import java.lang.reflect.Method;

public class SodiumChunkStatusListenerImpl implements ChunkStatusListener {
    @Override
    public void onChunkAdded(int x, int z) {
        SodiumWorldRenderer sodiumRenderer = SodiumWorldRenderer.instanceNullable();
        if (sodiumRenderer != null) {
            Method onChunkAdded = null;
            Method onChunkLightAdded = null;
            try {
                onChunkAdded = SodiumWorldRenderer.class.getMethod("onChunkAdded", (Class<?>[]) null);
                onChunkLightAdded = SodiumWorldRenderer.class.getMethod("onChunkLightAdded", (Class<?>[]) null);
            } catch (NoSuchMethodException | SecurityException e) {}
            if(onChunkAdded != null){
                try {
                    onChunkAdded.invoke(sodiumRenderer, (Object[]) null);
                } catch (Exception e) {}
            }
            if(onChunkLightAdded != null){
                try {
                    onChunkLightAdded.invoke(sodiumRenderer, (Object[]) null);
                } catch (Exception e) {}
            }
        }
    }

    @Override
    public void onChunkRemoved(int x, int z) {
        SodiumWorldRenderer sodiumRenderer = SodiumWorldRenderer.instanceNullable();
        if (sodiumRenderer != null) {
            Method onChunkRemoved = null;
            try{
                onChunkRemoved = SodiumWorldRenderer.class.getMethod("onChunkRemoved", (Class<?>[]) null);

            }catch (Exception e){}
            if (onChunkRemoved != null) {
                try{
                    onChunkRemoved.invoke(sodiumRenderer, (Object[]) null);
                }catch (Exception e){}
            }
        }
    }
}
