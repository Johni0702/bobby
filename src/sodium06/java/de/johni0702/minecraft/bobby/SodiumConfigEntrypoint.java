package de.johni0702.minecraft.bobby;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.minecraft.util.Identifier;

import java.util.Optional;

public class SodiumConfigEntrypoint implements ConfigEntryPoint {
    private static final Identifier SODIUM_RENDER_DISTANCE = Identifier.of("sodium:general.render_distance");

    @Override
    public void registerConfigLate(ConfigBuilder configBuilder) {
        // Workaround for https://github.com/IrisShaders/Iris/issues/2935
        Optional<ModContainer> iris = FabricLoader.getInstance().getModContainer("iris");
        if (iris.isPresent()) {
            Version brokenVersion;
            try {
                brokenVersion = Version.parse("1.10.0");
            } catch (VersionParsingException e) {
                throw new RuntimeException(e);
            }
            if (iris.get().getMetadata().getVersion().compareTo(brokenVersion) <= 0) {
                return;
            }
        }

        configBuilder.registerOwnModOptions()
                .registerOptionOverlay(SODIUM_RENDER_DISTANCE, configBuilder
                        .createIntegerOption(SODIUM_RENDER_DISTANCE)
                        .setRangeProvider(state -> {
                            int overwrite = Bobby.getInstance().getConfig().getMaxRenderDistance();
                            return new Range(2, Math.max(3, overwrite), 1);
                        }, ConfigState.UPDATE_ON_REBUILD)
                );
    }
}
