package de.johni0702.minecraft.bobby.util;

import net.minecraft.util.math.ChunkPos;

import java.util.stream.Stream;

public record RegionPos(int x, int z) {
    public Stream<ChunkPos> getContainedChunks() {
        int baseX = x << 5;
        int baseZ = z << 5;
        ChunkPos[] result = new ChunkPos[32 * 32];
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                result[x * 32 + z] = new ChunkPos(baseX + x, baseZ + z);
            }
        }
        return Stream.of(result);
    }

    public long toLong() {
        return ((long) z << 32) | ((long) x & 0xffffffffL);
    }

    public static RegionPos fromLong(long coords) {
        return new RegionPos((int) (coords << 32 >> 32), (int) (coords >> 32));
    }

    public static RegionPos from(ChunkPos chunkPos) {
        return new RegionPos(chunkPos.getRegionX(), chunkPos.getRegionZ());
    }
}
