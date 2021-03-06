package de.johni0702.minecraft.bobby;

import ca.stellardrift.confabricate.Confabricate;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.reference.ConfigurationReference;
import org.spongepowered.configurate.reference.ValueReference;

public class Bobby implements ClientModInitializer {
    public static final String MOD_ID = "bobby";
    { instance = this; }
    private static Bobby instance;
    public static Bobby getInstance() {
        return instance;
    }

    private final ModContainer modContainer = FabricLoader.getInstance().getModContainer(Bobby.MOD_ID).orElseThrow(RuntimeException::new);
    private ValueReference<BobbyConfig, CommentedConfigurationNode> configReference;

    @Override
    public void onInitializeClient() {
        try {
            ConfigurationReference<CommentedConfigurationNode> rootRef = Confabricate.configurationFor(modContainer, false);
            configReference = rootRef.referenceTo(BobbyConfig.class);
            rootRef.saveAsync();
        } catch (ConfigurateException e) {
            e.printStackTrace();
        }
    }

    public BobbyConfig getConfig() {
        return configReference != null ? configReference.get() : BobbyConfig.DEFAULT;
    }
}
