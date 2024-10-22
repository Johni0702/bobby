package de.johni0702.minecraft.bobby.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import de.johni0702.minecraft.bobby.FakeChunkManager;
import de.johni0702.minecraft.bobby.FakeChunkStorage;
import de.johni0702.minecraft.bobby.Worlds;
import de.johni0702.minecraft.bobby.ext.ClientChunkManagerExt;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.BiConsumer;

public class UpgradeCommand implements Command<FabricClientCommandSource> {
    @Override
    public int run(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        MinecraftClient client = source.getClient();
        ClientWorld world = source.getWorld();

        ClientChunkManagerExt chunkManager = (ClientChunkManagerExt) world.getChunkManager();
        FakeChunkManager bobbyChunkManager = chunkManager.bobby_getFakeChunkManager();
        if (bobbyChunkManager == null) {
            source.sendError(Text.translatable("bobby.upgrade.not_enabled"));
            return 0;
        }

        Worlds worlds = bobbyChunkManager.getWorlds();
        List<FakeChunkStorage> storages;
        if (worlds != null) {
            storages = worlds.getOutdatedWorlds();
        } else {
            storages = List.of(bobbyChunkManager.getStorage());
        }

        source.sendFeedback(Text.translatable("bobby.upgrade.begin"));
        new Thread(() -> {
            for (int i = 0; i < storages.size(); i++) {
                FakeChunkStorage storage = storages.get(i);
                try {
                    storage.upgrade(world.getRegistryKey(), new ProgressReported(client, i, storages.size()));
                } catch (IOException e) {
                    e.printStackTrace();
                    source.sendError(Text.of(e.getMessage()));
                }
                if (worlds != null) {
                    client.execute(() -> {
                        worlds.markAsUpToDate(storage);
                    });
                }
            }
            client.submit(() -> {
                if (worlds != null) {
                    worlds.recheckChunks(world, chunkManager.bobby_getRealChunksTracker());
                }
                source.sendFeedback(Text.translatable("bobby.upgrade.done"));
                bobbyChunkManager.loadMissingChunksFromCache();
            });
        }, "bobby-upgrade").start();

        return 0;
    }

    private static class ProgressReported implements BiConsumer<Integer, Integer> {
        private final MinecraftClient client;
        private final int worldIndex;
        private final int totalWorlds;
        private Instant nextReport = Instant.MIN;
        private int done;
        private int total = Integer.MAX_VALUE;

        public ProgressReported(MinecraftClient client, int worldIndex, int totalWorlds) {
            this.client = client;
            this.worldIndex = worldIndex;
            this.totalWorlds = totalWorlds;
        }

        @Override
        public synchronized void accept(Integer done, Integer total) {
            this.done = Math.max(this.done, done);
            this.total = Math.min(this.total, total);

            Instant now = Instant.now();
            if (now.isAfter(nextReport) || this.done == this.total) {
                nextReport = now.plus(3, ChronoUnit.SECONDS);

                Text text = Text.translatable("bobby.upgrade.progress", this.done, this.total, this.worldIndex + 1, this.totalWorlds);
                client.submit(() -> client.inGameHud.getChatHud().addMessage(text));
            }
        }
    }
}
