package de.johni0702.minecraft.bobby.mixin;

import de.johni0702.minecraft.bobby.Bobby;
import de.johni0702.minecraft.bobby.FakeChunk;
import de.johni0702.minecraft.bobby.FakeChunkManager;
import de.johni0702.minecraft.bobby.VisibleChunksTracker;
import de.johni0702.minecraft.bobby.ext.ClientChunkManagerExt;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.ChunkData;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
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
import java.util.function.Consumer;
import java.util.function.Supplier;

@Mixin(value = ClientChunkManager.class, priority = 900)
public abstract class ClientChunkManagerMixin implements ClientChunkManagerExt {
    @Shadow @Final private WorldChunk emptyChunk;
    @Shadow @Final ClientWorld world;

    @Shadow @Nullable public abstract WorldChunk getChunk(int i, int j, ChunkStatus chunkStatus, boolean bl);
    @Shadow private static int getChunkMapRadius(int loadDistance) { throw new AssertionError(); }

    protected FakeChunkManager bobbyChunkManager;

    // Tracks which real chunks are visible (whether or not the were actually received), so we can
    // properly unload (i.e. save and replace with fake) them when the server center pos or view distance changes.
    private final VisibleChunksTracker realChunksTracker = new VisibleChunksTracker();

    // List of real chunks saved just before they are unloaded, so we can restore fake ones in their place afterwards
    private final List<Pair<Long, Supplier<WorldChunk>>> bobbyChunkReplacements = new ArrayList<>();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bobbyInit(ClientWorld world, int loadDistance, CallbackInfo ci) {
        if (Bobby.getInstance().isEnabled()) {
            bobbyChunkManager = new FakeChunkManager(world, (ClientChunkManager) (Object) this);
            realChunksTracker.update(0, 0, getChunkMapRadius(loadDistance), null, null);
        }
    }

    @Override
    public FakeChunkManager bobby_getFakeChunkManager() {
        return bobbyChunkManager;
    }

    @Inject(method = "getChunk(IILnet/minecraft/world/chunk/ChunkStatus;Z)Lnet/minecraft/world/chunk/WorldChunk;", at = @At("RETURN"), cancellable = true)
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
    private void bobbyUnloadFakeChunk(int x, int z, PacketByteBuf buf, NbtCompound nbt, Consumer<ChunkData.BlockEntityVisitor> consumer, CallbackInfoReturnable<WorldChunk> cir) {
        if (bobbyChunkManager == null) {
            return;
        }

        bobby_pauseChunkStatusListener();

        // This needs to be called unconditionally because even if there is no chunk loaded at the moment,
        // we might already have one queued which we need to cancel as otherwise it will overwrite the real one later.
        bobbyChunkManager.unload(x, z, true);
    }

    @Inject(method = "loadChunkFromPacket", at = @At("RETURN"))
    private void bobbyPostLoadRealChunk(int x, int z, PacketByteBuf buf, NbtCompound nbt, Consumer<ChunkData.BlockEntityVisitor> consumer, CallbackInfoReturnable<WorldChunk> cir) {
        // Sodium moved this into the ClientPlayNetworkHandler, but I'd rather not move all our stuff there.
        // It looks like it's supposed to be idempotent (and ran even when the chunk fails to parse), so we'll just call
        // it here as well and thereby cancel out the above unload.
        bobby_onFakeChunkAdded(x, z);

        bobby_resumeChunkStatusListener();
    }

    @Unique
    private void saveRealChunk(long chunkPos) {
        int chunkX = ChunkPos.getPackedX(chunkPos);
        int chunkZ = ChunkPos.getPackedZ(chunkPos);

        WorldChunk chunk = getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null || chunk instanceof FakeChunk) {
            return;
        }

        Supplier<WorldChunk> copy = bobbyChunkManager.save(chunk);

        if (bobbyChunkManager.shouldBeLoaded(chunkX, chunkZ)) {
            bobbyChunkReplacements.add(Pair.of(chunkPos, copy));

            bobby_pauseChunkStatusListener();
        }
    }

    @Unique
    private void substituteFakeChunksForUnloadedRealOnes() {
        for (Pair<Long, Supplier<WorldChunk>> entry : bobbyChunkReplacements) {
            long chunkPos = entry.getKey();
            int chunkX = ChunkPos.getPackedX(chunkPos);
            int chunkZ = ChunkPos.getPackedZ(chunkPos);
            bobbyChunkManager.load(chunkX, chunkZ, entry.getValue().get());
        }
        bobbyChunkReplacements.clear();

        bobby_resumeChunkStatusListener();
    }

    @Inject(method = "unload", at = @At("HEAD"))
    private void bobbySaveChunk(int chunkX, int chunkZ, CallbackInfo ci) {
        if (bobbyChunkManager == null) {
            return;
        }

        saveRealChunk(ChunkPos.toLong(chunkX, chunkZ));
    }

    @Inject(method = "setChunkMapCenter", at = @At("HEAD"))
    private void bobbySaveChunksBeforeMove(int x, int z, CallbackInfo ci) {
        if (bobbyChunkManager == null) {
            return;
        }

        realChunksTracker.updateCenter(x, z, this::saveRealChunk, null);
    }

    @Inject(method = "updateLoadDistance", at = @At("HEAD"))
    private void bobbySaveChunksBeforeResize(int loadDistance, CallbackInfo ci) {
        if (bobbyChunkManager == null) {
            return;
        }

        realChunksTracker.updateViewDistance(getChunkMapRadius(loadDistance), this::saveRealChunk, null);
    }

    @Inject(method = { "setChunkMapCenter", "updateLoadDistance" }, at = @At("RETURN"))
    private void bobbySubstituteFakeChunksForUnloadedRealOnes(CallbackInfo ci) {
        if (bobbyChunkManager == null) {
            return;
        }

        substituteFakeChunksForUnloadedRealOnes();
    }

    @Inject(
        method = "unload",
        at = @At(
                value = "INVOKE",
                target = "Lnet/minecraft/client/world/ClientChunkManager$ClientChunkMap;compareAndSet(ILnet/minecraft/world/chunk/WorldChunk;Lnet/minecraft/world/chunk/WorldChunk;)Lnet/minecraft/world/chunk/WorldChunk;",
                shift = At.Shift.AFTER
        ),
        cancellable = true)
    private void returnEarlyIfReplacedByFakeChunk(int chunkX, int chunkZ, CallbackInfo ci) {
        if (bobbyChunkManager == null) {
            return;
        }
        substituteFakeChunksForUnloadedRealOnes();
        // method not inspected, likely it can just immediately call ci.cancel but in case its possible it doesn't i've mirrored the other unload
        WorldChunk chunk = this.world.getChunk(chunkX, chunkZ);
        if (chunk instanceof FakeChunk) {
            ci.cancel(); // bypass Sodium's unload hook
        }
    }

    @Inject(method = "getDebugString", at = @At("RETURN"), cancellable = true)
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
    public void bobby_onFakeChunkRemoved(int x, int z) {
        // Vanilla polls for chunks each frame, this is only of interest for Sodium (see SodiumChunkManagerMixin)
    }

    @Override
    public void bobby_pauseChunkStatusListener() {
        // Vanilla polls for chunks each frame, this is only of interest for Sodium (see SodiumChunkManagerMixin)
    }

    @Override
    public void bobby_resumeChunkStatusListener() {
        // Vanilla polls for chunks each frame, this is only of interest for Sodium (see SodiumChunkManagerMixin)
    }
}
