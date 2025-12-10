package de.johni0702.minecraft.bobby;

import com.mojang.serialization.MapCodec;
import de.johni0702.minecraft.bobby.util.RegionPos;
import io.netty.util.concurrent.DefaultThreadFactory;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;
import net.minecraft.world.storage.StorageIoWorker;
import net.minecraft.world.storage.StorageKey;
import net.minecraft.world.storage.VersionedChunkStorage;
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

public class FakeChunkStorage extends VersionedChunkStorage {
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
                new StorageKey("dummy", World.OVERWORLD, "bobby"),
                directory,
                MinecraftClient.getInstance().getDataFixer(),
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
                    int x = ChunkPos.getPackedX(entry);
                    int z = ChunkPos.getPackedZ(entry);
                    Files.deleteIfExists(directory.resolve("r." + x + "." + z + ".mca"));
                }
            }

            lastAccess.close();
        }
    }

    public void save(ChunkPos pos, NbtCompound chunk) {
        if (lastAccess != null) {
            lastAccess.touchRegion(pos.getRegionX(), pos.getRegionZ());
        }
        setNbt(pos, chunk);
    }

    public CompletableFuture<Optional<NbtCompound>> loadTag(ChunkPos pos) {
        return getNbt(pos).thenApply(maybeNbt -> maybeNbt.map(nbt -> loadTag(pos, nbt)));
    }

    private NbtCompound loadTag(ChunkPos pos, NbtCompound nbt) {
        if (nbt != null && lastAccess != null) {
            lastAccess.touchRegion(pos.getRegionX(), pos.getRegionZ());
        }
        if (nbt != null && nbt.getInt("DataVersion", 0) != SharedConstants.getGameVersion().dataVersion().id()) {
            if (sentUpgradeNotification.compareAndSet(false, true)) {
                MinecraftClient client = MinecraftClient.getInstance();
                client.execute(() -> {
                    Text text = Text.translatable(writeable ? "bobby.upgrade.required" : "bobby.upgrade.fallback_world");
                    client.inGameHud.getChatHud().addMessage(text);
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

    public void upgrade(RegistryKey<World> worldKey, BiConsumer<Integer, Integer> progress) throws IOException {
        Optional<RegistryKey<MapCodec<? extends ChunkGenerator>>> generatorKey =
                Optional.of(Registries.CHUNK_GENERATOR.getKey(FlatChunkGenerator.CODEC).orElseThrow());
        NbtCompound contextNbt = ServerChunkLoadingManager.getContextNbt(worldKey, generatorKey);

        List<ChunkPos> chunks = getRegions(directory).stream().flatMap(RegionPos::getContainedChunks).toList();

        AtomicInteger done = new AtomicInteger();
        AtomicInteger total = new AtomicInteger(chunks.size());
        progress.accept(done.get(), total.get());

        StorageIoWorker io = (StorageIoWorker) getWorker();

        // We ideally split the actual work of upgrading the chunk NBT across multiple threads, leaving a few for MC
        int workThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
        ExecutorService workExecutor = Executors.newFixedThreadPool(workThreads, new DefaultThreadFactory("bobby-upgrade-worker", true));

        try {
            for (ChunkPos chunkPos : chunks) {
                workExecutor.execute(() -> {
                    NbtCompound nbt;
                    try {
                        nbt = io.readChunkData(chunkPos).join().orElse(null);
                    } catch (CompletionException e) {
                        LOGGER.warn("Error reading chunk " + chunkPos.x + "/" + chunkPos.z + ":", e);
                        nbt = null;
                    }

                    if (nbt == null) {
                        progress.accept(done.get(), total.decrementAndGet());
                        return;
                    }

                    // Didn't have this set prior to Bobby 4.0.5 and upgrading from 1.18 to 1.19 wipes light data
                    // from chunks that don't have this set, so we need to set it before we upgrade the chunk.
                    nbt.putBoolean("isLightOn", true);

                    nbt = updateChunkNbt(nbt, -1, contextNbt);

                    io.setResult(chunkPos, nbt).join();

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
