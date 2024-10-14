package de.johni0702.minecraft.bobby;

import com.google.common.hash.Hashing;
import com.mojang.serialization.Codec;
import de.johni0702.minecraft.bobby.ext.ChunkLightProviderExt;
import de.johni0702.minecraft.bobby.ext.LightingProviderExt;
import de.johni0702.minecraft.bobby.ext.WorldChunkExt;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtLongArray;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.packet.s2c.play.LightData;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.chunk.ChunkManager;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.ReadableContainer;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public class ChunkSerializer {
    private static final Logger LOGGER = LogManager.getLogger();

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
    private static final Codec<PalettedContainer<BlockState>> BLOCK_CODEC = PalettedContainer.createPalettedContainerCodec(
            Block.STATE_IDS,
            BlockState.CODEC,
            PalettedContainer.PaletteProvider.BLOCK_STATE,
            Blocks.AIR.getDefaultState()
    );

    public static NbtCompound serialize(WorldChunk chunk, LightingProvider lightingProvider) {
        DynamicRegistryManager registryManager = chunk.getWorld().getRegistryManager();
        Registry<Biome> biomeRegistry = registryManager.getOrThrow(RegistryKeys.BIOME);
        Codec<ReadableContainer<RegistryEntry<Biome>>> biomeCodec = PalettedContainer.createReadableContainerCodec(
                biomeRegistry.getIndexedEntries(),
                biomeRegistry.getEntryCodec(),
                PalettedContainer.PaletteProvider.BIOME,
                biomeRegistry.getEntry(BiomeKeys.PLAINS.getValue()).orElseThrow()
        );

        ChunkPos chunkPos = chunk.getPos();
        NbtCompound level = new NbtCompound();
        level.putInt("DataVersion", SharedConstants.getGameVersion().getSaveVersion().getId());
        level.putInt("xPos", chunkPos.x);
        level.putInt("yPos", chunk.getBottomSectionCoord());
        level.putInt("zPos", chunkPos.z);
        level.putBoolean("isLightOn", true);
        level.putString("Status", "full");

        ChunkSection[] chunkSections = chunk.getSectionArray();
        NbtList sectionsTag = new NbtList();

        for (int y = lightingProvider.getBottomY(); y < lightingProvider.getTopY(); y++) {
            boolean empty = true;

            NbtCompound sectionTag = new NbtCompound();
            sectionTag.putByte("Y", (byte) y);

            int i = chunk.sectionCoordToIndex(y);
            ChunkSection chunkSection = i >= 0 && i < chunkSections.length ? chunkSections[i] : null;
            if (chunkSection != null) {
                sectionTag.put("block_states", BLOCK_CODEC.encodeStart(NbtOps.INSTANCE, chunkSection.getBlockStateContainer()).getOrThrow());
                sectionTag.put("biomes", biomeCodec.encodeStart(NbtOps.INSTANCE, chunkSection.getBiomeContainer()).getOrThrow());
                empty = false;
            }

            ChunkNibbleArray blockLight = chunk instanceof FakeChunk fakeChunk
                    ? fakeChunk.blockLight[i + 1]
                    : lightingProvider.get(LightType.BLOCK).getLightSection(ChunkSectionPos.from(chunkPos, y));
            if (blockLight != null && !blockLight.isUninitialized()) {
                sectionTag.putByteArray("BlockLight", blockLight.asByteArray());
                empty = false;
            }

            ChunkNibbleArray skyLight = chunk instanceof FakeChunk fakeChunk
                    ? fakeChunk.skyLight[i + 1]
                    : lightingProvider.get(LightType.SKY).getLightSection(ChunkSectionPos.from(chunkPos, y));
            if (skyLight != null && !skyLight.isUninitialized()) {
                sectionTag.putByteArray("SkyLight", skyLight.asByteArray());
                empty = false;
            }

            if (!empty) {
                sectionsTag.add(sectionTag);
            }
        }

        level.put("sections", sectionsTag);

        NbtList blockEntitiesTag;
        if (chunk instanceof FakeChunk fakeChunk) {
            blockEntitiesTag = fakeChunk.serializedBlockEntities;
        } else {
            blockEntitiesTag = new NbtList();
            for (BlockPos pos : chunk.getBlockEntityPositions()) {
                NbtCompound blockEntityTag = chunk.getPackedBlockEntityNbt(pos, registryManager);
                if (blockEntityTag != null) {
                    blockEntitiesTag.add(blockEntityTag);
                }
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
    public static Pair<WorldChunk, Supplier<WorldChunk>> deserialize(ChunkPos pos, NbtCompound level, World world) {
        BobbyConfig config = Bobby.getInstance().getConfig();

        ChunkPos chunkPos = new ChunkPos(level.getInt("xPos"), level.getInt("zPos"));
        if (!Objects.equals(pos, chunkPos)) {
            LOGGER.error("Chunk file at {} is in the wrong location; relocating. (Expected {}, got {})", pos, pos, chunkPos);
        }

        Registry<Biome> biomeRegistry = world.getRegistryManager().getOrThrow(RegistryKeys.BIOME);
        Codec<PalettedContainer<RegistryEntry<Biome>>> biomeCodec = PalettedContainer.createPalettedContainerCodec(
                biomeRegistry.getIndexedEntries(),
                biomeRegistry.getEntryCodec(),
                PalettedContainer.PaletteProvider.BIOME,
                biomeRegistry.getEntry(BiomeKeys.PLAINS.getValue()).orElseThrow()
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
                            .getOrThrow();
                } else {
                    blocks = new PalettedContainer<>(Block.STATE_IDS, Blocks.AIR.getDefaultState(), PalettedContainer.PaletteProvider.BLOCK_STATE);
                }

                PalettedContainer<RegistryEntry<Biome>> biomes;
                if (sectionTag.contains("biomes", NbtElement.COMPOUND_TYPE)) {
                    biomes = biomeCodec.parse(NbtOps.INSTANCE, sectionTag.getCompound("biomes"))
                            .promotePartial((errorMessage) -> logRecoverableError(chunkPos, y, errorMessage))
                            .getOrThrow();
                } else {
                    biomes = new PalettedContainer<>(biomeRegistry.getIndexedEntries(), biomeRegistry.getEntry(BiomeKeys.PLAINS.getValue()).orElseThrow(), PalettedContainer.PaletteProvider.BIOME);
                }

                ChunkSection chunkSection = new ChunkSection(blocks, biomes);
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

        return Pair.of(chunk, loadChunk(chunk, blockLight, skyLight, config));
    }

    private static Supplier<WorldChunk> loadChunk(
            FakeChunk chunk,
            ChunkNibbleArray[] blockLight,
            ChunkNibbleArray[] skyLight,
            BobbyConfig config
    ) {
        return () -> {
            ChunkPos pos = chunk.getPos();
            World world = chunk.getWorld();
            ChunkSection[] chunkSections = chunk.getSectionArray();

            boolean hasSkyLight = world.getDimension().hasSkyLight();
            ChunkManager chunkManager = world.getChunkManager();
            LightingProvider lightingProvider = chunkManager.getLightingProvider();
            LightingProviderExt lightingProviderExt = LightingProviderExt.get(lightingProvider);
            ChunkLightProviderExt blockLightProvider = ChunkLightProviderExt.get(lightingProvider.get(LightType.BLOCK));
            ChunkLightProviderExt skyLightProvider = ChunkLightProviderExt.get(lightingProvider.get(LightType.SKY));

            lightingProviderExt.bobby_enabledColumn(pos.toLong());

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

            return chunk;
        };
    }

    // This method is called before the original chunk is unloaded and needs to return a supplier
    // that can be called after the chunk has been unloaded to load a fake chunk in its place.
    // It also returns a fake chunk immediately that isn't loaded into the game (yet) but can safely
    // be serialized on another thread.
    public static Pair<WorldChunk, Supplier<WorldChunk>> shallowCopy(WorldChunk original) {
        BobbyConfig config = Bobby.getInstance().getConfig();

        World world = original.getWorld();
        ChunkPos chunkPos = original.getPos();

        ChunkSection[] chunkSections = original.getSectionArray();

        ChunkNibbleArray[] blockLight = new ChunkNibbleArray[chunkSections.length + 2];
        ChunkNibbleArray[] skyLight = new ChunkNibbleArray[chunkSections.length + 2];
        LightingProvider lightingProvider = world.getChunkManager().getLightingProvider();
        LightData initialLightData = WorldChunkExt.get(original).bobby_getInitialLightData();
        if (initialLightData != null) {
            Iterator<byte[]> blockNibbles = initialLightData.getBlockNibbles().iterator();
            Iterator<byte[]> skyNibbles = initialLightData.getSkyNibbles().iterator();
            for (int y = lightingProvider.getBottomY(), i = 0; y < lightingProvider.getTopY(); y++, i++) {
                boolean hasBlockData = initialLightData.getInitedBlock().get(i);
                boolean isBlockZero = initialLightData.getUninitedBlock().get(i);
                if (hasBlockData || isBlockZero) {
                    blockLight[i] = hasBlockData ? new ChunkNibbleArray(blockNibbles.next().clone()) : new ChunkNibbleArray();
                }
                boolean hasSkyData = initialLightData.getInitedSky().get(i);
                boolean isSkyZero = initialLightData.getUninitedSky().get(i);
                if (hasSkyData || isSkyZero) {
                    skyLight[i] = hasSkyData ? new ChunkNibbleArray(skyNibbles.next().clone()) : new ChunkNibbleArray();
                }
            }
        } else {
            for (int y = lightingProvider.getBottomY(), i = 0; y < lightingProvider.getTopY(); y++, i++) {
                blockLight[i] = lightingProvider.get(LightType.BLOCK).getLightSection(ChunkSectionPos.from(chunkPos, y));
                skyLight[i] = lightingProvider.get(LightType.SKY).getLightSection(ChunkSectionPos.from(chunkPos, y));
            }
        }

        FakeChunk fake = new FakeChunk(world, chunkPos, chunkSections);
        fake.blockLight = blockLight;
        fake.skyLight = skyLight;

        for (Map.Entry<Heightmap.Type, Heightmap> entry : original.getHeightmaps()) {
            fake.setHeightmap(entry.getKey(), entry.getValue());
        }

        NbtList blockEntitiesTag = new NbtList();
        for (BlockPos pos : original.getBlockEntityPositions()) {
            NbtCompound blockEntityTag = original.getPackedBlockEntityNbt(pos, world.getRegistryManager());
            if (blockEntityTag != null) {
                blockEntitiesTag.add(blockEntityTag);
                if (!config.isNoBlockEntities()) {
                    fake.addPendingBlockEntityNbt(blockEntityTag);
                }
            }
        }
        fake.serializedBlockEntities = blockEntitiesTag;

        return Pair.of(fake, loadChunk(fake, blockLight, skyLight, config));
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

    private static void logRecoverableError(ChunkPos chunkPos, int y, String message) {
        LOGGER.error("Recoverable errors when loading section [" + chunkPos.x + ", " + y + ", " + chunkPos.z + "]: " + message);
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
    public static long fingerprint(WorldChunk chunk) {
        ChunkSection[] sectionArray = chunk.getSectionArray();

        BitSet opaqueBlocks = new BitSet(sectionArray.length * 16 * 16 * 16);

        // We consider a chunk low quality (and return 1) if there are no y layers that have mixed content.
        // I.e. each 16x16x1 layer is either completely filled or completely empty.
        boolean lowQuality = true;

        int i = 0;
        for (ChunkSection chunkSection : sectionArray) {
            if (chunkSection == null) {
                i += 16 * 16 * 16;
                continue;
            }
            PalettedContainer<BlockState> container = chunkSection.getBlockStateContainer();
            for (int y = 0; y < 16; y++) {
                int opaqueCount = 0;

                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState blockState = container.get(x, y, z);
                        if (blockState.isOpaque()) {
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
