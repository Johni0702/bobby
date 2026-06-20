package de.johni0702.minecraft.bobby.mixin;

import de.johni0702.minecraft.bobby.Bobby;
import de.johni0702.minecraft.bobby.FakeChunk;
import de.johni0702.minecraft.bobby.FakeChunkManager;
import de.johni0702.minecraft.bobby.VisibleChunksTracker;
import de.johni0702.minecraft.bobby.ext.ClientChunkCacheExt;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin implements ClientChunkCacheExt {
    @Shadow @Final private LevelChunk emptyChunk;

    @Shadow @Nullable public abstract LevelChunk getChunk(int i, int j, ChunkStatus chunkStatus, boolean bl);
    @Shadow private static int calculateStorageRange(int loadDistance) { throw new AssertionError(); }

    protected FakeChunkManager bobbyChunkManager;

    // Tracks which real chunks are visible (whether or not the were actually received), so we can
    // properly unload (i.e. save and replace with fake) them when the server center pos or view distance changes.
    private final VisibleChunksTracker realChunksTracker = new VisibleChunksTracker();

    // List of real chunks saved just before they are unloaded, so we can restore fake ones in their place afterwards
    private final List<Pair<Long, Supplier<LevelChunk>>> bobbyChunkReplacements = new ArrayList<>();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bobbyInit(ClientLevel world, int loadDistance, CallbackInfo ci) {
        if (Bobby.getInstance().isEnabled()) {
            bobbyChunkManager = new FakeChunkManager(world, (ClientChunkCache) (Object) this);
            realChunksTracker.update(0, 0, calculateStorageRange(loadDistance), null, null);
        }
    }

    @Override
    public FakeChunkManager bobby_getFakeChunkManager() {
        return bobbyChunkManager;
    }

    @Override
    public VisibleChunksTracker bobby_getRealChunksTracker() {
        return realChunksTracker;
    }

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;", at = @At("RETURN"), cancellable = true)
    private void bobbyGetChunk(int x, int z, ChunkStatus chunkStatus, boolean orEmpty, CallbackInfoReturnable<LevelChunk> ci) {
        // Did we find a live chunk?
        if (ci.getReturnValue() != (orEmpty ? emptyChunk : null)) {
            return;
        }

        if (bobbyChunkManager == null) {
            return;
        }

        // Otherwise, see if we've got one
        LevelChunk chunk = bobbyChunkManager.getChunk(x, z);
        if (chunk != null) {
            ci.setReturnValue(chunk);
        }
    }

    @Inject(method = "replaceWithPacketData", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientChunkCache$Storage;getIndex(II)I"))
    private void bobbyUnloadFakeChunk(int x, int z, FriendlyByteBuf buf, Map<Heightmap.Types, long[]> heightmaps, Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer, CallbackInfoReturnable<LevelChunk> cir) {
        if (bobbyChunkManager == null) {
            return;
        }

        // This needs to be called unconditionally because even if there is no chunk loaded at the moment,
        // we might already have one queued which we need to cancel as otherwise it will overwrite the real one later.
        bobbyChunkManager.unload(x, z, true);
    }

    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void bobbyFingerprintRealChunk(CallbackInfoReturnable<LevelChunk> cir) {
        if (bobbyChunkManager == null) {
            return;
        }

        LevelChunk chunk = cir.getReturnValue();
        if (chunk == null) {
            return; // can happen when server sends out-of-bounds chunk
        }
        bobbyChunkManager.fingerprint(chunk);
    }

    @Unique
    private void saveRealChunk(long chunkPos) {
        int chunkX = ChunkPos.getX(chunkPos);
        int chunkZ = ChunkPos.getZ(chunkPos);

        LevelChunk chunk = getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null || chunk instanceof FakeChunk) {
            return;
        }

        Supplier<LevelChunk> copy = bobbyChunkManager.save(chunk);

        if (bobbyChunkManager.shouldBeLoaded(chunkX, chunkZ)) {
            bobbyChunkReplacements.add(Pair.of(chunkPos, copy));
        }
    }

    @Unique
    private void substituteFakeChunksForUnloadedRealOnes() {
        for (Pair<Long, Supplier<LevelChunk>> entry : bobbyChunkReplacements) {
            long chunkPos = entry.getKey();
            int chunkX = ChunkPos.getX(chunkPos);
            int chunkZ = ChunkPos.getZ(chunkPos);
            bobbyChunkManager.load(chunkX, chunkZ, entry.getValue().get());
        }
        bobbyChunkReplacements.clear();
    }

    @Inject(method = "drop", at = @At("HEAD"))
    private void bobbySaveChunk(ChunkPos pos, CallbackInfo ci) {
        if (bobbyChunkManager == null) {
            return;
        }

        saveRealChunk(pos.pack());
    }

    @Inject(method = "updateViewCenter", at = @At("HEAD"))
    private void bobbySaveChunksBeforeMove(int x, int z, CallbackInfo ci) {
        if (bobbyChunkManager == null) {
            return;
        }

        realChunksTracker.updateCenter(x, z, this::saveRealChunk, null);
    }

    @Inject(method = "updateViewRadius", at = @At("HEAD"))
    private void bobbySaveChunksBeforeResize(int loadDistance, CallbackInfo ci) {
        if (bobbyChunkManager == null) {
            return;
        }

        realChunksTracker.updateViewDistance(calculateStorageRange(loadDistance), this::saveRealChunk, null);
    }

    @Inject(method = { "drop", "updateViewCenter", "updateViewRadius" }, at = @At("RETURN"))
    private void bobbySubstituteFakeChunksForUnloadedRealOnes(CallbackInfo ci) {
        if (bobbyChunkManager == null) {
            return;
        }

        substituteFakeChunksForUnloadedRealOnes();
    }

    @Inject(method = "updateViewRadius", at = @At(value = "FIELD", target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;storage:Lnet/minecraft/client/multiplayer/ClientChunkCache$Storage;", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void reAddEmptyFakeChunks(CallbackInfo ci) {
        if (bobbyChunkManager == null) {
            return;
        }

        for (LevelChunk chunk : bobbyChunkManager.getFakeChunks()) {
            ChunkPos pos = chunk.getPos();
            bobbyChunkManager.markAsLoaded(pos.x(), pos.z(), chunk);
        }
    }

    @Inject(method = "gatherStats", at = @At("RETURN"), cancellable = true)
    private void bobbyDebugString(CallbackInfoReturnable<String> cir) {
        if (bobbyChunkManager == null) {
            return;
        }

        cir.setReturnValue(cir.getReturnValue() + " " + bobbyChunkManager.getDebugString());
    }

    @Override
    public void bobby_onFakeChunkAdded(int x, int z) {
        // Vanilla polls for chunks each frame, this is only of interest for Sodium (see SodiumChunkManagerMixin)
    }

    @Override
    public void bobby_onFakeChunkRemoved(int x, int z, boolean willBeReplaced) {
        // Vanilla polls for chunks each frame, this is only of interest for Sodium (see SodiumChunkManagerMixin)
    }
}
