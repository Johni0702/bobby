package de.johni0702.minecraft.bobby.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import de.johni0702.minecraft.bobby.FakeChunkManager;
import de.johni0702.minecraft.bobby.FakeChunkStorage;
import de.johni0702.minecraft.bobby.ext.ClientChunkManagerExt;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.command.CommandException;
import net.minecraft.network.message.MessageType;
import net.minecraft.text.Text;
import net.minecraft.util.registry.DynamicRegistryManager;
import net.minecraft.util.registry.Registry;

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
            throw new CommandException(Text.translatable("bobby.upgrade.not_enabled"));
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
                DynamicRegistryManager.Immutable registryManager = DynamicRegistryManager.BUILTIN.get();
                Registry<MessageType> registry = registryManager.get(Registry.MESSAGE_TYPE_KEY);
                client.submit(() -> client.inGameHud.onGameMessage(registry.get(MessageType.SYSTEM.getRegistry()), text));
            }
        }
    }
}
