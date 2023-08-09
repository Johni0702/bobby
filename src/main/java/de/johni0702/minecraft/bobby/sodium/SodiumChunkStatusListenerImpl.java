package de.johni0702.minecraft.bobby.sodium;

import de.johni0702.minecraft.bobby.mixin.sodium_05.SodiumWorldRendererAccessor;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkStatus;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

import static java.lang.invoke.MethodType.methodType;

public class SodiumChunkStatusListenerImpl implements ChunkStatusListener {

    @FunctionalInterface
    interface chunkFunctionInterface {
        void invoke(SodiumWorldRenderer renderer, int x, int z);

        static chunkFunctionInterface findVirtual(String funcName){
            try{
                MethodHandle handle = MethodHandles.lookup().findVirtual(SodiumWorldRenderer.class, funcName, methodType(void.class, int.class, int.class));
                return (SodiumWorldRenderer renderer, int x, int z) -> {
                    try{
                        handle.invoke(renderer, x, z);
                    }catch (Throwable t){
                        throw new RuntimeException(t);
                    }
                };
            }catch (NoSuchMethodException | IllegalAccessException e){
                throw new RuntimeException(e);
            }
        }
    }

    private static final chunkFunctionInterface ON_CHUNK_ADDED;
    private static final chunkFunctionInterface ON_CHUNK_LIGHT_ADDED;
    private static final chunkFunctionInterface ON_CHUNK_REMOVED;

    static {
        chunkFunctionInterface ON_CHUNK_ADDED_TMP;
        chunkFunctionInterface ON_CHUNK_LIGHT_ADDED_TMP;
        chunkFunctionInterface ON_CHUNK_REMOVED_TMP;

        Version sodiumVersion = FabricLoader.getInstance().getModContainer("sodium").get().getMetadata().getVersion();

        // sodium 0.4
        try {
            if (sodiumVersion.compareTo(Version.parse("0.5.0")) < 0){
                try{
                    ON_CHUNK_ADDED_TMP = chunkFunctionInterface.findVirtual("onChunkAdded");
                    ON_CHUNK_LIGHT_ADDED_TMP = chunkFunctionInterface.findVirtual("onChunkLightAdded");
                    ON_CHUNK_REMOVED_TMP = chunkFunctionInterface.findVirtual("onChunkRemoved");
                }catch (Exception e){
                    throw new RuntimeException("Sodium 0.4 is present, but Sodium 0.4 integration methods could not be found!", e);
                }

            }else{
                ON_CHUNK_ADDED_TMP = (SodiumWorldRenderer sodiumRenderer, int x, int z) -> {
                    try{
                        if (sodiumRenderer instanceof SodiumWorldRendererAccessor accessor) {
                            accessor.getRenderSectionManager().onChunkAdded(x, z);
                        }
                    }catch (Exception e){
                        throw new RuntimeException(e);
                    }
                };
                ON_CHUNK_LIGHT_ADDED_TMP = (SodiumWorldRenderer sodiumRenderer, int x, int z) -> {
                    try{
                        if (sodiumRenderer instanceof SodiumWorldRendererAccessor accessor) {
                            ChunkTrackerHolder.get(accessor.getWorld()).onChunkStatusAdded(x,z, ChunkStatus.FLAG_ALL);
                        }
                    }catch (Exception e){
                        throw new RuntimeException(e);
                    }
                };
                ON_CHUNK_REMOVED_TMP = (SodiumWorldRenderer sodiumRenderer, int x, int z) -> {
                    try{
                        if (sodiumRenderer instanceof SodiumWorldRendererAccessor accessor) {
                            accessor.getRenderSectionManager().onChunkRemoved(x, z);
                        }
                    }catch (Exception e){
                        throw new RuntimeException(e);
                    }
                };
            }

        } catch (VersionParsingException e) {
            throw new RuntimeException(e);
        }

        ON_CHUNK_ADDED = ON_CHUNK_ADDED_TMP;
        ON_CHUNK_LIGHT_ADDED = ON_CHUNK_LIGHT_ADDED_TMP;
        ON_CHUNK_REMOVED = ON_CHUNK_REMOVED_TMP;
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
