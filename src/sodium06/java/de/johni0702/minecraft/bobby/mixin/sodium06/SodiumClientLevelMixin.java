package de.johni0702.minecraft.bobby.mixin.sodium06;

import de.johni0702.minecraft.bobby.FakeChunkManager;
import de.johni0702.minecraft.bobby.ext.ClientChunkCacheExt;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientLevel.class, priority = 1100) // higher than Sodium so we get to run after it runs
public abstract class SodiumClientLevelMixin {
    @Shadow
    @Final
    private ClientChunkCache chunkSource;

    @Inject(method = "unload", at = @At("HEAD"))
    private void keepChunkRenderedIfReplacedByFakeChunk(LevelChunk chunk, CallbackInfo ci) {
        FakeChunkManager bobbyChunkManager = ((ClientChunkCacheExt) chunkSource).bobby_getFakeChunkManager();
        if (bobbyChunkManager == null) {
            return;
        }

        int x = chunk.getPos().x();
        int z = chunk.getPos().z();
        // Minecraft's chunk management is a bit broken. It'll immediately stop providing chunks (via `getChunk`) once
        // they're no longer in the view area, but it'll only actually `ClientLevel.unload` those chunks if their slot
        // in the chunks array is needed for a new chunk. And in case of a view distance reduction, it actually never
        // calls `ClientLevel.unload` for any chunks that were still in the old array.
        // As such, we can't really build our system around this method.
        //
        // Sodium's chunk tracking however does inject into it (as otherwise it'd leak all the chunks that become
        // displaced when the view center changes). This results in it un-rendering such chunks, even when we have a
        // fake chunk available for them.
        // To prevent that, when we have a fake chunk ready for the chunk that was just un-loaded, we tell sodium about
        // it again:
        if (bobbyChunkManager.getChunk(x, z) != null) {
            ((ClientChunkCacheExt) chunkSource).bobby_onFakeChunkAdded(x, z);
        }
    }
}
