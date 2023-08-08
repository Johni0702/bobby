package de.johni0702.minecraft.bobby;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class MixinConfigPlugin implements IMixinConfigPlugin {
    private final boolean hasSodium = FabricLoader.getInstance().isModLoaded("sodium");
    private final boolean hasStarlight = FabricLoader.getInstance().isModLoaded("starlight");

    private boolean modVersionNewerOrEqual(String modId, String version) {
        var modContainer = FabricLoader.getInstance().getModContainer(modId);
        if (modContainer.isEmpty()){ return false;}
        try{
            return modContainer.get().getMetadata().getVersion().compareTo(Version.parse(version)) >= 0;
        } catch (Exception e) {
            return false;
        }
    }

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
        if (mixinClassName.contains(".sodium.")) {
            return hasSodium;
        }

        if (mixinClassName.contains(".sodium_05.")){
            return modVersionNewerOrEqual("sodium", "0.5.0");
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
}
