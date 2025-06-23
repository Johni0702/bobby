package de.johni0702.minecraft.bobby.mixin;

import net.minecraft.client.render.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(FogRenderer.class)
public abstract class BackgroundRendererMixin {
    @ModifyVariable(method = "getFogColor", at = @At("HEAD"), argsOnly = true)
    private int clampMaxValue(int viewDistance) {
        return Math.min(viewDistance, 32);
    }
}
