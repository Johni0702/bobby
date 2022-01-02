package de.johni0702.minecraft.bobby.mixin;

import de.johni0702.minecraft.bobby.Bobby;
import net.minecraft.client.option.GameOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;

@Mixin(GameOptions.class)
public class GameOptionsMixin {
    @ModifyArg(method = "<init>",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/option/DoubleOption;setMax(F)V", ordinal = 0),
            slice = @Slice(from = @At(value = "FIELD", target = "Lnet/minecraft/client/option/Option;RENDER_DISTANCE:Lnet/minecraft/client/option/DoubleOption;", ordinal = 0)))
    private float extendMaxRenderDistance(float orgDistance) {
        return Math.max(orgDistance, Bobby.getInstance().getConfig().getMaxRenderDistance());
    }
}
