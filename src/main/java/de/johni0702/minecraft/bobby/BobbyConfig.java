package de.johni0702.minecraft.bobby;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

@ConfigSerializable
public class BobbyConfig {
    public static final BobbyConfig DEFAULT = new BobbyConfig();

    private boolean enabled = true;
    @Comment("Enables support for servers with multiple identically-named worlds.\n\nWhen enabled, a new local cache is started on each world change. If there are enough chunks with similar content in two caches, they will automatically be merged.\nThis does mean that you may need to walk around a little before Bobby loads in cached chunks if there is little identifying information in your world (e.g. flat worlds).\n\nYou may need to delete your existing cache if it is comprised of multiple worlds to prevent Bobby from merging into it.\nRequires a `/bobby upgrade` after enabling with existing cache.")
    private boolean dynamicMultiWorld = false;
    @Comment("Do not load block entities (e.g. chests) in fake chunks.\nThese need updating every tick which can add up.\n\nEnabled by default because the render distance for block entities is usually smaller than the server-view distance anyway.")
    private boolean noBlockEntities = true;
    @Comment("Reduces the light levels in fake chunks so you can tell the difference from real ones.")
    private boolean taintFakeChunks = false;
    @Comment("Delays the unloading of chunks which are outside your view distance.\nSaves you from having to reload all chunks when leaving the area for a short moment (e.g. cut scenes).\nDoes not work across dimensions.")
    private int unloadDelaySecs = 60;
    @Comment("Delete regions from the cache when they have not been loaded for X days.\n\nThe cache for a given world is cleaned up whenever you disconnect from the server.\nEntire worlds are cleaned up when the game starts and you have not visited them for X days.\n\nSet to -1 to disabled.\nSet to 0 to clean up everything after every disconnect.")
    private int deleteUnusedRegionsAfterDays = -1;
    @Comment("Changes the maximum value configurable for Render Distance.\n\nRequires Sodium.")
    private int maxRenderDistance = 32;
    @Comment("Overwrites the view-distance of the integrated server.\nThis allows Bobby to be useful in Singleplayer.\n\nDisabled when at 0.\nBobby is active in singleplayer only if this is enabled.\nRequires re-log to en-/disable.")
    private int viewDistanceOverwrite = 0;

    public BobbyConfig() {}

    public BobbyConfig(
            boolean enabled,
            boolean dynamicMultiWorld,
            boolean noBlockEntities,
            boolean taintFakeChunks,
            int unloadDelaySecs,
            int deleteUnusedRegionsAfterDays,
            int maxRenderDistance,
            int viewDistanceOverwrite
    ) {
        this.enabled = enabled;
        this.dynamicMultiWorld = dynamicMultiWorld;
        this.noBlockEntities = noBlockEntities;
        this.taintFakeChunks = taintFakeChunks;
        this.unloadDelaySecs = unloadDelaySecs;
        this.deleteUnusedRegionsAfterDays = deleteUnusedRegionsAfterDays;
        this.maxRenderDistance = maxRenderDistance;
        this.viewDistanceOverwrite = viewDistanceOverwrite;
    }

    public boolean isNoBlockEntities() {
        return noBlockEntities;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isDynamicMultiWorld() {
        return dynamicMultiWorld;
    }

    public boolean isTaintFakeChunks() {
        return taintFakeChunks;
    }

    public int getUnloadDelaySecs() {
        return unloadDelaySecs;
    }

    public int getDeleteUnusedRegionsAfterDays() {
        return deleteUnusedRegionsAfterDays;
    }

    public int getMaxRenderDistance() {
        return maxRenderDistance;
    }

    public int getViewDistanceOverwrite() {
        return viewDistanceOverwrite;
    }
}
