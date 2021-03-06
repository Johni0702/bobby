package de.johni0702.minecraft.bobby;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

@ConfigSerializable
public class BobbyConfig {
    public static final BobbyConfig DEFAULT = new BobbyConfig();

    private boolean enabled = true;
    @Comment("Overwrites the view-distance of the integrated server.\nThis allows Bobby to be useful in Singleplayer.\n\nDisabled when at 0.\nBobby is active in singleplayer only if this is enabled.\nRequires re-log to en-/disable.")
    private int viewDistanceOverwrite = 0;

    public BobbyConfig() {}

    public BobbyConfig(boolean enabled, int viewDistanceOverwrite) {
        this.enabled = enabled;
        this.viewDistanceOverwrite = viewDistanceOverwrite;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getViewDistanceOverwrite() {
        return viewDistanceOverwrite;
    }
}
