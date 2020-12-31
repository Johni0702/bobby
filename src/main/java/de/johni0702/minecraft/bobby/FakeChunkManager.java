package de.johni0702.minecraft.bobby;

import de.johni0702.minecraft.bobby.mixin.BiomeAccessAccessor;
import de.johni0702.minecraft.bobby.mixin.sodium.SodiumChunkManagerAccessor;
import io.netty.util.concurrent.DefaultThreadFactory;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.jellysquid.mods.sodium.client.world.ChunkStatusListener;
import me.jellysquid.mods.sodium.client.world.SodiumChunkManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.resource.FileResourcePackProvider;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.resource.ServerResourceManager;
import net.minecraft.resource.VanillaDataPackProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.Pair;
import net.minecraft.util.Util;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.registry.DynamicRegistryManager;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.util.thread.ReentrantThreadExecutor;
import net.minecraft.world.SaveProperties;
import net.minecraft.world.World;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import net.minecraft.world.level.storage.LevelStorage;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

public class FakeChunkManager {
    private static final String FALLBACK_LEVEL_NAME = "bobby-fallback";
    private static final MinecraftClient client = MinecraftClient.getInstance();

    private final ClientWorld world;
    private final ClientChunkManager clientChunkManager;
    private final FakeChunkStorage storage;
    private final @Nullable FakeChunkStorage fallbackStorage;

    private final Long2ObjectMap<WorldChunk> fakeChunks = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());
    private LongSet knownMissing = new LongOpenHashSet();

    // There unfortunately is only a synchronous api for loading chunks (even though that one just waits on a
    // CompletableFuture, annoying but oh well), so we call that blocking api from a separate thread pool.
    // The size of the pool must be sufficiently large such that there is always at least one query operation
    // running, as otherwise the storage io worker will start writing chunks which slows everything down to a crawl.
    private static final ExecutorService loadExecutor = Executors.newFixedThreadPool(8, new DefaultThreadFactory("bobby-loading", true));
    private final Long2ObjectMap<LoadingJob> loadingJobs = new Long2ObjectOpenHashMap<>();

    public FakeChunkManager(ClientWorld world, ClientChunkManager clientChunkManager) {
        this.world = world;
        this.clientChunkManager = clientChunkManager;

        long seedHash = ((BiomeAccessAccessor) world.getBiomeAccess()).getSeed();
        RegistryKey<World> worldKey = world.getRegistryKey();
        Identifier worldId = worldKey.getValue();
        Path storagePath = client.runDirectory
                .toPath()
                .resolve(".bobby")
                .resolve(getCurrentWorldOrServerName())
                .resolve(seedHash + "")
                .resolve(worldId.getNamespace())
                .resolve(worldId.getPath());

        storage = new FakeChunkStorage(storagePath.toFile(), null);

        FakeChunkStorage fallbackStorage = null;
        LevelStorage levelStorage = client.getLevelStorage();
        if (levelStorage.levelExists(FALLBACK_LEVEL_NAME)) {
            try (LevelStorage.Session session = levelStorage.createSession(FALLBACK_LEVEL_NAME)) {
                File worldDirectory = session.getWorldDirectory(worldKey);
                File regionDirectory = new File(worldDirectory, "region");
                fallbackStorage = new FakeChunkStorage(regionDirectory, getBiomeSource(session));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        this.fallbackStorage = fallbackStorage;
    }

    public WorldChunk getChunk(int x, int z) {
        return fakeChunks.get(ChunkPos.toLong(x, z));
    }

    public FakeChunkStorage getStorage() {
        return storage;
    }

    public void update(BooleanSupplier shouldKeepTicking) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }
        // Iterate through all chunks in view distance, try to load all we can and figure out which one can be unloaded.
        int centerX = player.chunkX;
        int centerZ = player.chunkZ;
        int viewDistance = client.options.viewDistance;
        LongSet missing = new LongOpenHashSet(knownMissing.size());
        LongSet toBeUnloaded = new LongOpenHashSet(fakeChunks.keySet());
        LongSet toBeCancelled = new LongOpenHashSet(loadingJobs.keySet());
        for (int x = centerX - viewDistance; x <= centerX + viewDistance; x++) {
            for (int z = centerZ - viewDistance; z <= centerZ + viewDistance; z++) {
                long chunkPos = ChunkPos.toLong(x, z);

                // We want this chunk, so don't unload it
                toBeUnloaded.remove(chunkPos);

                // If there already is a chunk loaded, there's nothing to do
                if (clientChunkManager.getChunk(x, z, ChunkStatus.FULL, false) != null) {
                    continue;
                }

                // If we already know that we don't have the given chunk, there's nothing we can do
                if (knownMissing.contains(chunkPos)) {
                    missing.add(chunkPos);
                    continue;
                }

                // Otherwise we'll try loading it, but only if we've still got time to do so
                if (!shouldKeepTicking.getAsBoolean()) {
                    // if there's no more time, then just return immediately.
                    // Do not pass go. Do not store the partial missing set. Do not unload chunks based on partial data.
                    client.getProfiler().pop();
                    return;
                }

                // We want this chunk to be loaded
                toBeCancelled.remove(chunkPos);

                LoadingJob loadingJob = loadingJobs.get(chunkPos);

                // If we have not yet scheduled the chunk for loading, do so now.
                if (loadingJob == null) {
                    loadingJobs.put(chunkPos, loadingJob = new LoadingJob(x, z));
                    loadExecutor.execute(loadingJob);
                }

                //noinspection OptionalAssignedToNull
                if (loadingJob.result == null) {
                    continue; // still loading
                }

                // Done loading
                loadingJobs.remove(chunkPos);

                client.getProfiler().push("loadFakeChunk");
                Optional<WorldChunk> chunk = loadingJob.complete();
                if (!chunk.isPresent()) {
                    missing.add(chunkPos);
                    knownMissing.add(chunkPos); // add it to this set as well in case we run out of time
                }
                client.getProfiler().pop();
            }
        }
        this.knownMissing = missing;

        // Anything remaining in the set is no longer needed and can now be unloaded
        for (long chunkPos : toBeUnloaded) {
            unload(ChunkPos.getPackedX(chunkPos), ChunkPos.getPackedZ(chunkPos));
        }
        // Any jobs remaining in this set are no longer needed and can now be cancelled
        for (long chunkPos : toBeCancelled){
            LoadingJob loadingJob = loadingJobs.remove(chunkPos);
            if (loadingJob != null) {
                loadingJob.cancelled = true;
            }
        }
    }

    private @Nullable Pair<CompoundTag, FakeChunkStorage> loadTag(int x, int z) {
        ChunkPos chunkPos = new ChunkPos(x, z);
        CompoundTag tag;
        try {
            tag = storage.loadTag(chunkPos);
            if (tag != null) {
                return new Pair<>(tag, storage);
            }
            if (fallbackStorage != null) {
                tag = fallbackStorage.loadTag(chunkPos);
                if (tag != null) {
                    return new Pair<>(tag, fallbackStorage);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public @Nullable WorldChunk load(int x, int z, CompoundTag tag, FakeChunkStorage storage) {
        ChunkPos chunkPos = new ChunkPos(x, z);
        WorldChunk chunk = storage.deserialize(chunkPos, tag, world);
        if (chunk == null) {
            return null;
        }

        fakeChunks.put(ChunkPos.toLong(x, z), chunk);

        LightingProvider lightingProvider = clientChunkManager.getLightingProvider();
        lightingProvider.setColumnEnabled(chunkPos, true);
        lightingProvider.setRetainData(chunkPos, false);

        int y = 0;
        for (ChunkSection section : chunk.getSectionArray()) {
            lightingProvider.setSectionStatus(ChunkSectionPos.from(x, y, z), ChunkSection.isEmpty(section));
            y++;
        }

        world.resetChunkColor(x, z);

        for (int i = 0; i < 16; i++) {
            world.scheduleBlockRenders(x, i, z);
        }

        return chunk;
    }

    public void unload(int x, int z) {
        WorldChunk chunk = fakeChunks.remove(ChunkPos.toLong(x, z));
        if (chunk != null) {
            LightingProvider lightingProvider = clientChunkManager.getLightingProvider();

            for (int y = 0; y < 16; y++) {
                world.scheduleBlockRenders(x, y, z);
                lightingProvider.setSectionStatus(ChunkSectionPos.from(x, y, z), true);
            }

            lightingProvider.setColumnEnabled(new ChunkPos(x, z), false);
            world.unloadBlockEntities(chunk);
        }
    }

    private static String getCurrentWorldOrServerName() {
        IntegratedServer integratedServer = client.getServer();
        if (integratedServer != null) {
            return integratedServer.getSaveProperties().getLevelName();
        }

        ServerInfo serverInfo = client.getCurrentServerEntry();
        if (serverInfo != null) {
            return serverInfo.address;
        }

        if (client.isConnectedToRealms()) {
            return "realms";
        }

        return "unknown";
    }

    private static BiomeSource getBiomeSource(LevelStorage.Session session) throws ExecutionException, InterruptedException {
        // How difficult could this possibly be? Oh, right, datapacks are a thing
        // Mostly puzzled this together from how MinecraftClient starts the integrated server.
        try (ResourcePackManager resourcePackManager = new ResourcePackManager(
                new VanillaDataPackProvider(),
                new FileResourcePackProvider(session.getDirectory(WorldSavePath.DATAPACKS).toFile(), ResourcePackSource.PACK_SOURCE_WORLD)
        )) {
            DataPackSettings dataPackSettings = MinecraftServer.loadDataPacks(resourcePackManager, MinecraftClient.method_29598(session), false);
            // We need our own executor, cause the MC one already has lots of packets in it
            Thread thread = Thread.currentThread();
            ReentrantThreadExecutor<Runnable> executor = new ReentrantThreadExecutor<Runnable>("") {
                @Override
                protected Runnable createTask(Runnable runnable) {
                    return runnable;
                }

                @Override
                protected boolean canExecute(Runnable task) {
                    return true;
                }

                @Override
                protected Thread getThread() {
                    return thread;
                }
            };
            CompletableFuture<ServerResourceManager> completableFuture = ServerResourceManager.reload(
                    resourcePackManager.createResourcePacks(),
                    CommandManager.RegistrationEnvironment.INTEGRATED,
                    2,
                    Util.getMainWorkerExecutor(),
                    executor
            );
            executor.runTasks(completableFuture::isDone);
            ServerResourceManager serverResourceManager = completableFuture.get();
            ResourceManager resourceManager = serverResourceManager.getResourceManager();
            DynamicRegistryManager.Impl registryTracker = DynamicRegistryManager.create();
            SaveProperties saveProperties = MinecraftClient.createSaveProperties(session, registryTracker, resourceManager, dataPackSettings);
            return saveProperties.getGeneratorOptions().getChunkGenerator().getBiomeSource();
        }
    }

    public String getDebugString() {
        return "F: " + fakeChunks.size() + " M: " + knownMissing.size() + " L: " + loadingJobs.size();
    }

    private class LoadingJob implements Runnable {
        private final int x;
        private final int z;
        private volatile boolean cancelled;
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType") // null while loading, empty() if no chunk was found
        private volatile Optional<Pair<CompoundTag, FakeChunkStorage>> result;

        public LoadingJob(int x, int z) {
            this.x = x;
            this.z = z;
        }

        @Override
        public void run() {
            if (cancelled) {
                return;
            }
            result = Optional.ofNullable(loadTag(x, z));
        }

        public Optional<WorldChunk> complete() {
            return result.map(it -> load(x, z, it.getLeft(), it.getRight()));
        }
    }

    public static class Sodium extends FakeChunkManager {

        private final SodiumChunkManagerAccessor sodiumChunkManager;

        public Sodium(ClientWorld world, SodiumChunkManager sodiumChunkManager) {
            super(world, sodiumChunkManager);
            this.sodiumChunkManager = (SodiumChunkManagerAccessor) sodiumChunkManager;
        }

        @Override
        public @Nullable WorldChunk load(int x, int z, CompoundTag tag, FakeChunkStorage storage) {
            WorldChunk chunk = super.load(x, z, tag, storage);

            if (chunk != null) {
                ChunkStatusListener listener = sodiumChunkManager.getListener();
                if (listener != null) {
                    listener.onChunkAdded(x, z);
                }
            }

            return chunk;
        }

        @Override
        public void unload(int x, int z) {
            super.unload(x, z);

            ChunkStatusListener listener = sodiumChunkManager.getListener();
            if (listener != null) {
                listener.onChunkRemoved(x, z);
            }
        }
    }
}
