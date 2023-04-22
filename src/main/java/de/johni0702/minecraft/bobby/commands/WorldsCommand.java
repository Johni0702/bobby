package de.johni0702.minecraft.bobby.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import de.johni0702.minecraft.bobby.FakeChunkManager;
import de.johni0702.minecraft.bobby.Worlds;
import de.johni0702.minecraft.bobby.ext.ClientChunkManagerExt;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;

public class WorldsCommand implements Command<FabricClientCommandSource> {

    private final boolean loadAllMetadata;

    public WorldsCommand(boolean loadAllMetadata) {
        this.loadAllMetadata = loadAllMetadata;
    }

    @Override
    public int run(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        ClientWorld world = source.getWorld();

        FakeChunkManager bobbyChunkManager = ((ClientChunkManagerExt) world.getChunkManager()).bobby_getFakeChunkManager();
        if (bobbyChunkManager == null) {
            source.sendError(Text.translatable("bobby.upgrade.not_enabled"));
            return 0;
        }

        Worlds worlds = bobbyChunkManager.getWorlds();
        if (worlds == null) {
            source.sendError(Text.translatable("bobby.dynamic_multi_world.not_enabled"));
            return 0;
        }

        worlds.sendInfo(source, loadAllMetadata);
        return 0;
    }
}
