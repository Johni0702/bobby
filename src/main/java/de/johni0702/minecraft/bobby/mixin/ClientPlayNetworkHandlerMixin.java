package de.johni0702.minecraft.bobby.mixin;

import de.johni0702.minecraft.bobby.FakeChunk;
import de.johni0702.minecraft.bobby.ext.ClientPlayNetworkHandlerExt;
import de.johni0702.minecraft.bobby.ext.WorldChunkExt;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.LightData;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin implements ClientPlayNetworkHandlerExt {
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


    //
    //
    // Vanilla's chunk builder isn't thread safe wrt to lighting. If a rebuild task is already active (as will
    // frequently be the case due to neighbor updates), it will continue to execute regardless of whether its light
    // column is still enabled.
    // This isn't an issue in principle, if the chunk builder was thread safe and had taken a snapshot of the
    // light data when it was scheduled. However, it is not, it simply accesses the live light data from the
    // builder thread, so we need to delay unloading that, otherwise it'll render the chunk as partially black.
    // We cannot just use the same enqueue method as vanilla because that queue is worked on in chunks, so it is
    // possible that our unload code runs on one frame and the vanilla load code only runs on the next one.
    // Instead, we must merge our runnable with the vanilla runnable and queue them together as a single one.
    //
    //
    @Unique
    private Runnable queuedUnloadFakeLightDataTask;
    @Unique
    private final boolean hasStarlight = FabricLoader.getInstance().isModLoaded("starlight");

    @Override
    public void bobby_queueUnloadFakeLightDataTask(Runnable runnable) {
        if (hasStarlight) {
            // Starlight turns the vanilla enqueueChunkUpdate call into a no-op:
            // https://github.com/PaperMC/Starlight/blob/cca03d62da48e876ac79196bad16864e8a96bbeb/src/main/java/ca/spottedleaf/starlight/mixin/client/multiplayer/ClientPacketListenerMixin.java#L108
            // So we cannot tag our runnable onto the vanilla one because that's never executed.
            // Luckily the reason starlight does this is that it gets rid of all the stupid delaying entirely, so we
            // don't need to delay either.
            runnable.run();
            return;
        }

        if (queuedUnloadFakeLightDataTask != null) {
            // If for some reason this wasn't consumed by [addUnloadFakeLightDataTask], run it immediately, so we don't
            // just leak the light data.
            queuedUnloadFakeLightDataTask.run();
        }
        queuedUnloadFakeLightDataTask = runnable;
    }

    @ModifyArg(method = "onChunkData", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/world/ClientWorld;enqueueChunkUpdate(Ljava/lang/Runnable;)V"))
    private Runnable addUnloadFakeLightDataTask(Runnable vanillaLoadLightDataTask) {
        if (queuedUnloadFakeLightDataTask != null) {
            Runnable unloadTask = queuedUnloadFakeLightDataTask;
            queuedUnloadFakeLightDataTask = null;
            return () -> {
                unloadTask.run();
                vanillaLoadLightDataTask.run();
            };
        }
        return vanillaLoadLightDataTask;
    }
}
