package de.johni0702.minecraft.bobby;

import de.johni0702.minecraft.bobby.ext.ChunkLightProviderExt;
import de.johni0702.minecraft.bobby.mixin.BiomeAccessAccessor;
import de.johni0702.minecraft.bobby.mixin.LightingProviderAccessor;
import de.johni0702.minecraft.bobby.mixin.sodium.SodiumChunkManagerAccessor;
import io.netty.util.concurrent.DefaultThreadFactory;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
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
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.level.storage.LevelStorage;
import org.jetbrains.annotations.Nullable;

import java.io.File;
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
    private final FakeChunkStorage storage;
    private final @Nullable FakeChunkStorage fallbackStorage;
    private int ticksSinceLastSave;

    private final Long2ObjectMap<WorldChunk> fakeChunks = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());
    private int centerX, centerZ, viewDistance;
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

        storage = FakeChunkStorage.getFor(storagePath.toFile(), null);

        FakeChunkStorage fallbackStorage = null;
        LevelStorage levelStorage = client.getLevelStorage();
        if (levelStorage.levelExists(FALLBACK_LEVEL_NAME)) {
            try (LevelStorage.Session session = levelStorage.createSession(FALLBACK_LEVEL_NAME)) {
                File worldDirectory = session.getWorldDirectory(worldKey);
                File regionDirectory = new File(worldDirectory, "region");
                fallbackStorage = FakeChunkStorage.getFor(regionDirectory, getBiomeSource(session));
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

        int oldCenterX = this.centerX;
        int oldCenterZ = this.centerZ;
        int oldViewDistance = this.viewDistance;
        int newCenterX = player.chunkX;
        int newCenterZ = player.chunkZ;
        int newViewDistance = client.options.viewDistance;
        if (oldCenterX != newCenterX || oldCenterZ != newCenterZ || oldViewDistance != newViewDistance) {
            // Firstly check which chunks can be unloaded / cancelled
            for (int x = oldCenterX - oldViewDistance; x <= oldCenterX + oldViewDistance; x++) {
                boolean xOutsideNew = x < newCenterX - newViewDistance || x > newCenterX + newViewDistance;
                for (int z = oldCenterZ - oldViewDistance; z <= oldCenterZ + oldViewDistance; z++) {
                    boolean zOutsideNew = z < newCenterZ - newViewDistance || z > newCenterZ + newViewDistance;
                    if (xOutsideNew || zOutsideNew) {
                        cancelLoad(x, z);
                        long chunkPos = ChunkPos.toLong(x, z);
                        toBeUnloaded.put(chunkPos, time);
                        unloadQueue.add(new Pair<>(chunkPos, time));
                    }
                }
            }

            // Then check which one we need to load
            for (int x = newCenterX - newViewDistance; x <= newCenterX + newViewDistance; x++) {
                boolean xOutsideOld = x < oldCenterX - oldViewDistance || x > oldCenterX + oldViewDistance;
                for (int z = newCenterZ - newViewDistance; z <= newCenterZ + newViewDistance; z++) {
                    boolean zOutsideOld = z < oldCenterZ - oldViewDistance || z > oldCenterZ + oldViewDistance;
                    if (xOutsideOld || zOutsideOld) {
                        long chunkPos = ChunkPos.toLong(x, z);

                        // We want this chunk, so don't unload it if it's still here
                        toBeUnloaded.remove(chunkPos);
                        // Not removing it from [unloadQueue], we check [toBeUnloaded] when we poll it.

                        // If there already is a chunk loaded, there's nothing to do
                        if (clientChunkManager.getChunk(x, z, ChunkStatus.FULL, false) != null) {
                            continue;
                        }

                        // All good, load it
                        LoadingJob loadingJob = new LoadingJob(x, z);
                        loadingJobs.put(chunkPos, loadingJob);
                        loadExecutor.execute(loadingJob);
                    }
                }
            }

            this.centerX = newCenterX;
            this.centerZ = newCenterZ;
            this.viewDistance = newViewDistance;
        }

        // Anything remaining in the set is no longer needed and can now be unloaded
        long unloadTime = time - config.getUnloadDelaySecs() * 1000L;
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

    public void load(int x, int z, CompoundTag tag, FakeChunkStorage storage) {
        Supplier<WorldChunk> chunkSupplier = storage.deserialize(new ChunkPos(x, z), tag, world);
        if (chunkSupplier == null) {
            return;
        }
        load(x, z, chunkSupplier.get());
    }

    protected void load(int x, int z, WorldChunk chunk) {
        fakeChunks.put(ChunkPos.toLong(x, z), chunk);

        world.resetChunkColor(x, z);

        for (int i = 0; i < 16; i++) {
            world.scheduleBlockRenders(x, i, z);
        }
    }

    public void unload(int x, int z, boolean willBeReplaced) {
        cancelLoad(x, z);
        WorldChunk chunk = fakeChunks.remove(ChunkPos.toLong(x, z));
        if (chunk != null) {
            LightingProviderAccessor lightingProvider = (LightingProviderAccessor) clientChunkManager.getLightingProvider();
            ChunkLightProviderExt blockLightProvider = (ChunkLightProviderExt) lightingProvider.getBlockLightProvider();
            ChunkLightProviderExt skyLightProvider = (ChunkLightProviderExt) lightingProvider.getSkyLightProvider();
            for (int y = 0; y < chunk.getSectionArray().length; y++) {
                if (blockLightProvider != null) {
                    blockLightProvider.bobby_removeSectionData(ChunkSectionPos.asLong(x, y, z));
                }
                if (skyLightProvider != null) {
                    skyLightProvider.bobby_removeSectionData(ChunkSectionPos.asLong(x, y, z));
                }
            }

            world.blockEntities.removeAll(chunk.getBlockEntities().values());
            world.tickingBlockEntities.removeAll(chunk.getBlockEntities().values());
        }
    }

    private void cancelLoad(int x, int z) {
        LoadingJob loadingJob = loadingJobs.remove(ChunkPos.toLong(x, z));
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

    public static class Sodium extends FakeChunkManager {

        private final SodiumChunkManagerAccessor sodiumChunkManager;

        public Sodium(ClientWorld world, SodiumChunkManager sodiumChunkManager) {
            super(world, sodiumChunkManager);
            this.sodiumChunkManager = (SodiumChunkManagerAccessor) sodiumChunkManager;
        }

        @Override
        protected void load(int x, int z, WorldChunk chunk) {
            super.load(x, z, chunk);

            ChunkStatusListener listener = sodiumChunkManager.getListener();
            if (listener != null) {
                listener.onChunkAdded(x, z);
            }
        }

        @Override
        public void unload(int x, int z, boolean willBeReplaced) {
            super.unload(x, z, willBeReplaced);

            ChunkStatusListener listener = sodiumChunkManager.getListener();
            if (listener != null) {
                listener.onChunkRemoved(x, z);
            }
        }
    }
}
