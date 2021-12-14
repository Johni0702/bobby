package de.johni0702.minecraft.bobby;

import com.mojang.serialization.Codec;
import de.johni0702.minecraft.bobby.ext.ChunkLightProviderExt;
import io.netty.util.concurrent.DefaultThreadFactory;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtLongArray;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.MessageType;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.chunk.ChunkManager;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;
import net.minecraft.world.storage.StorageIoWorker;
import net.minecraft.world.storage.VersionedChunkStorage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FakeChunkStorage extends VersionedChunkStorage {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Map<Path, FakeChunkStorage> active = new HashMap<>();

    public static final Pattern REGION_FILE_PATTERN = Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mca$");

    private static final ChunkNibbleArray COMPLETELY_DARK = new ChunkNibbleArray();
    private static final ChunkNibbleArray COMPLETELY_LIT = new ChunkNibbleArray();
    static {
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    COMPLETELY_LIT.set(x, y, z, 15);
                }
            }
        }
    }
    private static final Codec<PalettedContainer<BlockState>> BLOCK_CODEC = PalettedContainer.createCodec(
            Block.STATE_IDS,
            BlockState.CODEC,
            PalettedContainer.PaletteProvider.BLOCK_STATE,
            Blocks.AIR.getDefaultState()
    );

    public static FakeChunkStorage getFor(Path directory, boolean cleanupOnClose) {
        if (!MinecraftClient.getInstance().isOnThread()) {
            throw new IllegalStateException("Must be called from main thread.");
        }
        return active.computeIfAbsent(directory, f -> new FakeChunkStorage(directory, cleanupOnClose));
    }

    public static void closeAll() {
        for (FakeChunkStorage storage : active.values()) {
            try {
                storage.close();
            } catch (IOException e) {
                LOGGER.error("Failed to close storage", e);
            }
        }
        active.clear();
    }

    private final Path directory;
    private final AtomicBoolean sentUpgradeNotification = new AtomicBoolean();
    @Nullable
    private final LastAccessFile lastAccess;

    private FakeChunkStorage(Path directory, boolean cleanupOnClose) {
        super(directory, MinecraftClient.getInstance().getDataFixer(), false);

        this.directory = directory;

        LastAccessFile lastAccess = null;
        if (cleanupOnClose) {
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

    public @Nullable NbtCompound loadTag(ChunkPos pos) throws IOException {
        NbtCompound nbt = getNbt(pos);
        if (nbt != null && lastAccess != null) {
            lastAccess.touchRegion(pos.getRegionX(), pos.getRegionZ());
        }
        if (nbt != null && nbt.getInt("DataVersion") != SharedConstants.getGameVersion().getSaveVersion().getId()) {
            if (sentUpgradeNotification.compareAndSet(false, true)) {
                MinecraftClient client = MinecraftClient.getInstance();
                client.submit(() -> {
                    TranslatableText text = new TranslatableText("bobby.upgrade.required");
                    client.submit(() -> client.inGameHud.addChatMessage(MessageType.SYSTEM, text, Util.NIL_UUID));
                });
            }
            return null;
        }
        return nbt;
    }

    public NbtCompound serialize(WorldChunk chunk, LightingProvider lightingProvider) {
        Registry<Biome> biomeRegistry = chunk.getWorld().getRegistryManager().get(Registry.BIOME_KEY);
        Codec<PalettedContainer<Biome>> biomeCodec = PalettedContainer.createCodec(
                biomeRegistry,
                biomeRegistry.getCodec(),
                PalettedContainer.PaletteProvider.BIOME,
                biomeRegistry.getOrThrow(BiomeKeys.PLAINS)
        );

        ChunkPos chunkPos = chunk.getPos();
        NbtCompound level = new NbtCompound();
        level.putInt("DataVersion", SharedConstants.getGameVersion().getSaveVersion().getId());
        level.putInt("xPos", chunkPos.x);
        level.putInt("yPos", chunk.getBottomSectionCoord());
        level.putInt("zPos", chunkPos.z);

        ChunkSection[] chunkSections = chunk.getSectionArray();
        NbtList sectionsTag = new NbtList();

        for (int y = lightingProvider.getBottomY(); y < lightingProvider.getTopY(); y++) {
            boolean empty = true;

            NbtCompound sectionTag = new NbtCompound();
            sectionTag.putByte("Y", (byte) y);

            int i = chunk.sectionCoordToIndex(y);
            ChunkSection chunkSection = i >= 0 && i < chunkSections.length ? chunkSections[i] : null;
            if (chunkSection != null) {
                sectionTag.put("block_states", BLOCK_CODEC.encodeStart(NbtOps.INSTANCE, chunkSection.getBlockStateContainer()).getOrThrow(false, LOGGER::error));
                sectionTag.put("biomes", biomeCodec.encodeStart(NbtOps.INSTANCE, chunkSection.getBiomeContainer()).getOrThrow(false, LOGGER::error));
                empty = false;
            }

            ChunkNibbleArray blockLight = lightingProvider.get(LightType.BLOCK).getLightSection(ChunkSectionPos.from(chunkPos, y));
            if (blockLight != null && !blockLight.isUninitialized()) {
                sectionTag.putByteArray("BlockLight", blockLight.asByteArray());
                empty = false;
            }

            ChunkNibbleArray skyLight = lightingProvider.get(LightType.SKY).getLightSection(ChunkSectionPos.from(chunkPos, y));
            if (skyLight != null && !skyLight.isUninitialized()) {
                sectionTag.putByteArray("SkyLight", skyLight.asByteArray());
                empty = false;
            }

            if (!empty) {
                sectionsTag.add(sectionTag);
            }
        }

        level.put("sections", sectionsTag);

        NbtList blockEntitiesTag = new NbtList();
        for (BlockPos pos : chunk.getBlockEntityPositions()) {
            NbtCompound blockEntityTag = chunk.getPackedBlockEntityNbt(pos);
            if (blockEntityTag != null) {
                blockEntitiesTag.add(blockEntityTag);
            }
        }
        level.put("block_entities", blockEntitiesTag);

        NbtCompound hightmapsTag = new NbtCompound();
        for (Map.Entry<Heightmap.Type, Heightmap> entry : chunk.getHeightmaps()) {
            if (chunk.getStatus().getHeightmapTypes().contains(entry.getKey())) {
                hightmapsTag.put(entry.getKey().getName(), new NbtLongArray(entry.getValue().asLongArray()));
            }
        }
        level.put("Heightmaps", hightmapsTag);

        return level;
    }

    // Note: This method is called asynchronously, so any methods called must either be verified to be thread safe (and
    //       must be unlikely to loose that thread safety in the presence of third party mods) or must be delayed
    //       by moving them into the returned supplier which is executed on the main thread.
    //       For performance reasons though: The more stuff we can do async, the better.
    public @Nullable Supplier<WorldChunk> deserialize(ChunkPos pos, NbtCompound level, World world) {
        BobbyConfig config = Bobby.getInstance().getConfig();

        ChunkPos chunkPos = new ChunkPos(level.getInt("xPos"), level.getInt("zPos"));
        if (!Objects.equals(pos, chunkPos)) {
            LOGGER.error("Chunk file at {} is in the wrong location; relocating. (Expected {}, got {})", pos, pos, chunkPos);
        }

        Registry<Biome> biomeRegistry = world.getRegistryManager().get(Registry.BIOME_KEY);
        Codec<PalettedContainer<Biome>> biomeCodec = PalettedContainer.createCodec(
                biomeRegistry,
                biomeRegistry.getCodec(),
                PalettedContainer.PaletteProvider.BIOME,
                biomeRegistry.getOrThrow(BiomeKeys.PLAINS)
        );

        NbtList sectionsTag = level.getList("sections", NbtElement.COMPOUND_TYPE);
        ChunkSection[] chunkSections = new ChunkSection[world.countVerticalSections()];
        ChunkNibbleArray[] blockLight = new ChunkNibbleArray[chunkSections.length + 2];
        ChunkNibbleArray[] skyLight = new ChunkNibbleArray[chunkSections.length + 2];

        Arrays.fill(blockLight, COMPLETELY_DARK);

        for (int i = 0; i < sectionsTag.size(); i++) {
            NbtCompound sectionTag = sectionsTag.getCompound(i);
            int y = sectionTag.getByte("Y");
            int yIndex = world.sectionCoordToIndex(y);

            if (yIndex < -1 || yIndex > chunkSections.length) {
                // There used to be a bug where we pass the block coordinates to the ChunkSection constructor (as was
                // done in 1.16) but the constructor expects section coordinates now, leading to an incorrect y position
                // being stored in the ChunkSection. And under specific circumstances (a chunk unload packet without
                // prior chunk load packet) we ended up saving those again, leading to this index out of bounds
                // condition.
                // We cannot just undo the scaling here because the Y stored to disk is only a byte, so the real Y may
                // have overflown. Instead we will just ignore all sections for broken chunks.
                // Or at least we used to do that but with the world height conversion, there are now legitimate OOB
                // sections (cause the converter doesn't know whether the server has an extended depth), so we'll just
                // skip the invalid ones.
                continue;
            }

            if (yIndex >= 0 && yIndex < chunkSections.length) {
                PalettedContainer<BlockState> blocks;
                if (sectionTag.contains("block_states", NbtElement.COMPOUND_TYPE)) {
                    blocks = BLOCK_CODEC.parse(NbtOps.INSTANCE, sectionTag.getCompound("block_states"))
                            .promotePartial((errorMessage) -> logRecoverableError(chunkPos, y, errorMessage))
                            .getOrThrow(false, LOGGER::error);
                } else {
                    blocks = new PalettedContainer<>(Block.STATE_IDS, Blocks.AIR.getDefaultState(), PalettedContainer.PaletteProvider.BLOCK_STATE);
                }

                PalettedContainer<Biome> biomes;
                if (sectionTag.contains("biomes", NbtElement.COMPOUND_TYPE)) {
                    biomes = biomeCodec.parse(NbtOps.INSTANCE, sectionTag.getCompound("biomes"))
                            .promotePartial((errorMessage) -> logRecoverableError(chunkPos, y, errorMessage))
                            .getOrThrow(false, LOGGER::error);
                } else {
                    biomes = new PalettedContainer<>(biomeRegistry, biomeRegistry.getOrThrow(BiomeKeys.PLAINS), PalettedContainer.PaletteProvider.BIOME);
                }

                ChunkSection chunkSection = new ChunkSection(y, blocks, biomes);
                chunkSection.calculateCounts();
                if (!chunkSection.isEmpty()) {
                    chunkSections[yIndex] = chunkSection;
                }
            }

            if (sectionTag.contains("BlockLight", NbtElement.BYTE_ARRAY_TYPE)) {
                blockLight[yIndex + 1] = new ChunkNibbleArray(sectionTag.getByteArray("BlockLight"));
            }

            if (sectionTag.contains("SkyLight", NbtElement.BYTE_ARRAY_TYPE)) {
                skyLight[yIndex + 1] = new ChunkNibbleArray(sectionTag.getByteArray("SkyLight"));
            }
        }

        // Not all light sections are stored. For block light we simply fall back to a completely dark section.
        // For sky light we need to compute the section based on those above it. We are going top to bottom section.

        // The nearest section data read from storage
        ChunkNibbleArray fullSectionAbove = null;
        // The nearest section data computed from the one above (based on its bottom-most layer).
        // May be re-used for multiple sections once computed.
        ChunkNibbleArray inferredSection = COMPLETELY_LIT;
        for (int y = skyLight.length - 1; y >= 0; y--) {
            ChunkNibbleArray section = skyLight[y];

            // If we found a section, invalidate our inferred section cache and store it for later
            if (section != null) {
                inferredSection = null;
                fullSectionAbove = section;
                continue;
            }

            // If we are missing a section, infer it from the previous full section (the result of that can be re-used)
            if (inferredSection == null) {
                assert fullSectionAbove != null; // we only clear the cache when we set this
                inferredSection = floodSkylightFromAbove(fullSectionAbove);
            }
            skyLight[y] = inferredSection;
        }

        FakeChunk chunk = new FakeChunk(world, pos, chunkSections);

        NbtCompound hightmapsTag = level.getCompound("Heightmaps");
        EnumSet<Heightmap.Type> missingHightmapTypes = EnumSet.noneOf(Heightmap.Type.class);

        for (Heightmap.Type type : chunk.getStatus().getHeightmapTypes()) {
            String key = type.getName();
            if (hightmapsTag.contains(key, NbtElement.LONG_ARRAY_TYPE)) {
                chunk.setHeightmap(type, hightmapsTag.getLongArray(key));
            } else {
                missingHightmapTypes.add(type);
            }
        }

        Heightmap.populateHeightmaps(chunk, missingHightmapTypes);

        if (!config.isNoBlockEntities()) {
            NbtList blockEntitiesTag = level.getList("block_entities", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < blockEntitiesTag.size(); i++) {
                chunk.addPendingBlockEntityNbt(blockEntitiesTag.getCompound(i));
            }
        }

        return () -> {
            boolean hasSkyLight = world.getDimension().hasSkyLight();
            ChunkManager chunkManager = world.getChunkManager();
            LightingProvider lightingProvider = chunkManager.getLightingProvider();
            ChunkLightProviderExt blockLightProvider = ChunkLightProviderExt.get(lightingProvider.get(LightType.BLOCK));
            ChunkLightProviderExt skyLightProvider = ChunkLightProviderExt.get(lightingProvider.get(LightType.SKY));

            for (int i = -1; i < chunkSections.length + 1; i++) {
                int y = world.sectionIndexToCoord(i);
                if (blockLightProvider != null) {
                    blockLightProvider.bobby_addSectionData(ChunkSectionPos.from(pos, y).asLong(), blockLight[i + 1]);
                }
                if (skyLightProvider != null && hasSkyLight) {
                    skyLightProvider.bobby_addSectionData(ChunkSectionPos.from(pos, y).asLong(), skyLight[i + 1]);
                }
            }

            chunk.setTainted(config.isTaintFakeChunks());

            // MC lazily loads block entities when they are first accessed.
            // It does so in a thread-unsafe way though, so if they are first accessed from e.g. a render thread, this
            // will cause threading issues (afaict thread-unsafe access to a chunk's block entities is still a problem
            // even in vanilla, e.g. if a block entity is removed while it is accessed, but apparently no one at Mojang
            // has run into that so far). To work around this, we force all block entities to be initialized
            // immediately, before any other code gets access to the chunk.
            for (BlockPos blockPos : chunk.getBlockEntityPositions()) {
                chunk.getBlockEntity(blockPos);
            }

            chunk.setShouldRenderOnUpdate(true);

            return chunk;
        };
    }

    private static ChunkNibbleArray floodSkylightFromAbove(ChunkNibbleArray above) {
        if (above.isUninitialized()) {
            return new ChunkNibbleArray();
        } else {
            byte[] aboveBytes = above.asByteArray();
            byte[] belowBytes = new byte[2048];

            // Copy the bottom-most slice from above, 16 time over
            for (int i = 0; i < 16; i++) {
                System.arraycopy(aboveBytes, 0, belowBytes, i * 128, 128);
            }

            return new ChunkNibbleArray(belowBytes);
        }
    }

    public void upgrade(RegistryKey<World> worldKey, BiConsumer<Integer, Integer> progress) throws IOException {
        Optional<RegistryKey<Codec<? extends ChunkGenerator>>> generatorKey =
                Optional.of(Registry.CHUNK_GENERATOR.getKey(FlatChunkGenerator.CODEC).orElseThrow());

        List<ChunkPos> chunks;
        try (Stream<Path> stream = Files.list(directory)) {
            chunks = stream
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .map(REGION_FILE_PATTERN::matcher)
                    .filter(Matcher::matches)
                    .map(it -> new RegionPos(Integer.parseInt(it.group(1)), Integer.parseInt(it.group(2))))
                    .flatMap(RegionPos::getContainedChunks)
                    .collect(Collectors.toList());
        }

        AtomicInteger done = new AtomicInteger();
        AtomicInteger total = new AtomicInteger(chunks.size());
        progress.accept(done.get(), total.get());

        StorageIoWorker io = (StorageIoWorker) getWorker();

        // We ideally split the actual work of upgrading the chunk NBT across multiple threads, leaving a few for MC
        int workThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
        ExecutorService workExecutor = Executors.newFixedThreadPool(workThreads, new DefaultThreadFactory("bobby-upgrade-worker", true));

        try {
            for (ChunkPos chunkPos : chunks) {
                workExecutor.submit(() -> {
                    NbtCompound nbt;
                    try {
                        nbt = io.getNbt(chunkPos);
                    } catch (IOException e) {
                        LOGGER.warn("Error reading chunk " + chunkPos.x + "/" + chunkPos.z + ":", e);
                        nbt = null;
                    }

                    if (nbt == null) {
                        progress.accept(done.get(), total.decrementAndGet());
                        return;
                    }

                    nbt = updateChunkNbt(worldKey, null, nbt, generatorKey);

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

    private record RegionPos(int x, int z) {
        public Stream<ChunkPos> getContainedChunks() {
            int baseX = x << 5;
            int baseZ = z << 5;
            ChunkPos[] result = new ChunkPos[32 * 32];
            for (int x = 0; x < 32; x++) {
                for (int z = 0; z < 32; z++) {
                    result[x * 32 + z] = new ChunkPos(baseX + x, baseZ + z);
                }
            }
            return Stream.of(result);
        }
    }

    private static void logRecoverableError(ChunkPos chunkPos, int y, String message) {
        LOGGER.error("Recoverable errors when loading section [" + chunkPos.x + ", " + y + ", " + chunkPos.z + "]: " + message);
    }
}
