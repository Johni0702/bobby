package de.johni0702.minecraft.bobby.ext;

import net.minecraft.network.packet.s2c.play.LightData;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

public interface WorldChunkExt {
    void bobby_setInitialLightData(@Nullable LightData data);
    @Nullable LightData bobby_getInitialLightData();

    static WorldChunkExt get(WorldChunk chunk) {
        return (chunk instanceof WorldChunkExt) ? (WorldChunkExt) chunk : null;
    }
}
