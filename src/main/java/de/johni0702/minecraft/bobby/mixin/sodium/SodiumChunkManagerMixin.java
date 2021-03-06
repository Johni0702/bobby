package de.johni0702.minecraft.bobby.mixin.sodium;

import de.johni0702.minecraft.bobby.FakeChunkManager;
import de.johni0702.minecraft.bobby.FakeChunkStorage;
import de.johni0702.minecraft.bobby.mixin.ClientChunkManagerMixin;
import me.jellysquid.mods.sodium.client.world.ChunkStatusListener;
import me.jellysquid.mods.sodium.client.world.SodiumChunkManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.world.biome.source.BiomeArray;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SodiumChunkManager.class)
public abstract class SodiumChunkManagerMixin extends ClientChunkManagerMixin {
    @Shadow(remap = false) @Final private WorldChunk emptyChunk;
    @Shadow(remap = false) private ChunkStatusListener listener;
    @Shadow public abstract WorldChunk getChunk(int x, int z, ChunkStatus status, boolean create);

    private ChunkStatusListener suppressedListener;

    @Override
    protected FakeChunkManager createBobbyChunkManager(ClientWorld world) {
        return new FakeChunkManager.Sodium(world, (SodiumChunkManager) (Object) this);
    }

    @Inject(method = "getChunk", at = @At("RETURN"), cancellable = true)
    private void bobbyGetChunk(int x, int z, ChunkStatus chunkStatus, boolean orEmpty, CallbackInfoReturnable<WorldChunk> ci) {
        // Did we find a live chunk?
        if (ci.getReturnValue() != (orEmpty ? emptyChunk : null)) {
            return;
        }

        if (bobbyChunkManager == null) {
            return;
        }

        // Otherwise, see if we've got one
        WorldChunk chunk = bobbyChunkManager.getChunk(x, z);
        if (chunk != null) {
            ci.setReturnValue(chunk);
        }
    }

    @Inject(method = "loadChunkFromPacket", at = @At("HEAD"))
    private void bobbyUnloadFakeChunk(int x, int z, BiomeArray biomes, PacketByteBuf buf, CompoundTag tag, int verticalStripBitmask, boolean complete, CallbackInfoReturnable<WorldChunk> cir) {
        if (bobbyChunkManager == null) {
            return;
        }

        if (bobbyChunkManager.getChunk(x, z) != null) {
            // We'll be replacing a fake chunk with a real one.
            // Suppress the chunk status listener so it does
            // get removed before its re-rendered.
            suppressedListener = listener;
            listener = null;

            bobbyChunkManager.unload(x, z, true);
        }
    }

    @Inject(method = "loadChunkFromPacket", at = @At("RETURN"))
    private void bobbyFakeChunkReplaced(int x, int z, BiomeArray biomes, PacketByteBuf buf, CompoundTag tag, int verticalStripBitmask, boolean complete, CallbackInfoReturnable<WorldChunk> cir) {
        if (suppressedListener != null) {
            listener = suppressedListener;
            suppressedListener = null;

            // However, if we failed to load the chunk from the packet for whatever reason,
            // we need to notify the listener that the chunk has indeed been unloaded.
            if (getChunk(x, z, ChunkStatus.FULL, false) == null) {
                listener.onChunkRemoved(x, z);
            }
        }
    }

    @Inject(method = "unload", at = @At("HEAD"))
    private void bobbySaveChunk(int chunkX, int chunkZ, CallbackInfo ci) {
        if (bobbyChunkManager == null) {
            return;
        }

        WorldChunk chunk = getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null) {
            return;
        }

        // We'll be replacing a real chunk with a fake one.
        // Suppress the chunk status listener so it does
        // get removed before its re-rendered.
        suppressedListener = listener;
        listener = null;

        FakeChunkStorage storage = bobbyChunkManager.getStorage();
        CompoundTag tag = storage.serialize(chunk, getLightingProvider());
        storage.save(chunk.getPos(), tag);
        bobbyChunkReplacement = tag;
    }

    @Inject(method = "unload", at = @At("RETURN"))
    private void bobbyReplaceChunk(int chunkX, int chunkZ, CallbackInfo ci) {
        CompoundTag tag = bobbyChunkReplacement;
        bobbyChunkReplacement = null;
        if (tag == null) {
            return;
        }
        bobbyChunkManager.load(chunkX, chunkZ, tag, bobbyChunkManager.getStorage());

        if (suppressedListener != null) {
            listener = suppressedListener;
            suppressedListener = null;
        }
    }

    @Inject(method = "getDebugString", at = @At("RETURN"), cancellable = true)
    private void bobbyDebugString(CallbackInfoReturnable<String> cir) {
        if (bobbyChunkManager == null) {
            return;
        }

        cir.setReturnValue(cir.getReturnValue() + " " + bobbyChunkManager.getDebugString());
    }
}
