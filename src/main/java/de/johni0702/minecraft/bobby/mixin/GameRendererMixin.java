package de.johni0702.minecraft.bobby.mixin;

import de.johni0702.minecraft.bobby.FakeChunkManager;
import de.johni0702.minecraft.bobby.ext.ClientChunkManagerExt;
import de.johni0702.minecraft.bobby.util.FlawlessFrames;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

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

        this.client.getProfiler().push("bobbyUpdate");

        bobbyChunkManager.update(true, () -> true);

        this.client.getProfiler().pop();
    }
}
