package de.johni0702.minecraft.bobby;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

@ConfigSerializable
public class BobbyConfig {
    public static final BobbyConfig DEFAULT = new BobbyConfig();

    private boolean enabled = true;
    @Comment("Delays the unloading of chunks which are outside your view distance.\nSaves you from having to reload all chunks when leaving the area for a short moment (e.g. cut scenes).\nDoes not work across dimensions.")
    private int unloadDelaySecs = 60;
    @Comment("Changes the maximum value configurable for Render Distance.\n\nRequires Sodium.")
    private int maxRenderDistance = 32;
    @Comment("Overwrites the view-distance of the integrated server.\nThis allows Bobby to be useful in Singleplayer.\n\nDisabled when at 0.\nBobby is active in singleplayer only if this is enabled.\nRequires re-log to en-/disable.")
    private int viewDistanceOverwrite = 0;

    public BobbyConfig() {}

    public BobbyConfig(
            boolean enabled,
            int unloadDelaySecs,
            int maxRenderDistance,
            int viewDistanceOverwrite
    ) {
        this.enabled = enabled;
        this.unloadDelaySecs = unloadDelaySecs;
        this.maxRenderDistance = maxRenderDistance;
        this.viewDistanceOverwrite = viewDistanceOverwrite;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getUnloadDelaySecs() {
        return unloadDelaySecs;
    }

    public int getMaxRenderDistance() {
        return maxRenderDistance;
    }

    public int getViewDistanceOverwrite() {
        return viewDistanceOverwrite;
    }
}
