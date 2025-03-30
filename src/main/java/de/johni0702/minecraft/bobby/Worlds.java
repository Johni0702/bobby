package de.johni0702.minecraft.bobby;

import com.google.common.collect.Iterables;
import de.johni0702.minecraft.bobby.util.LimitedExecutor;
import de.johni0702.minecraft.bobby.util.RegionPos;
import io.netty.util.concurrent.DefaultThreadFactory;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.Long2BooleanFunction;
import it.unimi.dsi.fastutil.longs.Long2LongArrayMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.storage.StorageIoWorker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static net.minecraft.text.Text.literal;
import static net.minecraft.text.Text.translatable;

public class Worlds implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int CONCURRENT_REGION_LOADING_JOBS = 10;
    private static final int CONCURRENT_FINGERPRINT_JOBS = 10;
    private static final int CONCURRENT_COPY_JOBS = 10;
    private static final int MATCH_THRESHOLD = 10;
    private static final int MISMATCH_THRESHOLD = 100;
    private static final int CURRENT_SAVE_VERSION = SharedConstants.getGameVersion().getSaveVersion().getId();

    // Executor for saving. Single-threaded so we do not have to worry about races between multiple saves.
    private static final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(new DefaultThreadFactory("bobby-meta-saving", true));

    private static final Executor regionLoadingExecutor = new LimitedExecutor(Util.getIoWorkerExecutor(), CONCURRENT_REGION_LOADING_JOBS);
    private static final Executor computeFingerprintExecutor = new LimitedExecutor(Util.getIoWorkerExecutor(), CONCURRENT_FINGERPRINT_JOBS);
    private static final LimitedExecutor copyExecutor = new LimitedExecutor(Util.getIoWorkerExecutor(), CONCURRENT_COPY_JOBS);

    private static final Map<Path, Worlds> active = new HashMap<>();

    public static Worlds getFor(Path directory) {
        synchronized (active) {
            return active.computeIfAbsent(directory, f -> new Worlds(directory));
        }
    }

    public static void closeAll() {
        synchronized (active) {
            for (Worlds worlds : active.values()) {
                try {
                    worlds.close();
                } catch (IOException e) {
                    LOGGER.error("Failed to close storage at " + worlds.directory, e);
                }
            }
            active.clear();
        }
    }

    private final Path directory;
    private final Path metaFile;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    private final Int2ObjectMap<World> worlds = new Int2ObjectOpenHashMap<>();
    private final List<World> outdatedWorlds = new ArrayList<>();
    private final Object2LongMap<World> pendingMergeChecks = new Object2LongLinkedOpenHashMap<>();

    /**
     * ID for the next world we create. We intentionally do not re-use ids, so we do not have to wait for the storages
     * to fully close.
     */
    private int nextWorldId;

    /**
     * World we currently write new chunks to.
     */
    private int currentWorldId;

    private boolean dirty;
    private boolean metaDirty;

    private final Deque<Supplier<Future<?>>> workQueue = new ArrayDeque<>();
    private Future<?> workQueueBlockedOn;

    private final Set<RegionLoadingJob> regionLoadingJobs = new ObjectLinkedOpenHashSet<>();
    private final ObjectLinkedOpenHashSet<ComputeLegacyFingerprintJob> computeLegacyFingerprintJobs = new ObjectLinkedOpenHashSet<>(256, Hash.VERY_FAST_LOAD_FACTOR);

    private long now = System.currentTimeMillis();
    private long lastSave;

    private Worlds(Path directory) {
        this.directory = directory;
        this.metaFile = metaFile(directory);

        load(readFromDisk());

        if (!outdatedWorlds.isEmpty()) {
            Text text = translatable("bobby.upgrade.required");
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> client.inGameHud.getChatHud().addMessage(text));
        }
    }

    public void startNewWorld() {
        // When switching from one server to another, this function can be executed multiple times
        // Without this early return, that would create multiple empty words,
        // after which only the last one would be merged and the empty ones would stay in memory
        if (worlds.get(currentWorldId).knownRegions.isEmpty()) {
            return;
        }

        lock.writeLock().lock();
        try {
            currentWorldId = nextWorldId++;
            worlds.put(currentWorldId, new World(currentWorldId, CURRENT_SAVE_VERSION));
        } finally {
            lock.writeLock().unlock();
        }
    }

    public CompletableFuture<Optional<NbtCompound>> loadTag(ChunkPos chunkPos) {
        RegionPos regionPos = RegionPos.from(chunkPos);
        long regionCoord = regionPos.toLong();
        long chunkCoord = chunkPos.toLong();

        IntList worldsToLoad = null;
        List<CompletableFuture<Optional<NbtCompound>>> unknownAgeResults = null;
        World knownBestWorld = null;
        long knownBestAge = -1;

        lock.readLock().lock();
        try {
            for (World world : worlds.values()) {
                if (world.id != currentWorldId && world.mergingIntoWorld != currentWorldId) {
                    continue; // not current (or merging into current)
                }

                if (!world.knownRegions.contains(regionCoord)) {
                    continue; // world doesn't contain requested region
                }

                Region region = world.regions.get(regionCoord);
                if (region == null) {
                    // We have not yet loaded any details for this region, so we can't tell ahead of time whether this
                    // world contains the requested chunk, and we'll just have to try to see.
                    if (unknownAgeResults == null) {
                        unknownAgeResults = new ArrayList<>();
                    }
                    unknownAgeResults.add(world.storage.loadTag(chunkPos));

                    // We'll schedule the region to load regardless, so future queries may use it.
                    // (need to do that later though because it requires a write lock)
                    if (worldsToLoad == null) {
                        worldsToLoad = new IntArrayList();
                    }
                    worldsToLoad.add(world.id);
                } else {
                    long age = region.chunks.get(chunkCoord);
                    if (age == 0) {
                        continue; // world doesn't contain requested chunk
                    }
                    if (age == 1) {
                        // this is a legacy cache (or its metadata got lost) and we don't yet know if this chunk exists,
                        // need to go the slow path
                        if (unknownAgeResults == null) {
                            unknownAgeResults = new ArrayList<>();
                        }
                        unknownAgeResults.add(world.storage.loadTag(chunkPos));
                        continue;
                    }

                    if (age > knownBestAge) {
                        knownBestAge = age;
                        knownBestWorld = world;
                    }
                }
            }
        } finally {
            lock.readLock().unlock();
        }

        if (worldsToLoad != null) {
            lock.writeLock().lock();
            try {
                for (int id : worldsToLoad) {
                    World world = worlds.get(id);
                    if (world == null) continue;
                    if (world.regions.get(regionCoord) != null) continue;
                    world.loadRegion(regionPos);
                }
            } finally {
                lock.writeLock().unlock();
            }
        }

        if (unknownAgeResults == null) {
            // Fast path, we already know exactly which world has the most recent chunk
            if (knownBestWorld != null) {
                return knownBestWorld.storage.loadTag(chunkPos);
            } else {
                return CompletableFuture.completedFuture(Optional.empty());
            }
        } else if (knownBestWorld == null && unknownAgeResults.size() == 1) {
            // Fast path, we don't know the age of the chunk but there's only a single world to load from
            return unknownAgeResults.get(0);
        } else {
            // Slow path, we need to wait for the results of all unknown worlds and pick the most recent one
            CompletableFuture<?> allDone = CompletableFuture.allOf(unknownAgeResults.toArray(CompletableFuture[]::new));

            // Final copies, even though they are effectively final at this point...
            World fKnownBestWorld = knownBestWorld;
            long fKnownBestAge = knownBestAge;
            List<CompletableFuture<Optional<NbtCompound>>> fUnknownAgeResults = unknownAgeResults;

            return allDone.thenCompose(__ -> {
                NbtCompound bestResult = null;
                long bestAge = -1;

                // Find the most recent one
                for (CompletableFuture<Optional<NbtCompound>> future : fUnknownAgeResults) {
                    NbtCompound result = future.join().orElse(null);
                    if (result != null) {
                        long age = result.getLong("age", 0);
                        if (age > bestAge) {
                            bestAge = age;
                            bestResult = result;
                        }
                    }
                }

                if (fKnownBestWorld != null && fKnownBestAge > bestAge) {
                    return fKnownBestWorld.storage.loadTag(chunkPos);
                } else {
                    return CompletableFuture.completedFuture(Optional.ofNullable(bestResult));
                }
            });
        }
    }

    public static Path metaFile(Path directory) {
        return directory.resolve("worlds.meta");
    }

    public FakeChunkStorage getCurrentStorage() {
        assert MinecraftClient.getInstance().isOnThread();
        return worlds.get(currentWorldId).storage;
    }

    public List<FakeChunkStorage> getOutdatedWorlds() {
        assert MinecraftClient.getInstance().isOnThread();
        return outdatedWorlds.stream().map(it -> it.storage).collect(Collectors.toList());
    }

    public void markAsUpToDate(FakeChunkStorage storage) {
        World world = outdatedWorlds.stream().filter(it -> it.storage == storage).findFirst().orElse(null);
        assert world != null;

        world.version = CURRENT_SAVE_VERSION;
        world.markMetaDirty();
        for (World otherWorld : worlds.values()) {
            Match match = world.matchingWorlds.get(otherWorld.id);
            if (match != null) {
                otherWorld.matchingWorlds.put(world.id, match);
            }
        }

        worlds.put(world.id, world);
        outdatedWorlds.remove(world);
    }

    public void saveAll() {
        List<World> worlds;
        lock.readLock().lock();
        try {
            worlds = new ArrayList<>(this.worlds.values());
        } finally {
            lock.readLock().unlock();
        }
        for (World world : worlds) {
            world.storage.completeAll();
        }
    }

    @Override
    public void close() throws IOException {
        assert MinecraftClient.getInstance().isOnThread();

        lock.writeLock().lock();
        try {
            saveAll();

            if (dirty) {
                scheduleSave();
            }

            CompletableFuture.supplyAsync(() -> null, saveExecutor).join();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean update() {
        assert MinecraftClient.getInstance().isOnThread();
        lock.writeLock().lock();
        try {
            return updateWithLock();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private boolean updateWithLock() {
        now = System.currentTimeMillis();

        if (dirty && now - lastSave > 10_000) {
            scheduleSave();
        }

        // Jobs are being worked on in roughly the same order as we iterate over them, so once we've found a
        // few jobs that were still pending, there's not really much point in looking any further
        int misses = 0;

        Iterator<ComputeLegacyFingerprintJob> fingerprintJobsIter = computeLegacyFingerprintJobs.iterator();
        while (fingerprintJobsIter.hasNext()) {
            ComputeLegacyFingerprintJob job = fingerprintJobsIter.next();
            if (job.result == null) {
                if (misses++ > CONCURRENT_FINGERPRINT_JOBS * 2) {
                    break;
                }
                continue;
            }
            fingerprintJobsIter.remove();

            World world = job.world;
            ChunkPos chunkPos = job.chunkPos;
            long fingerprint = job.result;

            if (fingerprint == 0) {
                // Special case: Chunk was not found, remove it from the index
                Region region = world.regions.get(RegionPos.from(chunkPos).toLong());
                assert region != null;
                region.chunks.remove(chunkPos.toLong());
                region.chunkFingerprints.remove(chunkPos.toLong());
                region.dirty = true;
                world.markContentDirty();
            } else {
                world.setFingerprint(chunkPos, fingerprint);
            }

            job.future.complete(fingerprint);
        }

        misses = 0;
        Iterator<RegionLoadingJob> loadingJobsIter = regionLoadingJobs.iterator();
        while (loadingJobsIter.hasNext()) {
            RegionLoadingJob job = loadingJobsIter.next();
            if (job.result == null) {
                if (misses++ > CONCURRENT_REGION_LOADING_JOBS * 2) {
                    break;
                }
                continue;
            }
            loadingJobsIter.remove();

            World world = job.world;
            long regionCoord = job.regionPos.toLong();
            Region region = job.result;

            world.regions.put(regionCoord, region);
            Region updates = world.regionUpdates.remove(regionCoord);
            if (updates != null) {
                region.chunks.putAll(updates.chunks);
                region.chunkFingerprints.putAll(updates.chunkFingerprints);
                region.dirty = true;
                world.markContentDirty();
            }

            world.loadingRegions.remove(regionCoord).complete(null);
        }

        processWorkQueue();

        ObjectIterator<World> worldsIter = worlds.values().iterator();
        while (worldsIter.hasNext()) {
            World world = worldsIter.next();
            if (world.mergingIntoWorld != -1) {
                if (processMerge(world)) {
                    worldsIter.remove();
                    metaDirty = true;
                    dirty = true;
                }
            }
        }

        return processPendingMergeChecks();
    }


    private boolean processPendingMergeChecks() {
        boolean didMerge = false;

        Iterator<Object2LongMap.Entry<World>> iter = pendingMergeChecks.object2LongEntrySet().iterator();
        while (iter.hasNext()) {
            Object2LongMap.Entry<World> entry = iter.next();
            if (entry.getLongValue() + 1_000 > now) {
                break;
            }

            World targetWorld = entry.getKey();
            World currentWorld = worlds.get(currentWorldId);

            iter.remove();

            if (targetWorld == null) {
                continue; // target not currently available (e.g. might need upgrade)
            }

            if (currentWorld.mergingIntoWorld != -1 || targetWorld.mergingIntoWorld != -1) {
                continue; // already merging
            }

            Match match = targetWorld.getMatch(currentWorld);

            boolean matching = match.matching.size() - match.mismatching.size() * 2 > MATCH_THRESHOLD;
            boolean mismatching = match.mismatching.size() - match.matching.size() * 2 > MISMATCH_THRESHOLD;

            if (matching) {
                LOGGER.info("Merging world {} into {}: {} chunks matching, {} chunks mismatching",
                        currentWorld, targetWorld, match.matching.size(), match.mismatching.size());
                merge(currentWorld, targetWorld);
                didMerge = true;
            } else if (mismatching) {
                LOGGER.info("Marking worlds {} and {} as separate: {} chunks matching, {} chunks mismatching",
                        currentWorld, targetWorld, match.matching.size(), match.mismatching.size());
                currentWorld.nonMatchingWorlds.add(targetWorld.id);
                targetWorld.nonMatchingWorlds.add(currentWorld.id);
                currentWorld.matchingWorlds.remove(targetWorld.id);
                targetWorld.matchingWorlds.remove(currentWorld.id);
                currentWorld.metaDirty = true;
                targetWorld.metaDirty = true;
            }
        }

        return didMerge;
    }

    @SuppressWarnings("DuplicateBranchesInSwitch")
    private boolean processMerge(World sourceWorld) {
        World targetWorld = worlds.get(sourceWorld.mergingIntoWorld);

        if (targetWorld == null) {
            return false; // target not currently available (e.g. might need upgrade)
        }

        if (sourceWorld.mergeState == null) {
            sourceWorld.mergeState = new MergeState();
        }
        MergeState state = sourceWorld.mergeState;

        while (true) {
            LOGGER.debug("Merge of {} into {} is currently in stage {} ({} regions left, {}/{}/{} jobs pending/active/done)",
                    sourceWorld, targetWorld, state.stage, sourceWorld.regions.size(),
                    copyExecutor.queueSize(), copyExecutor.activeWorkers(), state.finishedJobs.size());

            switch (state.stage) {
                case BlockedByOtherMerge -> {
                    for (World world : worlds.values()) {
                        if (world.mergingIntoWorld == sourceWorld.id) {
                            return false;
                        }
                    }
                    state.stage = MergeStage.BlockedByPreviouslyQueuedWrites;
                }

                case BlockedByPreviouslyQueuedWrites -> {
                    state.stage = MergeStage.WaitForPreviouslyQueuedWrites;
                    Util.getIoWorkerExecutor().execute(() -> {
                        sourceWorld.storage.completeAll();
                        state.stage = MergeStage.Idle;
                    });
                }

                case WaitForPreviouslyQueuedWrites -> {
                    return false; // stage is advanced after above source.completeAll call
                }

                case Idle -> {
                    if (sourceWorld.knownRegions.isEmpty()) {
                        state.stage = MergeStage.DeleteSourceStorage;
                        Util.getIoWorkerExecutor().execute(() -> {
                            Path directory = sourceWorld.directory();
                            try {
                                sourceWorld.storage.close();

                                List<Path> toBeDeleted;
                                try (Stream<Path> stream = Files.list(directory)) {
                                    toBeDeleted = stream
                                            .filter(Files::isRegularFile)
                                            .filter(it -> !"worlds.meta".equals(it.getFileName().toString()))
                                            .collect(Collectors.toList());
                                }
                                for (Path path : toBeDeleted) {
                                    Files.delete(path);
                                }

                                boolean empty;
                                try (Stream<Path> stream = Files.list(directory)) {
                                    empty = stream.findAny().isEmpty();
                                }
                                if (empty) {
                                    Files.delete(directory);
                                }
                            } catch (IOException e) {
                                LOGGER.error("Failed to delete " + directory, e);
                            }
                            state.stage = MergeStage.Done;
                        });
                        return false;
                    }

                    // Pick an arbitrary region to move next
                    long regionCoord = sourceWorld.knownRegions.iterator().nextLong();
                    RegionPos regionPos = RegionPos.fromLong(regionCoord);

                    state.activeRegion = regionPos;

                    // Make sure the region metadata is loaded for source and target world
                    if (!sourceWorld.regions.containsKey(regionCoord)) {
                        sourceWorld.loadRegion(regionPos);
                    }
                    if (targetWorld.knownRegions.contains(regionCoord)) {
                        if (!targetWorld.regions.containsKey(regionCoord)) {
                            targetWorld.loadRegion(regionPos);
                        }
                    } else {
                        targetWorld.knownRegions.add(regionCoord);
                        targetWorld.regions.put(regionCoord, new Region());
                        targetWorld.markMetaDirty();
                    }
                    state.stage = MergeStage.WaitForRegion;
                }

                case WaitForRegion -> {
                    Region sourceRegion = sourceWorld.regions.get(state.activeRegion.toLong());
                    if (sourceRegion == null) {
                        return false;
                    }
                    Region targetRegion = targetWorld.regions.get(state.activeRegion.toLong());
                    if (targetRegion == null) {
                        return false;
                    }

                    for (Long2LongMap.Entry entry : sourceRegion.chunks.long2LongEntrySet()) {
                        long chunkCoord = entry.getLongKey();
                        long sourceChunkAge = entry.getLongValue();
                        long targetChunkAge = targetRegion.chunks.get(chunkCoord);

                        if (targetChunkAge > sourceChunkAge) {
                            continue;
                        }

                        CopyJob copyJob = new CopyJob(sourceWorld, targetWorld, new ChunkPos(chunkCoord), sourceChunkAge, targetChunkAge);
                        state.activeJobs.add(copyJob);
                        copyExecutor.execute(copyJob);
                    }

                    state.stage = MergeStage.Copying;
                }

                case Copying -> {
                    while (true) {
                        CopyJob job = state.activeJobs.peek();
                        if (job == null || !job.done) {
                            break;
                        }
                        state.activeJobs.remove();

                        state.finishedJobs.add(job);
                    }

                    if (state.activeJobs.isEmpty()) {
                        state.stage = MergeStage.Syncing;
                        Util.getIoWorkerExecutor().execute(() -> {
                            targetWorld.storage.completeAll();
                            state.stage = MergeStage.WriteTargetMeta;
                        });
                    } else {
                        return false;
                    }
                }

                case Syncing -> {
                    return false; // stage is advanced after above target.completeAll call
                }

                case WriteTargetMeta -> {
                    Region sourceRegion = sourceWorld.regions.get(state.activeRegion.toLong());
                    Region targetRegion = targetWorld.regions.get(state.activeRegion.toLong());

                    for (CopyJob job : state.finishedJobs) {
                        if (job.age == 0) {
                            continue;
                        }

                        long chunkCoord = job.chunkPos.toLong();
                        long fingerprint = sourceRegion.chunkFingerprints.get(chunkCoord);
                        long age = job.age;

                        targetRegion.chunks.put(chunkCoord, age);
                        targetRegion.chunkFingerprints.put(chunkCoord, fingerprint);
                    }
                    state.finishedJobs.clear();
                    targetRegion.dirty = true;
                    targetWorld.markContentDirty();

                    scheduleSave();
                    state.stage = MergeStage.SyncTargetMeta;
                    saveExecutor.execute(() -> state.stage = MergeStage.DeleteSourceMeta);
                }

                case SyncTargetMeta -> {
                    return false; // stage is advanced in above saveExecutor.execute callback
                }

                case DeleteSourceMeta -> {
                    long regionCoord = state.activeRegion.toLong();

                    sourceWorld.knownRegions.remove(regionCoord);
                    sourceWorld.regions.remove(regionCoord);
                    sourceWorld.markContentDirty();
                    sourceWorld.markMetaDirty();
                    state.stage = MergeStage.Idle;
                }

                case Done -> {
                    for (World world : worlds.values()) {
                        boolean changed = false;
                        changed |= world.nonMatchingWorlds.remove(sourceWorld.id);
                        changed |= world.matchingWorlds.remove(sourceWorld.id) != null;
                        if (changed) {
                            world.markMetaDirty();
                        }
                    }
                    return true;
                }
            }
        }
    }

    private void processWorkQueue() {
        assert MinecraftClient.getInstance().isOnThread();
        while (true) {
            if (workQueueBlockedOn != null) {
                if (!workQueueBlockedOn.isDone()) {
                    break;
                }
                workQueueBlockedOn = null;
            }
            Supplier<Future<?>> work = workQueue.peek();
            if (work == null) {
                break;
            }
            Future<?> future = work.get();
            if (future == null) {
                workQueue.poll();
            } else {
                workQueueBlockedOn = future;
            }
        }
    }

    public void runOrScheduleWork(Supplier<Future<?>> work) {
        assert MinecraftClient.getInstance().isOnThread();
        if (workQueue.isEmpty()) {
            lock.writeLock().lock();
            try {
                Future<?> future = work.get();
                if (future != null) {
                    workQueue.addFirst(work);
                    workQueueBlockedOn = future;
                }
            } finally {
                lock.writeLock().unlock();
            }
        } else {
            workQueue.add(work);
        }
    }

    /** Re-checks all visible chunks for matching fingerprints. Used after upgrading worlds to match against them. */
    public void recheckChunks(net.minecraft.world.World mcWorld, VisibleChunksTracker tracker) {
        // Wrapped to wait for all pending fingerprint updates to be committed, so we don't overwrite them
        runOrScheduleWork(() -> {
            tracker.forEach(chunkCoord -> {
                ChunkPos chunkPos = new ChunkPos(chunkCoord);
                RegionPos regionPos = RegionPos.from(chunkPos);
                long regionCoord = regionPos.toLong();

                World currentWorld = worlds.get(currentWorldId);
                Region region = currentWorld.regions.get(regionCoord);
                if (region == null) {
                    return;
                }

                long fingerprint = region.chunkFingerprints.get(chunkCoord);
                if (fingerprint == 0) {
                    return;
                }

                observeChunk(mcWorld, chunkPos, fingerprint);
            });
            return null;
        });
    }

    public void observeChunk(net.minecraft.world.World mcWorld, ChunkPos chunkPos, long fingerprint) {
        assert MinecraftClient.getInstance().isOnThread();
        runOrScheduleWork(() -> tryObserveChunk(mcWorld, chunkPos, fingerprint));
    }

    private CompletableFuture<?> tryObserveChunk(net.minecraft.world.World mcWorld, ChunkPos chunkPos, long fingerprint) {
        assert lock.isWriteLockedByCurrentThread();

        World currentWorld = worlds.get(currentWorldId);
        currentWorld.setFingerprint(chunkPos, fingerprint);

        if (worlds.size() == currentWorld.nonMatchingWorlds.size() + 1) {
            // there's no possible worlds we could match against, the current world is already isolated
            return null;
        }

        RegionPos regionPos = RegionPos.from(chunkPos);
        long regionCoord = regionPos.toLong();
        long chunkCoord = chunkPos.toLong();

        Predicate<World> couldWorldMatch = world -> {
            if (world == currentWorld) {
                return false; // don't want to match the world against itself
            }

            if (world.mergingIntoWorld != -1) {
                return false; // this world is in the process of being merged, we will already match against its target
            }

            if (currentWorld.nonMatchingWorlds.contains(world.id)) {
                return false; // we've already seen enough and decided that these do not match
            }

            if (!world.knownRegions.contains(regionCoord)) {
                return false; // we don't know what's at this chunk in that world, can't match it
            }

            return true;
        };

        for (World world : worlds.values()) {
            if (!couldWorldMatch.test(world)) {
                continue;
            }

            Region region = world.regions.get(regionCoord);
            if (region == null) {
                // We have not yet loaded that region (at least for that world, likely for other worlds too), so we'll
                // have to schedule it and check back later
                return CompletableFuture.allOf(
                        worlds.values().stream()
                                .filter(couldWorldMatch)
                                .filter(it -> !it.regions.containsKey(regionCoord))
                                .map(it -> it.loadRegion(regionPos))
                                .toArray(CompletableFuture[]::new)
                );
            } else {
                if (!region.chunks.containsKey(chunkCoord)) {
                    continue; // we don't know what's at this chunk in that world, can't match it
                }

                long worldChunkHash = region.chunkFingerprints.get(chunkCoord);
                if (worldChunkHash == 0) {
                    // We haven't yet computed that chunk hash (legacy cache), schedule it and check once that's done
                    return computeLegacyFingerprint(world, chunkPos, mcWorld);
                }

                if (fingerprint == 1 && worldChunkHash == 1) {
                    // neither chunk has enough entropy to draw any conclusions, see [ChunkSerializer.fingerprint]
                    continue;
                }

                Match match = world.getMatch(currentWorld);

                boolean changed;
                if (worldChunkHash == fingerprint) {
                    changed = match.matching.add(chunkCoord);
                    if (match.matching.size() > MATCH_THRESHOLD && !pendingMergeChecks.containsKey(world)) {
                        pendingMergeChecks.put(world, now);
                    }
                } else {
                    changed = match.mismatching.add(chunkCoord);
                    if (match.mismatching.size() > MISMATCH_THRESHOLD && !pendingMergeChecks.containsKey(world)) {
                        pendingMergeChecks.put(world, now);
                    }
                }
                if (changed) {
                    world.metaDirty = true;
                    currentWorld.metaDirty = true;
                }
            }
        }

        return null;
    }

    private void merge(World sourceWorld, World targetWorld) {
        while (targetWorld.mergingIntoWorld != -1) {
            targetWorld = worlds.get(targetWorld.mergingIntoWorld);
        }

        sourceWorld.mergingIntoWorld = targetWorld.id;
        sourceWorld.metaDirty = true;

        for (World world : worlds.values()) {
            if (world.mergingIntoWorld == sourceWorld.id) {
                world.mergingIntoWorld = targetWorld.id;
                world.metaDirty = true;
            }
        }

        if (sourceWorld.id == currentWorldId) {
            currentWorldId = targetWorld.id;
            metaDirty = true;
            dirty = true;
        }
    }

    private CompletableFuture<?> computeLegacyFingerprint(World world, ChunkPos chunkPos, net.minecraft.world.World mcWorld) {
        ComputeLegacyFingerprintJob newJob = new ComputeLegacyFingerprintJob(world, chunkPos, mcWorld);
        ComputeLegacyFingerprintJob existingJob = computeLegacyFingerprintJobs.addOrGet(newJob);
        if (existingJob != newJob) {
            return existingJob.future;
        }
        computeFingerprintExecutor.execute(newJob);
        return newJob.future;
    }

    private void scheduleSave() {
        lastSave = now;
        dirty = false;

        for (World world : worlds.values()) {
            metaDirty |= world.metaDirty;
            world.metaDirty = false;

            if (!world.contentDirty) {
                continue;
            }
            world.contentDirty = false;

            for (Long2ObjectMap.Entry<Region> entry : world.regions.long2ObjectEntrySet()) {
                long regionCoord = entry.getLongKey();
                Region region = entry.getValue();

                if (!region.dirty) {
                    continue;
                }
                region.dirty = false;

                NbtCompound nbt = region.saveToNbt();
                saveExecutor.execute(() -> {
                    RegionPos regionPos = RegionPos.fromLong(regionCoord);
                    try {
                        world.writeRegionToDisk(regionPos, nbt);
                    } catch (IOException e) {
                        LOGGER.error("Failed to save " + world.regionFile(regionPos), e);
                    }
                });
            }
        }

        if (metaDirty) {
            metaDirty = false;
            NbtCompound nbt = saveToNbt();
            saveExecutor.execute(() -> {
                try {
                    writeToDisk(nbt);
                } catch (IOException e) {
                    LOGGER.error("Failed to save worlds metadata to " + metaFile, e);
                }
            });
        }
    }

    private NbtCompound readFromDisk() {
        if (Files.exists(metaFile)) {
            try (InputStream in = Files.newInputStream(metaFile)) {
                return NbtIo.readCompressed(in, NbtSizeTracker.ofUnlimitedBytes());
            } catch (IOException e) {
                LOGGER.error("Failed to read " + metaFile, e);
            }
        }
        return null;
    }

    private void writeToDisk(NbtCompound nbt) throws IOException {
        Files.createDirectories(directory);

        Path tmpFile = Files.createTempFile(directory, "worlds", ".meta");
        try {
            try (OutputStream out = Files.newOutputStream(tmpFile)) {
                NbtIo.writeCompressed(nbt, out);
            }
            Files.move(tmpFile, metaFile, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    private NbtCompound saveToNbt() {
        NbtCompound root = new NbtCompound();
        NbtList worldsNbt = new NbtList();
        for (World world : Iterables.concat(worlds.values(), outdatedWorlds)) {
            NbtCompound worldNbt = new NbtCompound();
            worldNbt.putInt("id", world.id);
            worldNbt.putInt("version", world.version);
            worldNbt.putLongArray("regions", world.knownRegions.toLongArray());
            if (world.mergingIntoWorld != -1) {
                worldNbt.putInt("merging_into", world.mergingIntoWorld);
            }
            NbtList matchesNbt = new NbtList();
            for (Int2ObjectMap.Entry<Match> entry : world.matchingWorlds.int2ObjectEntrySet()) {
                int otherWorldId = entry.getIntKey();
                Match match = entry.getValue();

                NbtCompound matchNbt = new NbtCompound();
                matchNbt.putInt("world", otherWorldId);
                matchNbt.putLongArray("matching", match.matching.toLongArray());
                matchNbt.putLongArray("mismatching", match.mismatching.toLongArray());
                matchesNbt.add(matchNbt);
            }
            worldNbt.put("matches", matchesNbt);
            worldNbt.putIntArray("non_matching", world.nonMatchingWorlds.toIntArray());
            worldsNbt.add(worldNbt);
        }
        root.put("worlds", worldsNbt);
        root.putInt("next_world", nextWorldId);
        return root;
    }

    private void load(NbtCompound root) {
        if (root == null && !Files.exists(directory)) {
            nextWorldId = 1;
        } else if (root == null) {
            outdatedWorlds.add(new World(0, 0));

            try (Stream<Path> stream = Files.list(directory)) {
                stream.filter(Files::isDirectory).flatMapToInt(dir -> {
                    String name = dir.getFileName().toString();
                    try {
                        return IntStream.of(Integer.parseInt(name));
                    } catch (NumberFormatException e) {
                        return IntStream.of();
                    }
                }).forEach(id -> outdatedWorlds.add(new World(id, 0)));
            } catch (IOException e) {
                LOGGER.error("Failed to list files in " + directory, e);
            }

            for (World world : outdatedWorlds) {
                Path worldDirectory = world.directory();
                try {
                    for (RegionPos region : FakeChunkStorage.getRegions(worldDirectory)) {
                        world.knownRegions.add(region.toLong());
                    }
                } catch (IOException e) {
                    LOGGER.error("Failed to list files in " + worldDirectory, e);
                }
            }

            nextWorldId = outdatedWorlds.stream().mapToInt(it -> it.id).max().orElse(0) + 1;
        } else {
            for (NbtElement worldNbtElement : root.getListOrEmpty("worlds")) {
                NbtCompound worldNbt = (NbtCompound) worldNbtElement;
                int id = worldNbt.getInt("id").orElseThrow();
                int version = worldNbt.getInt("version").orElseThrow();
                World world = new World(id, version);
                world.knownRegions.addAll(LongArrayList.wrap(worldNbt.getLongArray("regions").orElseThrow()));
                worldNbt.getInt("merging_into").ifPresent(it -> world.mergingIntoWorld = it);
                for (NbtElement matchNbtElement : worldNbt.getList("matches").orElseThrow()) {
                    NbtCompound matchNbt = (NbtCompound) matchNbtElement;
                    int otherWorldId = matchNbt.getInt("world").orElseThrow();
                    Match match = new Match(
                            new LongOpenHashSet(worldNbt.getLongArray("matching").orElseThrow()),
                            new LongOpenHashSet(worldNbt.getLongArray("mismatching").orElseThrow())
                    );

                    World otherWorld = worlds.get(otherWorldId);
                    Match otherMatch = otherWorld != null ? otherWorld.matchingWorlds.get(world.id) : null;
                    if (otherMatch != null) {
                        otherMatch.addAll(match);
                        world.matchingWorlds.put(otherWorldId, otherMatch);
                    } else {
                        world.matchingWorlds.put(otherWorldId, match);
                    }
                }
                world.nonMatchingWorlds.addAll(IntArrayList.wrap(worldNbt.getIntArray("non_matching").orElseThrow()));

                if (world.version == CURRENT_SAVE_VERSION) {
                    worlds.put(world.id, world);
                } else {
                    outdatedWorlds.add(world);
                }
            }

            nextWorldId = root.getInt("next_world", 0);
        }

        World currentWorld;
        currentWorldId = nextWorldId++;
        worlds.put(currentWorldId, currentWorld = new World(currentWorldId, CURRENT_SAVE_VERSION));

        // Clean up empty worlds by merging them into the current world
        for (World world : worlds.values()) {
            if (world.knownRegions.isEmpty() && world.mergingIntoWorld == -1 && world != currentWorld) {
                merge(world, currentWorld);
            }
        }
    }

    private World getWorldForCommand(FabricClientCommandSource source, int id) {
        World world = worlds.get(id);
        if (world == null) {
            source.sendError(translatable("No active world with id %s. Run `/bobby worlds` for a list of available worlds.", id));
            return null;
        }
        return world;
    }

    public void userRequestedFork(FabricClientCommandSource source) {
        lock.writeLock().lock();
        try {
            World previousWorld = worlds.get(currentWorldId);

            currentWorldId = nextWorldId++;
            worlds.put(currentWorldId, new World(currentWorldId, CURRENT_SAVE_VERSION));

            World currentWorld = worlds.get(currentWorldId);

            currentWorld.nonMatchingWorlds.add(previousWorld.id);
            previousWorld.nonMatchingWorlds.add(currentWorld.id);

            metaDirty = true;
            dirty = true;

            source.sendFeedback(translatable("Created and switched to world %s.", currentWorld));
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void userRequestedMerge(FabricClientCommandSource source, int sourceId, int targetId) {
        lock.writeLock().lock();
        try {
            World sourceWorld = getWorldForCommand(source, sourceId);
            World targetWorld = getWorldForCommand(source, targetId);

            if (sourceWorld == null || targetWorld == null) {
                return;
            }

            while (targetWorld.mergingIntoWorld != -1) {
                targetWorld = worlds.get(targetWorld.mergingIntoWorld);
            }

            if (targetWorld == sourceWorld) {
                source.sendError(translatable("Target world is already being merged into source world."));
                return;
            }

            merge(sourceWorld, targetWorld);
            source.sendFeedback(translatable("Queued merge of %s into %s. Run `/bobby worlds` for status.", sourceWorld, targetWorld));
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void sendInfo(FabricClientCommandSource source, boolean loadAllMetadata) {
        lock.writeLock().lock();
        try {
            sendInfoWithLock(source, loadAllMetadata);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void sendInfoWithLock(FabricClientCommandSource source, boolean loadAllMetadata) {
        boolean allMetadataAvailable = true;

        source.sendFeedback(literal(""));

        ArrayList<World> sortedWorlds = new ArrayList<>(worlds.values());
        sortedWorlds.sort(Comparator.comparing(it -> it.id));
        for (World world : sortedWorlds) {
            long unloadedRegions = world.knownRegions.size() - world.regions.size();
            long knownChunks = countFingerprints(world, it -> it != 0);
            long lowQualityChunks = countFingerprints(world, it -> it == 1);
            long unknownChunks = countFingerprints(world, it -> it == 0);
            long unloadedChunks = unloadedRegions * (32 * 32);

            if (unloadedRegions > 0 || unknownChunks > 0) {
                allMetadataAvailable = false;
            }

            source.sendFeedback(translatable("World %s:", world));
            source.sendFeedback(translatable("  - Regions: %s (%s loaded, %s loading)",
                    world.knownRegions.size(), world.regions.size(), world.loadingRegions.size()));
            source.sendFeedback(translatable("  - Chunks: %s (%s low quality)", knownChunks, lowQualityChunks));
            if (unknownChunks > 0) {
                source.sendFeedback(translatable("             (+ up to %s of unknown state)", unknownChunks));
            }
            if (unloadedChunks > 0) {
                source.sendFeedback(translatable("             (+ up to %s in non-loaded regions)", unloadedChunks));
            }
            if (!world.matchingWorlds.isEmpty()) {
                source.sendFeedback(translatable("  - Matching against other worlds:"));
                for (World otherWorld : sortedWorlds) {
                    Match match = world.matchingWorlds.get(otherWorld.id);
                    if (match == null) {
                        continue;
                    }
                    source.sendFeedback(translatable("    - World %s: %s/%s chunks matching/mismatching",
                            otherWorld, match.matching.size(), match.mismatching.size()));
                }
            }
            if (!world.nonMatchingWorlds.isEmpty()) {
                source.sendFeedback(translatable("  - Not matching against worlds: %s",
                        world.nonMatchingWorlds.intStream().sorted().mapToObj(String::valueOf).collect(Collectors.joining(", "))));
            }
            if (world.mergingIntoWorld != -1) {
                source.sendFeedback(translatable("  - In the process of being merged into %s", world.mergingIntoWorld));
                MergeState mergeState = world.mergeState;
                if (mergeState != null) {
                    source.sendFeedback(translatable("    - Region: %s", mergeState.activeRegion));
                    source.sendFeedback(translatable("    - Stage: %s", mergeState.stage));
                    source.sendFeedback(translatable("    - Copy jobs: %s/%s/%s pending/active/done",
                            copyExecutor.queueSize(), copyExecutor.activeWorkers(), mergeState.finishedJobs.size()));
                }
            }
        }

        if (!outdatedWorlds.isEmpty()) {
            source.sendFeedback(translatable("Outdated worlds (run `/bobby upgrade`):"));
            for (World world : outdatedWorlds) {
                source.sendFeedback(translatable("  - World %s (%s regions)", world, world.knownRegions.size()));
            }
        }

        if (!computeLegacyFingerprintJobs.isEmpty()) {
            source.sendFeedback(translatable("Fingerprint jobs: %s remaining", computeLegacyFingerprintJobs.size()));
        }

        if (!workQueue.isEmpty()) {
            source.sendFeedback(translatable("Work queue: %s jobs, bocked on %s (%s)",
                    workQueue.size(), workQueue.peek(), workQueueBlockedOn));
        }

        if (allMetadataAvailable) {
            return;
        }

        source.sendFeedback(literal(""));

        if (loadAllMetadata) {
            List<CompletableFuture<?>> futures = new ArrayList<>();

            for (World world : sortedWorlds) {
                for (long regionCoord : world.knownRegions) {
                    if (!world.regions.containsKey(regionCoord)) {
                        futures.add(world.loadRegion(RegionPos.fromLong(regionCoord)));
                    }
                }
            }
            if (!futures.isEmpty()) {
                source.sendFeedback(translatable("Loading %s regions.. (run `/bobby worlds` to see progress)", futures.size()));
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                        .thenRunAsync(() -> sendInfo(source, true), MinecraftClient.getInstance());
                return;
            }

            for (World world : sortedWorlds) {
                for (Long2ObjectMap.Entry<Region> regionEntry : world.regions.long2ObjectEntrySet()) {
                    for (Long2LongMap.Entry entry : regionEntry.getValue().chunkFingerprints.long2LongEntrySet()) {
                        long chunkCoord = entry.getLongKey();
                        long fingerprint = entry.getLongValue();
                        if (fingerprint == 0) {
                            futures.add(computeLegacyFingerprint(world, new ChunkPos(chunkCoord), source.getWorld()));
                        }
                    }
                }
            }
            if (!futures.isEmpty()) {
                source.sendFeedback(translatable("Computing fingerprints for %s chunks.. (run `/bobby worlds` to see progress)", futures.size()));
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                        .thenRunAsync(() -> sendInfo(source, false), MinecraftClient.getInstance());
            }
        } else {
            source.sendFeedback(translatable("Run `/bobby worlds full` to load non-loaded regions and compute the state of currently unknown chunks."));
        }
    }

    private long countFingerprints(World world, Long2BooleanFunction filter) {
        return world.regions.values().stream()
                .mapToLong(region -> region.chunkFingerprints.values().longStream().filter(filter).count())
                .sum();
    }

    private class World {
        private final int id;
        private int version;

        private final LongSet knownRegions = new LongOpenHashSet();
        private final Long2ObjectMap<CompletableFuture<?>> loadingRegions = new Long2ObjectOpenHashMap<>();
        private final Long2ObjectMap<Region> regions = new Long2ObjectOpenHashMap<>();
        private final Long2ObjectMap<Region> regionUpdates = new Long2ObjectOpenHashMap<>();

        private int mergingIntoWorld = -1;
        private final Int2ObjectMap<Match> matchingWorlds = new Int2ObjectOpenHashMap<>();
        private final IntSet nonMatchingWorlds = new IntOpenHashSet();

        private final FakeChunkStorage storage;

        private MergeState mergeState;

        private boolean metaDirty;
        private boolean contentDirty;

        public World(int id, int version) {
            this.id = id;
            this.version = version;
            this.storage = FakeChunkStorage.getFor(directory(), true);
        }

        @Override
        public String toString() {
            return String.valueOf(id);
        }

        public Path directory() {
            // 0 is located directly in the main folder for backwards compatibility
            if (id == 0) {
                return directory;
            }
            return directory.resolve(String.valueOf(id));
        }

        private Path regionFile(RegionPos pos) {
            return directory().resolve("r." + pos.x() + "." + pos.z() + ".meta");
        }

        public void markMetaDirty() {
            dirty = true;
            metaDirty = true;
        }

        public void markContentDirty() {
            dirty = true;
            contentDirty = true;
        }

        public void setFingerprint(ChunkPos chunkPos, long fingerprint) {
            RegionPos regionPos = RegionPos.from(chunkPos);
            long chunkCoord = chunkPos.toLong();
            long regionCoord = regionPos.toLong();

            if (knownRegions.add(regionCoord)) {
                markMetaDirty();
                regions.put(regionCoord, new Region());
            }

            Region region = regions.get(regionCoord);
            if (region == null) {
                region = regionUpdates.get(regionCoord);
                if (region == null) {
                    regionUpdates.put(regionCoord, region = new Region());
                    loadRegion(regionPos);
                }
            }

            if (region.chunkFingerprints.put(chunkCoord, fingerprint) != fingerprint) {
                region.chunks.put(chunkCoord, now);
                region.dirty = true;
                markContentDirty();
            }
        }

        public Match getMatch(World otherWorld) {
            Match match = matchingWorlds.get(otherWorld.id);
            if (match == null) {
                match = otherWorld.matchingWorlds.get(id);
                if (match == null) {
                    match = new Match(new LongOpenHashSet(), new LongOpenHashSet());
                    matchingWorlds.put(otherWorld.id, match);
                    otherWorld.matchingWorlds.put(id, match);
                }
            }
            return match;
        }

        public CompletableFuture<?> loadRegion(RegionPos regionPos) {
            long regionCoord = regionPos.toLong();

            assert lock.isWriteLockedByCurrentThread();
            assert knownRegions.contains(regionCoord);

            CompletableFuture<?> existingFuture = loadingRegions.get(regionCoord);
            if (existingFuture != null) {
                return existingFuture;
            }

            CompletableFuture<?> future = new CompletableFuture<>();
            loadingRegions.put(regionCoord, future);

            RegionLoadingJob loadingJob = new RegionLoadingJob(this, regionPos);
            regionLoadingJobs.add(loadingJob);
            regionLoadingExecutor.execute(loadingJob);

            return future;
        }

        public void writeRegionToDisk(RegionPos regionPos, NbtCompound nbt) throws IOException {
            Files.createDirectories(directory);

            Path tmpFile = Files.createTempFile(directory, "region", ".meta");
            try {
                try (OutputStream out = Files.newOutputStream(tmpFile)) {
                    NbtIo.writeCompressed(nbt, out);
                }
                Files.move(tmpFile, regionFile(regionPos), StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmpFile);
            }
        }
    }

    private static class Region {
        private final Long2LongMap chunks = new Long2LongOpenHashMap(); // value is age in millis since epoch
        private final Long2LongMap chunkFingerprints = new Long2LongOpenHashMap();
        private boolean dirty;

        public static Region read(Path file, RegionPos pos) throws IOException {
            if (Files.notExists(file)) {
                Region region = new Region();
                pos.getContainedChunks().forEach(chunkPos -> {
                    long chunkCoord = chunkPos.toLong();
                    region.chunks.put(chunkCoord, 1); // 1 means "unknown age" (0 is reserved for "no chunk")
                    region.chunkFingerprints.put(chunkCoord, 0); // 0 means "unknown fingerprint"
                });
                return region;
            }

            NbtCompound root;
            try (InputStream in = Files.newInputStream(file)) {
                root = NbtIo.readCompressed(in, NbtSizeTracker.ofUnlimitedBytes());
            }

            long[] chunkCoords = root.getLongArray("chunk_coords").orElseGet(() -> new long[0]);
            long[] chunkAges = root.getLongArray("chunk_ages").orElseGet(() -> new long[0]);
            long[] chunkFingerprints = root.getLongArray("chunk_fingerprints").orElseGet(() -> new long[0]);

            Region region = new Region();
            region.chunks.putAll(new Long2LongArrayMap(chunkCoords, chunkAges));
            region.chunkFingerprints.putAll(new Long2LongArrayMap(chunkCoords, chunkFingerprints));
            return region;
        }

        public NbtCompound saveToNbt() {
            long[] chunkCoords = new long[chunks.size()];
            long[] chunkAges = new long[chunkCoords.length];
            long[] chunkFingerprints = new long[chunkCoords.length];

            int i = 0;
            for (Long2LongMap.Entry entry : chunks.long2LongEntrySet()) {
                long coord = entry.getLongKey();
                chunkCoords[i] = coord;
                chunkAges[i] = entry.getLongValue();
                chunkFingerprints[i] = this.chunkFingerprints.get(coord);
                i++;
            }

            NbtCompound root = new NbtCompound();
            root.putLongArray("chunk_coords", chunkCoords);
            root.putLongArray("chunk_ages", chunkAges);
            root.putLongArray("chunk_fingerprints", chunkFingerprints);
            return root;
        }
    }

    private record Match(LongSet matching, LongSet mismatching) {
        public void addAll(Match other) {
            matching.addAll(other.matching);
            mismatching.addAll(other.mismatching);
        }
    }

    private static class MergeState {
        private volatile MergeStage stage = MergeStage.BlockedByOtherMerge;
        private RegionPos activeRegion;
        private final Queue<CopyJob> activeJobs = new ArrayDeque<>();
        private final List<CopyJob> finishedJobs = new ArrayList<>();
    }

    private enum MergeStage {
        BlockedByOtherMerge,
        BlockedByPreviouslyQueuedWrites,
        WaitForPreviouslyQueuedWrites,
        Idle,
        WaitForRegion,
        Copying,
        Syncing,
        WriteTargetMeta,
        SyncTargetMeta,
        DeleteSourceMeta,
        DeleteSourceStorage,
        Done,
    }

    private static class RegionLoadingJob implements Runnable {
        private final World world;
        private final RegionPos regionPos;
        private volatile Region result;

        private RegionLoadingJob(World world, RegionPos regionPos) {
            this.world = world;
            this.regionPos = regionPos;
        }

        @Override
        public void run() {
            Path file = world.regionFile(regionPos);
            try {
                result = Region.read(file, regionPos);
            } catch (IOException e) {
                LOGGER.error("Failed to load " + file, e);
            } finally {
                if (result == null) {
                    result = new Region();
                }
            }
        }
    }

    private static class ComputeLegacyFingerprintJob implements Runnable {
        private final World world;
        private final ChunkPos chunkPos;
        private final net.minecraft.world.World mcWorld;
        private final CompletableFuture<Long> future = new CompletableFuture<>();
        private volatile Long result;

        private ComputeLegacyFingerprintJob(World world, ChunkPos chunkPos, net.minecraft.world.World mcWorld) {
            this.world = world;
            this.chunkPos = chunkPos;
            this.mcWorld = mcWorld;
        }

        @Override
        public void run() {
            NbtCompound nbt = world.storage.loadTag(chunkPos).join().orElse(null);
            if (nbt == null) {
                result = 0L;
                return;
            }

            WorldChunk chunk = ChunkSerializer.deserialize(chunkPos, nbt, mcWorld).getLeft();
            result = ChunkSerializer.fingerprint(chunk);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ComputeLegacyFingerprintJob that = (ComputeLegacyFingerprintJob) o;
            return world.id == that.world.id && chunkPos.equals(that.chunkPos);
        }

        @Override
        public int hashCode() {
            // Using 127 instead of the traditional 31, so worlds are separated by more than a regular render distance
            return world.id * 127 + RegionPos.hashCode(chunkPos.toLong());
        }
    }

    private static class CopyJob implements Runnable {
        private final World source;
        private final World target;
        private final ChunkPos chunkPos;
        private final long sourceAge;
        private final long targetAge;

        private long age;
        private volatile boolean done;

        private CopyJob(World source, World target, ChunkPos chunkPos, long sourceAge, long targetAge) {
            this.source = source;
            this.target = target;
            this.chunkPos = chunkPos;
            this.sourceAge = sourceAge;
            this.targetAge = targetAge;
        }

        @Override
        public void run() {
            NbtCompound nbt = source.storage.loadTag(chunkPos).join().orElse(null);
            if (nbt == null) {
                done = true;
                return;
            }

            // If source age is unknown, check the nbt for it
            if (sourceAge == 1) {
                age = nbt.getLong("age", 0);
                if (age < targetAge) {
                    done = true;
                    return;
                }
            } else {
                age = sourceAge;
            }

            // Save doesn't return a future, so we instead wait for all writes to be done (syncing to disk only once
            // after every job for the region is done)
            target.storage.save(chunkPos, nbt);
            ((StorageIoWorker) target.storage.getWorker()).completeAll(false).join();
            done = true;
        }
    }
}
