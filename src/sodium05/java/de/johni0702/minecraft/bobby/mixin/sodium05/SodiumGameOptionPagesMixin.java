package de.johni0702.minecraft.bobby.mixin.sodium05;

import de.johni0702.minecraft.bobby.Bobby;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptionPages;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(value = SodiumGameOptionPages.class, remap = false)
public abstract class SodiumGameOptionPagesMixin {
    @ModifyConstant(method = "lambda$general$0", constant = @Constant(intValue = 32))
    private static int bobbyMaxRenderDistance(int oldValue) {
        int overwrite = Bobby.getInstance().getConfig().getMaxRenderDistance();
        return Math.max(3, overwrite);
    }
}
