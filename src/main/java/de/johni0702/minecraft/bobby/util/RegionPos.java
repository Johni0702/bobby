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

    @Override
    public int hashCode() {
        return x ^ Integer.rotateRight(z, 16);
    }

    // The standard `Long.hashCode` results in close to worst-case performance with packet coordinates because it
    // simply boils down to `x ^ z` and so 7/0 has the same hash code (7) as 6/1, 5/2, 4/3, 3/4, 2/5, 1/6 and 0/7.
    public static int hashCode(long coords) {
        int x = (int) (coords << 32 >> 32);
        int z = (int) (coords >> 32);
        return x ^ Integer.rotateRight(z, 16);
    }
}
