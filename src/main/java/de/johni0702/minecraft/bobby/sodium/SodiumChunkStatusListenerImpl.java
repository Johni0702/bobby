package de.johni0702.minecraft.bobby.sodium;

import de.johni0702.minecraft.bobby.mixin.sodium_05.SodiumWorldRendererAccessor;
import de.johni0702.minecraft.bobby.util.SodiumVersionChecker;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkStatus;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

import static java.lang.invoke.MethodType.methodType;

public class SodiumChunkStatusListenerImpl implements ChunkStatusListener {

    /**
     * Compatibility class for Sodium 0.5.0
     */
    static public class Sodium05Compat {
        public static void onChunkAdded(SodiumWorldRenderer renderer, int x, int z){
            if(renderer instanceof SodiumWorldRendererAccessor accessor){
                accessor.getRenderSectionManager().onChunkAdded(x, z);
            }
        }

        public static void onChunkLightAdded(SodiumWorldRenderer renderer, int x, int z){
            if(renderer instanceof SodiumWorldRendererAccessor accessor){
                ChunkTrackerHolder.get(accessor.getWorld()).onChunkStatusAdded(x,z, ChunkStatus.FLAG_ALL);
            }
        }

        public static void onChunkRemoved(SodiumWorldRenderer renderer, int x, int z){
            if(renderer instanceof SodiumWorldRendererAccessor accessor){
                accessor.getRenderSectionManager().onChunkRemoved(x, z);
            }
        }
    }

    /**
     * Get a method handle for a chunk function in the given compatibility class.
     * I needs to eiter be a static method with the signature (SodiumWorldRenderer, int, int)void
     * or a function with the signature (int, int)void which is called on the given SodiumWorldRenderer instance.
     * @param compatClass The compatibility class to get the method from
     * @param methodName The name of the method
     * @param isStatic Whether the method is static or not
     * @return A method handle for the given method
     */
    private static MethodHandle getMethodHandle(Class<?> compatClass, String methodName, boolean isStatic){
        try{
            if(isStatic){
                return MethodHandles.lookup().findStatic(compatClass, methodName, methodType(void.class, SodiumWorldRenderer.class, int.class, int.class));
            }else{
                return MethodHandles.lookup().findVirtual(compatClass, methodName, methodType(void.class, int.class, int.class));
            }
        }catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    private static final MethodHandle ON_CHUNK_ADDED;
    private static final MethodHandle ON_CHUNK_LIGHT_ADDED;
    private static final MethodHandle ON_CHUNK_REMOVED;

    static {
        Class<?> compatibilityClass;
        boolean isStatic = false;

        try {
            if (SodiumVersionChecker.sodiumVersion == SodiumVersionChecker.SodiumVersions.Sodium0_4) {
                // in versions before 0.5.0 the methods are availabe in the SodiumWorldRenderer class
                // so static stays false, and we use the SodiumWorldRenderer class
                compatibilityClass = SodiumWorldRenderer.class;
            }else if (SodiumVersionChecker.sodiumVersion == SodiumVersionChecker.SodiumVersions.Sodium0_5){
                // in version 0.5.0 the compatibility class has to be used.
                // the methods are static so we set isStatic to true
                compatibilityClass = Sodium05Compat.class;
                isStatic = true;
            }else{
                throw new RuntimeException("Trying to use sodiumChunkStatusListener with an unsupported Sodium version.");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // get the method handles for all needed methods
        try{
            ON_CHUNK_ADDED = getMethodHandle(compatibilityClass, "onChunkAdded", isStatic);
            ON_CHUNK_LIGHT_ADDED = getMethodHandle(compatibilityClass, "onChunkLightAdded", isStatic);
            ON_CHUNK_REMOVED = getMethodHandle(compatibilityClass, "onChunkRemoved", isStatic);
        }catch (Exception e) {
            throw new RuntimeException("Sodium compatibility methods not found.", e);
        }

    }

    @Override
    public void onChunkAdded(int x, int z) {
        SodiumWorldRenderer sodiumRenderer = SodiumWorldRenderer.instanceNullable();
        if (sodiumRenderer != null) {
            try {
                ON_CHUNK_ADDED.invoke(sodiumRenderer, x, z);
                ON_CHUNK_LIGHT_ADDED.invoke(sodiumRenderer, x, z);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void onChunkRemoved(int x, int z) {
        SodiumWorldRenderer sodiumRenderer = SodiumWorldRenderer.instanceNullable();
        if (sodiumRenderer != null) {
            try {
                ON_CHUNK_REMOVED.invoke(sodiumRenderer, x, z);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
    }
}
