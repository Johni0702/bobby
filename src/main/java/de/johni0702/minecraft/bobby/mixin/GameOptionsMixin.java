package de.johni0702.minecraft.bobby.mixin;

import de.johni0702.minecraft.bobby.Bobby;
import net.minecraft.client.option.GameOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameOptions.class)
public class GameOptionsMixin {
    @Shadow
    public int viewDistance;

    @ModifyArg(method = "<init>",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/option/DoubleOption;setMax(F)V", ordinal = 0),
            slice = @Slice(from = @At(value = "FIELD", target = "Lnet/minecraft/client/option/Option;RENDER_DISTANCE:Lnet/minecraft/client/option/DoubleOption;", ordinal = 0)))
    private float extendMaxRenderDistance(float orgDistance) {
        return Math.max(orgDistance, Bobby.getInstance().getConfig().getMaxRenderDistance());
    }

    @Inject(method = "getViewDistance", at = @At("HEAD"), cancellable = true)
    private void forceClientDistanceWhenBobbyIsActive(CallbackInfoReturnable<Integer> ci) {
        if (Bobby.getInstance().isEnabled()) {
            ci.setReturnValue(this.viewDistance);
        }
    }
}
