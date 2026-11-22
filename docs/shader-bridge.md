# Shader Bridge SPI (`TurboShadersBridge`)

`dev.vulkanturbo.api.shader.TurboShadersBridge` is Pyroclast's zero-overhead Service Provider Interface (SPI) for Vulkan shader engines such as Hyaloclast (`vulkanshaders`).

---

## 1. Registration & Fast-Path Guard

- **Declarative registration**: Declare a Fabric entrypoint under `"turboshaders"` in `fabric.mod.json` implementing `TurboShadersExtension` (Pyroclast registers the first discovered entrypoint).
- **Programmatic registration**: Call `TurboShadersBridge.register(ext)` during client initialization.
- **State refresh**: Extensions call `TurboShadersBridge.refreshState()` whenever a shader pack is loaded, reloaded, or toggled.
- **Zero-overhead inactive path**: Hot rendering paths check plain `@JvmField` booleans (`active`, `swapchainOverrideActive`, `extendedBlockVerticesActive`, `entityRayTracingActive`, `skipWorldRasterizationActive`) with zero virtual dispatch or allocation when no shader pack is active.
- **Volatile capability & set caches**: `extension`, `bdaSupported`, `bdaCapabilityResolvedByExtension`, `accelStructSupported`, `rayTracingPipelineSupported`, `cachedBindlessDescriptorSet`, `cachedPackDescriptorSet`.
- **Mixin priority**: Pyroclast uses `mixinPriority = 900` in `vulkanturbo.mixins.json`. Shader engines should use a higher priority (e.g. `1100`) so Pyroclast installs RenderPearl hooks first.

---

## 2. `TurboShadersExtension` Contract

All methods on `TurboShadersExtension` provide safe defaults except `isShaderPackActive(): Boolean`.

### Identity, Queries & Diagnostics

- `val engineId: String` (`"turboshaders"`), `val displayName: String` (`engineId`)
- `isShaderPackActive(): Boolean`, `isShadowPassActive(): Boolean`, `shouldSkipWorldRasterization(): Boolean`, `isEntityRayTracingActive(): Boolean`, `shouldSuppressEntityBlobShadows(): Boolean` (defaults to `isShaderPackActive()`), `usesExtendedBlockVertexFormat(): Boolean`
- `buildCrashDiagnosticSection(): String?`, `drainDiagnosticLogs()`, `needsAfterGuiPass(): Boolean`, `createLogScreen(parent: Screen?, options: Options): Screen?`

### Vertex Format & Tesselation

- `resolveTerrainVertexFormat(layer, defaultFormat): VertexFormat`
- `onBeginBlockTesselation(blockState, pos)`, `onBeginFluidTesselation(fluidState, blockState, pos)`, `onEndSectionTesselation()`
- `populateExtendedBlockQuad(basePtr, vertexStride, x, y, z, quad, instance)`
- `populateExtendedFluidFace(basePtr, vertexStride, x0..v3, addBackFace)`

When `usesExtendedBlockVertexFormat()` is active, Pyroclast verifies `EXTENDED_TERRAIN_40B` (`40 B`: 28B vanilla header + `Normal` @ `28` `RGBA8_SNORM`, `Tangent` @ `32` `RGBA8_SNORM`, `MidUV` @ `36` `RG16_FLOAT`), writes the 28-byte header first, and delegates bytes `28..<vertexStride` to the extension. TLSF vertex heaps use `280 B` minimum alignment (`lcm(lcm(140, 40), vanillaAlign, activeStride)`) so `vertexOffset / stride` is always exact.

### Pipelines, Pass Hooks & Ordering Invariants

- `acceptsRegionMultiDrawOverride(layer, multidrawOverride): Boolean`
- `resolveRegionTerrainPipeline(layer, multidrawOverride, defaultPipeline): RenderPipeline`
- `resolveWorldPipeline(defaultPipeline): RenderPipeline`
- `onBeginRegionTerrainPass(renderPass, layer)`
- `onAfterRegionPipelineBound(renderPass, layer)`
- `onBeginNonTerrainWorldPass(renderPass)`

**Strict ordering invariants**:

1. **Region terrain pass (`RegionBatchPlan.renderLayer`)**:
   - `notifySharedQuadIndexBuffer(quadIndexBuffer)`
   - `onBeginRegionTerrainPass(renderPass, layer)` **before** `renderPass.setPipeline(...)` so 4-MRT color attachment overrides (`overridePendingPassColorViews`) are installed prior to `FrontendRenderPass.setPipeline` attachment validation.
   - `renderPass.setPipeline(...)`
   - `onAfterRegionPipelineBound(renderPass, layer)` **after** `setPipeline` (because `VulkanRenderPass.setPipeline` resets bound uniforms; also re-invoked on mid-layer compact/standard pipeline switches).
2. **Non-terrain world pass (`NonTerrainWorldPassMixin`)**:
   - Fires `onBeginNonTerrainWorldPass(renderPass)` immediately before `PreparedFrame.executeSolid`, `executeTranslucent`, and `executeTranslucentAfterTerrain`.
3. **Post-world / Pre-GUI & Post-GUI (`GameRendererMixin`)**:
   - `onAfterWorldRenderBeforeGui(cmd, mainColorTexture, mainColorView, mainDepthTexture, mainDepthView, width, height): Boolean` runs in `GameRenderer.renderLevel` **before** `render3dHud(...)` while `mainRenderTarget` still holds HUD-less world color and intact depth, using a transient `VkCommandBuffer`.
   - `notifyAfterGuiRender(cmd, uiColorTexture, uiColorView, width, height): Boolean` runs in `GameRenderer.render` **after** `GuiRenderer.render()` only when `(active || swapchainOverrideActive) && needsAfterGuiPass()`, transitioning `uiColorTexture` to `GENERAL` when `VulkanLayoutTracker` is enabled.

### Custom Descriptor Sets

- `getCustomDescriptorSetLayouts(vkDevice: Long): LongArray`
- `onBindCustomDescriptorSets(vkCommandBuffer, vkPipelineLayout, pipelineBindPoint, passKind)`
- `onBindCustomDescriptorSetsWithFirstSet(vkCommandBuffer, vkPipelineLayout, pipelineBindPoint, passKind, firstSet)`
- `PASS_KIND_*` constants: `GENERAL = 0`, `TERRAIN_SOLID = 1`, `TERRAIN_CUTOUT = 2`, `TERRAIN_TRANSLUCENT = 3`, `ENTITY = 4`.

`VulkanRenderPipelineMixin` appends non-empty custom set layouts to `VkPipelineLayoutCreateInfo.pSetLayouts` for world pipelines (excluding `gui`, `blit`, `post`, `vignette`, `crosshair`, `panorama`, `tracy`, `mojang_logo`) and tracks `(pipelineLayout -> baseCount)`. On `vkCmdBindPipeline`, if `cachedBindlessDescriptorSet != 0L`, `VulkanRenderPassMixin` binds `[bindlessSet]` or `[bindlessSet, packSet]` directly via `VK12.vkCmdBindDescriptorSets` (deduplicated per `(layout, bindless, pack)`); otherwise it calls `onBindCustomDescriptorSetsWithFirstSet`.

### Zero-Copy Geometry & Swapchain Hooks

- **Terrain slices (`UberGpuBufferMixin`)**:
  - `onSectionSliceUploadedDetailed(allocationKey, layerOrdinal, sectionPosLong, gpuBuffer, byteOffset, byteSize, vertexStride, vertexCount, isRegionShifted)` (delegating by default to `onSectionSliceUploaded(allocationKey, layerOrdinal, gpuBuffer, byteOffset, byteSize)`) fires on vertex-heap (`(meshSlot & 1) == 0`) uploads while `active == true`. When `isRegionShifted` is `true`, vertices are shifted by `(sectionPos & 3) * 16`, so TLAS instances must anchor at `sectionOrigin & ~63`.
  - `onSectionSliceFreed(allocationKey, layerOrdinal)` fires on vertex-heap frees while `active == true` (`TurboShadersBridge.onSectionSliceFreed` guards on `active`).
  - `onSharedQuadIndexBufferUpdated(gpuBuffer)`
- **Entity geometry**: `onEntityVerticesEmitted(...)`, `onEntityGeometrySubmitted(...)`.
- **Swapchain & Present**: `onConfigureSwapchain(...) -> SwapchainConfigOverride?`, `onSwapchainCreated(...)`, `interceptAcquireNextImage(...) -> Int` (`-1` = pass-through), `interceptQueuePresent(...) -> Boolean`, `interceptSurfaceBlit(...) -> Boolean`.

---

## 3. Bridge Utility APIs

`TurboShadersBridge` exposes shared helpers to keep Vulkan state consistent between Pyroclast and extensions:

- **MRT overrides**: `overridePendingPassColorViews` (invalidates render pass state cache and swaps `FrontendRenderPass.colorAttachments` + backend pending `VulkanGpuTextureView[]` before deferred `vkCmdBeginRenderingKHR`; automatically restored when a 1-target pipeline is bound).
- **Layout & cache sharing**: `notifyExternalImageLayout`, `getTrackedImageLayout`, `transitionImageLayout`, `transitionImageLayoutBatch`, `getSharedPipelineCache`, `notifyPipelineCompiled`, `invalidateRenderPassStateCache`.
- **Packed PBR math**: `computePackedFaceNormal`, `computePackedMidUv`, `computePackedTangent`.
- **Unified diagnostics**: `recordDiagnostic`, `createLogScreen`, `collectExtensionDiagnosticSections`.
