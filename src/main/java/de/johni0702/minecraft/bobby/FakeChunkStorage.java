package de.johni0702.minecraft.bobby;

import de.johni0702.minecraft.bobby.util.RegionPos;
import io.netty.util.concurrent.DefaultThreadFactory;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FakeChunkStorage extends SimpleRegionStorage {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Map<Path, FakeChunkStorage> active = new HashMap<>();

    public static final Pattern REGION_FILE_PATTERN = Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mca$");

    public static FakeChunkStorage getFor(Path directory, boolean writeable) {
        synchronized (active) {
            return active.computeIfAbsent(directory, f -> new FakeChunkStorage(directory, writeable));
        }
    }

    public static void closeAll() {
        synchronized (active) {
            for (FakeChunkStorage storage : active.values()) {
                try {
                    storage.close();
                } catch (IOException e) {
                    LOGGER.error("Failed to close storage", e);
                }
            }
            active.clear();
        }
    }

    private final Path directory;
    private final boolean writeable;
    private final AtomicBoolean sentUpgradeNotification = new AtomicBoolean();
    @Nullable
    private final LastAccessFile lastAccess;

    private FakeChunkStorage(Path directory, boolean writeable) {
        super(
                new RegionStorageInfo("dummy", Level.OVERWORLD, "bobby"),
                directory,
                Minecraft.getInstance().getFixerUpper(),
                false,
                DataFixTypes.CHUNK
        );

        this.directory = directory;
        this.writeable = writeable;

        LastAccessFile lastAccess = null;
        if (writeable) {
            try {
                Files.createDirectories(directory);

                lastAccess = new LastAccessFile(directory);
            } catch (IOException e) {
                LOGGER.error("Failed to read last_access file:", e);
            }
        }
        this.lastAccess = lastAccess;
    }

    @Override
    public void close() throws IOException {
        super.close();

        if (lastAccess != null) {
            int deleteUnusedRegionsAfterDays = Bobby.getInstance().getConfig().getDeleteUnusedRegionsAfterDays();
            if (deleteUnusedRegionsAfterDays >= 0) {
                for (long entry : lastAccess.pollRegionsOlderThan(deleteUnusedRegionsAfterDays)) {
                    int x = ChunkPos.getX(entry);
                    int z = ChunkPos.getZ(entry);
                    Files.deleteIfExists(directory.resolve("r." + x + "." + z + ".mca"));
                }
            }

            lastAccess.close();
        }
    }

    public void save(ChunkPos pos, CompoundTag chunk) {
        if (lastAccess != null) {
            lastAccess.touchRegion(pos.getRegionX(), pos.getRegionZ());
        }
        write(pos, chunk);
    }

    public CompletableFuture<Optional<CompoundTag>> loadTag(ChunkPos pos) {
        return read(pos).thenApply(maybeNbt -> maybeNbt.map(nbt -> loadTag(pos, nbt)));
    }

    private CompoundTag loadTag(ChunkPos pos, CompoundTag nbt) {
        if (nbt != null && lastAccess != null) {
            lastAccess.touchRegion(pos.getRegionX(), pos.getRegionZ());
        }
        if (nbt != null && nbt.getIntOr("DataVersion", 0) != SharedConstants.getCurrentVersion().dataVersion().version()) {
            if (sentUpgradeNotification.compareAndSet(false, true)) {
                Minecraft client = Minecraft.getInstance();
                client.execute(() -> {
                    Component text = Component.translatable(writeable ? "bobby.upgrade.required" : "bobby.upgrade.fallback_world");
                    client.gui.hud.getChat().addClientSystemMessage(text);
                });
            }
            return null;
        }
        return nbt;
    }

    public static List<RegionPos> getRegions(Path directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .map(REGION_FILE_PATTERN::matcher)
                    .filter(Matcher::matches)
                    .map(it -> new RegionPos(Integer.parseInt(it.group(1)), Integer.parseInt(it.group(2))))
                    .collect(Collectors.toList());
        }
    }

    public void upgrade(ResourceKey<Level> worldKey, BiConsumer<Integer, Integer> progress) throws IOException {
        Optional<Identifier> generatorKey =
                BuiltInRegistries.CHUNK_GENERATOR.getResourceKey(FlatLevelSource.CODEC).map(ResourceKey::identifier);
        CompoundTag contextNbt = ChunkMap.getChunkDataFixContextTag(worldKey, generatorKey);

        List<ChunkPos> chunks = getRegions(directory).stream().flatMap(RegionPos::getContainedChunks).toList();

        AtomicInteger done = new AtomicInteger();
        AtomicInteger total = new AtomicInteger(chunks.size());
        progress.accept(done.get(), total.get());

        IOWorker io = (IOWorker) chunkScanner();

        // We ideally split the actual work of upgrading the chunk NBT across multiple threads, leaving a few for MC
        int workThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
        ExecutorService workExecutor = Executors.newFixedThreadPool(workThreads, new DefaultThreadFactory("bobby-upgrade-worker", true));

        try {
            for (ChunkPos chunkPos : chunks) {
                workExecutor.execute(() -> {
                    CompoundTag nbt;
                    try {
                        nbt = io.loadAsync(chunkPos).join().orElse(null);
                    } catch (CompletionException e) {
                        LOGGER.warn("Error reading chunk " + chunkPos.x() + "/" + chunkPos.z() + ":", e);
                        nbt = null;
                    }

                    if (nbt == null) {
                        progress.accept(done.get(), total.decrementAndGet());
                        return;
                    }

                    // Didn't have this set prior to Bobby 4.0.5 and upgrading from 1.18 to 1.19 wipes light data
                    // from chunks that don't have this set, so we need to set it before we upgrade the chunk.
                    nbt.putBoolean("isLightOn", true);

                    nbt = upgradeChunkTag(nbt, -1, contextNbt, SharedConstants.getCurrentVersion().dataVersion().version());

                    io.store(chunkPos, nbt).join();

                    progress.accept(done.incrementAndGet(), total.get());
                });
            }
        } finally {
            workExecutor.shutdown();
        }

        try {
            //noinspection ResultOfMethodCallIgnored
            workExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        progress.accept(done.get(), total.get());
    }
}
