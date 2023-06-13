package de.johni0702.minecraft.bobby.mixin;

import de.johni0702.minecraft.bobby.ext.LightingProviderExt;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.chunk.light.LightingProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LightingProvider.class)
public abstract class LightingProviderMixin implements LightingProviderExt {
    @Unique
    private final LongSet bobbyActiveColumns = new LongOpenHashSet();

    @Override
    public void bobby_enabledColumn(long pos) {
        this.bobbyActiveColumns.add(pos);
    }

    @Override
    public void bobby_disableColumn(long pos) {
        this.bobbyActiveColumns.remove(pos);
    }

    @Inject(method = "isLightingEnabled", at = @At("HEAD"), cancellable = true)
    private void bobby_getLightSection(ChunkSectionPos pos, CallbackInfoReturnable<Boolean> ci) {
        if (bobbyActiveColumns.contains(pos.toChunkPos().toLong())) {
            ci.setReturnValue(true);
        }
    }
}
