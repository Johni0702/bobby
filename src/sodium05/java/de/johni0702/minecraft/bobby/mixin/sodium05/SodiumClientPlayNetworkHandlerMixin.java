package de.johni0702.minecraft.bobby.mixin.sodium05;

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

@Mixin(value = ClientPlayNetworkHandler.class, priority = 1100) // higher than Sodium so we get to run after it runs
public abstract class SodiumClientPlayNetworkHandlerMixin {
    @Shadow
    private ClientWorld world;

    @Inject(method = "onUnloadChunk", at = @At("RETURN"))
    private void keepChunkRenderedIfReplacedByFakeChunk(UnloadChunkS2CPacket packet, CallbackInfo ci) {
        int x = packet.pos().x;
        int z = packet.pos().z;
        WorldChunk chunk = this.world.getChunk(x, z);
        // Sodium removes the block and light flags from the unloaded chunk at the end of this method.
        // We however load our fake chunk at the end of the unload method in ClientChunkManager, so Sodium naturally
        // would get the last word and un-render the chunk. To prevent that, when we have replaced it with a fake chunk,
        // we simply re-add the flags.
        if (chunk instanceof FakeChunk) {
            ((ClientChunkManagerExt) world.getChunkManager()).bobby_onFakeChunkAdded(x, z);
        }
    }
}
