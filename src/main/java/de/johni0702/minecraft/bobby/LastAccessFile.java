package de.johni0702.minecraft.bobby;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Util;
import net.minecraft.util.math.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.stream.Stream;

import static de.johni0702.minecraft.bobby.FakeChunkStorage.REGION_FILE_PATTERN;

/**
 * Stores approximate (~1 minute resolution) access timestamps for region files of a given folder.
 *
 * We handle this all manually because file system access times are highly unreliable.
 */
public class LastAccessFile implements Closeable {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final String FILE_NAME = "last_access";

    private final Path path;
    private final Thread finalSaveThread = new Thread(this::saveOrLog, "bobby-save-last-access");
    private boolean closed;

    private final Long2LongMap accessMap;
    private long now = timestampSeconds(); // caching this because we don't need it accurate, we need it fast

    public LastAccessFile(Path directory) throws IOException {
        this.path = directory.resolve(FILE_NAME);
        try {
            this.accessMap = read(path);
        } catch (Exception e) {
            throw new IOException("Error parsing " + path, e);
        }

        if (Files.notExists(path)) {
            try (Stream<Path> stream = Files.list(directory)) {
                for (Path path : (Iterable<Path>) (stream::iterator)) {
                    Matcher matcher = REGION_FILE_PATTERN.matcher(path.getFileName().toString());
                    if (matcher.matches()) {
                        touchRegion(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
                    }
                }
            }
        }

        scheduleSave();

        Runtime.getRuntime().addShutdownHook(finalSaveThread);
    }

    public void touchRegion(int x, int z) {
        synchronized (accessMap) {
            accessMap.put(ChunkPos.toLong(x, z), now);
        }
    }

    private void scheduleSave() {
        if (closed) return;

        Util.getIoWorkerExecutor().submit(this::saveOrLog);

        CompletableFuture.delayedExecutor(1, TimeUnit.MINUTES).execute(this::scheduleSave);
    }

    private void saveOrLog() {
        try {
            save();
        } catch (IOException e) {
            LOGGER.error("Failed to save last access file at " + path + ":", e);
        }
    }

    private synchronized void save() throws IOException {
        if (closed) return;

        PacketByteBuf buf;
        synchronized (accessMap) {
            now = timestampSeconds(); // regularly update the time

            buf = new PacketByteBuf(Unpooled.buffer(accessMap.size() * 16));

            // We are storing the most recent access time right at the start, so we can quickly scan all worlds
            buf.writeVarLong(accessMap.values().longStream().max().orElse(0));

            for (Long2LongMap.Entry entry : accessMap.long2LongEntrySet()) {
                buf.writeVarInt(ChunkPos.getPackedX(entry.getLongKey()));
                buf.writeVarInt(ChunkPos.getPackedZ(entry.getLongKey()));
                buf.writeVarLong(entry.getLongValue());
            }
        }

        Path tmpFile = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        try {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            Files.write(tmpFile, bytes);
            Files.move(tmpFile, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    private static Long2LongMap read(Path path) throws IOException {
        Long2LongMap map = new Long2LongOpenHashMap();
        if (Files.exists(path)) {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.wrappedBuffer(Files.readAllBytes(path)));
            buf.readVarLong(); // most recent timestamp
            while (buf.isReadable()) {
                int x = buf.readVarInt();
                int z = buf.readVarInt();
                map.put(ChunkPos.toLong(x, z), buf.readVarLong());
            }
        }
        return map;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;

        save();

        Runtime.getRuntime().removeShutdownHook(finalSaveThread);

        closed = true;
    }

    public LongList pollRegionsOlderThan(long days) {
        synchronized (accessMap) {
            long timestamp = timestampSeconds() - days * 24 * 60 * 60;
            LongList list = new LongArrayList();
            accessMap.long2LongEntrySet().removeIf(it -> {
                if (it.getLongValue() <= timestamp) {
                    list.add(it.getLongKey());
                    return true;
                } else {
                    return false;
                }
            });
            return list;
        }
    }

    public static boolean isEverythingOlderThan(Path directory, long days) throws IOException {
        Path path = directory.resolve(FILE_NAME);
        if (Files.notExists(path)) {
            // missing the last access file, initialize it
            new LastAccessFile(directory).close();
        }

        // Read just enough to parse the most recent timestamp (VarLong encoded)
        ByteBuf buffer = Unpooled.buffer(10);
        try (InputStream rawIn = Files.newInputStream(path); BufferedInputStream in = new BufferedInputStream(rawIn)) {
            for (int i = 0; i < 10; i++) {
                int b = in.read();
                if (b == -1) {
                    break;
                } else {
                    buffer.writeByte(b);
                }
            }
        }
        long mostRecentTimestamp = new PacketByteBuf(buffer).readVarLong();

        return mostRecentTimestamp <= timestampSeconds() - days * 24 * 60 * 60;
    }

    private static long timestampSeconds() {
        return Util.getEpochTimeMs() / 1000;
    }
}
