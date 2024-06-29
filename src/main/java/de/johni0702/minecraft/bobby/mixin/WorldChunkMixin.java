package de.johni0702.minecraft.bobby.mixin;

import de.johni0702.minecraft.bobby.ext.WorldChunkExt;
import net.minecraft.network.packet.s2c.play.LightData;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(WorldChunk.class)
public class WorldChunkMixin implements WorldChunkExt {
    @Unique
    private LightData initialLightData;

    @Override
    public void bobby_setInitialLightData(@Nullable LightData data) {
        this.initialLightData = data;
    }

    @Override
    public @Nullable LightData bobby_getInitialLightData() {
        return initialLightData;
    }
}
