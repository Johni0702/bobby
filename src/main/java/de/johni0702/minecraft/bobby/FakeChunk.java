package de.johni0702.minecraft.bobby;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.UpgradeData;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.tick.ChunkTickScheduler;

// Fake chunks are of this subclass, primarily so we have an easy way of identifying them.
public class FakeChunk extends WorldChunk {
    public FakeChunk(World world, ChunkPos pos, ChunkSection[] sections) {
        super(world, pos, UpgradeData.NO_UPGRADE_DATA, new ChunkTickScheduler<>(), new ChunkTickScheduler<>(), 0L, sections, null, null);
    }
}
