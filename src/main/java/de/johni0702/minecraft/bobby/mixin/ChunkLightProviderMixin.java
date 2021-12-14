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
    private final Long2ObjectMap<ChunkNibbleArray> bobbyOriginalSectionData = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());

    @Override
    public void bobby_addSectionData(long pos, ChunkNibbleArray data) {
        this.bobbySectionData.put(pos, data);
        this.bobbyOriginalSectionData.remove(pos);
    }

    @Override
    public void bobby_removeSectionData(long pos) {
        this.bobbySectionData.remove(pos);
        this.bobbyOriginalSectionData.remove(pos);
    }

    @Override
    public void bobby_setTainted(long pos, int delta) {
        if (delta != 0) {
            ChunkNibbleArray original = this.bobbyOriginalSectionData.get(pos);
            if (original == null) {
                original = this.bobbySectionData.get(pos);
                if (original == null) {
                    return;
                }
                this.bobbyOriginalSectionData.put(pos, original);
            }

            ChunkNibbleArray updated = new ChunkNibbleArray();

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        updated.set(x, y, z, Math.min(Math.max(original.get(x, y, z) + delta, 0), 15));
                    }
                }
            }

            this.bobbySectionData.put(pos, updated);
        } else {
            ChunkNibbleArray original = this.bobbyOriginalSectionData.remove(pos);
            if (original == null) {
                return;
            }
            bobbySectionData.put(pos, original);
        }
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
