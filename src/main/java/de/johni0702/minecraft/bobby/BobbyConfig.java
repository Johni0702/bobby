package de.johni0702.minecraft.bobby;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

@ConfigSerializable
public class BobbyConfig {
    public static final BobbyConfig DEFAULT = new BobbyConfig();

    private boolean enabled = true;

    public BobbyConfig() {}

    public BobbyConfig(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
