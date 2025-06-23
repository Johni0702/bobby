package de.johni0702.minecraft.bobby.ext;

import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.light.ChunkLightingView;

public interface GameRendererExt {
    FogRenderer bobby_getSkyFogRenderer();

    static GameRendererExt get(GameRenderer view) {
        return (GameRendererExt) view;
    }
}
