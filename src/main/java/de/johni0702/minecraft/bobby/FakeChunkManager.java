package de.johni0702.minecraft.bobby;

import de.johni0702.minecraft.bobby.ext.LightEngineExt;
import de.johni0702.minecraft.bobby.ext.ClientChunkCacheExt;
import de.johni0702.minecraft.bobby.ext.ClientPacketListenerExt;
import de.johni0702.minecraft.bobby.ext.LevelLightEngineExt;
import de.johni0702.minecraft.bobby.mixin.BiomeManagerAccessor;
import de.johni0702.minecraft.bobby.mixin.ClientLevelAccessor;
import de.johni0702.minecraft.bobby.util.FileSystemUtils;
import io.netty.util.concurrent.DefaultThreadFactory;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Util;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.apache.commons.lang3.tuple.Pair;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

public class FakeChunkManager {
    private static final String FALLBACK_LEVEL_NAME = "bobby-fallback";
    private static final Minecraft client = Minecraft.getInstance();

    private final ClientLevel world;
    private final ClientChunkCache clientChunkManager;
    private final ClientChunkCacheExt clientChunkCacheExt;
    private final Worlds worlds;
    private final FakeChunkStorage storage;
    private final List<Function<ChunkPos, CompletableFuture<Optional<CompoundTag>>>> storages = new ArrayList<>();
    private int ticksSinceLastSave;

    private final Long2ObjectMap<LevelChunk> fakeChunks = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());
    private final VisibleChunksTracker chunkTracker = new VisibleChunksTracker();
    private final Long2LongMap toBeUnloaded = new Long2LongOpenHashMap();
    // Contains chunks in order to be unloaded. We keep the chunk and time so we can cross-reference it with
    // [toBeUnloaded] to see if the entry has since been removed / the time reset. This way we do not need
    // to remove entries from the middle of the queue.
    private final Deque<Pair<Long, Long>> unloadQueue = new ArrayDeque<>();

    // The api for loading chunks unfortunately does not handle cancellation, so we utilize a separate thread pool to
    // ensure only a small number of tasks are active at any one time, and all others can still be cancelled before
    // they are submitted.
    // The size of the pool must be sufficiently large such that there is always at least one query operation
    // running, as otherwise the storage io worker will start writing chunks which slows everything down to a crawl.
    private static final ExecutorService loadExecutor = Executors.newFixedThreadPool(8, new DefaultThreadFactory("bobby-loading", true));
    private final Long2ObjectMap<LoadingJob> loadingJobs = new Long2ObjectLinkedOpenHashMap<>();

    // Executor for serialization and saving. Single-threaded so we do not have to worry about races between multiple saves for the same chunk.
    private static final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(new DefaultThreadFactory("bobby-saving", true));

    private final Long2ObjectMap<FingerprintJob> fingerprintJobs = new Long2ObjectLinkedOpenHashMap<>();

    public FakeChunkManager(ClientLevel world, ClientChunkCache clientChunkManager) {
        this.world = world;
        this.clientChunkManager = clientChunkManager;
        this.clientChunkCacheExt = (ClientChunkCacheExt) clientChunkManager;

        BobbyConfig config = Bobby.getInstance().getConfig();

        String serverName = getCurrentWorldOrServerName(((ClientLevelAccessor) world).getConnection());
        if (serverName.isEmpty()) {
            serverName = "<empty>";
        }
        long seedHash = ((BiomeManagerAccessor) world.getBiomeManager()).getBiomeZoomSeed();
        ResourceKey<Level> worldKey = world.dimension();
        Identifier worldId = worldKey.identifier();
        Path storagePath = client.gameDirectory
                .toPath()
                .resolve(".bobby");
        if (oldFolderExists(storagePath, serverName)) {
            storagePath = storagePath.resolve(serverName);
        } else {
            storagePath = FileSystemUtils.resolveSafeDirectoryName(storagePath, serverName);
        }
        storagePath = storagePath
                .resolve(seedHash + "")
                .resolve(worldId.getNamespace())
                .resolve(worldId.getPath());

        if (config.isDynamicMultiWorld()) {
            worlds = Worlds.getFor(storagePath);
            worlds.startNewWorld();
            storages.add(worlds::loadTag);

            storage = null;
        } else {
            storage = FakeChunkStorage.getFor(storagePath, true);
            storages.add(storage::loadTag);

            worlds = null;
        }

        LevelStorageSource levelStorage = client.getLevelSource();
        if (levelStorage.levelExists(FALLBACK_LEVEL_NAME)) {
            try (LevelStorageSource.LevelStorageAccess session = levelStorage.validateAndCreateAccess(FALLBACK_LEVEL_NAME)) {
                Path worldDirectory = session.getDimensionPath(worldKey);
                Path regionDirectory = worldDirectory.resolve("region");
                FakeChunkStorage fallbackStorage = FakeChunkStorage.getFor(regionDirectory, false);
                storages.add(fallbackStorage::loadTag);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static boolean oldFolderExists(Path bobbyFolder, String name) {
        try {
            return Files.exists(bobbyFolder.resolve(name));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    public LevelChunk getChunk(int x, int z) {
        return fakeChunks.get(ChunkPos.pack(x, z));
    }

    public FakeChunkStorage getStorage() {
        return storage;
    }

    public Worlds getWorlds() {
        return worlds;
    }

    public void update(boolean blocking, BooleanSupplier shouldKeepTicking) {
        update(blocking, shouldKeepTicking, client.options.renderDistance().get());
    }

    private void update(boolean blocking, BooleanSupplier shouldKeepTicking, int newViewDistance) {
        // Once a minute, force chunks to disk
        if (++ticksSinceLastSave > 20 * 60) {
            if (worlds != null) {
                worlds.saveAll();
            } else {
                storage.synchronize(true);
            }

            ticksSinceLastSave = 0;
        }

        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }

        BobbyConfig config = Bobby.getInstance().getConfig();
        long time = Util.getMillis();

        List<LoadingJob> newJobs = new ArrayList<>();
        ChunkPos playerChunkPos = player.chunkPosition();
        int newCenterX =  playerChunkPos.x();
        int newCenterZ = playerChunkPos.z();
        chunkTracker.update(newCenterX, newCenterZ, newViewDistance, chunkPos -> {
            // Chunk is now outside view distance, can be unloaded / cancelled
            cancelLoad(chunkPos);
            toBeUnloaded.put(chunkPos, time);
            unloadQueue.add(Pair.of(chunkPos, time));
        }, chunkPos -> {
            // Chunk is now inside view distance, load it
            int x = ChunkPos.getX(chunkPos);
            int z = ChunkPos.getZ(chunkPos);

            // We want this chunk, so don't unload it if it's still here
            toBeUnloaded.remove(chunkPos);
            // Not removing it from [unloadQueue], we check [toBeUnloaded] when we poll it.

            // If there already is a chunk loaded, there's nothing to do
            if (clientChunkManager.getChunk(x, z, ChunkStatus.FULL, false) != null) {
                return;
            }

            // All good, load it
            int distanceX = Math.abs(x - newCenterX);
            int distanceZ = Math.abs(z - newCenterZ);
            int distanceSquared = distanceX * distanceX + distanceZ * distanceZ;
            newJobs.add(new LoadingJob(x, z, distanceSquared));
        });

        if (!newJobs.isEmpty()) {
            newJobs.sort(LoadingJob.BY_DISTANCE);
            newJobs.forEach(job -> {
                loadingJobs.put(ChunkPos.pack(job.x, job.z), job);
                loadExecutor.execute(job);
            });
        }

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
            unload(ChunkPos.getX(chunkPos), ChunkPos.getZ(chunkPos), false);

            if (countSinceLastThrottleCheck++ > 10) {
                countSinceLastThrottleCheck = 0;
                if (!shouldKeepTicking.getAsBoolean()) {
                    break;
                }
            }
        }

        ObjectIterator<FingerprintJob> fingerprintJobsIter = this.fingerprintJobs.values().iterator();
        jobs: while (fingerprintJobsIter.hasNext()) {
            FingerprintJob fingerprintJob = fingerprintJobsIter.next();

            while (fingerprintJob.result == 0) {
                // Still loading, should we wait for it?
                if (blocking) {
                    try {
                        // This code path is not the default one, it doesn't need super high performance, and having the
                        // workers notify the main thread just for it is probably not worth it.
                        //noinspection BusyWait
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    continue jobs;
                }
            }

            // Done loading
            fingerprintJobsIter.remove();

            assert worlds != null; // fingerprint jobs should only be queued when multi-world support is active
            worlds.observeChunk(world, fingerprintJob.chunk.getPos(), fingerprintJob.result);

            if (countSinceLastThrottleCheck++ > 10) {
                countSinceLastThrottleCheck = 0;
                if (!shouldKeepTicking.getAsBoolean()) {
                    break;
                }
            }
        }

        ObjectIterator<LoadingJob> loadingJobsIter = this.loadingJobs.values().iterator();
        jobs: while (loadingJobsIter.hasNext()) {
            LoadingJob loadingJob = loadingJobsIter.next();

            //noinspection OptionalAssignedToNull
            while (loadingJob.result == null) {
                // Still loading, should we wait for it?
                if (blocking) {
                    try {
                        // This code path is not the default one, it doesn't need super high performance, and having the
                        // workers notify the main thread just for it is probably not worth it.
                        //noinspection BusyWait
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    continue jobs;
                }
            }

            // Done loading
            loadingJobsIter.remove();

            ProfilerFiller profiler = Profiler.get();
            profiler.push("loadFakeChunk");
            loadingJob.complete();
            profiler.pop();

            if (!shouldKeepTicking.getAsBoolean()) {
                break;
            }
        }

        if (worlds != null) {
            boolean didMerge = worlds.update();
            if (didMerge) {
                loadMissingChunksFromCache();
            }
        }
    }

    public void loadMissingChunksFromCache() {
        // We do this by temporarily reducing the client view distance to 0. That will unload all chunks and then try
        // to re-load them (by canceling the unload when they were already loaded, or from the cache when they are
        // missing).
        update(false, () -> false, 0);
        update(false, () -> false);
    }

    public boolean shouldBeLoaded(int x, int z) {
        return chunkTracker.isInViewDistance(x, z);
    }

    private CompletableFuture<Optional<CompoundTag>> loadTag(int x, int z) {
        return loadTag(new ChunkPos(x, z), 0);
    }

    private CompletableFuture<Optional<CompoundTag>> loadTag(ChunkPos chunkPos, int storageIndex) {
        return storages.get(storageIndex).apply(chunkPos).thenCompose(maybeTag -> {
            if (maybeTag.isPresent()) {
                return CompletableFuture.completedFuture(maybeTag);
            }
            if (storageIndex + 1 < storages.size()) {
                return loadTag(chunkPos, storageIndex + 1);
            }
            return CompletableFuture.completedFuture(Optional.empty());
        });
    }

    public void load(int x, int z, LevelChunk chunk) {
        fakeChunks.put(ChunkPos.pack(x, z), chunk);

        markAsLoaded(x, z, chunk);
        world.onChunkLoaded(new ChunkPos(x, z));

        for (int i = world.getMinSectionY(); i < world.getMaxSectionY(); i++) {
            world.setSectionDirtyWithNeighbors(x, i, z);
        }

        clientChunkCacheExt.bobby_onFakeChunkAdded(x, z);
    }

    public void markAsLoaded(int x, int z, LevelChunk chunk) {
        clientChunkManager.addedLoadedChunks().add(new ChunkPos(x, z).pack());
        clientChunkManager.removedLoadedChunks().remove(new ChunkPos(x, z).pack());

        LongOpenHashSet addedEmptySections = clientChunkManager.addedEmptySections();
        LongOpenHashSet removedEmptySections = clientChunkManager.removedEmptySections();
        LevelChunkSection[] chunkSections = chunk.getSections();
        for (int i = 0; i < chunkSections.length; i++) {
            LevelChunkSection chunkSection = chunkSections[i];
            long sectionPos = SectionPos.asLong(x, chunk.getSectionYFromSectionIndex(i), z);
            if (chunkSection.hasOnlyAir()) {
                addedEmptySections.add(sectionPos);
                removedEmptySections.remove(sectionPos);
            }
        }
    }

    private void markAsUnloaded(int x, int z, LevelChunk chunk) {
        clientChunkManager.removedLoadedChunks().add(new ChunkPos(x, z).pack());
        clientChunkManager.addedLoadedChunks().remove(new ChunkPos(x, z).pack());

        LongOpenHashSet removedEmptySections = clientChunkManager.removedEmptySections();
        LongOpenHashSet addedEmptySections = clientChunkManager.addedEmptySections();
        LevelChunkSection[] chunkSections = chunk.getSections();
        for (int i = 0; i < chunkSections.length; i++) {
            LevelChunkSection chunkSection = chunkSections[i];
            long sectionPos = SectionPos.asLong(x, chunk.getSectionYFromSectionIndex(i), z);
            if (chunkSection.hasOnlyAir()) {
                removedEmptySections.add(sectionPos);
                addedEmptySections.remove(sectionPos);
            }
        }
    }

    public boolean unload(int x, int z, boolean willBeReplaced) {
        long chunkPos = ChunkPos.pack(x, z);
        cancelLoad(chunkPos);
        LevelChunk chunk = fakeChunks.remove(chunkPos);
        if (chunk != null) {
            chunk.clearAllBlockEntities();

            LevelLightEngine lightingProvider = clientChunkManager.getLightEngine();
            LevelLightEngineExt levelLightEngineExt = LevelLightEngineExt.get(lightingProvider);
            LightEngineExt blockLightProvider = LightEngineExt.get(lightingProvider.getLayerListener(LightLayer.BLOCK));
            LightEngineExt skyLightProvider = LightEngineExt.get(lightingProvider.getLayerListener(LightLayer.SKY));

            levelLightEngineExt.bobby_disableColumn(SectionPos.getZeroNode(x, z));

            // See the comment above the bobby_queueUnloadFakeLightDataTask implementation
            Runnable unloadLightData = () -> {
                for (int i = 0; i < chunk.getSections().length; i++) {
                    int y = world.getSectionYFromSectionIndex(i);
                    if (blockLightProvider != null) {
                        blockLightProvider.bobby_removeSectionData(SectionPos.asLong(x, y, z));
                    }
                    if (skyLightProvider != null) {
                        skyLightProvider.bobby_removeSectionData(SectionPos.asLong(x, y, z));
                    }
                }
            };
            if (willBeReplaced) {
                ClientPacketListener networkHandler = ((ClientLevelAccessor) world).getConnection();
                ClientPacketListenerExt.get(networkHandler).bobby_queueUnloadFakeLightDataTask(() -> {
                    if (fakeChunks.containsKey(chunkPos)) {
                        // Real chunk has been unloaded in the meantime and is now a fake chunk again, that fake
                        // chunk will have loaded its own fake light, so we shouldn't unload it.
                        return;
                    }
                    unloadLightData.run();
                });
            } else {
                unloadLightData.run();

                markAsUnloaded(x, z, chunk);
            }

            clientChunkCacheExt.bobby_onFakeChunkRemoved(x, z, willBeReplaced);

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

    public Supplier<LevelChunk> save(LevelChunk chunk) {
        Pair<LevelChunk, Supplier<LevelChunk>> copy = ChunkSerializer.shallowCopy(chunk);
        fingerprint(copy.getLeft());
        LevelLightEngine lightingProvider = chunk.getLevel().getLightEngine();
        FakeChunkStorage storage = worlds != null ? worlds.getCurrentStorage() : this.storage;
        saveExecutor.execute(() -> {
            CompoundTag nbt = ChunkSerializer.serialize(copy.getLeft(), lightingProvider);
            nbt.putLong("age", System.currentTimeMillis()); // fallback in case meta gets corrupted
            storage.save(chunk.getPos(), nbt);
        });
        return copy.getRight();
    }

    public void fingerprint(LevelChunk chunk) {
        if (worlds == null) {
            return;
        }

        long chunkCoord = chunk.getPos().pack();

        FingerprintJob job = fingerprintJobs.get(chunkCoord);
        if (job != null) {
            job.cancelled = true;
        }

        job = new FingerprintJob(chunk);
        fingerprintJobs.put(chunkCoord, job);
        Util.backgroundExecutor().execute(job);
    }

    private static String getCurrentWorldOrServerName(ClientPacketListener networkHandler) {
        IntegratedServer integratedServer = client.getSingleplayerServer();
        if (integratedServer != null) {
            return integratedServer.getWorldData().getLevelName();
        }

        ServerData serverInfo = networkHandler.getServerData();
        if (serverInfo != null) {
            if (serverInfo.isRealm()) {
                return "realms";
            }
            return serverInfo.ip.replace(':', '_');
        }

        return "unknown";
    }

    public String getDebugString() {
        return "F: " + fakeChunks.size() + " L: " + loadingJobs.size() + " U: " + toBeUnloaded.size() + " C: " + fingerprintJobs.size();
    }

    public Collection<LevelChunk> getFakeChunks() {
        return fakeChunks.values();
    }

    private class LoadingJob implements Runnable {
        private final int x;
        private final int z;
        private final int distanceSquared;
        private volatile boolean cancelled;
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType") // null while loading, empty() if no chunk was found
        private volatile Optional<Supplier<LevelChunk>> result;

        public LoadingJob(int x, int z, int distanceSquared) {
            this.x = x;
            this.z = z;
            this.distanceSquared = distanceSquared;
        }

        @Override
        public void run() {
            if (cancelled) {
                return;
            }
            Optional<CompoundTag> value;
            try {
                value = loadTag(x, z).get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
                value = Optional.empty();
            }
            if (cancelled) {
                return;
            }
            result = value.map(it -> ChunkSerializer.deserialize(new ChunkPos(x, z), it, world).getRight());
        }

        public void complete() {
            result.ifPresent(it -> load(x, z, it.get()));
        }

        public static final Comparator<LoadingJob> BY_DISTANCE = Comparator.comparing(it -> it.distanceSquared);
    }

    private static class FingerprintJob implements Runnable {
        private final LevelChunk chunk;
        private volatile boolean cancelled;
        private volatile long result;

        private FingerprintJob(LevelChunk chunk) {
            this.chunk = chunk;
        }

        @Override
        public void run() {
            if (cancelled) {
                return;
            }
            result = ChunkSerializer.fingerprint(chunk);
        }
    }
}
