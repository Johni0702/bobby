package de.johni0702.minecraft.bobby.mixin;

import de.johni0702.minecraft.bobby.Bobby;
import de.johni0702.minecraft.bobby.FakeChunkManager;
import de.johni0702.minecraft.bobby.FakeChunkStorage;
import de.johni0702.minecraft.bobby.IClientChunkManager;
import de.johni0702.minecraft.bobby.compat.IChunkStatusListener;
import de.johni0702.minecraft.bobby.ext.ClientChunkManagerExt;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.world.biome.source.BiomeArray;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientChunkManager.class)
public abstract class ClientChunkManagerMixin implements IClientChunkManager, ClientChunkManagerExt {
    @Shadow @Final private WorldChunk emptyChunk;

    @Shadow @Nullable public abstract WorldChunk getChunk(int i, int j, ChunkStatus chunkStatus, boolean bl);
    @Shadow public abstract LightingProvider getLightingProvider();

    protected FakeChunkManager bobbyChunkManager;
    // Cache of chunk which was just unloaded so we can immediately
    // load it again without having to wait for the storage io worker.
    protected  @Nullable CompoundTag bobbyChunkReplacement;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bobbyInit(ClientWorld world, int loadDistance, CallbackInfo ci) {
        if (Bobby.getInstance().isEnabled()) {
            bobbyChunkManager = new FakeChunkManager(world, (ClientChunkManager) (Object) this);
        }
    }

    @Override
    public FakeChunkManager getBobbyChunkManager() {
        return bobbyChunkManager;
    }

    @Override
    public IChunkStatusListener bobby_getListener() {
        return null;
    }

    @Override
    public void bobby_suppressListener() {
    }

    @Override
    public IChunkStatusListener bobby_restoreListener() {
        return null;
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
            // Suppress the chunk status listener so the chunk mesh does
            // not get removed before it is re-rendered.
            bobby_suppressListener();
        }

        // This needs to be called unconditionally because even if there is no chunk loaded at the moment,
        // we might already have one queued which we need to cancel as otherwise it will overwrite the real one later.
        bobbyChunkManager.unload(x, z, true);
    }

    @Inject(method = "loadChunkFromPacket", at = @At("RETURN"))
    private void bobbyFakeChunkReplaced(int x, int z, BiomeArray biomes, PacketByteBuf buf, CompoundTag tag, int verticalStripBitmask, boolean complete, CallbackInfoReturnable<WorldChunk> cir) {
        IChunkStatusListener listener = bobby_restoreListener();
        if (listener != null) {
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

        // We'll be replacing a fake chunk with a real one.
        // Suppress the chunk status listener so the chunk mesh does
        // not get removed before it is re-rendered.
        bobby_suppressListener();

        FakeChunkStorage storage = bobbyChunkManager.getStorage();
        CompoundTag tag = storage.serialize(chunk, getLightingProvider());
        storage.save(chunk.getPos(), tag);
        bobbyChunkReplacement = tag;
    }

    @Inject(method = "unload", at = @At("RETURN"))
    private void bobbyReplaceChunk(int chunkX, int chunkZ, CallbackInfo ci) {
        if (bobbyChunkManager == null) {
            return;
        }

        CompoundTag tag = bobbyChunkReplacement;
        bobbyChunkReplacement = null;
        if (tag == null) {
            return;
        }
        bobbyChunkManager.load(chunkX, chunkZ, tag, bobbyChunkManager.getStorage());

        bobby_restoreListener();
    }

    @Inject(method = "getDebugString", at = @At("RETURN"), cancellable = true)
    private void bobbyDebugString(CallbackInfoReturnable<String> cir) {
        if (bobbyChunkManager == null) {
            return;
        }

        cir.setReturnValue(cir.getReturnValue() + " " + bobbyChunkManager.getDebugString());
    }
}
