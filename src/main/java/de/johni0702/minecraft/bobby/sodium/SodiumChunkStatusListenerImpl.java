package de.johni0702.minecraft.bobby.sodium;

import de.johni0702.minecraft.bobby.mixin.sodium.SodiumWorldRendererAcessor;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkStatus;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;

public class SodiumChunkStatusListenerImpl implements ChunkStatusListener {

    private void onChunkLightAdded(int x, int z, SodiumWorldRendererAcessor accessInterface){
        if (accessInterface != null){
            ChunkTrackerHolder.get(accessInterface.getWorld()).onChunkStatusAdded(x,z, ChunkStatus.FLAG_ALL);
        }
    }

    @Override
    public void onChunkAdded(int x, int z) {
        SodiumWorldRenderer sodiumRenderer = SodiumWorldRenderer.instanceNullable();
        if (sodiumRenderer != null) {
            SodiumWorldRendererAcessor accessInterface = (SodiumWorldRendererAcessor) sodiumRenderer;

            accessInterface.getRenderSectionManager().onChunkAdded(x, z);
            onChunkLightAdded(x, z, accessInterface);
        }
    }

    @Override
    public void onChunkRemoved(int x, int z) {
        SodiumWorldRenderer sodiumRenderer = SodiumWorldRenderer.instanceNullable();
        if (sodiumRenderer != null) {
            SodiumWorldRendererAcessor accessInterface = (SodiumWorldRendererAcessor) sodiumRenderer;

            accessInterface.getRenderSectionManager().onChunkRemoved(x, z);
        }
    }
}
