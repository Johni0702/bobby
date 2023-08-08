package de.johni0702.minecraft.bobby.mixin.sodium_05;

import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = SodiumWorldRenderer.class, priority = 1010) // higher than our normal one
public interface SodiumWorldRendererAccessor {

    @Accessor(remap = false)
    public RenderSectionManager getRenderSectionManager();

    @Accessor(remap = false)
    public ClientWorld getWorld();

}