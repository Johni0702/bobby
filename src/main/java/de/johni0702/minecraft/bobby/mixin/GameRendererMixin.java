package de.johni0702.minecraft.bobby.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.johni0702.minecraft.bobby.FakeChunkManager;
import de.johni0702.minecraft.bobby.ext.ClientChunkManagerExt;
import de.johni0702.minecraft.bobby.ext.GameRendererExt;
import de.johni0702.minecraft.bobby.util.FlawlessFrames;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.profiler.Profilers;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin implements GameRendererExt {

    @Shadow
    @Final
    private MinecraftClient client;

    @Inject(method = "renderWorld", at = @At("HEAD"))
    private void blockingBobbyUpdate(CallbackInfo ci) {
        if (!FlawlessFrames.isActive()) {
            return;
        }

        ClientWorld world = this.client.world;
        if (world == null) {
            return;
        }

        FakeChunkManager bobbyChunkManager = ((ClientChunkManagerExt) world.getChunkManager()).bobby_getFakeChunkManager();
        if (bobbyChunkManager == null) {
            return;
        }

        Profiler profiler = Profilers.get();
        profiler.push("bobbyUpdate");

        bobbyChunkManager.update(true, () -> true);

        profiler.pop();
    }

    @Unique
    private final FogRenderer skyFogRenderer = new FogRenderer();

    @Override
    public FogRenderer bobby_getSkyFogRenderer() {
        return skyFogRenderer;
    }

    @WrapOperation(method = "renderWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/fog/FogRenderer;applyFog(Lnet/minecraft/client/render/Camera;ILnet/minecraft/client/render/RenderTickCounter;FLnet/minecraft/client/world/ClientWorld;)Lorg/joml/Vector4f;"))
    private Vector4f updateSkyFogRenderer(FogRenderer instance, Camera camera, int viewDistance, RenderTickCounter tickCounter, float skyDarkness, ClientWorld world, Operation<Vector4f> operation) {
        if (viewDistance >= 32) {
            skyFogRenderer.applyFog(camera, 32, tickCounter, skyDarkness, world);
        }
        return operation.call(instance, camera, viewDistance, tickCounter, skyDarkness, world);
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/fog/FogRenderer;rotate()V"))
    private void rotateSkyFogRenderer(CallbackInfo ci) {
        skyFogRenderer.rotate();
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void closeSkyFogRenderer(CallbackInfo ci) {
        skyFogRenderer.close();
    }
}
