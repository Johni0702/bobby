### 5.2.6-SNAPSHOT
- Update to Minecraft 1.21.4

### 5.2.5
- Update to Minecraft 1.21.2
- Fix missing fake chunks and "Network Protocol Error"s when server sends out-of-bounds chunk (#313)

### 5.2.4
- Add support for Sodium 0.6.0 (#308)

### 5.2.3
- Fix occasional chunk missing light data when its original chunk was loaded and immediately unloaded within 1-10 frames (#290)
- Fix newly loaded real chunks sometimes flickering black for a few frames without Sodium or Starlight (#290)

### 5.2.2
- Update to Minecraft 1.21

### 5.2.1
- Fix "Dynamic World Management" not working when switching server without full reconnect (#250) (thanks to KrisAphalon!)

### 5.2.0
- Update to Minecraft 1.20.6
- Fix game crashing/freezing when world name contains illegal characters (#67)
- Fix "This world contains data from an old version" on new worlds with "Dynamic World Management" enabled (#246)

### 5.1.0
- Add support for servers with multiple identically-named worlds (e.g. proxies) (#72)
  Note: Must be enabled in config.
  When enabled, a new local cache is started on each world change. If there are enough chunks with similar content in two caches, they will automatically be merged.
  This does mean that you may need to walk around a little before Bobby loads in cached chunks if there is little identifying information in your world (e.g. flat worlds).
  You may need to delete your existing cache if it is comprised of multiple worlds to prevent Bobby from merging into it.
- Fix compatibility with SkinShuffle (#192)

### 5.0.3
- Update to Minecraft 1.20.3

### 5.0.2
- Update to Minecraft 1.20.2
- Fix chunk at 0/0 not loading from cache when in render distance on join (#205)
- Add Ukrainian, Turkish and Belarusian translations

### 5.0.1
- Update to Sodium 0.5

### 5.0.0
- Update to Minecraft 1.20.1

### 4.0.6
- Fix sky fog rendering incorrectly as render distance goes beyond 32 (#152)
- Fix world not loading when `last_access` file has become corrupted (#92)
- Add Status field to chunk data so one could potentially use the files as a regular world
- Add Traditional Chinese translations

### 4.0.5
- Fix unloading of block entities without "Disable Block Entities" (#142)
- Fix crash when Sodium chunk_rendering mixins are disabled (#143)
- Fix light data being lost when upgrading 1.18 -> 1.19 (#136)
- Fix "Invalid client settings" with view distance greater than 127 when connecting to Paper server (#135)

### 4.0.4
- Update to Minecraft 1.19.4
- Add Brazilian Portuguese translations

### 4.0.3
- Fix server address detection on 1.19.3.
  Prior to this fix, all multiplayer worlds were saved as "unknown". If you have already explored a lot using 4.0.2, you can rename the folder in `.minecraft/.bobby/`.

### 4.0.2
- Update to Minecraft 1.19.3

### 4.0.1
- Sort fake chunks before loading so nearby chunks load first
- Fix render distance resetting after restart if above 32 (#109)
- Fix errors about Starlight in log when Starlight is not installed
- Fix lag spikes caused by chunk serialization on main thread when crossing chunk borders (#95)

### 4.0.0
- Update to Minecraft 1.19

### 3.1.1
- Update to Minecraft 1.18.2
- Remove dependency on full Confabricate (only including configurate-{core,hocon} now)

### 3.1.0
- Add option to automatically clean up unused cache after X days (#25)
- Add option to taint fake chunks, so you can tell the difference from real ones (#41)
- Force load all chunks every frame when rendering with ReplayMod or other FREX FlawlessFrames consumers
- Fix realms not writing to one consistent folder (#55)

### 3.0.1
- Allow for render distances greater than 32 chunks even without Sodium (#61)
- Fix render distance reverting to 32 in 1.18.1 (#61)
- Fix black chunks with Starlight installed (#18)

### 3.0.0
- Update to Minecraft 1.18
- Add `/bobby upgrade` to upgrade all chunks in cache to the current Minecraft version

### 2.0.4
- Fix chunk flicker with Sodium 0.3.0 (broke shortly before release)

### 2.0.3
- Update to Minecraft 1.17.1

### 2.0.2
- Support for Sodium 0.3.0
- Fix nearby fake chunks not loading after server view distance is reduced

### 2.0.1
- Fix AIOOB exception due to incorrect Y position of fake chunk sections (#27)
- Fix chunk light not properly unloading in tall worlds (#28)
- Fix chunks leaking if client view distance is not greater than server view distance
- Fix chunks reloading when changing render distance with Sodium

### 2.0.0
- Update to Minecraft 1.17

### 1.2.0
- Support for Sodium 0.2.0
- Add option to disable loading of block entities in fake chunks, active by default
- Fix loading of light data for entirely unlit or empty chunks (#24)
- Fix maximum value of view distance overwrite slider to be 32 instead of 16 (#21)

### 1.1.4
- Fix chunk flicker with Sodium when real chunk receives full update

### 1.1.3
- Fix regression introduced in 1.1.2 causing real chunks to not render with Sodium (#20)

### 1.1.2
- Fix light sometimes not updating on block breaking/placing due to race condition in Sodium-specific code (#19)

### 1.1.1
- Re-add chunk unload throttling, fixes short freeze when many chunks are unloaded (potentially 60 seconds after a teleport depending on unload delay)

### 1.1.0
- Vastly reduce main thread freezes when loading/unloading huge amounts of chunks by bypassing the lighting engine for fake chunks
- Add configurable delay to chunk unloading (default 60 seconds) so they do not need to be re-loaded when returning within the delay.
- Fix crashes due to thread-unsafe block entity initialization

### 1.0.0
- Add max render distance overwrite as config option (requires Sodium)
- Add singleplayer view-distance overwrite option (closes #2, closes #7)
- Add support for ingame config via ModMenu + ClothConfig (#2)
- Add basic config file (auto-reloads) for en-/disabling the mod (closes #2)
- Fix crash when connecting to server with port on Windows (fixes #5)
- Optimize chunk load/unload management
- Fix fake chunk loading becoming starved at high render distance
- Significantly reduce time spent on main thread for chunk loading
- Remove freezing on fake chunk loading thanks to the previous point and by spreading them out over multiple frame
- Reduce freezing on fake chunk unloading by spreading them out over multiple frame, though there is still one spike at the very end which I am unsure how to get rid of (light engine related)
- Fix invalid profiler.pop()

### 0.2.0
- Support for Sodium 0.1.0
- Fix various lighting issues
- Fix local cache becoming corrupt when switching between worlds too quickly (i.e. re-entering the same world before its cache was fully saved)
- Fix client stutter when crossing chunk boundaries
- Fix client freeze while fake chunks are being loaded (e.g. on join, after teleport, when moving around), they're now loaded in the background and will only appear once ready (instead of freezing the game until they are)
- Fix slow saving (which can then in turn be in the way when loading, causing that to be unbearably slow as well)

### 0.1.0
- Initial version
