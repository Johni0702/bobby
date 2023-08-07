package de.johni0702.minecraft.bobby.sodium;

import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import de.johni0702.minecraft.bobby.mixin.sodium.SodiumWorldRendererMixin;

import java.lang.reflect.Method;
import org.spongepowered.asm.mixin.*;

public class SodiumChunkStatusListenerImpl implements ChunkStatusListener {
    public static void callChunkFunction(String func, int x, int z){
        SodiumWorldRenderer sodiumRenderer = SodiumWorldRenderer.instanceNullable();
        if (sodiumRenderer != null) {
            Method chunkMethod = null;
            try{
                chunkMethod = SodiumWorldRenderer.class.getMethod(func, int.class, int.class);
            }catch (Exception e){}
            if (chunkMethod != null) {
                try{
                    chunkMethod.invoke(sodiumRenderer, x, z);
                }catch (Exception e){}
            }
        }
    }

    @Override
    public void onChunkAdded(int x, int z) {
        callChunkFunction("onChunkAdded", x, z);
        callChunkFunction("onChunkLightAdded", x, z);
    }

    @Override
    public void onChunkRemoved(int x, int z) {
        callChunkFunction("onChunkRemoved", x, z);
    }
}
