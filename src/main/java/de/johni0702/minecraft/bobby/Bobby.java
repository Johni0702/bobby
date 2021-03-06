package de.johni0702.minecraft.bobby;

import ca.stellardrift.confabricate.Confabricate;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.gui.entries.BooleanListEntry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.TranslatableText;
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

    public Screen createConfigScreen(Screen parent) {
        BobbyConfig defaultConfig = BobbyConfig.DEFAULT;
        BobbyConfig config = getConfig();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(new TranslatableText("title.bobby.config"));


        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        BooleanListEntry enabled = entryBuilder
                .startBooleanToggle(new TranslatableText("option.bobby.enabled"), config.isEnabled())
                .setDefaultValue(defaultConfig.isEnabled())
                .build();

        ConfigCategory general = builder.getOrCreateCategory(new TranslatableText("category.bobby.general"));
        general.addEntry(enabled);

        builder.setSavingRunnable(() -> configReference.setAndSaveAsync(new BobbyConfig(
                enabled.getValue()
        )));

        return builder.build();
    }
}
