package de.johni0702.minecraft.bobby.mixin;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Shadow private ClientWorld world;

    // If we replaced the real chunk with a fake one before unload() returned,
    // then we want to skip the remainder of this method which would otherwise
    // unload our light maps.
    @Inject(method = "onUnloadChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/world/ClientChunkManager;unload(II)V", shift = At.Shift.AFTER), cancellable = true)
    private void bobbyReplaceChunk(UnloadChunkS2CPacket packet, CallbackInfo ci) {
        if (world.getChunkManager().getChunk(packet.getX(), packet.getZ()) != null) {
            ci.cancel();
        }
    }
}
