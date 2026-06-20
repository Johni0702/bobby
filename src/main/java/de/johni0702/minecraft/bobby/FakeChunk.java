package de.johni0702.minecraft.bobby;

import de.johni0702.minecraft.bobby.ext.LightEngineExt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.ticks.LevelChunkTicks;
import org.jetbrains.annotations.Nullable;

// Fake chunks are of this subclass, primarily so we have an easy way of identifying them.
public class FakeChunk extends LevelChunk {

    private boolean isTainted;

    // Keeping these around, so we can safely serialize the chunk from any thread
    public DataLayer[] blockLight;
    public DataLayer[] skyLight;
    public ListTag serializedBlockEntities;

    public FakeChunk(Level world, ChunkPos pos, LevelChunkSection[] sections) {
        super(world, pos, UpgradeData.EMPTY, new LevelChunkTicks<>(), new LevelChunkTicks<>(), 0L, sections, null, null);
    }

    public void setTainted(boolean enabled) {
        if (isTainted == enabled) {
            return;
        }
        isTainted = enabled;

        Minecraft client = Minecraft.getInstance();
        double gamma = client.options.gamma().get();
        LevelRenderer worldRenderer = client.levelRenderer;

        LevelLightEngine lightingProvider = getLevel().getLightEngine();
        LightEngineExt blockLightProvider = LightEngineExt.get(lightingProvider.getLayerListener(LightLayer.BLOCK));
        LightEngineExt skyLightProvider = LightEngineExt.get(lightingProvider.getLayerListener(LightLayer.SKY));

        int blockDelta = enabled ? 5 : 0;
        int skyDelta = enabled ? -3 + (int) (-7 * gamma) : 0;

        int x = getPos().x();
        int z = getPos().z();
        for (int y = getMinSectionY(); y < getMaxSectionY(); y++) {
            updateTaintedState(blockLightProvider, x, y, z, blockDelta);
            updateTaintedState(skyLightProvider, x, y, z, skyDelta);
            client.levelExtractor.setSectionDirtyWithNeighbors(x, y, z);
        }
    }

    private void updateTaintedState(LightEngineExt lightProvider, int x, int y, int z, int delta) {
        if (lightProvider == null) {
            return;
        }
        lightProvider.bobby_setTainted(SectionPos.asLong(x, y, z), delta);
    }

    public void setHeightmap(Heightmap.Types type, Heightmap heightmap) {
        this.heightmaps.put(type, heightmap);
    }

    @Override
    public @Nullable BlockState setBlockState(BlockPos pos, BlockState state, int flags) {
        // This should never be called for fake chunks, but some server incorrectly send block updates for chunks
        // they just unloaded, which can then result in a race condition between this update and the background thread
        // which serializes the chunk.
        // See https://github.com/Johni0702/bobby/issues/341
        return null;
    }
}
