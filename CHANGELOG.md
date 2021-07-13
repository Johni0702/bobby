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
