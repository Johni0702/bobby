package de.johni0702.minecraft.bobby.mixin;

import de.johni0702.minecraft.bobby.Bobby;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.SimpleOption;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameOptions.class)
public class GameOptionsMixin {
    @Shadow
    private @Final SimpleOption<Integer> viewDistance;

    @Inject(method = "getClampedViewDistance", at = @At("HEAD"), cancellable = true)
    private void forceClientDistanceWhenBobbyIsActive(CallbackInfoReturnable<Integer> ci) {
        if (Bobby.getInstance().isEnabled()) {
            ci.setReturnValue(this.viewDistance.getValue());
        }
    }

    @ModifyArg(
            method = "<init>",
            slice = @Slice(from = @At(value = "CONSTANT", args = "stringValue=options.renderDistance")),
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/option/SimpleOption$ValidatingIntSliderCallbacks;<init>(IIZ)V", ordinal = 0),
            index = 1
    )
    private int considerBobbyMaxRenderDistanceSetting(int vanillaSetting) {
        int bobbySetting = Bobby.getInstance().getConfig().getMaxRenderDistance();
        return Math.max(vanillaSetting, bobbySetting);
    }
}
