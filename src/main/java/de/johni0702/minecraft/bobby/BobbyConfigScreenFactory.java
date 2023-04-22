package de.johni0702.minecraft.bobby;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.gui.entries.BooleanListEntry;
import me.shedaniel.clothconfig2.gui.entries.IntegerListEntry;
import me.shedaniel.clothconfig2.gui.entries.IntegerSliderEntry;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import java.util.function.Consumer;

public class BobbyConfigScreenFactory {
    public static Screen createConfigScreen(Screen parent, BobbyConfig config, Consumer<BobbyConfig> update) {
        BobbyConfig defaultConfig = BobbyConfig.DEFAULT;

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.translatable("title.bobby.config"));


        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        BooleanListEntry enabled = entryBuilder
                .startBooleanToggle(Text.translatable("option.bobby.enabled"), config.isEnabled())
                .setDefaultValue(defaultConfig.isEnabled())
                .build();

        BooleanListEntry dynamicMultiWorld = entryBuilder
                .startBooleanToggle(Text.translatable("option.bobby.dynamic_multi_world"), config.isDynamicMultiWorld())
                .setTooltip(Text.translatable("tooltip.option.bobby.dynamic_multi_world"))
                .setDefaultValue(defaultConfig.isDynamicMultiWorld())
                .build();

        BooleanListEntry noBlockEntities = entryBuilder
                .startBooleanToggle(Text.translatable("option.bobby.no_block_entities"), config.isNoBlockEntities())
                .setTooltip(Text.translatable("tooltip.option.bobby.no_block_entities"))
                .setDefaultValue(defaultConfig.isNoBlockEntities())
                .build();

        BooleanListEntry taintFakeChunks = entryBuilder
                .startBooleanToggle(Text.translatable("option.bobby.taint_fake_chunks"), config.isTaintFakeChunks())
                .setTooltip(Text.translatable("tooltip.option.bobby.taint_fake_chunks"))
                .setDefaultValue(defaultConfig.isTaintFakeChunks())
                .build();

        IntegerListEntry unloadDelaySecs = entryBuilder
                .startIntField(Text.translatable("option.bobby.unload_delay"), config.getUnloadDelaySecs())
                .setTooltip(Text.translatable("tooltip.option.bobby.unload_delay"))
                .setDefaultValue(defaultConfig.getUnloadDelaySecs())
                .build();

        IntegerListEntry deleteUnusedRegionsAfterDays = entryBuilder
                .startIntField(Text.translatable("option.bobby.delete_unused_regions_after_days"), config.getDeleteUnusedRegionsAfterDays())
                .setTooltip(Text.translatable("tooltip.option.bobby.delete_unused_regions_after_days"))
                .setDefaultValue(defaultConfig.getDeleteUnusedRegionsAfterDays())
                .build();

        IntegerListEntry maxRenderDistance = entryBuilder
                .startIntField(Text.translatable("option.bobby.max_render_distance"), config.getMaxRenderDistance())
                .setTooltip(Text.translatable("tooltip.option.bobby.max_render_distance"))
                .setDefaultValue(defaultConfig.getMaxRenderDistance())
                .build();

        IntegerSliderEntry viewDistanceOverwrite = entryBuilder
                .startIntSlider(Text.translatable("option.bobby.view_distance_overwrite"), config.getViewDistanceOverwrite(), 0, 32)
                .setTooltip(Text.translatable("tooltip.option.bobby.view_distance_overwrite"))
                .setDefaultValue(defaultConfig.getViewDistanceOverwrite())
                .build();

        ConfigCategory general = builder.getOrCreateCategory(Text.translatable("category.bobby.general"));
        general.addEntry(enabled);
        general.addEntry(dynamicMultiWorld);
        general.addEntry(noBlockEntities);
        general.addEntry(taintFakeChunks);
        general.addEntry(unloadDelaySecs);
        general.addEntry(deleteUnusedRegionsAfterDays);
        general.addEntry(maxRenderDistance);
        general.addEntry(viewDistanceOverwrite);

        builder.setSavingRunnable(() -> update.accept(new BobbyConfig(
                enabled.getValue(),
                dynamicMultiWorld.getValue(),
                noBlockEntities.getValue(),
                taintFakeChunks.getValue(),
                unloadDelaySecs.getValue(),
                deleteUnusedRegionsAfterDays.getValue(),
                maxRenderDistance.getValue(),
                viewDistanceOverwrite.getValue()
        )));

        return builder.build();
    }
}
