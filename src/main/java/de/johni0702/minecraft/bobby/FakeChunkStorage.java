package de.johni0702.minecraft.bobby;

import de.johni0702.minecraft.bobby.ext.ChunkLightProviderExt;
import de.johni0702.minecraft.bobby.mixin.LightingProviderAccessor;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.DummyClientTickScheduler;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtLongArray;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.minecraft.world.biome.source.BiomeArray;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkManager;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ColumnChunkNibbleArray;
import net.minecraft.world.chunk.UpgradeData;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import net.minecraft.world.storage.VersionedChunkStorage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public class FakeChunkStorage extends VersionedChunkStorage {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Map<File, FakeChunkStorage> active = new HashMap<>();
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

    public static FakeChunkStorage getFor(File file, BiomeSource biomeSource) {
        if (!MinecraftClient.getInstance().isOnThread()) {
            throw new IllegalStateException("Must be called from main thread.");
        }
        return active.computeIfAbsent(file, f -> new FakeChunkStorage(file, biomeSource));
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

    private final BiomeSource biomeSource;

    private FakeChunkStorage(File file, BiomeSource biomeSource) {
        super(file, null, false);
        this.biomeSource = biomeSource;
    }

    public void save(ChunkPos pos, NbtCompound chunk) {
        NbtCompound tag = new NbtCompound();
        tag.putInt("DataVersion", SharedConstants.getGameVersion().getWorldVersion());
        tag.put("Level", chunk);
        setNbt(pos, tag);
    }

    public @Nullable NbtCompound loadTag(ChunkPos pos) throws IOException {
        NbtCompound tag = getNbt(pos);
        if (tag == null) {
            return null;
        }
        return tag.getCompound("Level");
    }

    public NbtCompound serialize(Chunk chunk, LightingProvider lightingProvider) {
        ChunkPos chunkPos = chunk.getPos();
        NbtCompound level = new NbtCompound();
        level.putInt("xPos", chunkPos.x);
        level.putInt("zPos", chunkPos.z);

        ChunkSection[] chunkSections = chunk.getSectionArray();
        NbtList sectionsTag = new NbtList();

        for (ChunkSection chunkSection : chunkSections) {
            if (chunkSection == null) {
                continue;
            }
            int y = chunkSection.getYOffset() >> 4;
            boolean empty = true;

            NbtCompound sectionTag = new NbtCompound();
            sectionTag.putByte("Y", (byte) y);

            if (chunkSection != WorldChunk.EMPTY_SECTION) {
                chunkSection.getContainer().write(sectionTag, "Palette", "BlockStates");
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

        level.put("Sections", sectionsTag);

        BiomeArray biomeArray = chunk.getBiomeArray();
        if (biomeArray != null) {
            level.putIntArray("Biomes", biomeArray.toIntArray());
        }

        NbtList blockEntitiesTag = new NbtList();
        for (BlockPos pos : chunk.getBlockEntityPositions()) {
            NbtCompound blockEntityTag = chunk.getPackedBlockEntityNbt(pos);
            if (blockEntityTag != null) {
                blockEntitiesTag.add(blockEntityTag);
            }
        }
        level.put("TileEntities", blockEntitiesTag);

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

        BiomeArray biomeArray;
        if (level.contains("Biomes", 11)) {
            biomeArray = new BiomeArray(world.getRegistryManager().get(Registry.BIOME_KEY), level.getIntArray("Biomes"));
        } else if (biomeSource != null) {
            biomeArray = new BiomeArray(world.getRegistryManager().get(Registry.BIOME_KEY), chunkPos, biomeSource);
        } else {
            LOGGER.error("Chunk file at {} has neither Biomes key nor biomeSource.", pos);
            return null;
        }
        NbtList sectionsTag = level.getList("Sections", 10);
        ChunkSection[] chunkSections = new ChunkSection[16];
        ChunkNibbleArray[] blockLight = new ChunkNibbleArray[chunkSections.length];
        ChunkNibbleArray[] skyLight = new ChunkNibbleArray[chunkSections.length];

        Arrays.fill(blockLight, COMPLETELY_DARK);

        for (int i = 0; i < sectionsTag.size(); i++) {
            NbtCompound sectionTag = sectionsTag.getCompound(i);
            int y = sectionTag.getByte("Y");
            if (sectionTag.contains("Palette", 9) && sectionTag.contains("BlockStates", 12)) {
                ChunkSection chunkSection = new ChunkSection(y << 4);
                chunkSection.getContainer().read(
                        sectionTag.getList("Palette", 10),
                        sectionTag.getLongArray("BlockStates"));
                chunkSection.calculateCounts();
                if (!chunkSection.isEmpty()) {
                    chunkSections[y] = chunkSection;
                }
            }

            if (sectionTag.contains("BlockLight", 7)) {
                blockLight[y] = new ChunkNibbleArray(sectionTag.getByteArray("BlockLight"));
            }

            if (sectionTag.contains("SkyLight", 7)) {
                skyLight[y] = new ChunkNibbleArray(sectionTag.getByteArray("SkyLight"));
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
                inferredSection = new ChunkNibbleArray((new ColumnChunkNibbleArray(fullSectionAbove, 0)).asByteArray());
            }
            skyLight[y] = inferredSection;
        }

        WorldChunk chunk = new WorldChunk(
                world,
                pos,
                biomeArray,
                UpgradeData.NO_UPGRADE_DATA,
                DummyClientTickScheduler.get(),
                DummyClientTickScheduler.get(),
                0L,
                chunkSections,
                null
        );

        NbtCompound hightmapsTag = level.getCompound("Heightmaps");
        EnumSet<Heightmap.Type> missingHightmapTypes = EnumSet.noneOf(Heightmap.Type.class);

        for (Heightmap.Type type : chunk.getStatus().getHeightmapTypes()) {
            String key = type.getName();
            if (hightmapsTag.contains(key, 12)) {
                chunk.setHeightmap(type, hightmapsTag.getLongArray(key));
            } else {
                missingHightmapTypes.add(type);
            }
        }

        Heightmap.populateHeightmaps(chunk, missingHightmapTypes);

        if (!config.isNoBlockEntities()) {
            NbtList blockEntitiesTag = level.getList("TileEntities", 10);
            for (int i = 0; i < blockEntitiesTag.size(); i++) {
                chunk.addPendingBlockEntityNbt(blockEntitiesTag.getCompound(i));
            }
        }

        return () -> {
            boolean hasSkyLight = world.getDimension().hasSkyLight();
            ChunkManager chunkManager = world.getChunkManager();
            LightingProviderAccessor lightingProvider = (LightingProviderAccessor) chunkManager.getLightingProvider();
            ChunkLightProviderExt blockLightProvider = (ChunkLightProviderExt) lightingProvider.getBlockLightProvider();
            ChunkLightProviderExt skyLightProvider = (ChunkLightProviderExt) lightingProvider.getSkyLightProvider();

            for (int y = 0; y < chunkSections.length; y++) {
                if (blockLightProvider != null) {
                    blockLightProvider.bobby_addSectionData(ChunkSectionPos.from(pos, y).asLong(), blockLight[y]);
                }
                if (skyLightProvider != null && hasSkyLight) {
                    skyLightProvider.bobby_addSectionData(ChunkSectionPos.from(pos, y).asLong(), skyLight[y]);
                }
            }

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
}
