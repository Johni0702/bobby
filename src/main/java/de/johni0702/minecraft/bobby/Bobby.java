package de.johni0702.minecraft.bobby;

import ca.stellardrift.confabricate.Confabricate;
import de.johni0702.minecraft.bobby.commands.UpgradeCommand;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v1.ClientCommandManager;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.reference.ConfigurationReference;
import org.spongepowered.configurate.reference.ValueReference;

import static net.fabricmc.fabric.api.client.command.v1.ClientCommandManager.literal;

public class Bobby implements ClientModInitializer {
    public static final String MOD_ID = "bobby";
    { instance = this; }
    private static Bobby instance;
    public static Bobby getInstance() {
        return instance;
    }

    private static final MinecraftClient client = MinecraftClient.getInstance();

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

        ClientCommandManager.DISPATCHER.register(literal("bobby")
                .then(literal("upgrade").executes(new UpgradeCommand()))
        );
    }

    public BobbyConfig getConfig() {
        return configReference != null ? configReference.get() : BobbyConfig.DEFAULT;
    }

    public boolean isEnabled() {
        BobbyConfig config = getConfig();
        return config.isEnabled()
                // For singleplayer, disable ourselves unless the view-distance overwrite is active.
                && (client.getServer() == null || config.getViewDistanceOverwrite() != 0);
    }

    public Screen createConfigScreen(Screen parent) {
        if (FabricLoader.getInstance().isModLoaded("cloth-config2")) {
            return BobbyConfigScreenFactory.createConfigScreen(parent, getConfig(), configReference::setAndSaveAsync);
        }
        return null;
    }
}
