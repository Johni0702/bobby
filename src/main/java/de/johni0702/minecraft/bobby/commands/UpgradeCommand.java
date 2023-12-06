package de.johni0702.minecraft.bobby.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import de.johni0702.minecraft.bobby.FakeChunkManager;
import de.johni0702.minecraft.bobby.FakeChunkStorage;
import de.johni0702.minecraft.bobby.ext.ClientChunkManagerExt;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.BiConsumer;

public class UpgradeCommand implements Command<FabricClientCommandSource> {
    @Override
    public int run(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        MinecraftClient client = source.getClient();
        ClientWorld world = source.getWorld();

        FakeChunkManager bobbyChunkManager = ((ClientChunkManagerExt) world.getChunkManager()).bobby_getFakeChunkManager();
        if (bobbyChunkManager == null) {
            source.sendError(Text.translatable("bobby.upgrade.not_enabled"));
            return 0;
        }
        FakeChunkStorage storage = bobbyChunkManager.getStorage();

        source.sendFeedback(Text.translatable("bobby.upgrade.begin"));
        new Thread(() -> {
            try {
                storage.upgrade(world.getRegistryKey(), new ProgressReported(client));
            } catch (IOException e) {
                e.printStackTrace();
                source.sendError(Text.of(e.getMessage()));
            }
            client.submit(() -> {
                source.sendFeedback(Text.translatable("bobby.upgrade.done"));
                bobbyChunkManager.loadMissingChunksFromCache();
            });
        }, "bobby-upgrade").start();

        return 0;
    }

    private static class ProgressReported implements BiConsumer<Integer, Integer> {
        private final MinecraftClient client;
        private Instant nextReport = Instant.MIN;
        private int done;
        private int total = Integer.MAX_VALUE;

        public ProgressReported(MinecraftClient client) {
            this.client = client;
        }

        @Override
        public synchronized void accept(Integer done, Integer total) {
            this.done = Math.max(this.done, done);
            this.total = Math.min(this.total, total);

            Instant now = Instant.now();
            if (now.isAfter(nextReport)) {
                nextReport = now.plus(3, ChronoUnit.SECONDS);

                Text text = Text.translatable("bobby.upgrade.progress", this.done, this.total);
                client.submit(() -> client.inGameHud.getChatHud().addMessage(text));
            }
        }
    }
}
