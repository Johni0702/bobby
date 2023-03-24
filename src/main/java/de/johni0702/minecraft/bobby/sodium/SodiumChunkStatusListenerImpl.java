package de.johni0702.minecraft.bobby.sodium;

import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;

public class SodiumChunkStatusListenerImpl implements ChunkStatusListener {
    @Override
    public void onChunkAdded(int x, int z) {
        SodiumWorldRenderer sodiumRenderer = SodiumWorldRenderer.instanceNullable();
        if (sodiumRenderer != null) {
            sodiumRenderer.onChunkAdded(x, z);
            sodiumRenderer.onChunkLightAdded(x, z); // fake chunks have their light ready immediately
        }
    }

    @Override
    public void onChunkRemoved(int x, int z) {
        SodiumWorldRenderer sodiumRenderer = SodiumWorldRenderer.instanceNullable();
        if (sodiumRenderer != null) {
            sodiumRenderer.onChunkRemoved(x, z);
        }
    }
}
