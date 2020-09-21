package de.johni0702.minecraft.bobby.mixin;

import de.johni0702.minecraft.bobby.FakeChunkManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.profiler.Profiler;
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

import java.util.function.BooleanSupplier;

@Mixin(ClientChunkManager.class)
public abstract class ClientChunkManagerMixin {
	@Shadow @Final private WorldChunk emptyChunk;

	@Shadow @Nullable public abstract WorldChunk getChunk(int i, int j, ChunkStatus chunkStatus, boolean bl);
	@Shadow public abstract LightingProvider getLightingProvider();

	private FakeChunkManager bobbyChunkManager;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void bobbyInit(ClientWorld world, int loadDistance, CallbackInfo ci) {
		bobbyChunkManager = new FakeChunkManager(world, (ClientChunkManager) (Object) this);
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
		bobbyChunkManager.getStorage().save(chunk, getLightingProvider());
	}

	@Inject(method = "unload", at = @At("RETURN"))
	private void bobbyReplaceChunk(int chunkX, int chunkZ, CallbackInfo ci) {
		if (getChunk(chunkX, chunkX, ChunkStatus.FULL, false) == null) {
			bobbyChunkManager.load(chunkX, chunkZ);
		}
	}

	@Inject(method = "tick", at = @At("HEAD"))
	private void bobbyTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
		Profiler profiler = MinecraftClient.getInstance().getProfiler();
		profiler.push("checkFakeChunks");
		bobbyChunkManager.update(shouldKeepTicking);
		profiler.pop();
	}
}
