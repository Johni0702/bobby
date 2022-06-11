package de.johni0702.minecraft.bobby;

import de.johni0702.minecraft.bobby.ext.ChunkLightProviderExt;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.UpgradeData;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import net.minecraft.world.tick.ChunkTickScheduler;

// Fake chunks are of this subclass, primarily so we have an easy way of identifying them.
public class FakeChunk extends WorldChunk {

    private boolean isTainted;

    public FakeChunk(World world, ChunkPos pos, ChunkSection[] sections) {
        super(world, pos, UpgradeData.NO_UPGRADE_DATA, new ChunkTickScheduler<>(), new ChunkTickScheduler<>(), 0L, sections, null, null);
    }

    public void setTainted(boolean enabled) {
        if (isTainted == enabled) {
            return;
        }
        isTainted = enabled;

        MinecraftClient client = MinecraftClient.getInstance();
        double gamma = client.options.getGamma().getValue();
        WorldRenderer worldRenderer = client.worldRenderer;

        LightingProvider lightingProvider = getWorld().getLightingProvider();
        ChunkLightProviderExt blockLightProvider = ChunkLightProviderExt.get(lightingProvider.get(LightType.BLOCK));
        ChunkLightProviderExt skyLightProvider = ChunkLightProviderExt.get(lightingProvider.get(LightType.SKY));

        int blockDelta = enabled ? 5 : 0;
        int skyDelta = enabled ? -3 + (int) (-7 * gamma) : 0;

        int x = getPos().x;
        int z = getPos().z;
        for (int y = getBottomSectionCoord(); y < getTopSectionCoord(); y++) {
            updateTaintedState(blockLightProvider, x, y, z, blockDelta);
            updateTaintedState(skyLightProvider, x, y, z, skyDelta);
            worldRenderer.scheduleBlockRender(x, y, z);
        }
    }

    private void updateTaintedState(ChunkLightProviderExt lightProvider, int x, int y, int z, int delta) {
        if (lightProvider == null) {
            return;
        }
        lightProvider.bobby_setTainted(ChunkSectionPos.asLong(x, y, z), delta);
    }
}
