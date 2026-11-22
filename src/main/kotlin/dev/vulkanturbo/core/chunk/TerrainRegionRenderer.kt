package dev.vulkanturbo.core

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.MeshData
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.device.GpuDevice
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.IndexType
import com.mojang.renderpearl.api.pipeline.PolygonMode
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderType
import com.mojang.renderpearl.api.vertex.VertexFormat
import dev.vulkanturbo.VulkanTurbo
import dev.vulkanturbo.api.shader.TurboShadersBridge
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.client.renderer.chunk.SectionCompiler
import net.minecraft.client.renderer.oit.OitPipelineSet
import net.minecraft.core.SectionPos
import net.minecraft.resources.Identifier
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.charset.StandardCharsets
import java.util.IdentityHashMap
import java.util.function.UnaryOperator
import kotlin.math.abs
import kotlin.math.roundToInt

/** Per-section directional quad bucket layout and tight local plane bounds for SOLID/CUTOUT. */
class TerrainSectionBuckets(
    @JvmField val bucketVertexStarts: IntArray,
    @JvmField val bucketIndexCounts: IntArray,
    /** `[0]=downMaxY, [1]=upMinY, [2]=northMaxZ, [3]=southMinZ, [4]=westMaxX, [5]=eastMinX`. */
    @JvmField val faceLimits: FloatArray,
)

/** Duck interface mixed into `MeshData` to carry directional bucket layout and compact drawState. */
interface BucketedMeshData {
    fun `vulkanturbo$getBuckets`(): TerrainSectionBuckets?
    fun `vulkanturbo$setBuckets`(buckets: TerrainSectionBuckets?)
    fun `vulkanturbo$isRegionShifted`(): Boolean
    fun `vulkanturbo$setRegionShifted`(shifted: Boolean)
    fun `vulkanturbo$getSectionKey`(): Long
    fun `vulkanturbo$setSectionKey`(sectionKey: Long)
    fun `vulkanturbo$setDrawState`(drawState: MeshData.DrawState)
}

/** Duck interface mixed into `ChunkSectionsToRender.DrawIndirect`. */
interface RegionBackedDrawIndirect {
    fun `vulkanturbo$setRegionPlan`(plan: RegionBatchPlan?)
    fun `vulkanturbo$getRegionPlan`(): RegionBatchPlan?
}

enum class RegionDrawMode {
    NONE,
    MULTI_DRAW_EXT,
    VK_INDIRECT,
}

/**
 * Registers clean-room region push-constant terrain pipelines (compact 20B, standard 28B, and
 * extended 40B PBR) and serves our bundled vertex shader source directly from the mod jar.
 */
object TerrainRegionPipelines {
    private val SHADER_ID = Identifier.fromNamespaceAndPath("vulkanturbo", "core/terrain_region")

    const val DEFAULT_VERTEX_STRIDE = 28
    const val COMPACT_VERTEX_STRIDE = 20
    const val EXTENDED_VERTEX_STRIDE = 40
    const val REGION_PUSH_CONSTANT_BYTES = 16
    /** Least common multiple of 20B compact and 28B standard vertex strides (`7 * 20 = 5 * 28 = 140`). */
    const val UNIVERSAL_VERTEX_ALIGNMENT = 140
    const val COMPACT_POS_BIAS = 16.0f
    const val COMPACT_POS_SCALE = 512.0f
    const val COMPACT_POS_INV_SCALE = 1.0f / COMPACT_POS_SCALE
    const val COMPACT_MIN_COORD = -16.0f
    const val COMPACT_MAX_COORD = 111.998f

    /**
     * Compact 20-byte terrain vertex format (`-28.6%` vs vanilla 28B):
     * - `Position`: `RGBA16_UINT` (8B: `xyz` quantized at `1/512` with `+16.0` bias + `w = packed UV2`)
     * - `Color`:    `RGBA8_UNORM` (4B)
     * - `UV0`:      `RG32_FLOAT`  (8B: lossless atlas UVs)
     */
    @JvmField
    val COMPACT_TERRAIN_20B: VertexFormat = VertexFormat.builder(0)
        .addAttribute("Position", GpuFormat.RGBA16_UINT)
        .addAttribute("Color", GpuFormat.RGBA8_UNORM)
        .addAttribute("UV0", GpuFormat.RG32_FLOAT)
        .build()

    /**
     * Extended 40-byte PBR terrain vertex format matching `TurboShadersExtension` (`IGNIS_TERRAIN_40B`):
     * - `Position`: `RGB32_FLOAT` (12B)
     * - `Color`:    `RGBA8_UNORM` (4B)
     * - `UV0`:      `RG32_FLOAT`  (8B)
     * - `UV2`:      `RG16_SINT`   (4B)
     * - `Normal`:   `RGBA8_SNORM` (4B)
     * - `Tangent`:  `RGBA8_SNORM` (4B)
     * - `MidUV`:    `RG16_FLOAT`  (4B)
     */
    @JvmField
    val EXTENDED_TERRAIN_40B: VertexFormat = VertexFormat.builder(0)
        .addAttribute("Position", GpuFormat.RGB32_FLOAT)
        .addAttribute("Color", GpuFormat.RGBA8_UNORM)
        .addAttribute("UV0", GpuFormat.RG32_FLOAT)
        .addAttribute("UV2", GpuFormat.RG16_SINT)
        .addAttribute("Normal", GpuFormat.RGBA8_SNORM)
        .addAttribute("Tangent", GpuFormat.RGBA8_SNORM)
        .addAttribute("MidUV", GpuFormat.RG16_FLOAT)
        .build()

    @Volatile
    private var registered = false

    @JvmField var solidCompactPipeline: RenderPipeline? = null
    @JvmField var cutoutCompactPipeline: RenderPipeline? = null
    @JvmField var translucentCompactPipeline: RenderPipeline? = null
    @JvmField var wireframeCompactPipeline: RenderPipeline? = null
    @JvmField var oitCompactPipelines: OitPipelineSet? = null

    @JvmField var solidPipeline: RenderPipeline? = null
    @JvmField var cutoutPipeline: RenderPipeline? = null
    @JvmField var translucentPipeline: RenderPipeline? = null
    @JvmField var wireframePipeline: RenderPipeline? = null
    @JvmField var oitStandardPipelines: OitPipelineSet? = null
    @JvmField var vanillaWireframeMultidraw: RenderPipeline? = null

    @JvmField var solidExtendedPipeline: RenderPipeline? = null
    @JvmField var cutoutExtendedPipeline: RenderPipeline? = null
    @JvmField var translucentExtendedPipeline: RenderPipeline? = null
    @JvmField var wireframeExtendedPipeline: RenderPipeline? = null
    @JvmField var oitExtendedPipelines: OitPipelineSet? = null

    @Volatile
    private var cachedRegionVertexSource: String? = null

    @Volatile
    private var backendChecked = false

    @Volatile
    private var backendSupported = false

    @Volatile
    private var cachedDrawMode = RegionDrawMode.NONE

    @Volatile
    private var cachedMultiDrawIndirect = false

    @Volatile
    private var cachedForceVkIndirectFallback = false

    @JvmStatic
    fun registerAll(
        genericBlocksSnippet: RenderPipeline.Snippet,
        litBlocksSnippet: RenderPipeline.Snippet,
        vanillaWireframe: RenderPipeline?,
        registerOptional: UnaryOperator<RenderPipeline>,
    ) {
        if (registered) return
        vanillaWireframeMultidraw = vanillaWireframe

        val standardSnippet = RenderPipeline.builder(litBlocksSnippet)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.TERRAIN_INFO)
            .withVertexShader(SHADER_ID)
            .withFragmentShader("core/terrain")
            .withShaderDefine("MULTIDRAW_TERRAIN")
            .withPushConstantSize(REGION_PUSH_CONSTANT_BYTES)
            .buildSnippet()

        val compactSnippet = RenderPipeline.builder(litBlocksSnippet)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.TERRAIN_INFO)
            .withVertexShader(SHADER_ID)
            .withFragmentShader("core/terrain")
            .withVertexBinding(0, COMPACT_TERRAIN_20B)
            .withShaderDefine("MULTIDRAW_TERRAIN")
            .withShaderDefine("COMPACT_VERTEX_20B")
            .withPushConstantSize(REGION_PUSH_CONSTANT_BYTES)
            .buildSnippet()

        val extendedSnippet = RenderPipeline.builder(litBlocksSnippet)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.TERRAIN_INFO)
            .withVertexShader(SHADER_ID)
            .withFragmentShader("core/terrain")
            .withVertexBinding(0, EXTENDED_TERRAIN_40B)
            .withShaderDefine("MULTIDRAW_TERRAIN")
            .withShaderDefine("EXTENDED_VERTEX_40B")
            .withPushConstantSize(REGION_PUSH_CONSTANT_BYTES)
            .buildSnippet()

        solidPipeline = registerOptional.apply(
            RenderPipeline.builder(standardSnippet)
                .withLocation(Identifier.fromNamespaceAndPath("vulkanturbo", "pipeline/solid_terrain_region"))
                .withColorTargetState(ColorTargetState.DEFAULT)
                .build(),
        )
        cutoutPipeline = registerOptional.apply(
            RenderPipeline.builder(standardSnippet)
                .withLocation(Identifier.fromNamespaceAndPath("vulkanturbo", "pipeline/cutout_terrain_region"))
                .withShaderDefine("ALPHA_CUTOUT", 0.5f)
                .withColorTargetState(ColorTargetState.DEFAULT)
                .build(),
        )
        translucentPipeline = registerOptional.apply(
            RenderPipeline.builder(standardSnippet)
                .withLocation(Identifier.fromNamespaceAndPath("vulkanturbo", "pipeline/translucent_terrain_region"))
                .withShaderDefine("ALPHA_CUTOUT", 0.1f)
                .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
                .build(),
        )
        wireframePipeline = registerOptional.apply(
            RenderPipeline.builder(standardSnippet)
                .withLocation(Identifier.fromNamespaceAndPath("vulkanturbo", "pipeline/wireframe_terrain_region"))
                .withPolygonMode(PolygonMode.WIREFRAME)
                .withColorTargetState(ColorTargetState.DEFAULT)
                .build(),
        )

        solidCompactPipeline = registerOptional.apply(
            RenderPipeline.builder(compactSnippet)
                .withLocation(Identifier.fromNamespaceAndPath("vulkanturbo", "pipeline/solid_terrain_region_compact"))
                .withColorTargetState(ColorTargetState.DEFAULT)
                .build(),
        )
        cutoutCompactPipeline = registerOptional.apply(
            RenderPipeline.builder(compactSnippet)
                .withLocation(Identifier.fromNamespaceAndPath("vulkanturbo", "pipeline/cutout_terrain_region_compact"))
                .withShaderDefine("ALPHA_CUTOUT", 0.5f)
                .withColorTargetState(ColorTargetState.DEFAULT)
                .build(),
        )
        translucentCompactPipeline = registerOptional.apply(
            RenderPipeline.builder(compactSnippet)
                .withLocation(Identifier.fromNamespaceAndPath("vulkanturbo", "pipeline/translucent_terrain_region_compact"))
                .withShaderDefine("ALPHA_CUTOUT", 0.1f)
                .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
                .build(),
        )
        wireframeCompactPipeline = registerOptional.apply(
            RenderPipeline.builder(compactSnippet)
                .withLocation(Identifier.fromNamespaceAndPath("vulkanturbo", "pipeline/wireframe_terrain_region_compact"))
                .withPolygonMode(PolygonMode.WIREFRAME)
                .withColorTargetState(ColorTargetState.DEFAULT)
                .build(),
        )

        solidExtendedPipeline = registerOptional.apply(
            RenderPipeline.builder(extendedSnippet)
                .withLocation(Identifier.fromNamespaceAndPath("vulkanturbo", "pipeline/solid_terrain_region_extended40b"))
                .withColorTargetState(ColorTargetState.DEFAULT)
                .build(),
        )
        cutoutExtendedPipeline = registerOptional.apply(
            RenderPipeline.builder(extendedSnippet)
                .withLocation(Identifier.fromNamespaceAndPath("vulkanturbo", "pipeline/cutout_terrain_region_extended40b"))
                .withShaderDefine("ALPHA_CUTOUT", 0.5f)
                .withColorTargetState(ColorTargetState.DEFAULT)
                .build(),
        )
        translucentExtendedPipeline = registerOptional.apply(
            RenderPipeline.builder(extendedSnippet)
                .withLocation(Identifier.fromNamespaceAndPath("vulkanturbo", "pipeline/translucent_terrain_region_extended40b"))
                .withShaderDefine("ALPHA_CUTOUT", 0.1f)
                .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
                .build(),
        )
        wireframeExtendedPipeline = registerOptional.apply(
            RenderPipeline.builder(extendedSnippet)
                .withLocation(Identifier.fromNamespaceAndPath("vulkanturbo", "pipeline/wireframe_terrain_region_extended40b"))
                .withPolygonMode(PolygonMode.WIREFRAME)
                .withColorTargetState(ColorTargetState.DEFAULT)
                .build(),
        )

        val oitStandardSet = OitPipelineSet.builder(
            "vulkanturbo_terrain_region",
            RenderPipeline.builder(genericBlocksSnippet)
                .withVertexShader(SHADER_ID)
                .withFragmentShader("core/terrain")
                .withBindGroupLayout(BindGroupLayouts.TERRAIN_INFO)
                .withShaderDefine("ALPHA_CUTOUT", 0.1f)
                .withShaderDefine("MULTIDRAW_TERRAIN")
                .withPushConstantSize(REGION_PUSH_CONSTANT_BYTES),
        ).withAccumulateModifier { builder ->
            builder.withBindGroupLayout(BindGroupLayouts.SAMPLER2)
        }.build()
        registerOptional.apply(oitStandardSet.depthBoundsPipeline())
        registerOptional.apply(oitStandardSet.transmittancePipeline())
        registerOptional.apply(oitStandardSet.accumulatePipeline())
        oitStandardPipelines = oitStandardSet

        val oitCompactSet = OitPipelineSet.builder(
            "vulkanturbo_terrain_region_compact",
            RenderPipeline.builder(genericBlocksSnippet)
                .withVertexShader(SHADER_ID)
                .withFragmentShader("core/terrain")
                .withBindGroupLayout(BindGroupLayouts.TERRAIN_INFO)
                .withVertexBinding(0, COMPACT_TERRAIN_20B)
                .withShaderDefine("ALPHA_CUTOUT", 0.1f)
                .withShaderDefine("MULTIDRAW_TERRAIN")
                .withShaderDefine("COMPACT_VERTEX_20B")
                .withPushConstantSize(REGION_PUSH_CONSTANT_BYTES),
        ).withAccumulateModifier { builder ->
            builder.withBindGroupLayout(BindGroupLayouts.SAMPLER2)
        }.build()
        registerOptional.apply(oitCompactSet.depthBoundsPipeline())
        registerOptional.apply(oitCompactSet.transmittancePipeline())
        registerOptional.apply(oitCompactSet.accumulatePipeline())
        oitCompactPipelines = oitCompactSet

        val oitExtendedSet = OitPipelineSet.builder(
            "vulkanturbo_terrain_region_extended40b",
            RenderPipeline.builder(genericBlocksSnippet)
                .withVertexShader(SHADER_ID)
                .withFragmentShader("core/terrain")
                .withBindGroupLayout(BindGroupLayouts.TERRAIN_INFO)
                .withVertexBinding(0, EXTENDED_TERRAIN_40B)
                .withShaderDefine("ALPHA_CUTOUT", 0.1f)
                .withShaderDefine("MULTIDRAW_TERRAIN")
                .withShaderDefine("EXTENDED_VERTEX_40B")
                .withPushConstantSize(REGION_PUSH_CONSTANT_BYTES),
        ).withAccumulateModifier { builder ->
            builder.withBindGroupLayout(BindGroupLayouts.SAMPLER2)
        }.build()
        registerOptional.apply(oitExtendedSet.depthBoundsPipeline())
        registerOptional.apply(oitExtendedSet.transmittancePipeline())
        registerOptional.apply(oitExtendedSet.accumulatePipeline())
        oitExtendedPipelines = oitExtendedSet

        registered = true
    }

    @JvmStatic
    fun resolveShader(id: Identifier, type: ShaderType): String? {
        if (type != ShaderType.VERTEX || id != SHADER_ID) return null
        cachedRegionVertexSource?.let { return it }
        val path = "/assets/vulkanturbo/shaders/core/terrain_region.vsh"
        val loaded = TerrainRegionPipelines::class.java.getResourceAsStream(path)?.use { stream ->
            String(stream.readAllBytes(), StandardCharsets.UTF_8)
        } ?: throw IllegalStateException("Missing bundled shader resource: $path")
        cachedRegionVertexSource = loaded
        return loaded
    }

    /**
     * Verifies that [pipeline] declares at least 16 bytes of push constants (`RegionOrigin` + `Visibility`)
     * required by `terrain_region.vsh` and `RegionBatchPlan`.
     */
    @JvmStatic
    fun isPipelineCompatibleWithRegionPushConstants(pipeline: RenderPipeline?): Boolean =
        pipeline != null && pipeline.pushConstantSize() >= REGION_PUSH_CONSTANT_BYTES

    /**
     * Verifies that [pipeline] binds `COMPACT_TERRAIN_20B` (20B stride with `RGBA16_UINT` `Position`)
     * on vertex buffer slot 0 AND declares at least 16 bytes of push constants.
     */
    @JvmStatic
    fun isPipelineCompatibleWith20B(pipeline: RenderPipeline?): Boolean {
        if (pipeline == null || pipeline.pushConstantSize() < REGION_PUSH_CONSTANT_BYTES) {
            return false
        }
        val vf = pipeline.getVertexFormatBinding(0) ?: return false
        if (vf === COMPACT_TERRAIN_20B) {
            return true
        }
        if (vf.vertexSize != COMPACT_VERTEX_STRIDE) {
            return false
        }
        val posElem = vf.getElement("Position") ?: return false
        val colElem = vf.getElement("Color") ?: return false
        val uv0Elem = vf.getElement("UV0") ?: return false
        return posElem.offset() == 0 && posElem.format() == GpuFormat.RGBA16_UINT &&
            colElem.offset() == 8 && colElem.format() == GpuFormat.RGBA8_UNORM &&
            uv0Elem.offset() == 12 && uv0Elem.format() == GpuFormat.RG32_FLOAT
    }

    @JvmStatic
    fun onBackendDetected(vulkan: Boolean, device: GpuDevice?) {
        backendSupported = vulkan
        if (!vulkan || device == null) {
            cachedDrawMode = RegionDrawMode.NONE
            cachedMultiDrawIndirect = false
            backendChecked = true
            return
        }
        try {
            val features = device.getDeviceInfo().features()
            val canMultiExt = features.multiDrawDirectInterleaved()
            val canIndirect = features.multiDrawIndirect() || features.drawIndirect()
            cachedMultiDrawIndirect = features.multiDrawIndirect()
            cachedDrawMode = when {
                !cachedForceVkIndirectFallback && canMultiExt -> RegionDrawMode.MULTI_DRAW_EXT
                canIndirect -> RegionDrawMode.VK_INDIRECT
                else -> RegionDrawMode.NONE
            }
            backendChecked = true
            VulkanTurbo.log.info("Terrain region draw mode: {} (multiDrawExt={}, vkIndirect={})", cachedDrawMode, canMultiExt, canIndirect)
        } catch (_: Throwable) {
            cachedDrawMode = RegionDrawMode.NONE
            backendChecked = true
        }
    }

    @JvmStatic
    fun setForceVkIndirectFallbackForTesting(force: Boolean) {
        cachedForceVkIndirectFallback = force
    }

    @JvmStatic
    fun resolveDrawMode(): RegionDrawMode {
        if (!VulkanTurbo.fastChunkPrepEnabled || !VulkanTurbo.isBackendAllowed() || !isPipelineCompatibleWithRegionPushConstants(solidPipeline)) {
            return RegionDrawMode.NONE
        }
        if (backendChecked) {
            return if (backendSupported) cachedDrawMode else RegionDrawMode.NONE
        }
        return try {
            val device = RenderSystem.getDevice()
            val vulkan = device.getDeviceInfo().backendName().equals("Vulkan", ignoreCase = true)
            onBackendDetected(vulkan, device)
            cachedDrawMode
        } catch (_: Throwable) {
            RegionDrawMode.NONE
        }
    }

    @JvmStatic
    fun isMultiDrawIndirectSupported(): Boolean = cachedMultiDrawIndirect

    @JvmStatic
    fun isRegionBatchingActive(): Boolean = resolveDrawMode() != RegionDrawMode.NONE

    @JvmStatic
    fun isCompactVertexActive(): Boolean =
        isRegionBatchingActive() &&
            !TurboShadersBridge.active &&
            isPipelineCompatibleWith20B(solidCompactPipeline) &&
            isPipelineCompatibleWith20B(cutoutCompactPipeline) &&
            isPipelineCompatibleWith20B(translucentCompactPipeline)

    @JvmStatic
    fun activeTerrainVertexStride(layer: ChunkSectionLayer): Int {
        if (isCompactVertexActive()) {
            return COMPACT_VERTEX_STRIDE
        }
        val base = layer.vertexFormat().getVertexSize()
        return if (TurboShadersBridge.active) {
            TurboShadersBridge.resolveTerrainVertexFormat(layer, layer.vertexFormat()).getVertexSize()
        } else {
            base
        }
    }

    internal fun gcd(a: Int, b: Int): Int {
        var x = abs(a)
        var y = abs(b)
        while (y != 0) {
            val t = x % y
            x = y
            y = t
        }
        return if (x == 0) 1 else x
    }

    internal fun lcm(a: Int, b: Int): Int {
        if (a <= 0 || b <= 0) return maxOf(a, b, 1)
        return (a / gcd(a, b)) * b
    }

    /**
     * Computes a `TlsfAllocator` alignment that is an exact integer multiple of `COMPACT_VERTEX_STRIDE` (20B),
     * `DEFAULT_VERTEX_STRIDE` (28B), [vanillaAlignSize], and [activeTerrainVertexStride], guaranteeing
     * lossless `baseVertex = vertexOffset / stride` division even if compact mode or `TurboShaders`
     * is toggled mid-session without recreating `SectionRenderDispatcher`.
     */
    @JvmStatic
    fun recommendedVertexAlignment(layer: ChunkSectionLayer, vanillaAlignSize: Int): Int {
        val baseLcm = lcm(
            lcm(UNIVERSAL_VERTEX_ALIGNMENT, EXTENDED_VERTEX_STRIDE),
            vanillaAlignSize.coerceAtLeast(1),
        )
        return lcm(baseLcm, activeTerrainVertexStride(layer))
    }

    @JvmStatic
    fun recommendedHeapSizeBytes(layer: ChunkSectionLayer): Int {
        val defaultSize = 134_217_728
        if (!VulkanTurbo.isBackendAllowed() || !VulkanTurbo.fastChunkPrepEnabled) return defaultSize
        return try {
            val info = RenderSystem.getDevice().getDeviceInfo()
            val maxAlloc = info.limits().maxMemoryAllocationSize()
            if (VulkanTurbo.isHighEndGpu) {
                if (layer == ChunkSectionLayer.SOLID) {
                    when {
                        maxAlloc >= 512L * 1024L * 1024L -> 536_870_912
                        maxAlloc >= 256L * 1024L * 1024L -> 268_435_456
                        else -> defaultSize
                    }
                } else {
                    if (maxAlloc >= 256L * 1024L * 1024L) 268_435_456 else defaultSize
                }
            } else if (layer == ChunkSectionLayer.SOLID && maxAlloc >= 512L * 1024L * 1024L) {
                268_435_456
            } else {
                defaultSize
            }
        } catch (_: Throwable) {
            defaultSize
        }
    }

    @JvmStatic
    fun acceptsRegionOverride(layer: ChunkSectionLayer, multidrawOverride: RenderPipeline?): Boolean {
        if (multidrawOverride == null || multidrawOverride === vanillaWireframeMultidraw) {
            return true
        }
        if (layer == ChunkSectionLayer.TRANSLUCENT) {
            val oit = RenderPipelines.OIT_TERRAIN_MULTIDRAW
            if (multidrawOverride === oit.depthBoundsPipeline() ||
                multidrawOverride === oit.transmittancePipeline() ||
                multidrawOverride === oit.accumulatePipeline()
            ) {
                val useExtended40B = TurboShadersBridge.extendedBlockVerticesActive && solidExtendedPipeline != null
                val hasOitSet = when {
                    useExtended40B -> oitExtendedPipelines != null
                    isCompactVertexActive() -> oitCompactPipelines != null && oitStandardPipelines != null
                    else -> oitStandardPipelines != null
                }
                if (!hasOitSet) {
                    return false
                }
                val resolved = resolvePipeline(layer.ordinal, compact = false, multidrawOverride = multidrawOverride)
                return isPipelineCompatibleWithRegionPushConstants(resolved)
            }
        }
        if (!TurboShadersBridge.acceptsRegionMultiDrawOverride(layer, multidrawOverride)) {
            return false
        }
        val resolved = resolvePipeline(layer.ordinal, compact = false, multidrawOverride = multidrawOverride)
        return isPipelineCompatibleWithRegionPushConstants(resolved)
    }

    internal fun resolvePipeline(
        layerOrdinal: Int,
        compact: Boolean,
        multidrawOverride: RenderPipeline?,
    ): RenderPipeline {
        if (compact && !TurboShadersBridge.active) {
            val candidate = if (multidrawOverride === vanillaWireframeMultidraw) {
                wireframeCompactPipeline ?: solidCompactPipeline
            } else if (layerOrdinal == 2 && multidrawOverride != null) {
                val oit = RenderPipelines.OIT_TERRAIN_MULTIDRAW
                val oitCompact = oitCompactPipelines
                if (oitCompact != null) {
                    when {
                        multidrawOverride === oit.depthBoundsPipeline() -> oitCompact.depthBoundsPipeline()
                        multidrawOverride === oit.transmittancePipeline() -> oitCompact.transmittancePipeline()
                        multidrawOverride === oit.accumulatePipeline() -> oitCompact.accumulatePipeline()
                        else -> translucentCompactPipeline
                    }
                } else {
                    translucentCompactPipeline
                }
            } else {
                when (layerOrdinal) {
                    0 -> solidCompactPipeline
                    1 -> cutoutCompactPipeline
                    else -> translucentCompactPipeline
                }
            }
            if (isPipelineCompatibleWith20B(candidate)) {
                return candidate!!
            }
        }

        val useExtended40B = TurboShadersBridge.extendedBlockVerticesActive && solidExtendedPipeline != null
        val defaultPipeline = if (useExtended40B) {
            if (multidrawOverride === vanillaWireframeMultidraw) {
                wireframeExtendedPipeline ?: solidExtendedPipeline!!
            } else if (layerOrdinal == 2 && multidrawOverride != null) {
                val oit = RenderPipelines.OIT_TERRAIN_MULTIDRAW
                val oitExt = oitExtendedPipelines
                if (oitExt != null) {
                    when {
                        multidrawOverride === oit.depthBoundsPipeline() -> oitExt.depthBoundsPipeline()
                        multidrawOverride === oit.transmittancePipeline() -> oitExt.transmittancePipeline()
                        multidrawOverride === oit.accumulatePipeline() -> oitExt.accumulatePipeline()
                        else -> translucentExtendedPipeline!!
                    }
                } else {
                    translucentExtendedPipeline!!
                }
            } else {
                when (layerOrdinal) {
                    0 -> solidExtendedPipeline!!
                    1 -> cutoutExtendedPipeline!!
                    else -> translucentExtendedPipeline!!
                }
            }
        } else if (multidrawOverride != null && multidrawOverride === vanillaWireframeMultidraw) {
            wireframePipeline ?: solidPipeline!!
        } else if (layerOrdinal == 2 && multidrawOverride != null) {
            val oit = RenderPipelines.OIT_TERRAIN_MULTIDRAW
            val oitStd = oitStandardPipelines
            if (oitStd != null) {
                when {
                    multidrawOverride === oit.depthBoundsPipeline() -> oitStd.depthBoundsPipeline()
                    multidrawOverride === oit.transmittancePipeline() -> oitStd.transmittancePipeline()
                    multidrawOverride === oit.accumulatePipeline() -> oitStd.accumulatePipeline()
                    else -> translucentPipeline!!
                }
            } else {
                translucentPipeline!!
            }
        } else {
            when (layerOrdinal) {
                0 -> solidPipeline!!
                1 -> cutoutPipeline!!
                else -> translucentPipeline!!
            }
        }

        val layer = when (layerOrdinal) {
            0 -> ChunkSectionLayer.SOLID
            1 -> ChunkSectionLayer.CUTOUT
            else -> ChunkSectionLayer.TRANSLUCENT
        }
        val resolved = if (TurboShadersBridge.active) {
            TurboShadersBridge.resolveRegionTerrainPipeline(layer, multidrawOverride, defaultPipeline)
        } else {
            defaultPipeline
        }
        return if (isPipelineCompatibleWithRegionPushConstants(resolved)) resolved else defaultPipeline
    }
}

/**
 * Classifies and reorders `SOLID` and `CUTOUT` terrain quads into 7 contiguous directional
 * buckets (`DOWN, UP, NORTH, SOUTH, WEST, EAST, UNASSIGNED`) on worker threads during
 * `SectionCompiler.compile`, shifts `(sectionPos & 3) * 16` into vertex positions, compacts
 * vanilla 28-byte vertices down to 20 bytes (`COMPACT_TERRAIN_20B`) when active (falling back to
 * full float32 28B if any modded model extends outside `[-16.0, +111.998]`), and shifts/compacts
 * `TRANSLUCENT` meshes in-place without disturbing their sorted quad order.
 */
object TerrainBucketBuilder {
    const val BUCKET_DOWN = 0
    const val BUCKET_UP = 1
    const val BUCKET_NORTH = 2
    const val BUCKET_SOUTH = 3
    const val BUCKET_WEST = 4
    const val BUCKET_EAST = 5
    const val BUCKET_UNASSIGNED = 6
    const val BUCKET_COUNT = 7

    private const val DEFAULT_VERTEX_STRIDE = TerrainRegionPipelines.DEFAULT_VERTEX_STRIDE
    private const val COMPACT_VERTEX_STRIDE = TerrainRegionPipelines.COMPACT_VERTEX_STRIDE

    private class WorkerScratch {
        var quadBuckets = IntArray(512)
        var scratchBuf: ByteBuffer = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.nativeOrder())

        fun ensureBucketsCapacity(quadCount: Int): IntArray {
            if (quadBuckets.size < quadCount) {
                quadBuckets = IntArray(Integer.highestOneBit(quadCount - 1) shl 1)
            }
            return quadBuckets
        }

        fun ensureScratchBuffer(neededBytes: Int): ByteBuffer {
            if (scratchBuf.capacity() < neededBytes) {
                val cap = Integer.highestOneBit(neededBytes - 1) shl 1
                scratchBuf = ByteBuffer.allocateDirect(cap).order(ByteOrder.nativeOrder())
            }
            return scratchBuf
        }
    }

    private val workerScratch = ThreadLocal.withInitial(::WorkerScratch)

    internal fun isWithinCompactRange(coord: Float): Boolean =
        coord >= TerrainRegionPipelines.COMPACT_MIN_COORD && coord <= TerrainRegionPipelines.COMPACT_MAX_COORD

    internal fun canCompactBufferLosslessly(
        srcBase: Long,
        vertexCount: Int,
        vertexStride: Int,
        regOffX: Float,
        regOffY: Float,
        regOffZ: Float,
    ): Boolean {
        val vStrideL = vertexStride.toLong()
        for (v in 0 until vertexCount) {
            val vAddr = srcBase + v.toLong() * vStrideL
            val sx = MemoryUtil.memGetFloat(vAddr) + regOffX
            val sy = MemoryUtil.memGetFloat(vAddr + 4L) + regOffY
            val sz = MemoryUtil.memGetFloat(vAddr + 8L) + regOffZ
            if (!isWithinCompactRange(sx) || !isWithinCompactRange(sy) || !isWithinCompactRange(sz)) {
                return false
            }
        }
        return true
    }

    internal fun packCompactCoord(coord: Float): Int {
        val q = ((coord + TerrainRegionPipelines.COMPACT_POS_BIAS) * TerrainRegionPipelines.COMPACT_POS_SCALE).roundToInt()
        return q.coerceIn(0, 65535)
    }

    internal fun unpackCompactCoord(quantized: Int): Float =
        (quantized and 0xFFFF).toFloat() * TerrainRegionPipelines.COMPACT_POS_INV_SCALE - TerrainRegionPipelines.COMPACT_POS_BIAS

    internal fun packCompactUv2(uv2Lo: Int, uv2Hi: Int): Int =
        (uv2Lo and 0xFF) or ((uv2Hi and 0xFF) shl 8)

    internal fun writeCompactVertex(srcAddr: Long, dstAddr: Long, regOffX: Float, regOffY: Float, regOffZ: Float) {
        val qx = packCompactCoord(MemoryUtil.memGetFloat(srcAddr) + regOffX)
        val qy = packCompactCoord(MemoryUtil.memGetFloat(srcAddr + 4L) + regOffY)
        val qz = packCompactCoord(MemoryUtil.memGetFloat(srcAddr + 8L) + regOffZ)
        val color = MemoryUtil.memGetInt(srcAddr + 12L)
        val uv0 = MemoryUtil.memGetLong(srcAddr + 16L)
        val uv2Lo = MemoryUtil.memGetShort(srcAddr + 24L).toInt()
        val uv2Hi = MemoryUtil.memGetShort(srcAddr + 26L).toInt()
        val packedPosXY = (qx and 0xFFFF) or ((qy and 0xFFFF) shl 16)
        val packedPosZW = (qz and 0xFFFF) or (packCompactUv2(uv2Lo, uv2Hi) shl 16)
        MemoryUtil.memPutInt(dstAddr, packedPosXY)
        MemoryUtil.memPutInt(dstAddr + 4L, packedPosZW)
        MemoryUtil.memPutInt(dstAddr + 8L, color)
        MemoryUtil.memPutLong(dstAddr + 12L, uv0)
    }

    @JvmStatic
    fun bucketAndShiftResults(sectionPos: SectionPos, results: SectionCompiler.Results) {
        val sectionKey = sectionPos.asLong()
        for (mesh in results.renderedLayers.values) {
            (mesh as? BucketedMeshData)?.`vulkanturbo$setSectionKey`(sectionKey)
        }
        if (!TerrainRegionPipelines.isRegionBatchingActive()) return
        val regOffX = (sectionPos.x() and 3) * 16.0f
        val regOffY = (sectionPos.y() and 3) * 16.0f
        val regOffZ = (sectionPos.z() and 3) * 16.0f
        val compact = TerrainRegionPipelines.isCompactVertexActive()

        results.renderedLayers[ChunkSectionLayer.SOLID]?.let { mesh ->
            processBucketedMesh(mesh, regOffX, regOffY, regOffZ, compact)
        }
        results.renderedLayers[ChunkSectionLayer.CUTOUT]?.let { mesh ->
            processBucketedMesh(mesh, regOffX, regOffY, regOffZ, compact)
        }
        results.renderedLayers[ChunkSectionLayer.TRANSLUCENT]?.let { mesh ->
            processTranslucentMesh(mesh, regOffX, regOffY, regOffZ, compact)
        }
    }

    internal fun classifyQuadDirection(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
    ): Int {
        val d1x = x2 - x0; val d1y = y2 - y0; val d1z = z2 - z0
        val d2x = x3 - x1; val d2y = y3 - y1; val d2z = z3 - z1
        val nx = d1y * d2z - d1z * d2y
        val ny = d1z * d2x - d1x * d2z
        val nz = d1x * d2y - d1y * d2x

        val ax = abs(nx)
        val ay = abs(ny)
        val az = abs(nz)
        val tol = 1e-5f
        return when {
            ay > 1e-6f && ax <= ay * tol && az <= ay * tol -> if (ny < 0f) BUCKET_DOWN else BUCKET_UP
            az > 1e-6f && ax <= az * tol && ay <= az * tol -> if (nz < 0f) BUCKET_NORTH else BUCKET_SOUTH
            ax > 1e-6f && ay <= ax * tol && az <= ax * tol -> if (nx < 0f) BUCKET_WEST else BUCKET_EAST
            else -> BUCKET_UNASSIGNED
        }
    }

    private fun processBucketedMesh(
        mesh: MeshData,
        regOffX: Float,
        regOffY: Float,
        regOffZ: Float,
        compactRequested: Boolean,
    ) {
        if (mesh.indexBuffer() != null) return
        val drawState = mesh.drawState()
        val vertexCount = drawState.vertexCount()
        val quadCount = vertexCount ushr 2
        if (quadCount <= 0 || (vertexCount and 3) != 0) return
        val vertexStride = drawState.format().getVertexSize()
        if (vertexStride < 16 || (vertexStride and 3) != 0) return
        val quadStride = vertexStride * 4
        val vb = mesh.vertexBuffer()
        val totalBytes = quadCount * quadStride
        if (vb.remaining() < totalBytes) return

        val srcBase = MemoryUtil.memAddress(vb)
        val compact = compactRequested &&
            vertexStride == DEFAULT_VERTEX_STRIDE &&
            canCompactBufferLosslessly(srcBase, vertexCount, vertexStride, regOffX, regOffY, regOffZ)
        val scratch = workerScratch.get()
        val quadBuckets = scratch.ensureBucketsCapacity(quadCount)
        val vStrideL = vertexStride.toLong()
        val qStrideL = quadStride.toLong()

        val counts = IntArray(BUCKET_COUNT)
        var downMaxY = Float.NEGATIVE_INFINITY
        var upMinY = Float.POSITIVE_INFINITY
        var northMaxZ = Float.NEGATIVE_INFINITY
        var southMinZ = Float.POSITIVE_INFINITY
        var westMaxX = Float.NEGATIVE_INFINITY
        var eastMinX = Float.POSITIVE_INFINITY

        for (q in 0 until quadCount) {
            val qAddr = srcBase + q.toLong() * qStrideL
            val v1Addr = qAddr + vStrideL
            val v2Addr = qAddr + vStrideL * 2L
            val v3Addr = qAddr + vStrideL * 3L

            val x0 = MemoryUtil.memGetFloat(qAddr)
            val y0 = MemoryUtil.memGetFloat(qAddr + 4L)
            val z0 = MemoryUtil.memGetFloat(qAddr + 8L)
            val x1 = MemoryUtil.memGetFloat(v1Addr)
            val y1 = MemoryUtil.memGetFloat(v1Addr + 4L)
            val z1 = MemoryUtil.memGetFloat(v1Addr + 8L)
            val x2 = MemoryUtil.memGetFloat(v2Addr)
            val y2 = MemoryUtil.memGetFloat(v2Addr + 4L)
            val z2 = MemoryUtil.memGetFloat(v2Addr + 8L)
            val x3 = MemoryUtil.memGetFloat(v3Addr)
            val y3 = MemoryUtil.memGetFloat(v3Addr + 4L)
            val z3 = MemoryUtil.memGetFloat(v3Addr + 8L)

            val b = classifyQuadDirection(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3)
            quadBuckets[q] = b
            counts[b]++

            when (b) {
                BUCKET_DOWN -> {
                    val m = maxOf(maxOf(y0, y1), maxOf(y2, y3))
                    if (m > downMaxY) downMaxY = m
                }
                BUCKET_UP -> {
                    val m = minOf(minOf(y0, y1), minOf(y2, y3))
                    if (m < upMinY) upMinY = m
                }
                BUCKET_NORTH -> {
                    val m = maxOf(maxOf(z0, z1), maxOf(z2, z3))
                    if (m > northMaxZ) northMaxZ = m
                }
                BUCKET_SOUTH -> {
                    val m = minOf(minOf(z0, z1), minOf(z2, z3))
                    if (m < southMinZ) southMinZ = m
                }
                BUCKET_WEST -> {
                    val m = maxOf(maxOf(x0, x1), maxOf(x2, x3))
                    if (m > westMaxX) westMaxX = m
                }
                BUCKET_EAST -> {
                    val m = minOf(minOf(x0, x1), minOf(x2, x3))
                    if (m < eastMinX) eastMinX = m
                }
            }
        }

        val bucketVertexStarts = IntArray(BUCKET_COUNT)
        val bucketIndexCounts = IntArray(BUCKET_COUNT)
        var runningQuads = 0
        var nonZeroBuckets = 0
        for (b in 0 until BUCKET_COUNT) {
            val c = counts[b]
            if (c > 0) nonZeroBuckets++
            bucketVertexStarts[b] = runningQuads shl 2
            bucketIndexCounts[b] = c * 6
            runningQuads += c
        }

        val shift = (regOffX != 0.0f) || (regOffY != 0.0f) || (regOffZ != 0.0f)
        if (nonZeroBuckets <= 1) {
            if (compact) {
                for (v in 0 until vertexCount) {
                    val vSrc = srcBase + v.toLong() * 28L
                    val vDst = srcBase + v.toLong() * 20L
                    writeCompactVertex(vSrc, vDst, regOffX, regOffY, regOffZ)
                }
            } else if (shift) {
                for (v in 0 until vertexCount) {
                    val vAddr = srcBase + v.toLong() * vStrideL
                    MemoryUtil.memPutFloat(vAddr, MemoryUtil.memGetFloat(vAddr) + regOffX)
                    MemoryUtil.memPutFloat(vAddr + 4L, MemoryUtil.memGetFloat(vAddr + 4L) + regOffY)
                    MemoryUtil.memPutFloat(vAddr + 8L, MemoryUtil.memGetFloat(vAddr + 8L) + regOffZ)
                }
            }
        } else if (compact) {
            val compactTotalBytes = vertexCount * COMPACT_VERTEX_STRIDE
            val scratchBuf = scratch.ensureScratchBuffer(compactTotalBytes)
            val dstBase = MemoryUtil.memAddress(scratchBuf)
            val writeByteCursor = LongArray(BUCKET_COUNT)
            for (b in 0 until BUCKET_COUNT) {
                writeByteCursor[b] = dstBase + (bucketVertexStarts[b].toLong() * 20L)
            }
            for (q in 0 until quadCount) {
                val b = quadBuckets[q]
                val srcAddr = srcBase + q.toLong() * 112L
                val dstAddr = writeByteCursor[b]
                writeByteCursor[b] = dstAddr + 80L
                for (v in 0 until 4) {
                    writeCompactVertex(srcAddr + v.toLong() * 28L, dstAddr + v.toLong() * 20L, regOffX, regOffY, regOffZ)
                }
            }
            MemoryUtil.memCopy(dstBase, srcBase, compactTotalBytes.toLong())
        } else {
            val scratchBuf = scratch.ensureScratchBuffer(totalBytes)
            val dstBase = MemoryUtil.memAddress(scratchBuf)
            val writeByteCursor = LongArray(BUCKET_COUNT)
            for (b in 0 until BUCKET_COUNT) {
                writeByteCursor[b] = dstBase + (bucketVertexStarts[b].toLong() * vStrideL)
            }

            if (shift && vertexStride == DEFAULT_VERTEX_STRIDE) {
                for (q in 0 until quadCount) {
                    val b = quadBuckets[q]
                    val srcAddr = srcBase + q.toLong() * 112L
                    val dstAddr = writeByteCursor[b]
                    writeByteCursor[b] = dstAddr + 112L
                    for (v in 0 until 4) {
                        val vSrc = srcAddr + v.toLong() * 28L
                        val vDst = dstAddr + v.toLong() * 28L
                        MemoryUtil.memPutFloat(vDst, MemoryUtil.memGetFloat(vSrc) + regOffX)
                        MemoryUtil.memPutFloat(vDst + 4L, MemoryUtil.memGetFloat(vSrc + 4L) + regOffY)
                        MemoryUtil.memPutFloat(vDst + 8L, MemoryUtil.memGetFloat(vSrc + 8L) + regOffZ)
                        MemoryUtil.memPutLong(vDst + 12L, MemoryUtil.memGetLong(vSrc + 12L))
                        MemoryUtil.memPutLong(vDst + 20L, MemoryUtil.memGetLong(vSrc + 20L))
                    }
                }
            } else if (shift) {
                val tailBytes = vStrideL - 12L
                for (q in 0 until quadCount) {
                    val b = quadBuckets[q]
                    val srcAddr = srcBase + q.toLong() * qStrideL
                    val dstAddr = writeByteCursor[b]
                    writeByteCursor[b] = dstAddr + qStrideL
                    for (v in 0 until 4) {
                        val vSrc = srcAddr + v.toLong() * vStrideL
                        val vDst = dstAddr + v.toLong() * vStrideL
                        MemoryUtil.memPutFloat(vDst, MemoryUtil.memGetFloat(vSrc) + regOffX)
                        MemoryUtil.memPutFloat(vDst + 4L, MemoryUtil.memGetFloat(vSrc + 4L) + regOffY)
                        MemoryUtil.memPutFloat(vDst + 8L, MemoryUtil.memGetFloat(vSrc + 8L) + regOffZ)
                        MemoryUtil.memCopy(vSrc + 12L, vDst + 12L, tailBytes)
                    }
                }
            } else {
                for (q in 0 until quadCount) {
                    val b = quadBuckets[q]
                    val srcAddr = srcBase + q.toLong() * qStrideL
                    val dstAddr = writeByteCursor[b]
                    writeByteCursor[b] = dstAddr + qStrideL
                    MemoryUtil.memCopy(srcAddr, dstAddr, qStrideL)
                }
            }
            MemoryUtil.memCopy(dstBase, srcBase, totalBytes.toLong())
        }

        val bucketed = mesh as BucketedMeshData
        if (compact) {
            vb.limit(vb.position() + vertexCount * COMPACT_VERTEX_STRIDE)
            bucketed.`vulkanturbo$setDrawState`(
                MeshData.DrawState(
                    TerrainRegionPipelines.COMPACT_TERRAIN_20B,
                    vertexCount,
                    drawState.indexCount(),
                    drawState.primitiveTopology(),
                    drawState.indexType(),
                ),
            )
        }
        val faceLimits = floatArrayOf(downMaxY, upMinY, northMaxZ, southMinZ, westMaxX, eastMinX)
        bucketed.`vulkanturbo$setBuckets`(
            TerrainSectionBuckets(bucketVertexStarts, bucketIndexCounts, faceLimits),
        )
        bucketed.`vulkanturbo$setRegionShifted`(true)
    }

    private fun processTranslucentMesh(
        mesh: MeshData,
        regOffX: Float,
        regOffY: Float,
        regOffZ: Float,
        compactRequested: Boolean,
    ) {
        val drawState = mesh.drawState()
        val vertexCount = drawState.vertexCount()
        if (vertexCount <= 0) return
        val vertexStride = drawState.format().getVertexSize()
        if (vertexStride < 16 || (vertexStride and 3) != 0) return
        val vb = mesh.vertexBuffer()
        if (vb.remaining() < vertexCount * vertexStride) return

        val srcBase = MemoryUtil.memAddress(vb)
        val compact = compactRequested &&
            vertexStride == DEFAULT_VERTEX_STRIDE &&
            canCompactBufferLosslessly(srcBase, vertexCount, vertexStride, regOffX, regOffY, regOffZ)
        if (compact) {
            for (v in 0 until vertexCount) {
                val vSrc = srcBase + v.toLong() * 28L
                val vDst = srcBase + v.toLong() * 20L
                writeCompactVertex(vSrc, vDst, regOffX, regOffY, regOffZ)
            }
            vb.limit(vb.position() + vertexCount * COMPACT_VERTEX_STRIDE)
            (mesh as BucketedMeshData).`vulkanturbo$setDrawState`(
                MeshData.DrawState(
                    TerrainRegionPipelines.COMPACT_TERRAIN_20B,
                    vertexCount,
                    drawState.indexCount(),
                    drawState.primitiveTopology(),
                    drawState.indexType(),
                ),
            )
        } else if (regOffX != 0.0f || regOffY != 0.0f || regOffZ != 0.0f) {
            val vStrideL = vertexStride.toLong()
            for (v in 0 until vertexCount) {
                val vAddr = srcBase + v.toLong() * vStrideL
                MemoryUtil.memPutFloat(vAddr, MemoryUtil.memGetFloat(vAddr) + regOffX)
                MemoryUtil.memPutFloat(vAddr + 4L, MemoryUtil.memGetFloat(vAddr + 4L) + regOffY)
                MemoryUtil.memPutFloat(vAddr + 8L, MemoryUtil.memGetFloat(vAddr + 8L) + regOffZ)
            }
        }
        (mesh as BucketedMeshData).`vulkanturbo$setRegionShifted`(true)
    }
}

/**
 * Reusable multi-frame off-heap multi-draw command plan for `SOLID`, `CUTOUT`, and `TRANSLUCENT`
 * layers. Supports both `vkCmdDrawMultiIndexedEXT` (`MULTI_DRAW_EXT`) and retained GPU
 * `vkCmdDrawIndexedIndirect` (`VK_INDIRECT` fallback).
 */
class RegionBatchPlan {
    companion object {
        const val LAYER_COUNT = 3
        private const val INDIRECT_COMMAND_SIZE = 20L
        private const val INDIRECT_RING_SLOTS = 3

        @JvmStatic
        fun render(
            plan: RegionBatchPlan,
            layer: ChunkSectionLayer,
            renderPass: RenderPass,
            quadIndexBuffer: GpuBuffer?,
            indexType: IndexType?,
            multidrawOverride: RenderPipeline?,
        ) = plan.renderLayer(layer, renderPass, quadIndexBuffer, indexType, multidrawOverride)

        internal fun writeIndirectCommands(
            srcMultiDrawInts: IntBuffer,
            intOffset: Int,
            drawCount: Int,
            dstAddr: Long,
        ) {
            val srcAddr = MemoryUtil.memAddress(srcMultiDrawInts, 0) + (intOffset.toLong() shl 2)
            var s = srcAddr
            var d = dstAddr
            for (i in 0 until drawCount) {
                val firstIndex = MemoryUtil.memGetInt(s)
                val indexCount = MemoryUtil.memGetInt(s + 4L)
                val vertexOffset = MemoryUtil.memGetInt(s + 8L)
                // VkDrawIndexedIndirectCommand: indexCount, instanceCount=1, firstIndex, vertexOffset, firstInstance=0
                MemoryUtil.memPutLong(d, (indexCount.toLong() and 0xFFFFFFFFL) or (1L shl 32))
                MemoryUtil.memPutLong(d + 8L, (firstIndex.toLong() and 0xFFFFFFFFL) or (vertexOffset.toLong() shl 32))
                MemoryUtil.memPutInt(d + 16L, 0)
                s += 12L
                d += 20L
            }
        }
    }

    private var drawInts: IntBuffer? = MemoryUtil.memAllocInt(8192 * 3)
    private var drawIntsCount = 0

    private var pushConstantsBuf: ByteBuffer? = MemoryUtil.memAlloc(16)
    private val vbSliceCache = IdentityHashMap<GpuBuffer, GpuBufferSlice>(16)

    private val batchRegionX = Array(LAYER_COUNT) { IntArray(128) }
    private val batchRegionY = Array(LAYER_COUNT) { IntArray(128) }
    private val batchRegionZ = Array(LAYER_COUNT) { IntArray(128) }
    private val batchVisibility = Array(LAYER_COUNT) { FloatArray(128) }
    private val batchVertexBuffer = Array(LAYER_COUNT) { arrayOfNulls<GpuBuffer>(128) }
    private val batchIndexBuffer = Array(LAYER_COUNT) { arrayOfNulls<GpuBuffer>(128) }
    private val batchIndexType = Array(LAYER_COUNT) { arrayOfNulls<IndexType>(128) }
    private val batchIntOffset = Array(LAYER_COUNT) { IntArray(128) }
    private val batchDrawCount = Array(LAYER_COUNT) { IntArray(128) }
    private val batchCompactVertex = Array(LAYER_COUNT) { BooleanArray(128) }
    private val batchCount = IntArray(LAYER_COUNT)
    private val layerHasFallback = BooleanArray(LAYER_COUNT)
    private val layerUseCompact = BooleanArray(LAYER_COUNT)

    // Retained triple-buffered indirect command buffer for VK_INDIRECT fallback.
    private val indirectBuffers = arrayOfNulls<GpuBuffer>(INDIRECT_RING_SLOTS)
    private val indirectBufferCaps = LongArray(INDIRECT_RING_SLOTS)
    private var activeIndirectSlot = 0
    private var uploadedIndirectRevision = -1
    private var currentRevision = 1

    fun reset() {
        drawIntsCount = 0
        drawInts?.clear()
        for (li in 0 until LAYER_COUNT) {
            batchCount[li] = 0
            layerHasFallback[li] = false
            layerUseCompact[li] = false
        }
        currentRevision++
    }

    fun markFallback(layerOrdinal: Int) {
        if (layerOrdinal in 0 until LAYER_COUNT) {
            layerHasFallback[layerOrdinal] = true
        }
    }

    fun hasFallback(layer: ChunkSectionLayer): Boolean {
        val li = layer.ordinal
        return li in 0 until LAYER_COUNT && layerHasFallback[li]
    }

    fun setLayerCompact(layerOrdinal: Int, compact: Boolean) {
        if (layerOrdinal in 0 until LAYER_COUNT) {
            layerUseCompact[layerOrdinal] = compact
        }
    }

    fun getBatchCount(layerOrdinal: Int): Int =
        if (layerOrdinal in 0 until LAYER_COUNT) batchCount[layerOrdinal] else 0

    fun isBatchCompact(layerOrdinal: Int, batchIdx: Int): Boolean =
        layerOrdinal in 0 until LAYER_COUNT && batchIdx in 0 until batchCount[layerOrdinal] && batchCompactVertex[layerOrdinal][batchIdx]

    fun releaseStaleBuffers() {
        for (li in 0 until LAYER_COUNT) {
            val vbs = batchVertexBuffer[li]
            val ibs = batchIndexBuffer[li]
            val its = batchIndexType[li]
            for (i in batchCount[li] until vbs.size) {
                if (vbs[i] == null && ibs[i] == null) break
                vbs[i] = null
                ibs[i] = null
                its[i] = null
            }
        }
        if (vbSliceCache.size > 32) {
            vbSliceCache.clear()
        }
    }

    /** Clears all retained GPU buffer references on world unload or renderer reset. */
    fun clearAllBufferReferences() {
        reset()
        for (li in 0 until LAYER_COUNT) {
            batchVertexBuffer[li].fill(null)
            batchIndexBuffer[li].fill(null)
            batchIndexType[li].fill(null)
        }
        vbSliceCache.clear()
    }

    /** Releases triple-buffered GPU indirect buffers and off-heap command buffers before `VulkanDevice.close()` destroys VMA. */
    fun closeGpuResources() {
        clearAllBufferReferences()
        for (i in 0 until INDIRECT_RING_SLOTS) {
            val buf = indirectBuffers[i]
            indirectBuffers[i] = null
            indirectBufferCaps[i] = 0L
            if (buf != null) {
                try {
                    if (!buf.isClosed()) {
                        buf.close()
                    }
                } catch (_: Throwable) {
                }
            }
        }
        drawInts?.let {
            drawInts = null
            MemoryUtil.memFree(it)
        }
        pushConstantsBuf?.let {
            pushConstantsBuf = null
            MemoryUtil.memFree(it)
        }
        uploadedIndirectRevision = -1
        activeIndirectSlot = 0
    }

    fun hasLayerBatches(layerOrdinal: Int): Boolean =
        layerOrdinal in 0 until LAYER_COUNT && batchCount[layerOrdinal] > 0

    fun beginBatch(
        layerOrdinal: Int,
        regionOriginX: Int,
        regionOriginY: Int,
        regionOriginZ: Int,
        visibility: Float,
        vertexBuffer: GpuBuffer,
        indexBuffer: GpuBuffer? = null,
        indexType: IndexType? = null,
        compactVertex: Boolean = false,
    ): Int {
        val idx = batchCount[layerOrdinal]++
        if (idx >= batchRegionX[layerOrdinal].size) {
            val newCap = idx * 2
            batchRegionX[layerOrdinal] = batchRegionX[layerOrdinal].copyOf(newCap)
            batchRegionY[layerOrdinal] = batchRegionY[layerOrdinal].copyOf(newCap)
            batchRegionZ[layerOrdinal] = batchRegionZ[layerOrdinal].copyOf(newCap)
            batchVisibility[layerOrdinal] = batchVisibility[layerOrdinal].copyOf(newCap)
            batchVertexBuffer[layerOrdinal] = batchVertexBuffer[layerOrdinal].copyOf(newCap)
            batchIndexBuffer[layerOrdinal] = batchIndexBuffer[layerOrdinal].copyOf(newCap)
            batchIndexType[layerOrdinal] = batchIndexType[layerOrdinal].copyOf(newCap)
            batchIntOffset[layerOrdinal] = batchIntOffset[layerOrdinal].copyOf(newCap)
            batchDrawCount[layerOrdinal] = batchDrawCount[layerOrdinal].copyOf(newCap)
            batchCompactVertex[layerOrdinal] = batchCompactVertex[layerOrdinal].copyOf(newCap)
        }
        batchRegionX[layerOrdinal][idx] = regionOriginX
        batchRegionY[layerOrdinal][idx] = regionOriginY
        batchRegionZ[layerOrdinal][idx] = regionOriginZ
        batchVisibility[layerOrdinal][idx] = visibility
        batchVertexBuffer[layerOrdinal][idx] = vertexBuffer
        batchIndexBuffer[layerOrdinal][idx] = indexBuffer
        batchIndexType[layerOrdinal][idx] = indexType
        batchIntOffset[layerOrdinal][idx] = drawIntsCount
        batchDrawCount[layerOrdinal][idx] = 0
        batchCompactVertex[layerOrdinal][idx] = compactVertex
        return idx
    }

    fun appendBatchDrawsPacked2(layerOrdinal: Int, batchIdx: Int, srcPairs: IntArray, srcCount: Int) {
        if (srcCount <= 0) return
        val neededInts = drawIntsCount + srcCount * 3
        val intsBuf = ensureDrawIntsCapacity(neededInts)
        var dstPtr = MemoryUtil.memAddress(intsBuf, 0) + (drawIntsCount.toLong() shl 2)
        var p = 0
        for (i in 0 until srcCount) {
            val indexCount = srcPairs[p]
            val vertexOffset = srcPairs[p + 1]
            MemoryUtil.memPutLong(dstPtr, indexCount.toLong() shl 32)
            MemoryUtil.memPutInt(dstPtr + 8L, vertexOffset)
            dstPtr += 12L
            p += 2
        }
        drawIntsCount = neededInts
        batchDrawCount[layerOrdinal][batchIdx] += srcCount
    }

    fun appendBatchDraws(layerOrdinal: Int, batchIdx: Int, srcInts: IntArray, srcCount: Int) {
        if (srcCount <= 0) return
        val neededInts = drawIntsCount + srcCount * 3
        val intsBuf = ensureDrawIntsCapacity(neededInts)
        var dstPtr = MemoryUtil.memAddress(intsBuf, 0) + (drawIntsCount.toLong() shl 2)
        var s = 0
        for (i in 0 until srcCount) {
            val firstIdx = srcInts[s].toLong() and 0xFFFFFFFFL
            val idxCount = srcInts[s + 1].toLong() shl 32
            MemoryUtil.memPutLong(dstPtr, firstIdx or idxCount)
            MemoryUtil.memPutInt(dstPtr + 8L, srcInts[s + 2])
            dstPtr += 12L
            s += 3
        }
        drawIntsCount = neededInts
        batchDrawCount[layerOrdinal][batchIdx] += srcCount
    }

    private fun ensureDrawIntsCapacity(neededInts: Int): IntBuffer {
        val current = drawInts
        if (current == null) {
            val newCap = maxOf(8192 * 3, Integer.highestOneBit((neededInts - 1).coerceAtLeast(1)) shl 1)
            val allocated = MemoryUtil.memAllocInt(newCap)
            drawInts = allocated
            return allocated
        }
        if (neededInts > current.capacity()) {
            val newCap = Integer.highestOneBit(neededInts - 1) shl 1
            val resized = MemoryUtil.memAllocInt(newCap)
            MemoryUtil.memCopy(
                MemoryUtil.memAddress(current, 0),
                MemoryUtil.memAddress(resized, 0),
                drawIntsCount.toLong() shl 2,
            )
            MemoryUtil.memFree(current)
            drawInts = resized
            return resized
        }
        return current
    }

    private fun requirePushConstantsBuf(): ByteBuffer {
        var buf = pushConstantsBuf
        if (buf == null) {
            buf = MemoryUtil.memAlloc(16)
            pushConstantsBuf = buf
        }
        return buf
    }

    private fun ensureIndirectBufferUploaded(): GpuBuffer? {
        val totalDraws = drawIntsCount / 3
        if (totalDraws <= 0) return null
        if (uploadedIndirectRevision == currentRevision) {
            val existing = indirectBuffers[activeIndirectSlot]
            if (existing != null && !existing.isClosed()) {
                return existing
            }
        }
        val slot = (activeIndirectSlot + 1) % INDIRECT_RING_SLOTS
        activeIndirectSlot = slot
        val neededBytes = totalDraws.toLong() * INDIRECT_COMMAND_SIZE
        var buf = indirectBuffers[slot]
        if (buf == null || buf.isClosed() || indirectBufferCaps[slot] < neededBytes) {
            buf?.close()
            val allocBytes = (neededBytes.coerceAtLeast(16384L) * 3L) / 2L
            val device = RenderSystem.getDevice()
            buf = device.createBuffer(
                { "VulkanTurbo Region Indirect [$slot]" },
                GpuBuffer.USAGE_INDIRECT_PARAMETERS or GpuBuffer.USAGE_MAP_WRITE,
                allocBytes,
            )
            indirectBuffers[slot] = buf
            indirectBufferCaps[slot] = allocBytes
        }
        val intsBuf = drawInts ?: return null
        buf.map(0L, neededBytes, false, true).use { view ->
            val dstAddr = MemoryUtil.memAddress(view.data())
            writeIndirectCommands(intsBuf, 0, totalDraws, dstAddr)
        }
        uploadedIndirectRevision = currentRevision
        return buf
    }

    fun renderLayer(
        layer: ChunkSectionLayer,
        renderPass: RenderPass,
        quadIndexBuffer: GpuBuffer?,
        indexType: IndexType?,
        multidrawOverride: RenderPipeline?,
    ) {
        val li = layer.ordinal
        val count = batchCount[li]
        if (count == 0 || quadIndexBuffer == null || indexType == null) return
        if (TurboShadersBridge.skipWorldRasterizationActive) return

        val drawMode = TerrainRegionPipelines.resolveDrawMode()
        if (drawMode == RegionDrawMode.NONE) return

        var currentCompact = batchCompactVertex[li][0]
        var activePipeline = TerrainRegionPipelines.resolvePipeline(li, currentCompact, multidrawOverride)
        if (!TerrainRegionPipelines.isPipelineCompatibleWithRegionPushConstants(activePipeline)) {
            return
        }
        if (currentCompact && !TerrainRegionPipelines.isPipelineCompatibleWith20B(activePipeline)) {
            currentCompact = false
            activePipeline = TerrainRegionPipelines.resolvePipeline(li, false, multidrawOverride)
        }

        if (TurboShadersBridge.active) {
            TurboShadersBridge.notifySharedQuadIndexBuffer(quadIndexBuffer)
            // Must run BEFORE setPipeline: the 4-MRT view override has to be in place when
            // FrontendRenderPass.setPipeline validates the pass attachment count.
            TurboShadersBridge.onBeginRegionTerrainPass(renderPass, layer)
        }
        renderPass.setPipeline(RenderSystem.getCompiledPipeline(activePipeline))
        if (TurboShadersBridge.active) {
            // Must run AFTER setPipeline: VulkanRenderPass.setPipeline clears the backend uniform
            // list, so VsMotion/atlases have to be (re)bound against the just-bound pipeline.
            TurboShadersBridge.onAfterRegionPipelineBound(renderPass, layer)
        }

        val indirectGpuBuf = if (drawMode == RegionDrawMode.VK_INDIRECT) ensureIndirectBufferUploaded() else null
        val deviceLimits = RenderSystem.getDevice().deviceInfo.limits()
        val maxMultiDraw = deviceLimits.maxMultiDrawDirectInterleavedDrawCount()
        val maxIndirectDraw = if (TerrainRegionPipelines.isMultiDrawIndirectSupported()) {
            deviceLimits.maxDrawIndirectDrawCount().coerceAtLeast(1)
        } else {
            1
        }

        val rxArr = batchRegionX[li]
        val ryArr = batchRegionY[li]
        val rzArr = batchRegionZ[li]
        val visArr = batchVisibility[li]
        val vbArr = batchVertexBuffer[li]
        val ibArr = batchIndexBuffer[li]
        val itArr = batchIndexType[li]
        val offArr = batchIntOffset[li]
        val cntArr = batchDrawCount[li]
        val cmpArr = batchCompactVertex[li]
        val intsBuf = drawInts ?: return
        val pushBuf = requirePushConstantsBuf()
        val pushAddr = MemoryUtil.memAddress(pushBuf)
        val sliceCache = vbSliceCache

        var lastVb: GpuBuffer? = null
        var lastIb: GpuBuffer? = null
        var lastIt: IndexType? = null
        var lastRx = Int.MIN_VALUE
        var lastRy = Int.MIN_VALUE
        var lastRz = Int.MIN_VALUE
        var lastVis = Float.NaN

        for (i in 0 until count) {
            val drawCount = cntArr[i]
            if (drawCount <= 0) continue

            val batchCompact = cmpArr[i]
            if (batchCompact != currentCompact) {
                val candidate = TerrainRegionPipelines.resolvePipeline(li, batchCompact, multidrawOverride)
                if (!batchCompact || TerrainRegionPipelines.isPipelineCompatibleWith20B(candidate)) {
                    currentCompact = batchCompact
                    activePipeline = candidate
                    renderPass.setPipeline(RenderSystem.getCompiledPipeline(activePipeline))
                    if (TurboShadersBridge.active) {
                        TurboShadersBridge.onAfterRegionPipelineBound(renderPass, layer)
                    }
                    lastVb = null
                    lastIb = null
                    lastIt = null
                    lastRx = Int.MIN_VALUE
                    lastRy = Int.MIN_VALUE
                    lastRz = Int.MIN_VALUE
                    lastVis = Float.NaN
                }
            }

            val vb = vbArr[i]!!
            if (vb !== lastVb) {
                var slice = sliceCache[vb]
                if (slice == null) {
                    slice = vb.slice()
                    sliceCache[vb] = slice
                }
                renderPass.setVertexBuffer(0, slice)
                lastVb = vb
            }

            val ib = ibArr[i] ?: quadIndexBuffer
            val it = itArr[i] ?: indexType
            if (ib !== lastIb || it != lastIt) {
                renderPass.setIndexBuffer(ib, it)
                lastIb = ib
                lastIt = it
            }

            val rx = rxArr[i]
            val ry = ryArr[i]
            val rz = rzArr[i]
            val vis = visArr[i]
            if (rx != lastRx || ry != lastRy || rz != lastRz || vis != lastVis) {
                MemoryUtil.memPutLong(pushAddr, (rx.toLong() and 0xFFFFFFFFL) or (ry.toLong() shl 32))
                MemoryUtil.memPutLong(
                    pushAddr + 8L,
                    (rz.toLong() and 0xFFFFFFFFL) or (java.lang.Float.floatToRawIntBits(vis).toLong() shl 32),
                )
                pushBuf.clear()
                renderPass.pushConstants(pushBuf)
                lastRx = rx
                lastRy = ry
                lastRz = rz
                lastVis = vis
            }

            if (drawMode == RegionDrawMode.VK_INDIRECT && indirectGpuBuf != null) {
                var remaining = drawCount
                var drawIdx = offArr[i] / 3
                while (remaining > 0) {
                    val chunk = minOf(remaining, maxIndirectDraw)
                    val byteOff = drawIdx.toLong() * INDIRECT_COMMAND_SIZE
                    val byteSize = chunk.toLong() * INDIRECT_COMMAND_SIZE
                    renderPass.drawIndexedIndirect(indirectGpuBuf.slice(byteOff, byteSize), chunk)
                    remaining -= chunk
                    drawIdx += chunk
                }
            } else {
                var remaining = drawCount
                var intOffset = offArr[i]
                while (remaining > 0) {
                    val chunk = minOf(remaining, maxMultiDraw)
                    intsBuf.clear().limit(intOffset + chunk * 3).position(intOffset)
                    renderPass.multiDrawIndexed(intsBuf, 1, 0, chunk)
                    remaining -= chunk
                    intOffset += chunk * 3
                }
            }
        }
        intsBuf.clear()
    }
}
