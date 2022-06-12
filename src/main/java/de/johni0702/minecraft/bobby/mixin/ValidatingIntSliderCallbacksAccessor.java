package de.johni0702.minecraft.bobby.mixin;

import net.minecraft.client.option.SimpleOption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SimpleOption.ValidatingIntSliderCallbacks.class)
public interface ValidatingIntSliderCallbacksAccessor {
    @Accessor
    @Mutable
    void setMaxInclusive(int value);
}
