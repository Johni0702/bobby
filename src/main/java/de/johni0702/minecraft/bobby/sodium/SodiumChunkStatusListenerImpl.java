package de.johni0702.minecraft.bobby.sodium;

import de.johni0702.minecraft.bobby.mixin.sodium_05.SodiumWorldRendererAccessor;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkStatus;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;

import java.lang.reflect.Method;

public class SodiumChunkStatusListenerImpl implements ChunkStatusListener {

    private void callIfExists(Object callObject, String func, Object... args){
        if (callObject == null) return;
        Method method = null;
        try{
            method = callObject.getClass().getMethod(func, (Class<?>[]) args);
        }catch (Exception e){}
        if (method != null) {
            try{
                method.invoke(callObject, args);
            }catch (Exception e){}
        }
    }

    private void onChunkLightAdded(int x, int z, SodiumWorldRendererAccessor accessInterface){
        if (accessInterface != null){
            ChunkTrackerHolder.get(accessInterface.getWorld()).onChunkStatusAdded(x,z, ChunkStatus.FLAG_ALL);
        }
    }

    @Override
    public void onChunkAdded(int x, int z) {
        SodiumWorldRenderer sodiumRenderer = SodiumWorldRenderer.instanceNullable();
        if (sodiumRenderer != null) {
            try{
                if (sodiumRenderer instanceof SodiumWorldRendererAccessor accessor) {
                    accessor.getRenderSectionManager().onChunkAdded(x, z);
                    onChunkLightAdded(x, z, accessor);
                    return;
                }
            }catch (Exception e){}

            callIfExists(sodiumRenderer, "onChunkAdded", x, z);
            callIfExists(sodiumRenderer, "onChunkLightAdded", x, z);

        }
    }

    @Override
    public void onChunkRemoved(int x, int z) {
        SodiumWorldRenderer sodiumRenderer = SodiumWorldRenderer.instanceNullable();
        if (sodiumRenderer != null) {
            try {
                if (sodiumRenderer instanceof SodiumWorldRendererAccessor accessor) {
                    accessor.getRenderSectionManager().onChunkRemoved(x, z);
                    return;
                }
            }catch (Exception e){}

            callIfExists(sodiumRenderer, "onChunkRemoved", x, z);
        }
    }
}
