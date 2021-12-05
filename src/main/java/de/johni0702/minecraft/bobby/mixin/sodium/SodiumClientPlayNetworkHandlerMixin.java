package de.johni0702.minecraft.bobby.mixin.sodium;

import de.johni0702.minecraft.bobby.FakeChunk;
import de.johni0702.minecraft.bobby.ext.ClientChunkManagerExt;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientPlayNetworkHandler.class, priority = 900) // lower than Sodium so we get to return before it runs
public abstract class SodiumClientPlayNetworkHandlerMixin implements ClientChunkManagerExt {
    @Shadow
    private ClientWorld world;

    @Inject(method = "onUnloadChunk", at = @At("RETURN"), cancellable = true)
    private void returnEarlyIfReplacedByFakeChunk(UnloadChunkS2CPacket packet, CallbackInfo ci) {
        WorldChunk chunk = this.world.getChunk(packet.getX(), packet.getZ());
        if (chunk instanceof FakeChunk) {
            ci.cancel(); // bypass Sodium's unload hook
        }
    }
}
