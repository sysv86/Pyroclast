# Configuration & Diagnostics

Reference for Pyroclast's configuration file, vendor auto-tuning, in-game UI, built-in profiler, diagnostic logging, and runtime flags.

---

## 1. Configuration File (`vulkanturbo.json`)

Stored at `<FabricLoader.configDir>/vulkanturbo.json` and auto-generated on first launch (`VulkanTurboConfig.kt`, `ConfigStore.kt`). All keys are booleans:

| Key | Default | Effect |
| :--- | :--- | :--- |
| `enabled` | `true` | Master toggle for Pyroclast optimizations. |
| `verboseLogging` | `false` | Logs `RenderStats` skip ratios every `5400` frames or `300` ticks (when `benchmark = false`). |
| `stateDedup` | `true` | Enables Vulkan & frontend render pass state deduplication. |
| `normalCache` | `true` | Enables packed 24-bit `SNORM8x3` normal caching on `BufferBuilder`. |
| `scopedBarriers` | `true` | Enables stage/access-scoped `VK_KHR_synchronization2` barriers. |
| `fastChunkPrep` | `true` | Enables 4×4×4 region MDI batching, directional face bucketing, and `RegionBatchPlan` reuse. |
| `mappedChunkSections` | `true` | Writes dynamic uniforms and indirect commands directly into persistently mapped GPU ring buffers. |
| `bufferViewCache` | `true` | Enables the 4096-entry texel `VkBufferView` LRU cache. |
| `fastEntityRender` | `true` | Enables direct off-heap entity, particle, weather, glyph, blit, and rect fast paths. |
| `cacheVoxelBounds` | `true` | Caches immutable `AABB` on `VoxelShape.bounds()`. |
| `asyncOcclusionCulling` | `true` | Offloads `SectionOcclusionGraph` BFS updates to `Util.backgroundExecutor()`. |
| `autoVendorOptimizations` | `true` | Auto-enables `pipelineCache` and `optimalImageLayouts` on AMD (`0x1002`/`0x1022`) and Intel (`0x8086`) unless explicitly overridden. |
| `pipelineCache` | `false` | Enables disk-backed `VkPipelineCache` (`<gameDir>/cache/vulkanturbo/`). Omitted from JSON until explicitly toggled. |
| `optimalImageLayouts` | `false` | Enables per-texture `VkImageLayout` tracking and deferred `vkCmdBeginRenderingKHR`. Omitted from JSON until explicitly toggled; latched once on device init (requires restart). |
| `benchmark` | `false` | Enables `BenchmarkRecorder` (`3600` warmup frames, `5400` sampled world-only frames). |

### High-End GPU Classification (`VulkanTurboConfig.isHighEndGpu`)

Used to scale TLSF heap sizes (`256 MiB` / `512 MiB`) and ambient sync chunk compile budgets (`3` compiles / `2.5 ms` vs `1` compile / `1.0 ms`). Evaluates `true` for discrete GPUs with `>= 12 GiB` device-local VRAM, or `>= 8 GiB` matching `rtx 3080/3090/4070/4080/4090/5070/5080/5090` or `rx 7800/7900/9070`.

---

## 2. In-Game UI & Error Notifications

- **Video Settings entry (`VideoSettingsScreenMixin`)**: Adds a **Pyroclast Settings...** button at the top of Minecraft's Video Settings screen.
- **Settings screen (`VulkanTurboSettingsScreen`)**: Organizes options into five sections (*General*, *Chunk & Occlusion*, *Rendering & Geometry*, *Vulkan Backend*, *Diagnostics*), saves changes immediately via `ConfigStore.save` + `VulkanTurbo.refreshRuntimeFlags()`, and includes a **View Log...** button (highlighted with error count when errors are present).
- **Error Log screen (`VulkanErrorLogScreen`)**: Displays unified Pyroclast and shader-extension session logs with Mod filter (*All* / *Pyroclast* / extension), Level filter (*All* / *Errors* / *Warnings+*), and *Copy Log*, *Clear*, *Open Logs*, and *Close* actions.
- **Toast & hotkey (`DiagnosticNotices`)**: Posts a clickable 8-second bottom-right `SystemToast` on errors and binds **`F9`** to open `VulkanErrorLogScreen` while a toast is active or unread errors exist.
- **F3 Debug Overlay**: Pyroclast does not add extra lines to F3.

---

## 3. Profiler & Diagnostic Logs

- **`FrameProfiler`**:
  - Measures GPU frame time via a 4-slot rotating non-stalling RenderPearl timestamp `GpuQueryPool` (`QUERY_ROTATIONS = 4`).
  - Tracks CPU phases (`extract`, `chunkPrep`, `render`, `acq`, `blit`, `submit`, `present`), work counters (`passes`, `barriers`, `writeBuf`, `draws`), and render-thread allocation rate (`KB/f`, `MB/s`) via `com.sun.management.ThreadMXBean`.
- **`BenchmarkRecorder`**: When `benchmark = true`, measures `5400` world-only frames (no GUI open) after `3600` warmup frames and logs average ms/FPS, p95, p99, entity count, render distance, visible sections, `FrameProfiler`, and `RenderStats` summaries.
- **`VulkanDiagnosticLog` & `CrashReportMixin`**:
  - Maintains a `512`-entry in-memory ring buffer, writes session logs to `<gameDir>/pyroclast/logs/latest.log`, and writes atomic crash dumps to `<gameDir>/pyroclast/crash-logs/crash-YYYY-MM-DD_HH-mm-ss.log` via an uncaught-exception handler and shutdown hook (`Pyroclast-CrashLog-ShutdownHook`).
  - Appends a `-- Pyroclast (VulkanTurbo) Diagnostics --` section (including extension diagnostics and the last 50 log entries) to vanilla `CrashReport`.

---

## 4. JVM & Gradle Properties

| Flag / Env Var | Scope | Effect |
| :--- | :--- | :--- |
| `-Dvt.vulkan.validation=true` or `VT_VULKAN_VALIDATION=1` | Client JVM | Injects `VK_LAYER_KHRONOS_validation` and `VK_EXT_validation_features` (Synchronization Validation) via `VulkanInstanceValidationMixin`. |
| `-Pvt.quickWorld=<saveName>` | Gradle `runClient` | Passes `--quickPlaySingleplayer <saveName>` to the dev client. |
| `-Pvt.jfr=<file>` | Gradle `runClient` | Enables Java Flight Recorder (`-XX:StartFlightRecording=filename=<file>,dumponexit=true,settings=profile` + `DebugNonSafepoints`). |
