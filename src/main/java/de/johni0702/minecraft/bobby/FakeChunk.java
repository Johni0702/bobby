package de.johni0702.minecraft.bobby;

import net.minecraft.client.world.DummyClientTickScheduler;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.source.BiomeArray;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.UpgradeData;
import net.minecraft.world.chunk.WorldChunk;

// Fake chunks are of this subclass, primarily so we have an easy way of identifying them.
public class FakeChunk extends WorldChunk {
    public FakeChunk(World world, ChunkPos pos, BiomeArray biomes, ChunkSection[] sections) {
        super(world, pos, biomes, UpgradeData.NO_UPGRADE_DATA, DummyClientTickScheduler.get(), DummyClientTickScheduler.get(), 0L, sections, null);
    }
}
