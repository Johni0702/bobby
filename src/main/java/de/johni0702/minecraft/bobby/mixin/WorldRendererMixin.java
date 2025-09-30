package de.johni0702.minecraft.bobby.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import de.johni0702.minecraft.bobby.ext.GameRendererExt;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.fog.FogRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    @Shadow public abstract double getViewDistance();

    @Shadow @Final private MinecraftClient client;

    @ModifyArg(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/WorldRenderer;renderSky(Lnet/minecraft/client/render/FrameGraphBuilder;Lnet/minecraft/client/render/Camera;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"))
    private GpuBufferSlice clampMaxValue(GpuBufferSlice fogBuffer) {
        if (getViewDistance() >= 32) {
            fogBuffer = ((GameRendererExt) client.gameRenderer).bobby_getSkyFogRenderer().getFogBuffer(FogRenderer.FogType.WORLD);
        }
        return fogBuffer;
    }
}
