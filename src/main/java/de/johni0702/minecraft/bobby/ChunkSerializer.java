package de.johni0702.minecraft.bobby;

import com.google.common.hash.Hashing;
import com.mojang.serialization.Codec;
import de.johni0702.minecraft.bobby.ext.LightEngineExt;
import de.johni0702.minecraft.bobby.ext.LevelLightEngineExt;
import de.johni0702.minecraft.bobby.ext.WorldChunkExt;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public class ChunkSerializer {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final DataLayer COMPLETELY_DARK = new DataLayer();
    private static final DataLayer COMPLETELY_LIT = new DataLayer();
    static {
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    COMPLETELY_LIT.set(x, y, z, 15);
                }
            }
        }
    }
    private static final Codec<PalettedContainer<BlockState>> BLOCK_CODEC = PalettedContainer.codecRW(
            BlockState.CODEC,
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),
            Blocks.AIR.defaultBlockState()
    );

    public static CompoundTag serialize(LevelChunk chunk, LevelLightEngine lightingProvider) {
        RegistryAccess registryManager = chunk.getLevel().registryAccess();
        Registry<Biome> biomeRegistry = registryManager.lookupOrThrow(Registries.BIOME);
        Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec = PalettedContainer.codecRO(
                biomeRegistry.holderByNameCodec(),
                Strategy.createForBiomes(biomeRegistry.asHolderIdMap()),
                biomeRegistry.get(Biomes.PLAINS.identifier()).orElseThrow()
        );

        ChunkPos chunkPos = chunk.getPos();
        CompoundTag level = new CompoundTag();
        level.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        level.putInt("xPos", chunkPos.x());
        level.putInt("yPos", chunk.getMinSectionY());
        level.putInt("zPos", chunkPos.z());
        level.putBoolean("isLightOn", true);
        level.putString("Status", "full");

        LevelChunkSection[] chunkSections = chunk.getSections();
        ListTag sectionsTag = new ListTag();

        for (int y = lightingProvider.getMinLightSection(); y < lightingProvider.getMaxLightSection(); y++) {
            boolean empty = true;

            CompoundTag sectionTag = new CompoundTag();
            sectionTag.putByte("Y", (byte) y);

            int i = chunk.getSectionIndexFromSectionY(y);
            LevelChunkSection chunkSection = i >= 0 && i < chunkSections.length ? chunkSections[i] : null;
            if (chunkSection != null) {
                sectionTag.put("block_states", BLOCK_CODEC.encodeStart(NbtOps.INSTANCE, chunkSection.getStates()).getOrThrow());
                sectionTag.put("biomes", biomeCodec.encodeStart(NbtOps.INSTANCE, chunkSection.getBiomes()).getOrThrow());
                empty = false;
            }

            DataLayer blockLight = chunk instanceof FakeChunk fakeChunk
                    ? fakeChunk.blockLight[i + 1]
                    : lightingProvider.getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(chunkPos, y));
            if (blockLight != null && !blockLight.isEmpty()) {
                sectionTag.putByteArray("BlockLight", blockLight.getData());
                empty = false;
            }

            DataLayer skyLight = chunk instanceof FakeChunk fakeChunk
                    ? fakeChunk.skyLight[i + 1]
                    : lightingProvider.getLayerListener(LightLayer.SKY).getDataLayerData(SectionPos.of(chunkPos, y));
            if (skyLight != null && !skyLight.isEmpty()) {
                sectionTag.putByteArray("SkyLight", skyLight.getData());
                empty = false;
            }

            if (!empty) {
                sectionsTag.add(sectionTag);
            }
        }

        level.put("sections", sectionsTag);

        ListTag blockEntitiesTag;
        if (chunk instanceof FakeChunk fakeChunk) {
            blockEntitiesTag = fakeChunk.serializedBlockEntities;
        } else {
            blockEntitiesTag = new ListTag();
            for (BlockPos pos : chunk.getBlockEntitiesPos()) {
                CompoundTag blockEntityTag = chunk.getBlockEntityNbtForSaving(pos, registryManager);
                if (blockEntityTag != null) {
                    blockEntitiesTag.add(blockEntityTag);
                }
            }
        }
        level.put("block_entities", blockEntitiesTag);

        CompoundTag hightmapsTag = new CompoundTag();
        for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
            if (chunk.getPersistedStatus().heightmapsAfter().contains(entry.getKey())) {
                hightmapsTag.put(entry.getKey().getSerializationKey(), new LongArrayTag(entry.getValue().getRawData()));
            }
        }
        level.put("Heightmaps", hightmapsTag);

        return level;
    }

    // Note: This method is called asynchronously, so any methods called must either be verified to be thread safe (and
    //       must be unlikely to loose that thread safety in the presence of third party mods) or must be delayed
    //       by moving them into the returned supplier which is executed on the main thread.
    //       For performance reasons though: The more stuff we can do async, the better.
    public static Pair<LevelChunk, Supplier<LevelChunk>> deserialize(ChunkPos pos, CompoundTag level, Level world) {
        BobbyConfig config = Bobby.getInstance().getConfig();

        ChunkPos chunkPos = new ChunkPos(level.getIntOr("xPos", 0), level.getIntOr("zPos", 0));
        if (!Objects.equals(pos, chunkPos)) {
            LOGGER.error("Chunk file at {} is in the wrong location; relocating. (Expected {}, got {})", pos, pos, chunkPos);
        }

        Registry<Biome> biomeRegistry = world.registryAccess().lookupOrThrow(Registries.BIOME);
        Codec<PalettedContainer<Holder<Biome>>> biomeCodec = PalettedContainer.codecRW(
                biomeRegistry.holderByNameCodec(),
                Strategy.createForBiomes(biomeRegistry.asHolderIdMap()),
                biomeRegistry.get(Biomes.PLAINS.identifier()).orElseThrow()
        );

        ListTag sectionsTag = level.getListOrEmpty("sections");
        LevelChunkSection[] chunkSections = new LevelChunkSection[world.getSectionsCount()];
        DataLayer[] blockLight = new DataLayer[chunkSections.length + 2];
        DataLayer[] skyLight = new DataLayer[chunkSections.length + 2];

        Arrays.fill(blockLight, COMPLETELY_DARK);

        for (int i = 0; i < sectionsTag.size(); i++) {
            Optional<CompoundTag> maybeSectionTag = sectionsTag.getCompound(i);
            if (maybeSectionTag.isEmpty()) continue;
            CompoundTag sectionTag = maybeSectionTag.get();
            int y = sectionTag.getByteOr("Y", (byte) 0);
            int yIndex = world.getSectionIndexFromSectionY(y);

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
                PalettedContainer<BlockState> blocks = sectionTag
                        .getCompound("block_states")
                        .map(tag -> BLOCK_CODEC.parse(NbtOps.INSTANCE, tag)
                                .promotePartial((errorMessage) -> logRecoverableError(chunkPos, y, errorMessage))
                                .getOrThrow())
                        .orElseGet(() -> new PalettedContainer<>(Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)));

                PalettedContainer<Holder<Biome>> biomes = sectionTag
                        .getCompound("biomes")
                        .map(tag -> biomeCodec.parse(NbtOps.INSTANCE, tag)
                                .promotePartial((errorMessage) -> logRecoverableError(chunkPos, y, errorMessage))
                                .getOrThrow())
                        .orElseGet(() -> new PalettedContainer<>(biomeRegistry.get(Biomes.PLAINS.identifier()).orElseThrow(), Strategy.createForBiomes(biomeRegistry.asHolderIdMap())));

                LevelChunkSection chunkSection = new LevelChunkSection(blocks, biomes);
                chunkSection.recalcBlockCounts();
                if (!chunkSection.hasOnlyAir()) {
                    chunkSections[yIndex] = chunkSection;
                }
            }

            blockLight[yIndex + 1] = sectionTag.getByteArray("BlockLight")
                    .map(DataLayer::new)
                    .orElse(null);

            skyLight[yIndex + 1] = sectionTag.getByteArray("SkyLight")
                    .map(DataLayer::new)
                    .orElse(null);
        }

        // Not all light sections are stored. For block light we simply fall back to a completely dark section.
        // For sky light we need to compute the section based on those above it. We are going top to bottom section.

        // The nearest section data read from storage
        DataLayer fullSectionAbove = null;
        // The nearest section data computed from the one above (based on its bottom-most layer).
        // May be re-used for multiple sections once computed.
        DataLayer inferredSection = COMPLETELY_LIT;
        for (int y = skyLight.length - 1; y >= 0; y--) {
            DataLayer section = skyLight[y];

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

        CompoundTag hightmapsTag = level.getCompoundOrEmpty("Heightmaps");
        EnumSet<Heightmap.Types> missingHightmapTypes = EnumSet.noneOf(Heightmap.Types.class);

        for (Heightmap.Types type : chunk.getPersistedStatus().heightmapsAfter()) {
            String key = type.getSerializationKey();
            Optional<long[]> maybeTag = hightmapsTag.getLongArray(key);
            if (maybeTag.isPresent()) {
                chunk.setHeightmap(type, maybeTag.get());
            } else {
                missingHightmapTypes.add(type);
            }
        }

        Heightmap.primeHeightmaps(chunk, missingHightmapTypes);

        if (!config.isNoBlockEntities()) {
            level.getList("block_entities")
                    .stream()
                    .flatMap(ListTag::compoundStream)
                    .forEach(chunk::setBlockEntityNbt);
        }

        return Pair.of(chunk, loadChunk(chunk, blockLight, skyLight, config));
    }

    private static Supplier<LevelChunk> loadChunk(
            FakeChunk chunk,
            DataLayer[] blockLight,
            DataLayer[] skyLight,
            BobbyConfig config
    ) {
        return () -> {
            ChunkPos pos = chunk.getPos();
            Level world = chunk.getLevel();
            LevelChunkSection[] chunkSections = chunk.getSections();

            boolean hasSkyLight = world.dimensionType().hasSkyLight();
            ChunkSource chunkManager = world.getChunkSource();
            LevelLightEngine lightingProvider = chunkManager.getLightEngine();
            LevelLightEngineExt levelLightEngineExt = LevelLightEngineExt.get(lightingProvider);
            LightEngineExt blockLightProvider = LightEngineExt.get(lightingProvider.getLayerListener(LightLayer.BLOCK));
            LightEngineExt skyLightProvider = LightEngineExt.get(lightingProvider.getLayerListener(LightLayer.SKY));

            levelLightEngineExt.bobby_enabledColumn(SectionPos.getZeroNode(pos.x(), pos.z()));

            for (int i = -1; i < chunkSections.length + 1; i++) {
                int y = world.getSectionYFromSectionIndex(i);
                if (blockLightProvider != null) {
                    blockLightProvider.bobby_addSectionData(SectionPos.of(pos, y).asLong(), blockLight[i + 1]);
                }
                if (skyLightProvider != null && hasSkyLight) {
                    skyLightProvider.bobby_addSectionData(SectionPos.of(pos, y).asLong(), skyLight[i + 1]);
                }
            }

            chunk.setTainted(config.isTaintFakeChunks());

            // MC lazily loads block entities when they are first accessed.
            // It does so in a thread-unsafe way though, so if they are first accessed from e.g. a render thread, this
            // will cause threading issues (afaict thread-unsafe access to a chunk's block entities is still a problem
            // even in vanilla, e.g. if a block entity is removed while it is accessed, but apparently no one at Mojang
            // has run into that so far). To work around this, we force all block entities to be initialized
            // immediately, before any other code gets access to the chunk.
            for (BlockPos blockPos : chunk.getBlockEntitiesPos()) {
                chunk.getBlockEntity(blockPos);
            }

            return chunk;
        };
    }

    // This method is called before the original chunk is unloaded and needs to return a supplier
    // that can be called after the chunk has been unloaded to load a fake chunk in its place.
    // It also returns a fake chunk immediately that isn't loaded into the game (yet) but can safely
    // be serialized on another thread.
    public static Pair<LevelChunk, Supplier<LevelChunk>> shallowCopy(LevelChunk original) {
        BobbyConfig config = Bobby.getInstance().getConfig();

        Level world = original.getLevel();
        ChunkPos chunkPos = original.getPos();

        LevelChunkSection[] chunkSections = original.getSections();

        DataLayer[] blockLight = new DataLayer[chunkSections.length + 2];
        DataLayer[] skyLight = new DataLayer[chunkSections.length + 2];
        LevelLightEngine lightingProvider = world.getChunkSource().getLightEngine();
        ClientboundLightUpdatePacketData initialLightData = WorldChunkExt.get(original).bobby_getInitialLightData();
        if (initialLightData != null) {
            Iterator<byte[]> blockNibbles = initialLightData.blockUpdates().iterator();
            Iterator<byte[]> skyNibbles = initialLightData.skyUpdates().iterator();
            for (int y = lightingProvider.getMinLightSection(), i = 0; y < lightingProvider.getMaxLightSection(); y++, i++) {
                boolean hasBlockData = initialLightData.blockYMask().get(i);
                boolean isBlockZero = initialLightData.emptyBlockYMask().get(i);
                if (hasBlockData || isBlockZero) {
                    blockLight[i] = hasBlockData ? new DataLayer(blockNibbles.next().clone()) : new DataLayer();
                }
                boolean hasSkyData = initialLightData.skyYMask().get(i);
                boolean isSkyZero = initialLightData.emptySkyYMask().get(i);
                if (hasSkyData || isSkyZero) {
                    skyLight[i] = hasSkyData ? new DataLayer(skyNibbles.next().clone()) : new DataLayer();
                }
            }
        } else {
            for (int y = lightingProvider.getMinLightSection(), i = 0; y < lightingProvider.getMaxLightSection(); y++, i++) {
                blockLight[i] = lightingProvider.getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(chunkPos, y));
                skyLight[i] = lightingProvider.getLayerListener(LightLayer.SKY).getDataLayerData(SectionPos.of(chunkPos, y));
            }
        }

        FakeChunk fake = new FakeChunk(world, chunkPos, chunkSections);
        fake.blockLight = blockLight;
        fake.skyLight = skyLight;

        for (Map.Entry<Heightmap.Types, Heightmap> entry : original.getHeightmaps()) {
            fake.setHeightmap(entry.getKey(), entry.getValue());
        }

        ListTag blockEntitiesTag = new ListTag();
        for (BlockPos pos : original.getBlockEntitiesPos()) {
            CompoundTag blockEntityTag = original.getBlockEntityNbtForSaving(pos, world.registryAccess());
            if (blockEntityTag != null) {
                blockEntitiesTag.add(blockEntityTag);
                if (!config.isNoBlockEntities()) {
                    fake.setBlockEntityNbt(blockEntityTag);
                }
            }
        }
        fake.serializedBlockEntities = blockEntitiesTag;

        return Pair.of(fake, loadChunk(fake, blockLight, skyLight, config));
    }

    private static DataLayer floodSkylightFromAbove(DataLayer above) {
        if (above.isEmpty()) {
            return new DataLayer();
        } else {
            byte[] aboveBytes = above.getData();
            byte[] belowBytes = new byte[2048];

            // Copy the bottom-most slice from above, 16 time over
            for (int i = 0; i < 16; i++) {
                System.arraycopy(aboveBytes, 0, belowBytes, i * 128, 128);
            }

            return new DataLayer(belowBytes);
        }
    }

    private static void logRecoverableError(ChunkPos chunkPos, int y, String message) {
        LOGGER.error("Recoverable errors when loading section [" + chunkPos.x() + ", " + y + ", " + chunkPos.z() + "]: " + message);
    }

    /**
     * Computes a fingerprint for the blocks in the given chunk.
     *
     * Merely differentiates between opaque and non-opaque block, so should be fairly fast and stable across Minecraft
     * versions.
     *
     * Never returns 0 (so it may be used to indicate an absence of a value).
     * Returns 1 if the chunk does not contain enough entropy to reliably match against other chunks (e.g. flat world
     * chunk without any notable structure).
     */
    public static long fingerprint(LevelChunk chunk) {
        LevelChunkSection[] sectionArray = chunk.getSections();

        BitSet opaqueBlocks = new BitSet(sectionArray.length * 16 * 16 * 16);

        // We consider a chunk low quality (and return 1) if there are no y layers that have mixed content.
        // I.e. each 16x16x1 layer is either completely filled or completely empty.
        boolean lowQuality = true;

        int i = 0;
        for (LevelChunkSection chunkSection : sectionArray) {
            if (chunkSection == null) {
                i += 16 * 16 * 16;
                continue;
            }
            PalettedContainer<BlockState> container = chunkSection.getStates();
            for (int y = 0; y < 16; y++) {
                int opaqueCount = 0;

                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState blockState = container.get(x, y, z);
                        if (blockState.canOcclude()) {
                            opaqueBlocks.set(i);
                            opaqueCount++;
                        }
                        i++;
                    }
                }

                if (lowQuality && opaqueCount > 0 && opaqueCount < 16 * 16) {
                    lowQuality = false;
                }
            }
        }

        if (lowQuality) {
            return 1;
        }

        long fingerprint = Hashing.farmHashFingerprint64().hashBytes(opaqueBlocks.toByteArray()).asLong();
        // 0 and 1 are reserved
        if (fingerprint == 0 || fingerprint == 1) {
            return fingerprint + 2;
        }
        return fingerprint;
    }
}
