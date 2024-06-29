package de.johni0702.minecraft.bobby.mixin;

import de.johni0702.minecraft.bobby.FakeChunk;
import de.johni0702.minecraft.bobby.ext.WorldChunkExt;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.LightData;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    private @Shadow ClientWorld world;

    //
    //
    // MC doesn't actually load the light data until the next frame (or potentially even next 10 frames when many
    // chunks are queued), so if the chunk is unloaded before that happens, our code which is supposed to substitute it
    // with a fake chunk won't be able to find any light data for it, and so the fake chunk it creates won't have proper
    // lighting.
    // This mixin solves that by synchronously storing the initial light data in the created chunk instance, where our
    // fake chunk creation code can then get it from in such cases.
    //
    //
    @Inject(method = "onChunkData", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayNetworkHandler;loadChunk(IILnet/minecraft/network/packet/s2c/play/ChunkData;)V", shift = At.Shift.AFTER))
    private void storeInitialLightData(ChunkDataS2CPacket packet, CallbackInfo ci) {
        int chunkX = packet.getChunkX();
        int chunkZ = packet.getChunkZ();
        WorldChunk chunk = this.world.getChunkManager().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null || chunk instanceof FakeChunk) {
            return; // failed to load, ignore
        }
        WorldChunkExt.get(chunk).bobby_setInitialLightData(packet.getLightData());
    }

    // Once MC does actually load the light data, we can drop our manually kept light data, so it can be GCed.
    @Inject(method = "readLightData", at = @At("HEAD"))
    private void storeInitialLightData(int chunkX, int chunkZ, LightData data, CallbackInfo ci) {
        WorldChunk chunk = this.world.getChunkManager().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null || chunk instanceof FakeChunk) {
            return; // already unloaded, nothing to do
        }
        WorldChunkExt.get(chunk).bobby_setInitialLightData(null);
    }
}
