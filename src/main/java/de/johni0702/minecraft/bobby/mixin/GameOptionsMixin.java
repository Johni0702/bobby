package de.johni0702.minecraft.bobby.mixin;

import de.johni0702.minecraft.bobby.Bobby;
import net.minecraft.client.option.GameOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameOptions.class)
public class GameOptionsMixin {
    @Shadow
    public int viewDistance;

    @Inject(method = "getViewDistance", at = @At("HEAD"), cancellable = true)
    private void forceClientDistanceWhenBobbyIsActive(CallbackInfoReturnable<Integer> ci) {
        if (Bobby.getInstance().isEnabled()) {
            ci.setReturnValue(this.viewDistance);
        }
    }
}
