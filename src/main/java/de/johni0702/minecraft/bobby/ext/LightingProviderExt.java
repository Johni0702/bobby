package de.johni0702.minecraft.bobby.ext;

import net.minecraft.world.chunk.light.LightingProvider;

public interface LightingProviderExt {
    void bobby_enabledColumn(long pos);
    void bobby_disableColumn(long pos);

    static LightingProviderExt get(LightingProvider provider) {
        return (provider instanceof LightingProviderExt) ? (LightingProviderExt) provider : null;
    }
}
