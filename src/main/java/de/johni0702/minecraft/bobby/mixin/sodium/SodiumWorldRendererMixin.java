package de.johni0702.minecraft.bobby.mixin.sodium;

import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = SodiumWorldRenderer.class, priority = 1010) // higher than our normal one
public abstract class SodiumWorldRendererMixin {
    @Shadow private RenderSectionManager renderSectionManager;

    @Unique
    public void onChunkAdded(int x, int z){
        if (this.renderSectionManager != null)
            this.renderSectionManager.onChunkAdded(x, z);
    }

    @Unique
    public void onChunkLightAdded(int x, int z){
        return;
    }

    @Unique
    public void onChunkRemoved(int x, int z){
        if (this.renderSectionManager != null)
            this.renderSectionManager.onChunkRemoved(x, z);
    }
}