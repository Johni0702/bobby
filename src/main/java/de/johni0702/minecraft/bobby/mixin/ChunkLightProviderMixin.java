package de.johni0702.minecraft.bobby.mixin;

import de.johni0702.minecraft.bobby.ext.ChunkLightProviderExt;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.light.ChunkLightProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ChunkLightProvider.class, targets = {
        "ca.spottedleaf.starlight.common.light.StarLightInterface$1",
        "ca.spottedleaf.starlight.common.light.StarLightInterface$2"
})
public abstract class ChunkLightProviderMixin implements ChunkLightProviderExt {
    private final Long2ObjectMap<ChunkNibbleArray> bobbySectionData = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());

    @Override
    public void bobby_addSectionData(long pos, ChunkNibbleArray data) {
        this.bobbySectionData.put(pos, data);
    }

    @Override
    public void bobby_removeSectionData(long pos) {
        this.bobbySectionData.remove(pos);
    }

    @Inject(method = "getLightSection(Lnet/minecraft/util/math/ChunkSectionPos;)Lnet/minecraft/world/chunk/ChunkNibbleArray;", at = @At("HEAD"), cancellable = true)
    private void bobby_getLightSection(ChunkSectionPos pos, CallbackInfoReturnable<ChunkNibbleArray> ci) {
        ChunkNibbleArray data = this.bobbySectionData.get(pos.asLong());
        if (data != null) {
            ci.setReturnValue(data);
        }
    }

    @Inject(method = "getLightLevel(Lnet/minecraft/util/math/BlockPos;)I", at = @At("HEAD"), cancellable = true)
    private void bobby_getLightSection(BlockPos blockPos, CallbackInfoReturnable<Integer> ci) {
        ChunkNibbleArray data = this.bobbySectionData.get(ChunkSectionPos.from(blockPos).asLong());
        if (data != null) {
            ci.setReturnValue(data.get(
                    ChunkSectionPos.getLocalCoord(blockPos.getX()),
                    ChunkSectionPos.getLocalCoord(blockPos.getY()),
                    ChunkSectionPos.getLocalCoord(blockPos.getZ())
            ));
        }
    }
}
