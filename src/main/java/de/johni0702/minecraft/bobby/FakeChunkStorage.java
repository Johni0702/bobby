package de.johni0702.minecraft.bobby;

import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.DummyClientTickScheduler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
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
import net.minecraft.world.chunk.UpgradeData;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import net.minecraft.world.storage.VersionedChunkStorage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public class FakeChunkStorage extends VersionedChunkStorage {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Map<File, FakeChunkStorage> active = new HashMap<>();

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

    public void save(ChunkPos pos, CompoundTag chunk) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", SharedConstants.getGameVersion().getWorldVersion());
        tag.put("Level", chunk);
        setTagAt(pos, tag);
    }

    public @Nullable CompoundTag loadTag(ChunkPos pos) throws IOException {
        CompoundTag tag = getNbt(pos);
        if (tag == null) {
            return null;
        }
        return tag.getCompound("Level");
    }

    public CompoundTag serialize(Chunk chunk, LightingProvider lightingProvider) {
        ChunkPos chunkPos = chunk.getPos();
        CompoundTag level = new CompoundTag();
        level.putInt("xPos", chunkPos.x);
        level.putInt("zPos", chunkPos.z);

        ChunkSection[] chunkSections = chunk.getSectionArray();
        ListTag sectionsTag = new ListTag();

        for (ChunkSection chunkSection : chunkSections) {
            if (chunkSection == null) {
                continue;
            }
            int y = chunkSection.getYOffset() >> 4;
            boolean empty = true;

            CompoundTag sectionTag = new CompoundTag();
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

        ListTag blockEntitiesTag = new ListTag();
        for (BlockPos pos : chunk.getBlockEntityPositions()) {
            CompoundTag blockEntityTag = chunk.getPackedBlockEntityTag(pos);
            if (blockEntityTag != null) {
                blockEntitiesTag.add(blockEntityTag);
            }
        }
        level.put("TileEntities", blockEntitiesTag);

        CompoundTag hightmapsTag = new CompoundTag();
        for (Map.Entry<Heightmap.Type, Heightmap> entry : chunk.getHeightmaps()) {
            if (chunk.getStatus().getHeightmapTypes().contains(entry.getKey())) {
                hightmapsTag.put(entry.getKey().getName(), new LongArrayTag(entry.getValue().asLongArray()));
            }
        }
        level.put("Heightmaps", hightmapsTag);

        return level;
    }

    // Note: This method is called asynchronously, so any methods called must either be verified to be thread safe (and
    //       must be unlikely to loose that thread safety in the presence of third party mods) or must be delayed
    //       by moving them into the returned supplier which is executed on the main thread.
    //       For performance reasons though: The more stuff we can do async, the better.
    public @Nullable Supplier<WorldChunk> deserialize(ChunkPos pos, CompoundTag level, World world) {
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
        ListTag sectionsTag = level.getList("Sections", 10);
        ChunkSection[] chunkSections = new ChunkSection[16];

        for (int i = 0; i < sectionsTag.size(); i++) {
            CompoundTag sectionTag = sectionsTag.getCompound(i);
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

        CompoundTag hightmapsTag = level.getCompound("Heightmaps");
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

        ListTag blockEntitiesTag = level.getList("TileEntities", 10);
        for (int i = 0; i < blockEntitiesTag.size(); i++) {
            chunk.addPendingBlockEntityTag(blockEntitiesTag.getCompound(i));
        }

        return () -> {
            boolean hasSkyLight = world.getDimension().hasSkyLight();
            ChunkManager chunkManager = world.getChunkManager();
            LightingProvider lightingProvider = chunkManager.getLightingProvider();
            lightingProvider.setRetainData(pos, true);

            for (int i = 0; i < sectionsTag.size(); i++) {
                CompoundTag sectionTag = sectionsTag.getCompound(i);
                int y = sectionTag.getByte("Y");

                if (sectionTag.contains("BlockLight", 7)) {
                    lightingProvider.enqueueSectionData(LightType.BLOCK, ChunkSectionPos.from(pos, y),
                            new ChunkNibbleArray(sectionTag.getByteArray("BlockLight")), true);
                }

                if (hasSkyLight && sectionTag.contains("SkyLight", 7)) {
                    lightingProvider.enqueueSectionData(LightType.SKY, ChunkSectionPos.from(pos, y),
                            new ChunkNibbleArray(sectionTag.getByteArray("SkyLight")), true);
                }
            }

            return chunk;
        };
    }
}