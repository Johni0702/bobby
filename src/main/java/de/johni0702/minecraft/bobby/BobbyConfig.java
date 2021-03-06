package de.johni0702.minecraft.bobby;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

@ConfigSerializable
public class BobbyConfig {
    public static final BobbyConfig DEFAULT = new BobbyConfig();

    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }
}
