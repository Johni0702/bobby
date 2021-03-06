package de.johni0702.minecraft.bobby.mixin;

import de.johni0702.minecraft.bobby.Bobby;
import net.minecraft.server.integrated.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {
    @ModifyArg(method = "tick", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I"), index = 1)
    private int bobbyViewDistanceOverwrite(int viewDistance) {
        int overwrite = Bobby.getInstance().getConfig().getViewDistanceOverwrite();
        if (overwrite != 0) {
            viewDistance = overwrite;
        }
        return viewDistance;
    }
}
