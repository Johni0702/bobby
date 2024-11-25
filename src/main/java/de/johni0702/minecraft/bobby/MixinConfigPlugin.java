package de.johni0702.minecraft.bobby;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.util.List;
import java.util.Set;

public class MixinConfigPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bobby/Mixin");

    private final boolean hasSodium = FabricLoader.getInstance().isModLoaded("sodium");
    private final boolean hasSodium06 = hasSodium && hasClass("net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder");
    {
        if (hasSodium && !hasSodium06) {
            LOGGER.error("Sodium version appears to be neither compatible with 0.6");
        }
    }
    private final boolean hasStarlight = FabricLoader.getInstance().isModLoaded("starlight");

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!hasStarlight && targetClassName.startsWith("ca.spottedleaf.starlight.")) {
            return false;
        }
        if (mixinClassName.contains(".sodium06.")) {
            return hasSodium06;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    private static boolean hasClass(String name) {
        try {
            MixinService.getService().getBytecodeProvider().getClassNode(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Exception e) {
            LOGGER.error("Unexpected exception checking whether class exists:", e);
            return false;
        }
    }
}
