package de.johni0702.minecraft.bobby.mixin;

import de.johni0702.minecraft.bobby.Bobby;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameOptions.class)
public class GameOptionsMixin {
    @Mutable
    @Final
    @Shadow
    private SimpleOption<Integer> viewDistance;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bobbyInit(CallbackInfo ci) {
        // TODO: Make this better
        this.viewDistance = new SimpleOption<>("options.renderDistance", SimpleOption.emptyTooltip(), (optionText, value) -> GameOptions.getGenericValueText(optionText, Text.translatable("options.chunks", value)), new SimpleOption.ValidatingIntSliderCallbacks(2, Bobby.getInstance().getConfig().getMaxRenderDistance()), 12, value -> MinecraftClient.getInstance().worldRenderer.scheduleTerrainUpdate());
    }


    @Inject(method = "getViewDistance", at = @At("HEAD"), cancellable = true)
    private void forceClientDistanceWhenBobbyIsActive(CallbackInfoReturnable<SimpleOption<Integer>> ci) {
        if (Bobby.getInstance().isEnabled()) {
            ci.setReturnValue(this.viewDistance);
        }
    }
}
