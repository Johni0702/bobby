package de.johni0702.minecraft.bobby;

import de.johni0702.minecraft.bobby.ext.ChunkLightProviderExt;
import de.johni0702.minecraft.bobby.ext.ClientChunkManagerExt;
import de.johni0702.minecraft.bobby.mixin.BiomeAccessAccessor;
import de.johni0702.minecraft.bobby.mixin.LightingProviderAccessor;
import io.netty.util.concurrent.DefaultThreadFactory;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.resource.FileResourcePackProvider;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.resource.ResourceType;
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
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.level.storage.LevelStorage;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class FakeChunkManager {
    private static final String FALLBACK_LEVEL_NAME = "bobby-fallback";
    private static final MinecraftClient client = MinecraftClient.getInstance();

    private final ClientWorld world;
    private final ClientChunkManager clientChunkManager;
    private final ClientChunkManagerExt clientChunkManagerExt;
    private final FakeChunkStorage storage;
    private final @Nullable FakeChunkStorage fallbackStorage;
    private int ticksSinceLastSave;

    private final Long2ObjectMap<WorldChunk> fakeChunks = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());
    private final VisibleChunksTracker chunkTracker = new VisibleChunksTracker();
    private final Long2LongMap toBeUnloaded = new Long2LongOpenHashMap();
    // Contains chunks in order to be unloaded. We keep the chunk and time so we can cross-reference it with
    // [toBeUnloaded] to see if the entry has since been removed / the time reset. This way we do not need
    // to remove entries from the middle of the queue.
    private final Deque<Pair<Long, Long>> unloadQueue = new ArrayDeque<>();

    // There unfortunately is only a synchronous api for loading chunks (even though that one just waits on a
    // CompletableFuture, annoying but oh well), so we call that blocking api from a separate thread pool.
    // The size of the pool must be sufficiently large such that there is always at least one query operation
    // running, as otherwise the storage io worker will start writing chunks which slows everything down to a crawl.
    private static final ExecutorService loadExecutor = Executors.newFixedThreadPool(8, new DefaultThreadFactory("bobby-loading", true));
    private final Long2ObjectMap<LoadingJob> loadingJobs = new Long2ObjectOpenHashMap<>();

    public FakeChunkManager(ClientWorld world, ClientChunkManager clientChunkManager) {
        this.world = world;
        this.clientChunkManager = clientChunkManager;
        this.clientChunkManagerExt = (ClientChunkManagerExt) clientChunkManager;

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

        storage = FakeChunkStorage.getFor(storagePath);

        FakeChunkStorage fallbackStorage = null;
        LevelStorage levelStorage = client.getLevelStorage();
        if (levelStorage.levelExists(FALLBACK_LEVEL_NAME)) {
            try (LevelStorage.Session session = levelStorage.createSession(FALLBACK_LEVEL_NAME)) {
                Path worldDirectory = session.getWorldDirectory(worldKey);
                Path regionDirectory = worldDirectory.resolve("region");
                fallbackStorage = FakeChunkStorage.getFor(regionDirectory);
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
        // Once a minute, force chunks to disk
        if (++ticksSinceLastSave > 20 * 60) {
            // completeAll is blocking, so we run it on the io pool
            Util.getIoWorkerExecutor().execute(storage::completeAll);

            ticksSinceLastSave = 0;
        }

        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }

        BobbyConfig config = Bobby.getInstance().getConfig();
        long time = Util.getMeasuringTimeMs();

        ChunkPos playerChunkPos = player.getChunkPos();
        int newCenterX =  playerChunkPos.x;
        int newCenterZ = playerChunkPos.z;
        int newViewDistance = client.options.viewDistance;
        chunkTracker.update(newCenterX, newCenterZ, newViewDistance, chunkPos -> {
            // Chunk is now outside view distance, can be unloaded / cancelled
            cancelLoad(chunkPos);
            toBeUnloaded.put(chunkPos, time);
            unloadQueue.add(new Pair<>(chunkPos, time));
        }, chunkPos -> {
            // Chunk is now inside view distance, load it
            int x = ChunkPos.getPackedX(chunkPos);
            int z = ChunkPos.getPackedZ(chunkPos);

            // We want this chunk, so don't unload it if it's still here
            toBeUnloaded.remove(chunkPos);
            // Not removing it from [unloadQueue], we check [toBeUnloaded] when we poll it.

            // If there already is a chunk loaded, there's nothing to do
            if (clientChunkManager.getChunk(x, z, ChunkStatus.FULL, false) != null) {
                return;
            }

            // All good, load it
            LoadingJob loadingJob = new LoadingJob(x, z);
            loadingJobs.put(chunkPos, loadingJob);
            loadExecutor.execute(loadingJob);
        });

        // Anything remaining in the set is no longer needed and can now be unloaded
        long unloadTime = time - config.getUnloadDelaySecs() * 1000L;
        int countSinceLastThrottleCheck = 0;
        while (true) {
            Pair<Long, Long> next = unloadQueue.pollFirst();
            if (next == null) {
                break;
            }
            long chunkPos = next.getLeft();
            long queuedTime = next.getRight();

            if (queuedTime > unloadTime) {
                // Unload is still being delayed, put the entry back into the queue
                // and be done for this update.
                unloadQueue.addFirst(next);
                break;
            }

            long actualQueuedTime = toBeUnloaded.remove(chunkPos);
            if (actualQueuedTime != queuedTime) {
                // The chunk has either been un-queued or re-queued.
                if (actualQueuedTime != 0) {
                    // If it was re-queued, put it back in the map.
                    toBeUnloaded.put(chunkPos, actualQueuedTime);
                }
                // Either way, skip it for now and go to the next entry.
                continue;
            }

            // This chunk is due for unloading
            unload(ChunkPos.getPackedX(chunkPos), ChunkPos.getPackedZ(chunkPos), false);

            if (countSinceLastThrottleCheck++ > 10) {
                countSinceLastThrottleCheck = 0;
                if (!shouldKeepTicking.getAsBoolean()) {
                    break;
                }
            }
        }

        ObjectIterator<LoadingJob> loadingJobsIter = this.loadingJobs.values().iterator();
        while (loadingJobsIter.hasNext()) {
            LoadingJob loadingJob = loadingJobsIter.next();

            //noinspection OptionalAssignedToNull
            if (loadingJob.result == null) {
                continue; // still loading
            }

            // Done loading
            loadingJobsIter.remove();

            client.getProfiler().push("loadFakeChunk");
            loadingJob.complete();
            client.getProfiler().pop();

            if (!shouldKeepTicking.getAsBoolean()) {
                break;
            }
        }
    }

    public void loadMissingChunksFromCache() {
        // We do this by temporarily reducing the client view distance to 0. That will unload all chunks and then try
        // to re-load them (by canceling the unload when they were already loaded, or from the cache when they are
        // missing).
        int orgViewDistance = client.options.viewDistance;
        client.options.viewDistance = 0;
        try {
            update(() -> false);
        } finally {
            client.options.viewDistance = orgViewDistance;
        }
        update(() -> false);
    }

    public boolean shouldBeLoaded(int x, int z) {
        return chunkTracker.isInViewDistance(x, z);
    }

    private @Nullable Pair<NbtCompound, FakeChunkStorage> loadTag(int x, int z) {
        ChunkPos chunkPos = new ChunkPos(x, z);
        NbtCompound tag;
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

    public void load(int x, int z, NbtCompound tag, FakeChunkStorage storage) {
        Supplier<WorldChunk> chunkSupplier = storage.deserialize(new ChunkPos(x, z), tag, world);
        if (chunkSupplier == null) {
            return;
        }
        load(x, z, chunkSupplier.get());
    }

    protected void load(int x, int z, WorldChunk chunk) {
        fakeChunks.put(ChunkPos.toLong(x, z), chunk);

        world.resetChunkColor(new ChunkPos(x, z));

        for (int i = world.getBottomSectionCoord(); i < world.getTopSectionCoord(); i++) {
            world.scheduleBlockRenders(x, i, z);
        }

        clientChunkManagerExt.bobby_onFakeChunkAdded(x, z);
    }

    public boolean unload(int x, int z, boolean willBeReplaced) {
        long chunkPos = ChunkPos.toLong(x, z);
        cancelLoad(chunkPos);
        WorldChunk chunk = fakeChunks.remove(chunkPos);
        if (chunk != null) {
            LightingProviderAccessor lightingProvider = (LightingProviderAccessor) clientChunkManager.getLightingProvider();
            ChunkLightProviderExt blockLightProvider = (ChunkLightProviderExt) lightingProvider.getBlockLightProvider();
            ChunkLightProviderExt skyLightProvider = (ChunkLightProviderExt) lightingProvider.getSkyLightProvider();
            for (int i = 0; i < chunk.getSectionArray().length; i++) {
                int y = world.sectionIndexToCoord(i);
                if (blockLightProvider != null) {
                    blockLightProvider.bobby_removeSectionData(ChunkSectionPos.asLong(x, y, z));
                }
                if (skyLightProvider != null) {
                    skyLightProvider.bobby_removeSectionData(ChunkSectionPos.asLong(x, y, z));
                }
            }

            clientChunkManagerExt.bobby_onFakeChunkRemoved(x, z);

            return true;
        }
        return false;
    }

    private void cancelLoad(long chunkPos) {
        LoadingJob loadingJob = loadingJobs.remove(chunkPos);
        if (loadingJob != null) {
            loadingJob.cancelled = true;
        }
    }

    private static String getCurrentWorldOrServerName() {
        IntegratedServer integratedServer = client.getServer();
        if (integratedServer != null) {
            return integratedServer.getSaveProperties().getLevelName();
        }

        ServerInfo serverInfo = client.getCurrentServerEntry();
        if (serverInfo != null) {
            return serverInfo.address.replace(':', '_');
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
                ResourceType.SERVER_DATA,
                new VanillaDataPackProvider(),
                new FileResourcePackProvider(session.getDirectory(WorldSavePath.DATAPACKS).toFile(), ResourcePackSource.PACK_SOURCE_WORLD)
        )) {
            DynamicRegistryManager.Impl registryTracker = DynamicRegistryManager.create();
            DataPackSettings dataPackSettings = MinecraftServer.loadDataPacks(resourcePackManager, MinecraftClient.loadDataPackSettings(session), false);
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
                    registryTracker,
                    CommandManager.RegistrationEnvironment.INTEGRATED,
                    2,
                    Util.getMainWorkerExecutor(),
                    executor
            );
            executor.runTasks(completableFuture::isDone);
            ServerResourceManager serverResourceManager = completableFuture.get();
            ResourceManager resourceManager = serverResourceManager.getResourceManager();
            SaveProperties saveProperties = MinecraftClient.createSaveProperties(session, registryTracker, resourceManager, dataPackSettings);
            return saveProperties.getGeneratorOptions().getChunkGenerator().getBiomeSource();
        }
    }

    public String getDebugString() {
        return "F: " + fakeChunks.size() + " L: " + loadingJobs.size() + " U: " + toBeUnloaded.size();
    }

    private class LoadingJob implements Runnable {
        private final int x;
        private final int z;
        private volatile boolean cancelled;
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType") // null while loading, empty() if no chunk was found
        private volatile Optional<Supplier<WorldChunk>> result;

        public LoadingJob(int x, int z) {
            this.x = x;
            this.z = z;
        }

        @Override
        public void run() {
            if (cancelled) {
                return;
            }
            result = Optional.ofNullable(loadTag(x, z))
                    .map(it -> it.getRight().deserialize(new ChunkPos(x, z), it.getLeft(), world));
        }

        public void complete() {
            result.ifPresent(it -> load(x, z, it.get()));
        }
    }
}
