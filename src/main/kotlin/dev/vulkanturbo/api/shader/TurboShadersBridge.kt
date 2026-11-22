package dev.vulkanturbo.api.shader

import com.mojang.blaze3d.vertex.QuadInstance
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.vertex.VertexFormat
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView
import dev.vulkanturbo.VulkanTurbo
import dev.vulkanturbo.core.NormalCache
import dev.vulkanturbo.core.VulkanFrontendRenderPassAccessor
import dev.vulkanturbo.core.VulkanLayoutTracker
import dev.vulkanturbo.core.VulkanPipelineCacheManager
import dev.vulkanturbo.core.VulkanRenderPassStateCache
import dev.vulkanturbo.core.VulkanTrackedRenderPass
import dev.vulkanturbo.core.VulkanTrackedTexture
import dev.vulkanturbo.diagnostics.VulkanDiagnosticLog
import dev.vulkanturbo.gui.VulkanErrorLogScreen
import net.minecraft.client.Options
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.client.resources.model.geometry.BakedQuad
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FluidState
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkImageBlit
import java.lang.Float.floatToRawIntBits
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Swapchain configuration override returned by [TurboShadersExtension.onConfigureSwapchain]
 * when HDR output (`A2B10G10R10_UNORM_PACK32` / `R16G16B16A16_SFLOAT`) or FrameGen requires
 * custom swapchain image format, color space, usage flags (`VK_IMAGE_USAGE_STORAGE_BIT`), or
 * present mode.
 */
data class SwapchainConfigOverride(
    val imageFormat: Int,
    val colorSpace: Int,
    val extraUsageFlags: Int,
    val presentMode: Int,
)

/**
 * First-class integration SPI for **TurboShaders** (and compatible clean-room Vulkan shader engines).
 *
 * Implementations can be registered either:
 * 1. Declaratively via the `"turboshaders"` entrypoint in `fabric.mod.json`, or
 * 2. Programmatically at runtime via [TurboShadersBridge.register].
 *
 * When no extension is registered (or when no shader pack is active), [TurboShadersBridge] folds all
 * hot-path checks down to a single plain `@JvmField` boolean read (`TurboShadersBridge.active`) with
 * zero virtual dispatch or heap allocations.
 */
interface TurboShadersExtension {
    /** Identifier of the shader engine (e.g. `"turboshaders"`). */
    val engineId: String
        get() = "turboshaders"

    /** Human-readable display name for UI and diagnostics. */
    val displayName: String
        get() = engineId

    /** Returns an optional diagnostic section to append to crash reports. */
    fun buildCrashDiagnosticSection(): String? = null

    /** Drains buffered diagnostic logs from the shader engine. */
    fun drainDiagnosticLogs() {}

    /** Returns `true` when the shader engine needs the post-GUI command buffer pass. */
    fun needsAfterGuiPass(): Boolean = false

    /** Creates an optional shader engine log viewer screen. */
    fun createLogScreen(parent: Screen?, options: Options): Screen? = null

    /** Returns `true` when a shader pipeline/pack is currently active. */
    fun isShaderPackActive(): Boolean

    /** Returns `true` while rendering the real-time shadow-map pass. */
    fun isShadowPassActive(): Boolean = false

    /**
     * Returns `true` when Tier 3 Full Path Tracing is active and vanilla world geometry
     * rasterization passes (sky, terrain, entities, clouds, weather) should be skipped in favor
     * of ray-traced primary rays, keeping only depth reprojection, block outline, and GUI.
     */
    fun shouldSkipWorldRasterization(): Boolean = false

    /**
     * Returns `true` when Tier 2 (`RayQuery`) or Tier 3 (`Path Tracing`) is active and dynamic
     * entity/block-entity/particle geometry should be streamed into the per-frame Entity BLAS.
     */
    fun isEntityRayTracingActive(): Boolean = false

    /**
     * Returns `true` when vanilla entity blob shadows (`ShadowFeatureRenderer`) should be skipped
     * because real-time shadow mapping or ray tracing is active.
     */
    fun shouldSuppressEntityBlobShadows(): Boolean = isShaderPackActive()

    /**
     * Allows TurboShaders to substitute an extended `VertexFormat` (e.g. with PBR `at_tangent`,
     * `mc_midTexCoord`, `mc_Entity` attributes) when `SectionCompiler` allocates a layer `BufferBuilder`.
     */
    fun resolveTerrainVertexFormat(layer: ChunkSectionLayer, defaultFormat: VertexFormat): VertexFormat =
        defaultFormat

    /**
     * Returns `true` if `TurboShaders` wants `VulkanTurbo`'s 4x4x4 Region Multi-Draw Indirect
     * (`RegionBatchPlan`) to execute even when a custom [multidrawOverride] pipeline is passed.
     */
    fun acceptsRegionMultiDrawOverride(
        layer: ChunkSectionLayer,
        multidrawOverride: RenderPipeline?,
    ): Boolean = false

    /**
     * Resolves the `RenderPipeline` to bind for 4x4x4 Region Multi-Draw Indirect (`SOLID`, `CUTOUT`,
     * or `TRANSLUCENT`). Allows `TurboShaders` to supply its G-buffer or shadow-map region pipeline
     * while keeping `VulkanTurbo`'s 16-byte push-constant + `vkCmdDrawMultiIndexedEXT` submission path.
     */
    fun resolveRegionTerrainPipeline(
        layer: ChunkSectionLayer,
        multidrawOverride: RenderPipeline?,
        defaultPipeline: RenderPipeline,
    ): RenderPipeline = defaultPipeline

    /**
     * Resolves a replacement `RenderPipeline` for non-terrain world draws (entities, block entities,
     * particles, water mask, sky, clouds, weather) when G-Buffer MRT or custom shader pipelines are active.
     */
    fun resolveWorldPipeline(defaultPipeline: RenderPipeline): RenderPipeline = defaultPipeline

    /**
     * Invoked inside `RegionBatchPlan.renderLayer` BEFORE `renderPass.setPipeline(...)`, allowing
     * `TurboShaders` to stage 4-MRT color attachment views: `FrontendRenderPass.setPipeline`
     * validates the pass attachment count against the pipeline's color target states, so view
     * overrides must happen first.
     */
    fun onBeginRegionTerrainPass(renderPass: RenderPass, layer: ChunkSectionLayer) {}

    /**
     * Invoked inside `RegionBatchPlan.renderLayer` right AFTER `renderPass.setPipeline(...)`.
     * `VulkanRenderPass.setPipeline` clears the backend uniform list on every pipeline switch, so
     * uniform bindings (`VsMotion`, atlases) must be (re)applied here, after the G-Buffer pipeline
     * is the currently bound one.
     */
    fun onAfterRegionPipelineBound(renderPass: RenderPass, layer: ChunkSectionLayer) {}

    /**
     * Invoked inside the main world pass at the exact boundary where Mojang finishes drawing
     * opaque/translucent terrain and begins executing entity, block-entity and particle feature
     * submits (`FeatureRenderDispatcher.PreparedFrame.execute*`) into [renderPass].
     *
     * Entity/particle draws do not go through `onBeginRegionTerrainPass`, so a shader engine that
     * renders them into a 4-MRT G-Buffer must attach its color views and `VsMotion` UBO here,
     * via [TurboShadersBridge.overridePendingPassColorViews]. The hook also fires before the
     * translucent feature batch in the same world RenderPass.
     */
    fun onBeginNonTerrainWorldPass(renderPass: RenderPass) {}

    /**
     * Returns additional `VkDescriptorSetLayout` handles (e.g. Set 1 = `WorldUBO` + G-Buffer,
     * Set 2 = `BindlessTextureTable`) to append to `VkPipelineLayoutCreateInfo` when compiling
     * shader-aware `VulkanRenderPipeline` instances.
     */
    fun getCustomDescriptorSetLayouts(vkDevice: Long): LongArray = LongArray(0)

    /**
     * Invoked when binding a graphics pipeline or before issuing draw calls inside `VulkanRenderPass`
     * or `TerrainRegionRenderer` so the shader engine can bind custom `VkDescriptorSet`s (Set 1
     * `WorldUBO`, Set 2 `BindlessTextureTable`).
     */
    fun onBindCustomDescriptorSets(
        vkCommandBuffer: Long,
        vkPipelineLayout: Long,
        pipelineBindPoint: Int,
        passKind: Int,
    ) {}

    /**
     * Variant of [onBindCustomDescriptorSets] that also receives the exact `firstSet` offset at
     * which the custom descriptor sets were appended to the pipeline layout. The default
     * implementation delegates to [onBindCustomDescriptorSets] so existing integrations keep
     * working; implementations that bind through
     * `vs_core_bind_custom_descriptor_sets(cmd, layout, bindPoint, firstSet)` should override this
     * method and forward [firstSet] verbatim.
     *
     * The legacy four-argument callback receives [firstSet] in its last slot (the public SPI
     * parameter is historically named `passKind`), so existing integrations that map that slot
     * directly to the FFI `firstSet` argument keep receiving the correct offset.
     */
    fun onBindCustomDescriptorSetsWithFirstSet(
        vkCommandBuffer: Long,
        vkPipelineLayout: Long,
        pipelineBindPoint: Int,
        passKind: Int,
        firstSet: Int,
    ) {
        onBindCustomDescriptorSets(vkCommandBuffer, vkPipelineLayout, pipelineBindPoint, firstSet)
    }

    /**
     * Returns `true` when `TurboShaders` extends `DefaultVertexFormat.BLOCK` beyond 28 bytes (or
     * attaches per-quad shader attributes via [populateExtendedBlockQuad] / [populateExtendedFluidFace]).
     */
    fun usesExtendedBlockVertexFormat(): Boolean = false

    /** Notifies the current chunk-worker thread which [BlockState] and [BlockPos] are being tessellated. */
    fun onBeginBlockTesselation(blockState: BlockState, pos: BlockPos) {}

    /** Notifies the current chunk-worker thread which [FluidState] and [BlockPos] are being tessellated. */
    fun onBeginFluidTesselation(fluidState: FluidState, blockState: BlockState, pos: BlockPos) {}

    /** Invoked on the chunk-worker thread when `SectionCompiler.compile` finishes a section. */
    fun onEndSectionTesselation() {}

    /**
     * Populates custom shader vertex attributes (at byte offsets `28 ..< vertexStride`) for all 4
     * vertices of a block quad after `VulkanTurbo` has written the standard 28-byte header per vertex.
     */
    fun populateExtendedBlockQuad(
        basePtr: Long,
        vertexStride: Int,
        x: Float,
        y: Float,
        z: Float,
        quad: BakedQuad,
        instance: QuadInstance,
    ) {}

    /**
     * Populates custom shader vertex attributes (at byte offsets `28 ..< vertexStride`) for 4 (or 8
     * when [addBackFace] is `true`) fluid vertices after `VulkanTurbo` has written the standard
     * 28-byte header per vertex.
     */
    fun populateExtendedFluidFace(
        basePtr: Long,
        vertexStride: Int,
        x0: Float, y0: Float, z0: Float, u0: Float, v0: Float,
        x1: Float, y1: Float, z1: Float, u1: Float, v1: Float,
        x2: Float, y2: Float, z2: Float, u2: Float, v2: Float,
        x3: Float, y3: Float, z3: Float, u3: Float, v3: Float,
        addBackFace: Boolean,
    ) {}

    /**
     * Notifies the shader engine when a terrain section vertex slice is uploaded into an
     * `UberGpuBuffer` heap so Tier 2/3 ray tracing can build zero-copy BLAS over the slice.
     */
    fun onSectionSliceUploaded(
        allocationKey: Any,
        layerOrdinal: Int,
        gpuBuffer: GpuBuffer,
        byteOffset: Long,
        byteSize: Long,
    ) {}

    /**
     * Detailed slice upload callback including packed `SectionPos.asLong()`, [vertexStride],
     * [vertexCount], and [isRegionShifted] (`true` when vertices are shifted by `(sectionPos & 3) * 16`
     * so TLAS instances must be anchored at `RegionOrigin = (sectionOrigin & ~63)`).
     */
    fun onSectionSliceUploadedDetailed(
        allocationKey: Any,
        layerOrdinal: Int,
        sectionPosLong: Long,
        gpuBuffer: GpuBuffer,
        byteOffset: Long,
        byteSize: Long,
        vertexStride: Int,
        vertexCount: Int,
        isRegionShifted: Boolean,
    ) {
        onSectionSliceUploaded(allocationKey, layerOrdinal, gpuBuffer, byteOffset, byteSize)
    }

    /**
     * Notifies the shader engine when a terrain section vertex slice is freed from an
     * `UberGpuBuffer` heap.
     */
    fun onSectionSliceFreed(allocationKey: Any, layerOrdinal: Int) {}

    /**
     * Notifies the shader engine of the active shared sequential quad index `GpuBuffer` (`0,1,2, 2,3,0`)
     * so zero-copy BLAS builds can reference the exact same index buffer device address.
     */
    fun onSharedQuadIndexBufferUpdated(gpuBuffer: GpuBuffer) {}

    /**
     * Streams camera-relative dynamic entity, block-entity, or particle quad vertices (`basePtr`,
     * `vertexCount`, `vertexStride`) to the shader engine when [isEntityRayTracingActive] is `true`.
     */
    fun onEntityVerticesEmitted(basePtr: Long, vertexCount: Int, vertexStride: Int) {}

    /**
     * Streams structured dynamic entity / block-entity / particle geometry and its 3x4 or 4x4
     * world-relative transform matrix into `EntityBlasManager` for TLAS construction.
     */
    fun onEntityGeometrySubmitted(
        entityId: Int,
        vertexBufferAddress: Long,
        vertexOffsetBytes: Long,
        vertexCount: Int,
        vertexStride: Int,
        indexBufferAddress: Long,
        indexCount: Int,
        transformMatrix: FloatArray,
        isBlockEntity: Boolean,
    ) {}

    /**
     * Invoked in `GameRenderer.render()` **immediately after `renderLevel()` (and `applyPostEffects()`)
     * and BEFORE `clearDepthTexture` / `GuiRenderer.render()`**.
     *
     * At this exact frame point:
     * - [mainColorTexture] contains the complete HUD-less world color buffer (`HudlessColor`),
     * - [mainDepthTexture] still contains the intact scene depth buffer (before `clearDepthTexture`
     *   wipes it to `0.0` for 3D GUI items),
     * - No GUI elements have been drawn into [mainColorTexture] yet.
     *
     * Returning `true` indicates that the shader engine executed its deferred lighting / ray tracing /
     * post-processing graph and wrote the tonemapped result back into [mainColorTexture] so
     * `GuiRenderer.render()` can composite the UI cleanly on top.
     */
    fun onAfterWorldRenderBeforeGui(
        cmd: VkCommandBuffer,
        mainColorTexture: VulkanGpuTexture,
        mainColorView: VulkanGpuTextureView,
        mainDepthTexture: VulkanGpuTexture,
        mainDepthView: VulkanGpuTextureView,
        width: Int,
        height: Int,
    ): Boolean = false

    /**
     * Invoked in `GameRenderer.render()` immediately AFTER `GuiRenderer.render()` has composited
     * the GUI over the HUD-less world color, and before the frame is presented.
     *
     * At this exact frame point [uiColorTexture] holds the fully composited UI color buffer
     * (`RES_UI_COLOR`). When FrameGen is enabled this layer must be delivered separately to
     * `PresentThread` / `optical_flow_fg.comp` instead of being baked into `RES_HUDLESS_COLOR`.
     *
     * Returning `true` indicates that the shader engine recorded work into [cmd] that must be
     * executed on the Vulkan command encoder.
     */
    fun notifyAfterGuiRender(
        cmd: VkCommandBuffer,
        uiColorTexture: VulkanGpuTexture,
        uiColorView: VulkanGpuTextureView,
        width: Int,
        height: Int,
    ): Boolean = false

    /**
     * Allows the shader engine to customize swapchain creation parameters (e.g. selecting
     * `VK_FORMAT_A2B10G10R10_UNORM_PACK32` / `VK_FORMAT_R16G16B16A16_SFLOAT`, `HDR10_ST2084` /
     * `EXTENDED_SRGB_LINEAR`, and adding `VK_IMAGE_USAGE_STORAGE_BIT` for compute composition).
     */
    fun onConfigureSwapchain(
        surfaceHandle: Long,
        requestedWidth: Int,
        requestedHeight: Int,
        defaultFormat: Int,
        defaultColorSpace: Int,
        defaultUsage: Int,
        defaultPresentMode: Int,
    ): SwapchainConfigOverride? = null

    /**
     * Notified whenever `VulkanGpuSurface.configure` creates or recreates the `VkSwapchainKHR`.
     */
    fun onSwapchainCreated(
        swapchainHandle: Long,
        images: LongArray,
        imageFormat: Int,
        colorSpace: Int,
        width: Int,
        height: Int,
    ) {}

    /**
     * Allows `PresentThread` / FrameGen to intercept `vkAcquireNextImageKHR` on `VulkanGpuSurface`.
     * Returns an acquired image index `>= 0` if handled by the shader engine, or `-1` to use the
     * default RenderPearl `vkAcquireNextImageKHR` path.
     */
    fun interceptAcquireNextImage(
        swapchainHandle: Long,
        timeoutNs: Long,
        semaphore: Long,
        fence: Long,
    ): Int = -1

    /**
     * Allows `PresentThread` / FrameGen to intercept `vkQueuePresentKHR` on `VulkanGpuSurface`
     * so RenderPearl and `PresentThread` never issue unsynchronized double presents.
     * Returns `true` if presentation was handled by the shader engine.
     */
    fun interceptQueuePresent(
        queueHandle: Long,
        swapchainHandle: Long,
        imageIndex: Int,
        waitSemaphore: Long,
    ): Boolean = false

    /**
     * Allows the shader engine to intercept the final `vkCmdBlitImage` in `VulkanGpuSurface.blitFromTexture`
     * (after `VulkanLayoutTracker` has transitioned `srcTexture`) for HDR OETF / paper-white composition
     * or FrameGen optical-flow dispatch. Returning `true` skips the default `vkCmdBlitImage` call.
     */
    fun interceptSurfaceBlit(
        cmd: VkCommandBuffer,
        srcTexture: VulkanGpuTexture?,
        srcImage: Long,
        srcLayout: Int,
        dstImage: Long,
        dstLayout: Int,
        regions: VkImageBlit.Buffer,
        filter: Int,
    ): Boolean = false
}

/**
 * Static fast-path bridge between `VulkanTurbo` and `TurboShaders`.
 */
object TurboShadersBridge {
    const val PASS_KIND_GENERAL = 0
    const val PASS_KIND_TERRAIN_SOLID = 1
    const val PASS_KIND_TERRAIN_CUTOUT = 2
    const val PASS_KIND_TERRAIN_TRANSLUCENT = 3
    const val PASS_KIND_ENTITY = 4

    @Volatile
    var extension: TurboShadersExtension? = null
        private set

    /** Plain boolean updated via [refreshState] so hot loops pay no volatile read when inactive. */
    @JvmField
    var active: Boolean = false

    @JvmField
    @Volatile
    var bdaSupported: Boolean = false

    @JvmField
    @Volatile
    var bdaCapabilityResolvedByExtension: Boolean = false

    @JvmField
    @Volatile
    var accelStructSupported: Boolean = false

    @JvmField
    var swapchainOverrideActive: Boolean = false

    @JvmField
    @Volatile
    var rayTracingPipelineSupported: Boolean = false

    /** Plain boolean indicating whether extended block vertex attributes are active. */
    @JvmField
    var extendedBlockVerticesActive: Boolean = false

    /** Plain boolean indicating whether dynamic entity/block-entity RTX geometry collection is active. */
    @JvmField
    var entityRayTracingActive: Boolean = false

    /** Plain boolean indicating whether Tier 3 Full Path Tracing skips vanilla world rasterization. */
    @JvmField
    var skipWorldRasterizationActive: Boolean = false

    /** Cached `VkDescriptorSet` handle for the bindless texture table (custom set 0). */
    @JvmField
    @Volatile
    var cachedBindlessDescriptorSet: Long = 0L

    /** Cached `VkDescriptorSet` handle for shader-pack custom descriptors (custom set 1). */
    @JvmField
    @Volatile
    var cachedPackDescriptorSet: Long = 0L

    @Volatile
    private var lastQuadIndexBuffer: GpuBuffer? = null

    /**
     * Raw `VkPipelineLayout` handle -> `firstSet` offset at which custom shader descriptor sets
     * were appended during pipeline compilation. Populated only when extra layouts were actually
     * appended, and removed when the layout is destroyed. Keeps custom descriptor binding off
     * vanilla GUI/post pipelines.
     */
    private val customSetPipelineLayouts = ConcurrentHashMap<Long, Int>()

    private val IDENTITY_MAT4 = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    /**
     * Registers (or replaces) the active [TurboShadersExtension] and refreshes fast-path flags.
     */
    @JvmStatic
    fun register(ext: TurboShadersExtension?) {
        extension = ext
        lastQuadIndexBuffer = null
        cachedBindlessDescriptorSet = 0L
        cachedPackDescriptorSet = 0L
        refreshState()
        if (ext != null) {
            VulkanTurbo.log.info("Registered TurboShaders integration bridge: {}", ext.engineId)
        }
    }

    /**
     * Called by `TurboShaders` whenever a shader pack is loaded, reloaded, or toggled on/off
     * so `VulkanTurbo`'s non-volatile fast-path flags update immediately.
     */
    @JvmStatic
    fun refreshState() {
        val ext = extension
        val isPackOn = ext != null && ext.isShaderPackActive()
        active = isPackOn
        extendedBlockVerticesActive = isPackOn && ext.usesExtendedBlockVertexFormat()
        entityRayTracingActive = isPackOn && ext.isEntityRayTracingActive()
        skipWorldRasterizationActive = isPackOn && ext.shouldSkipWorldRasterization()
        if (!isPackOn) {
            cachedBindlessDescriptorSet = 0L
            cachedPackDescriptorSet = 0L
        }
    }

    /** Updates cached custom `VkDescriptorSet` handles for direct Java-side binding without FFI overhead. */
    @JvmStatic
    fun updateCachedCustomDescriptorSets(bindlessSet: Long, packSet: Long) {
        cachedBindlessDescriptorSet = bindlessSet
        cachedPackDescriptorSet = packSet
    }

    @JvmStatic
    fun shouldSkipWorldRasterization(): Boolean {
        if (!active) return false
        return extension?.shouldSkipWorldRasterization() ?: false
    }

    @JvmStatic
    fun shouldSuppressEntityBlobShadows(): Boolean {
        if (!active) return false
        return extension?.shouldSuppressEntityBlobShadows() ?: false
    }

    @JvmStatic
    fun resolveTerrainVertexFormat(layer: ChunkSectionLayer, defaultFormat: VertexFormat): VertexFormat {
        if (!active) return defaultFormat
        return extension?.resolveTerrainVertexFormat(layer, defaultFormat) ?: defaultFormat
    }

    @JvmStatic
    fun acceptsRegionMultiDrawOverride(
        layer: ChunkSectionLayer,
        multidrawOverride: RenderPipeline?,
    ): Boolean {
        if (!active) return false
        return extension?.acceptsRegionMultiDrawOverride(layer, multidrawOverride) ?: false
    }

    @JvmStatic
    fun resolveRegionTerrainPipeline(
        layer: ChunkSectionLayer,
        multidrawOverride: RenderPipeline?,
        defaultPipeline: RenderPipeline,
    ): RenderPipeline {
        if (!active) return defaultPipeline
        return extension?.resolveRegionTerrainPipeline(layer, multidrawOverride, defaultPipeline) ?: defaultPipeline
    }

    @JvmStatic
    fun resolveWorldPipeline(defaultPipeline: RenderPipeline): RenderPipeline {
        if (!active) return defaultPipeline
        return extension?.resolveWorldPipeline(defaultPipeline) ?: defaultPipeline
    }

    @JvmStatic
    fun onBeginRegionTerrainPass(renderPass: RenderPass, layer: ChunkSectionLayer) {
        if (!active) return
        extension?.onBeginRegionTerrainPass(renderPass, layer)
    }

    @JvmStatic
    fun onAfterRegionPipelineBound(renderPass: RenderPass, layer: ChunkSectionLayer) {
        if (!active) return
        extension?.onAfterRegionPipelineBound(renderPass, layer)
    }

    @JvmStatic
    fun onBeginNonTerrainWorldPass(renderPass: RenderPass) {
        if (!active) return
        extension?.onBeginNonTerrainWorldPass(renderPass)
    }

    /**
     * Returns `true` when the pipeline identified by [pipelineName] is a world or shader G-Buffer
     * pipeline that may attach custom shader descriptor sets, excluding vanilla GUI, post-process,
     * and screen-blit pipelines.
     */
    @JvmStatic
    fun shouldAttachCustomDescriptorSets(pipelineName: String?): Boolean {
        if (pipelineName.isNullOrBlank()) return false
        val lower = pipelineName.lowercase()
        if (lower.contains("gui") ||
            lower.contains("blit") ||
            lower == "post" ||
            lower.endsWith(":post") ||
            lower.contains("/post") ||
            lower.contains("post_") ||
            lower.contains("_post") ||
            lower.contains("vignette") ||
            lower.contains("crosshair") ||
            lower.contains("panorama") ||
            lower.contains("tracy") ||
            lower.contains("mojang_logo")
        ) {
            return false
        }
        return true
    }

    @JvmStatic
    fun getCustomDescriptorSetLayouts(vkDevice: Long): LongArray {
        val ext = extension ?: return LongArray(0)
        return ext.getCustomDescriptorSetLayouts(vkDevice)
    }

    /**
     * Records that [pipelineLayout] was compiled with custom descriptor sets appended starting at
     * [firstSet]. Called from the `vkCreatePipelineLayout` redirect in `VulkanRenderPipelineMixin`
     * only when extra layouts were actually appended.
     */
    @JvmStatic
    fun registerPipelineLayoutWithCustomSets(pipelineLayout: Long, firstSet: Int) {
        if (pipelineLayout == 0L) return
        customSetPipelineLayouts[pipelineLayout] = firstSet
    }

    /** Returns `true` when [pipelineLayout] is known to carry custom shader descriptor sets. */
    @JvmStatic
    fun isPipelineLayoutRegistered(pipelineLayout: Long): Boolean =
        pipelineLayout != 0L && customSetPipelineLayouts.containsKey(pipelineLayout)

    /**
     * Returns the exact `firstSet` offset registered for [pipelineLayout], or `0` when unknown.
     */
    @JvmStatic
    fun getFirstSetForPipelineLayout(pipelineLayout: Long): Int =
        customSetPipelineLayouts[pipelineLayout] ?: 0

    /** Removes [pipelineLayout] from the registry when its Vulkan handle is destroyed. */
    @JvmStatic
    fun unregisterPipelineLayoutWithCustomSets(pipelineLayout: Long) {
        if (pipelineLayout == 0L) return
        customSetPipelineLayouts.remove(pipelineLayout)
    }

    @JvmStatic
    fun onBindCustomDescriptorSets(
        vkCommandBuffer: Long,
        vkPipelineLayout: Long,
        pipelineBindPoint: Int,
        passKind: Int,
    ) {
        if (!active) return
        val ext = extension ?: return
        // The exact first-set offset is derived from the registry entry created at pipeline
        // compile time, so the extension binds the custom sets at the right index. The default
        // onBindCustomDescriptorSetsWithFirstSet implementation delegates to the legacy callback.
        if (!isPipelineLayoutRegistered(vkPipelineLayout)) return
        val firstSet = getFirstSetForPipelineLayout(vkPipelineLayout)
        ext.onBindCustomDescriptorSetsWithFirstSet(
            vkCommandBuffer, vkPipelineLayout, pipelineBindPoint, passKind, firstSet,
        )
    }

    @JvmStatic
    fun invalidateRenderPassStateCache(renderPass: RenderPass) {
        if (renderPass is VulkanFrontendRenderPassAccessor) {
            renderPass.vtResetBoundPipeline()
        }
        val target: Any = (renderPass as? VulkanFrontendRenderPassAccessor)?.vtBackendRenderPass() ?: renderPass
        (target as? VulkanRenderPassStateCache)?.vtInvalidateStateCache()
    }

    @JvmStatic
    fun onBeginBlockTesselation(blockState: BlockState, pos: BlockPos) {
        if (!active) return
        extension?.onBeginBlockTesselation(blockState, pos)
    }

    @JvmStatic
    fun onBeginFluidTesselation(fluidState: FluidState, blockState: BlockState, pos: BlockPos) {
        if (!active) return
        extension?.onBeginFluidTesselation(fluidState, blockState, pos)
    }

    @JvmStatic
    fun onEndSectionTesselation() {
        if (!active) return
        extension?.onEndSectionTesselation()
    }

    @JvmStatic
    fun populateExtendedBlockQuad(
        basePtr: Long,
        vertexStride: Int,
        x: Float,
        y: Float,
        z: Float,
        quad: BakedQuad,
        instance: QuadInstance,
    ) {
        extension?.populateExtendedBlockQuad(basePtr, vertexStride, x, y, z, quad, instance)
    }

    @JvmStatic
    fun populateExtendedFluidFace(
        basePtr: Long,
        vertexStride: Int,
        x0: Float, y0: Float, z0: Float, u0: Float, v0: Float,
        x1: Float, y1: Float, z1: Float, u1: Float, v1: Float,
        x2: Float, y2: Float, z2: Float, u2: Float, v2: Float,
        x3: Float, y3: Float, z3: Float, u3: Float, v3: Float,
        addBackFace: Boolean,
    ) {
        extension?.populateExtendedFluidFace(
            basePtr, vertexStride,
            x0, y0, z0, u0, v0,
            x1, y1, z1, u1, v1,
            x2, y2, z2, u2, v2,
            x3, y3, z3, u3, v3,
            addBackFace,
        )
    }

    @JvmStatic
    fun onSectionSliceUploaded(
        allocationKey: Any,
        layerOrdinal: Int,
        gpuBuffer: GpuBuffer,
        byteOffset: Long,
        byteSize: Long,
    ) {
        if (!active) return
        extension?.onSectionSliceUploaded(allocationKey, layerOrdinal, gpuBuffer, byteOffset, byteSize)
    }

    @JvmStatic
    fun onSectionSliceUploadedDetailed(
        allocationKey: Any,
        layerOrdinal: Int,
        sectionPosLong: Long,
        gpuBuffer: GpuBuffer,
        byteOffset: Long,
        byteSize: Long,
        vertexStride: Int,
        vertexCount: Int,
        isRegionShifted: Boolean,
    ) {
        if (!active) return
        extension?.onSectionSliceUploadedDetailed(
            allocationKey,
            layerOrdinal,
            sectionPosLong,
            gpuBuffer,
            byteOffset,
            byteSize,
            vertexStride,
            vertexCount,
            isRegionShifted,
        )
    }

    @JvmStatic
    fun onSectionSliceFreed(allocationKey: Any, layerOrdinal: Int) {
        if (!active) return
        extension?.onSectionSliceFreed(allocationKey, layerOrdinal)
    }

    @JvmStatic
    fun notifySharedQuadIndexBuffer(gpuBuffer: GpuBuffer?) {
        if (!active || gpuBuffer == null || gpuBuffer === lastQuadIndexBuffer) return
        lastQuadIndexBuffer = gpuBuffer
        extension?.onSharedQuadIndexBufferUpdated(gpuBuffer)
    }

    @JvmStatic
    fun onEntityVerticesEmitted(basePtr: Long, vertexCount: Int, vertexStride: Int) {
        if (!entityRayTracingActive || basePtr == 0L || vertexCount <= 0) return
        extension?.onEntityVerticesEmitted(basePtr, vertexCount, vertexStride)
    }

    @JvmStatic
    fun onEntityGeometrySubmitted(
        entityId: Int,
        vertexBufferAddress: Long,
        vertexOffsetBytes: Long,
        vertexCount: Int,
        vertexStride: Int,
        indexBufferAddress: Long,
        indexCount: Int,
        transformMatrix: FloatArray,
        isBlockEntity: Boolean,
    ) {
        if (!entityRayTracingActive) return
        extension?.onEntityGeometrySubmitted(
            entityId,
            vertexBufferAddress,
            vertexOffsetBytes,
            vertexCount,
            vertexStride,
            indexBufferAddress,
            indexCount,
            transformMatrix,
            isBlockEntity,
        )
    }

    @JvmStatic
    fun notifyAfterWorldRenderBeforeGui(
        cmd: VkCommandBuffer,
        mainColorTexture: VulkanGpuTexture,
        mainColorView: VulkanGpuTextureView,
        mainDepthTexture: VulkanGpuTexture,
        mainDepthView: VulkanGpuTextureView,
        width: Int,
        height: Int,
    ): Boolean {
        if (!active) return false
        return extension?.onAfterWorldRenderBeforeGui(
            cmd,
            mainColorTexture,
            mainColorView,
            mainDepthTexture,
            mainDepthView,
            width,
            height,
        ) ?: false
    }

    /**
     * Dispatches the post-GUI frame point to the registered extension. Called from the
     * `GuiRenderer.render()` post-hook in `GameRendererMixin` after the UI has been composited
     * into [uiColorTexture] (`RES_UI_COLOR`).
     */
    @JvmStatic
    fun needsAfterGuiPass(): Boolean = extension?.needsAfterGuiPass() ?: false

    @JvmStatic
    fun notifyAfterGuiRender(
        cmd: VkCommandBuffer,
        uiColorTexture: VulkanGpuTexture,
        uiColorView: VulkanGpuTextureView,
        width: Int,
        height: Int,
    ): Boolean {
        if (!active && !swapchainOverrideActive) return false
        return extension?.notifyAfterGuiRender(cmd, uiColorTexture, uiColorView, width, height) ?: false
    }

    @JvmStatic
    fun onConfigureSwapchain(
        surfaceHandle: Long,
        requestedWidth: Int,
        requestedHeight: Int,
        defaultFormat: Int,
        defaultColorSpace: Int,
        defaultUsage: Int,
        defaultPresentMode: Int,
    ): SwapchainConfigOverride? {
        val ext = extension ?: return null
        return ext.onConfigureSwapchain(
            surfaceHandle,
            requestedWidth,
            requestedHeight,
            defaultFormat,
            defaultColorSpace,
            defaultUsage,
            defaultPresentMode,
        )
    }

    @JvmStatic
    fun onSwapchainCreated(
        swapchainHandle: Long,
        images: LongArray,
        imageFormat: Int,
        colorSpace: Int,
        width: Int,
        height: Int,
    ) {
        extension?.onSwapchainCreated(swapchainHandle, images, imageFormat, colorSpace, width, height)
    }

    @JvmStatic
    fun interceptAcquireNextImage(
        swapchainHandle: Long,
        timeoutNs: Long,
        semaphore: Long,
        fence: Long,
    ): Int {
        if (!active) return -1
        return extension?.interceptAcquireNextImage(swapchainHandle, timeoutNs, semaphore, fence) ?: -1
    }

    @JvmStatic
    fun interceptQueuePresent(
        queueHandle: Long,
        swapchainHandle: Long,
        imageIndex: Int,
        waitSemaphore: Long,
    ): Boolean {
        if (!active) return false
        return extension?.interceptQueuePresent(queueHandle, swapchainHandle, imageIndex, waitSemaphore) ?: false
    }

    @JvmStatic
    fun interceptSurfaceBlit(
        cmd: VkCommandBuffer,
        srcTexture: VulkanGpuTexture?,
        srcImage: Long,
        srcLayout: Int,
        dstImage: Long,
        dstLayout: Int,
        regions: VkImageBlit.Buffer,
        filter: Int,
    ): Boolean {
        return extension?.interceptSurfaceBlit(
            cmd, srcTexture, srcImage, srcLayout, dstImage, dstLayout, regions, filter,
        ) ?: false
    }

    /**
     * Replaces the color attachments of a deferred `VulkanRenderPass` before `vkCmdBeginRenderingKHR`
     * is emitted on the first draw call (used by shader engines for MRT G-Buffer output).
     */
    @JvmStatic
    fun overridePendingPassColorViews(
        renderPass: RenderPass,
        colorViews: Array<VulkanGpuTextureView?>,
    ): Boolean {
        invalidateRenderPassStateCache(renderPass)
        if (renderPass is VulkanFrontendRenderPassAccessor) {
            return renderPass.vtApplyColorViewsOverride(colorViews)
        }
        val tracked = renderPass as? VulkanTrackedRenderPass ?: return false
        return tracked.vtOverrideColorViews(colorViews)
    }

    // --- Vulkan Backend Synchronization & Pipeline Cache Sharing APIs for TurboShaders ---

    @JvmStatic
    fun notifyExternalImageLayout(texture: VulkanGpuTexture, newVkImageLayout: Int) {
        (texture as VulkanTrackedTexture).vtSetLayout(newVkImageLayout)
    }

    @JvmStatic
    fun getTrackedImageLayout(texture: VulkanGpuTexture): Int =
        (texture as VulkanTrackedTexture).vtGetLayout()

    @JvmStatic
    fun transitionImageLayout(
        cmd: VkCommandBuffer,
        texture: VulkanGpuTexture,
        targetVkImageLayout: Int,
    ): Int = VulkanLayoutTracker.transition(cmd, texture, targetVkImageLayout)

    @JvmStatic
    fun transitionImageLayoutBatch(
        cmd: VkCommandBuffer,
        textures: Array<Any?>,
        targetLayout: Int,
    ): Boolean {
        if (textures.isEmpty()) return false
        if (textures.javaClass.componentType == VulkanGpuTexture::class.java) {
            @Suppress("UNCHECKED_CAST")
            return VulkanLayoutTracker.transitionBatch(cmd, textures as Array<VulkanGpuTexture?>, targetLayout)
        }
        val converted = arrayOfNulls<VulkanGpuTexture>(textures.size)
        var anyValid = false
        for (i in textures.indices) {
            val tex = textures[i] as? VulkanGpuTexture
            if (tex != null) {
                converted[i] = tex
                anyValid = true
            }
        }
        if (!anyValid) return false
        return VulkanLayoutTracker.transitionBatch(cmd, converted, targetLayout)
    }

    @JvmStatic
    fun getSharedPipelineCache(vkDevice: VkDevice): Long =
        VulkanPipelineCacheManager.getOrCreateCache(vkDevice)

    @JvmStatic
    fun notifyPipelineCompiled(vkDevice: VkDevice) {
        VulkanPipelineCacheManager.notifyCompiled(vkDevice)
    }

    // --- Fast Native PBR / G-Buffer Vertex Math Utilities for TurboShaders ---

    @JvmStatic
    fun computePackedFaceNormal(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        materialMask: Int,
    ): Int {
        val ax = x1 - x0; val ay = y1 - y0; val az = z1 - z0
        val bx = x2 - x0; val by = y2 - y0; val bz = z2 - z0
        var nx = ay * bz - az * by
        var ny = az * bx - ax * bz
        var nz = ax * by - ay * bx
        val lenSq = nx * nx + ny * ny + nz * nz
        if (lenSq > 1e-12f) {
            val invLen = 1.0f / sqrt(lenSq)
            nx *= invLen
            ny *= invLen
            nz *= invLen
        } else {
            nx = 0.0f; ny = 1.0f; nz = 0.0f
        }
        val rgb = NormalCache.packNormal(nx, ny, nz)
        val w = (materialMask and 0x7F) shl 24
        return rgb or w
    }

    @JvmStatic
    fun computePackedMidUv(
        u0: Float, v0: Float,
        u1: Float, v1: Float,
        u2: Float, v2: Float,
        u3: Float, v3: Float,
    ): Long {
        val midU = (u0 + u1 + u2 + u3) * 0.25f
        val midV = (v0 + v1 + v2 + v3) * 0.25f
        val lo = floatToRawIntBits(midU).toLong() and 0xFFFFFFFFL
        val hi = floatToRawIntBits(midV).toLong() shl 32
        return lo or hi
    }

    @JvmStatic
    fun computePackedTangent(
        x0: Float, y0: Float, z0: Float, u0: Float, v0: Float,
        x1: Float, y1: Float, z1: Float, u1: Float, v1: Float,
        x2: Float, y2: Float, z2: Float, u2: Float, v2: Float,
    ): Int {
        val dx1 = x1 - x0; val dy1 = y1 - y0; val dz1 = z1 - z0
        val dx2 = x2 - x0; val dy2 = y2 - y0; val dz2 = z2 - z0
        val du1 = u1 - u0; val dv1 = v1 - v0
        val du2 = u2 - u0; val dv2 = v2 - v0

        val det = du1 * dv2 - du2 * dv1
        if (det > -1e-8f && det < 1e-8f) {
            return (127 shl 24) or 127
        }
        val invDet = 1.0f / det
        var tx = (dv2 * dx1 - dv1 * dx2) * invDet
        var ty = (dv2 * dy1 - dv1 * dy2) * invDet
        var tz = (dv2 * dz1 - dv1 * dz2) * invDet

        val lenSq = tx * tx + ty * ty + tz * tz
        if (lenSq > 1e-12f) {
            val invLen = 1.0f / sqrt(lenSq)
            tx *= invLen
            ty *= invLen
            tz *= invLen
        } else {
            return (127 shl 24) or 127
        }

        val handedness = if (det < 0.0f) (-127 and 0xFF) else 127
        val rgb = NormalCache.packNormal(tx, ty, tz)
        return rgb or (handedness shl 24)
    }

    // --- Unified Diagnostic Log & GUI Bridge ---

    @JvmStatic
    @JvmOverloads
    fun recordDiagnostic(
        level: Int,
        modId: String,
        source: String,
        summary: String,
        details: String? = null,
        throwable: Throwable? = null,
        showToast: Boolean = (level >= 2),
    ) {
        val logLevel = when {
            level <= 0 -> VulkanDiagnosticLog.LogLevel.INFO
            level == 1 -> VulkanDiagnosticLog.LogLevel.WARN
            else -> VulkanDiagnosticLog.LogLevel.ERROR
        }
        VulkanDiagnosticLog.record(
            level = logLevel,
            source = source,
            summary = summary,
            details = details,
            throwable = throwable,
            modId = modId,
            showToast = showToast,
        )
    }

    @JvmStatic
    fun createLogScreen(returnScreen: Screen?, options: Options): Screen =
        extension?.createLogScreen(returnScreen, options) ?: VulkanErrorLogScreen(returnScreen, options)

    @JvmStatic
    fun collectExtensionDiagnosticSections(): String? {
        val ext = extension ?: return null
        return runCatching { ext.buildCrashDiagnosticSection()?.takeIf { it.isNotBlank() } }.getOrNull()
    }
}
