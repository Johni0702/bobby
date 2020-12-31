package de.johni0702.minecraft.bobby.mixin.sodium;

import de.johni0702.minecraft.bobby.FakeChunkManager;
import de.johni0702.minecraft.bobby.FakeChunkStorage;
import de.johni0702.minecraft.bobby.mixin.ClientChunkManagerMixin;
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
    @Shadow @Final private WorldChunk emptyChunk;

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

        // Otherwise, see if we've got one
        WorldChunk chunk = bobbyChunkManager.getChunk(x, z);
        if (chunk != null) {
            ci.setReturnValue(chunk);
        }
    }

    @Inject(method = "loadChunkFromPacket", at = @At("HEAD"))
    private void bobbyUnloadFakeChunk(int x, int z, BiomeArray biomes, PacketByteBuf buf, CompoundTag tag, int verticalStripBitmask, boolean complete, CallbackInfoReturnable<WorldChunk> cir) {
        bobbyChunkManager.unload(x, z);
    }

    @Inject(method = "unload", at = @At("HEAD"))
    private void bobbySaveChunk(int chunkX, int chunkZ, CallbackInfo ci) {
        WorldChunk chunk = getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null) {
            return;
        }
        FakeChunkStorage storage = bobbyChunkManager.getStorage();
        CompoundTag tag = storage.serialize(chunk, getLightingProvider());
        storage.save(chunk.getPos(), tag);
        bobbyChunkReplacement = tag;
    }

    @Inject(method = "unload", at = @At("RETURN"))
    private void bobbyReplaceChunk(int chunkX, int chunkZ, CallbackInfo ci) {
        CompoundTag tag = bobbyChunkReplacement;
        bobbyChunkReplacement = null;
        if (tag == null || bobbyChunkManager.getChunk(chunkX, chunkZ) != null) {
            return;
        }
        bobbyChunkManager.load(chunkX, chunkZ, tag, bobbyChunkManager.getStorage());
    }

    @Inject(method = "getDebugString", at = @At("RETURN"), cancellable = true)
    private void bobbyDebugString(CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(cir.getReturnValue() + " " + bobbyChunkManager.getDebugString());
    }
}
