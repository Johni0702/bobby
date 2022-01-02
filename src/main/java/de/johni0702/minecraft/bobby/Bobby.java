package de.johni0702.minecraft.bobby;

import ca.stellardrift.confabricate.Confabricate;
import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import de.johni0702.minecraft.bobby.commands.UpgradeCommand;
import de.johni0702.minecraft.bobby.ext.ClientChunkManagerExt;
import de.johni0702.minecraft.bobby.util.FlawlessFrames;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v1.ClientCommandManager;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Util;
import net.minecraft.world.chunk.WorldChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.reference.ConfigurationReference;
import org.spongepowered.configurate.reference.ValueReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.fabricmc.fabric.api.client.command.v1.ClientCommandManager.literal;

public class Bobby implements ClientModInitializer {
    private static final Logger LOGGER = LogManager.getLogger();

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

        FlawlessFrames.onClientInitialization();

        configReference.subscribe(new TaintChunksConfigHandler()::update);

        Util.getIoWorkerExecutor().execute(this::cleanupOldWorlds);
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

    private void cleanupOldWorlds() {
        int deleteUnusedRegionsAfterDays = Bobby.getInstance().getConfig().getDeleteUnusedRegionsAfterDays();
        if (deleteUnusedRegionsAfterDays < 0) {
            return;
        }

        Path basePath = client.runDirectory.toPath().resolve(".bobby");

        List<Path> toBeDeleted;
        try (Stream<Path> stream = Files.walk(basePath, 4)) {
            toBeDeleted = stream
                    .filter(it -> basePath.relativize(it).getNameCount() == 4)
                    .filter(it -> {
                        try {
                            return LastAccessFile.isEverythingOlderThan(it, deleteUnusedRegionsAfterDays);
                        } catch (IOException e) {
                            LOGGER.error("Failed to read last used file in " + it + ":", e);
                            return false;
                        }
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            LOGGER.error("Failed to index bobby cache for cleanup:", e);
            return;
        }

        for (Path path : toBeDeleted) {
            try {
                //noinspection UnstableApiUsage
                MoreFiles.deleteRecursively(path, RecursiveDeleteOption.ALLOW_INSECURE);

                deleteParentsIfEmpty(path);
            } catch (IOException e) {
                LOGGER.error("Failed to delete " + path + ":", e);
            }
        }
    }

    private static void deleteParentsIfEmpty(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent == null) {
            return;
        }
        try (Stream<Path> stream = Files.list(parent)) {
            if (stream.findAny().isPresent()) {
                return;
            }
        }
        Files.delete(parent);
        deleteParentsIfEmpty(parent);
    }

    private class TaintChunksConfigHandler {
        private boolean wasEnabled = getConfig().isTaintFakeChunks();

        public void update(BobbyConfig config) {
            client.submit(() -> setEnabled(config.isTaintFakeChunks()));
        }

        private void setEnabled(boolean enabled) {
            if (wasEnabled == enabled) {
                return;
            }
            wasEnabled = enabled;

            ClientWorld world = client.world;
            if (world == null) {
                return;
            }

            FakeChunkManager bobbyChunkManager = ((ClientChunkManagerExt) world.getChunkManager()).bobby_getFakeChunkManager();
            if (bobbyChunkManager == null) {
                return;
            }

            for (WorldChunk fakeChunk : bobbyChunkManager.getFakeChunks()) {
                ((FakeChunk) fakeChunk).setTainted(enabled);
            }
        }
    }
}
