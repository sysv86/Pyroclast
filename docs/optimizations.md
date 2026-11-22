# Engine Optimizations

Technical breakdown of Pyroclast's rendering optimizations across its four core subsystems (`src/main/kotlin/dev/vulkanturbo/` and `src/main/java/dev/vulkanturbo/mixin/`).

---

## 1. Vulkan Backend (`core/vulkan`, `mixin/vulkan`)

- **State deduplication (`VulkanRenderPassStateCache`, `VulkanRenderPassMixin`, `FrontendRenderPassMixin`)**:
  - Skips redundant `vkCmdBindPipeline`, `setUniform`, `enableScissor`/`disableScissor`, `setVertexBuffer` (slots `0..3`), and `setIndexBuffer` calls on `VulkanRenderPass`.
  - Deduplicates `setPipeline` and reuses identical `TextureViewAndSampler` bindings on `FrontendRenderPass`.
  - Uses stack scratch pointers (`VulkanSyncOptimizer.bindVertexBuffer` -> `nvkCmdBindVertexBuffers`) to avoid `DirectLongBufferU` allocations.
  - Exposes `vtInvalidateStateCache()` for external shader engines that mutate `VkCommandBuffer` state directly.
- **Scoped synchronization barriers (`VulkanSyncOptimizer`, `VulkanEncoderSyncMixin`, `VulkanSurfaceSyncMixin`)**:
  - Replaces Mojang's global `ALL_COMMANDS -> ALL_COMMANDS` barriers with per-encoder (`EncoderSyncState`) stage- and access-scoped `vkCmdPipelineBarrier2KHR` barriers tracking `BARRIER_RENDER_PASS`, `BARRIER_BUFFER_TRANSFER`, and `BARRIER_IMAGE_TRANSFER`.
  - Falls back to `ALL_COMMANDS_BIT` on untracked hazards (pass labels containing `"OIT"`, `"Transmittance"`, `"Depth Bounds"`, or `colorTargetCount > 4`).
  - Expands stage/access masks with `ACCELERATION_STRUCTURE_BUILD_BIT_KHR` and `RAY_TRACING_SHADER_BIT_KHR` when `TurboShadersBridge.accelStructSupported` / `rayTracingPipelineSupported` are active.
  - Narrows swapchain present barrier `dstStageMask` to `VK_PIPELINE_STAGE_2_NONE` and acquire wait stage to `VK_PIPELINE_STAGE_2_TRANSFER_BIT` (bypassed when `TurboShadersBridge.active || swapchainOverrideActive`).
- **Optimal image layout tracking & deferred dynamic rendering (`VulkanLayoutTracker`, `LayoutTransitionMasks`, `VulkanCommandEncoderLayoutMixin`, `VulkanRenderPassLayoutMixin`)**:
  - Tracks per-`VulkanGpuTexture` `VkImageLayout` (initialized to `GENERAL`) and transitions into `COLOR_ATTACHMENT_OPTIMAL`, `DEPTH_STENCIL_ATTACHMENT_OPTIMAL`, `DEPTH_STENCIL_READ_ONLY_OPTIMAL`, `SHADER_READ_ONLY_OPTIMAL`, `TRANSFER_SRC_OPTIMAL`, and `TRANSFER_DST_OPTIMAL` via batched barriers (`transitionBatch` / `transitionTransferPair`).
  - Defers `vkCmdBeginRenderingKHR` until the first draw call across all 8 `RenderPassBackend` draw overloads, eliding empty passes with no clear values and suspending/resuming dynamic rendering if a mid-pass sampled texture requires transition.
  - Auto-enabled on AMD (`0x1002` / `0x1022`) and Intel (`0x8086`) when `autoVendorOptimizations = true`; disabled by default on NVIDIA (`0x10DE`). Latched once on renderer initialization.
- **Disk-backed `VkPipelineCache` (`VulkanPipelineCacheManager`, `PipelineCacheHeader`, `VulkanRenderPipelineMixin`)**:
  - Supplies a persistent `VkPipelineCache` to `vkCreateGraphicsPipelines`, saved atomically (2-second debounce on `VulkanTurbo-PipelineCacheWriter` daemon thread and flushed on device close) to `<gameDir>/cache/vulkanturbo/pipeline-<vendor>-<device>-<driver>-<uuid8>.bin` with 32-byte Vulkan header + 16-byte UUID validation.
  - Auto-enabled on AMD/Intel via `autoVendorOptimizations = true`; off by default on NVIDIA.
- **Texel `VkBufferView` LRU cache (`VulkanBufferViewCache`, `BufferViewRegistry`)**:
  - Caches up to `4096` `VkBufferView` handles keyed by `(deviceAddr, vkBuffer, offset, range, format)` with an O(1) `hotEntry` pointer ahead of per-buffer buckets.
  - Defers destruction on LRU eviction and `VulkanGpuBuffer.Direct.close()` via `encoder.queueForDestroy`.
- **Persistent VMA mapping & BDA promotion (`VulkanPersistentlyMappedBuffer`, `VulkanBackendVmaMixin`)**:
  - Keeps host-mapped `VulkanGpuBuffer.Direct` buffers persistently mapped across frames (skipping `vmaUnmapMemory` until `destroy()`), checks `VK_MEMORY_PROPERTY_HOST_COHERENT_BIT` via `vmaGetAllocationMemoryProperties`, and flushes only non-coherent allocations.
  - Enables `VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT` on Vulkan 1.2+ when `TurboShadersBridge.bdaSupported` is true, promoting vertex/index buffers with `SHADER_DEVICE_ADDRESS_BIT`, `STORAGE_BUFFER_BIT`, and (when supported) `ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR`.

---

## 2. Chunk, Culling & Region Terrain (`core/chunk`, `mixin/chunk`)

- **4×4×4 Region Multi-Draw & directional face bucketing (`TerrainRegionPipelines`, `TerrainBucketBuilder`, `RegionBatchPlan`, `ChunkRenderPrepOptimizer`)**:
  - Uses bundled vertex shader `vulkanturbo:core/terrain_region` (`terrain_region.vsh`) with a 16-byte push constant (`ivec3 RegionOrigin` + `float RegionVisibility`).
  - Worker threads shift vertices by `(sectionPos & 3) * 16` into 64×64×64 region space and pack `SOLID` / `CUTOUT` quads into 7 contiguous buckets (`DOWN, UP, NORTH, SOUTH, WEST, EAST, UNASSIGNED`) with local `faceLimits[6]`. `SOLID` performs per-section directional plane culling; `CUTOUT` coalesces contiguous non-empty runs; `TRANSLUCENT` shifts/compacts in-place while preserving quad sort order.
  - `RegionDrawMode` prefers `MULTI_DRAW_EXT` (`vkCmdDrawMultiIndexedEXT`), falling back to `VK_INDIRECT` (triple-buffered GPU indirect command ring, 20-byte `VkDrawIndexedIndirectCommand`), or `NONE`. Supports standard, wireframe, and OIT (`OitPipelineSet`) pipelines.
  - `ChunkRenderPrepOptimizer.prepareIndirect` reuses the entire `RegionBatchPlan` in O(1) across frames while `visibleSectionsEpoch`, `meshUploadEpoch`, fade state, camera open-axis bounds (`safeMin/Max X/Y/Z`), and `REGION_RESORT_DIST_SQ_THRESHOLD` (`64.0`) remain valid.
- **Terrain vertex formats & TLSF heap alignment (`TerrainRegionPipelines`, `ChunkPipelineOptimizer`)**:

| Format | Stride | Layout | When Active |
| :--- | :--- | :--- | :--- |
| `COMPACT_TERRAIN_20B` | `20 B` (`-28.6%` vs vanilla) | `Position` @ `0` (`RGBA16_UINT`: `xyz` quantized at `1/512` with `+16.0` bias, `w` = packed `UV2`), `Color` @ `8` (`RGBA8_UNORM`), `UV0` @ `12` (`RG32_FLOAT`) | Region batching enabled and `TurboShadersBridge.active == false` (falls back per-section to 28B if any vertex exceeds `[-16.0, +111.998]`) |
| Vanilla `BLOCK` | `28 B` | `Position` @ `0` (`RGB32_FLOAT`), `Color` @ `12` (`RGBA8_UNORM`), `UV0` @ `16` (`RG32_FLOAT`), `UV2` @ `24` (`RG16_SINT`) | Fallback / non-region path |
| `EXTENDED_TERRAIN_40B` | `40 B` | 28B header + `Normal` @ `28` (`RGBA8_SNORM`), `Tangent` @ `32` (`RGBA8_SNORM`), `MidUV` @ `36` (`RG16_FLOAT`) | Shader pack active via `TurboShadersBridge` (`usesExtendedBlockVertexFormat()`) |

  - **Alignment**: `recommendedVertexAlignment` computes `lcm(lcm(140, 40), vanillaAlign, activeStride)` (`280 B` minimum), ensuring exact `vertexOffset / stride` division for 20B, 28B, and 40B buffers.
  - **Heap sizing**: Scales `SOLID` TLSF heaps to `512 MiB` (high-end GPU, `maxAlloc >= 512 MiB`) or `256 MiB`, and non-`SOLID` to `256 MiB` on high-end GPUs (default `128 MiB`).
- **Async occlusion culling (`AsyncOcclusionCoordinator`, `SectionOcclusionGraphMixin`)**:
  - Offloads full and partial `SectionOcclusionGraph` BFS updates to `Util.backgroundExecutor()` with single serialized worker execution, immutable camera/empty/loaded snapshots, epoch-guarded `waitAndReset()`, and lock-free render-thread `Octree` handoff.
  - Removes `Vector3d` and `BlockPos.containing` allocations in the distant-section ray-march loop.
- **Meshing, lighting, visibility & queue caches**:
  - `CompiledSectionMeshMixin` (`CachedSectionMesh`): flat arrays for 6 TLSF allocations (`layer * 2 + isIndex`), buckets, strides, and `hasBlockEntities`/`hasRenderableDraws` flags.
  - `BlockModelLighterCacheMixin` (`LighterFlatCache`): 256-slot (128×2-way) L1 flat cache with O(1) generation-stamp invalidation.
  - `BlockModelLighterMixin`: reuses thread-local `MutableBlockPos` and eliminates redundant neighbor/center lookups in `prepareQuadAmbientOcclusion`.
  - `BlockShouldRenderFaceMixin` (`OcclusionTable`): thread-local 256-entry (128×2-way) identity cache invalidated via `cacheInvalidateEpoch` on resource reload or world reset.
  - `FluidRendererMixin`: single off-heap reservation (`4*stride` or `8*stride`) with 64-bit stores and an 8-slot thread-local `MutableBlockPos` ring (`FluidScratchRing`).
  - `VisGraphMixin` + `VisibilitySetMixin`: primitive `IntArray(4096)` BFS with precomputed 1,352 boundary indices and a single `long` bitmask mirroring the 6×6 `VisibilitySet`.
  - `VoxelShapeMixin`: volatile cached immutable `AABB` on `VoxelShape.bounds()`.
  - `SimpleBitStorageMixin`: removes bounds-validation and division overhead in `get`/`set`/`getAndSet`.
  - `SectionTaskDynamicQueueMixin`: O(1) swap-remove polling; adaptive sync compile budgets (`1` compile / `1.0 ms` default; `3` compiles / `2.5 ms` on high-end GPUs).
  - `LevelExtractorBlockEntitiesMixin`: indexed scan skipping sections where `!hasBlockEntities()`.

---

## 3. Vertex & Dynamic Uniform Buffers (`core/vertex`, `mixin/vertex`)

- **`NormalCache` (`BufferBuilderMixin`)**: Per-builder cache mapping `(x, y, z) ->` packed 24-bit `SNORM8x3`, written as `memPutShort` + `memPutByte` on little-endian hosts.
- **Batch off-heap quad reservations (`BufferBuilderMixin`, `VertexConsumerMixin`)**: Verifies exact `VertexFormat` element layouts before issuing single-reservation native writes for `BLOCK` (`28 B` / `40 B`), `ENTITY` (`36 B`), `PARTICLE` (`28 B`), `POSITION_TEX_COLOR` (`24 B`), `POSITION_TEX_LIGHTMAP_COLOR` (`28 B`), and `POSITION_COLOR` (`16 B`). Reuses isolated scratch `Vector3f`/`Vector2f`.
- **Mapped dynamic uniform & indirect rings (`DynamicGpuDataMixin`, `DynamicGpuDataStorageMappedMixin`)**: `RawTransformWriter`, `RawChunkSectionWriter`, and `RawChunkCommandWriter` write `Transform`, `TerrainTransform`, `ChunkSectionInfo`, and `IndexedDraw` batches directly into persistently mapped GPU ring buffers.
- **Translucency sorting & `UberGpuBuffer` (`MeshDataMixin`, `MeshDataSortStateMixin`, `UberGpuBufferMixin`)**: Direct native `decodeQuadCentroidsFast`, packed-`long` descending float-key sorting (`Arrays.sort(long[])`), 64-bit quad index emission, `Reference2ObjectOpenHashMap` lookup, idle upload short-circuiting with empty VRAM heap reclamation.

---

## 4. Entity, Particle, Weather & GUI (`core/entity`, `mixin/entity`)

- **`ModelPartMixin` & `ModelPartCubeMixin`**: Flat `ModelPart[]` / `Cube[]` caches, direct ZYX matrix rotation without `Quaternionf`, and single-reservation `36 B` `ENTITY` native writes with `Math.fma`.
- **`ShadowFeatureRendererMixin`**: Suppresses vanilla blob shadows when `TurboShadersBridge.shouldSuppressEntityBlobShadows()` is true; otherwise batches `ShadowPiece` quads into `144 B` (`4 × 36 B`) reservations.
- **`QuadParticleRenderStateMixin`, `SingleQuadParticleMixin`, `WeatherEffectRendererMixin`**: Scalar inline quaternion-rotated quad axes into `112 B` (`4 × 28 B`) `PARTICLE` reservations.
- **GUI & Text (`BakedSheetGlyphMixin`, `GlyphRenderStateMixin`, `BlitRenderStateMixin`, `ColoredRectangleRenderStateMixin`, `GuiRenderStateMixin`)**: Caches `TextureSetup` per `GlyphRenderState`, reuses scratch `Matrix4f`, batches glyphs (`24 B` GUI / `28 B` world), blits (`96 B`), and colored rectangles (`64 B`), and uses a scalar bounds loop in `GuiRenderState.hasIntersection`.
- **`SubmitNodeCollectionMixin`**: Render-thread `PoseStack.Pose` pool (up to `16,384` poses, reset each frame in `GameRendererMixin`).
- **`EnvironmentAttributeValueSamplerMixin` & `ItemEnchantmentsMixin`**: Flat `EnvironmentAttributeLayer[]` iteration and `Collections.emptySet()` fast path for unenchanted items.
