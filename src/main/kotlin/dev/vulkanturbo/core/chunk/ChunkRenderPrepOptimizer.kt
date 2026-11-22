package dev.vulkanturbo.core

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.pipeline.IndexType
import dev.vulkanturbo.VulkanTurbo
import dev.vulkanturbo.api.shader.TurboShadersBridge
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.minecraft.client.renderer.DynamicGpuData
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender
import net.minecraft.client.renderer.chunk.RenderSectionRegion
import net.minecraft.client.renderer.chunk.SectionMesh
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher
import net.minecraft.client.renderer.state.OptionsRenderState
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.texture.TextureManager
import net.minecraft.util.Util
import org.joml.Matrix4fc
import org.joml.Vector3fc
import org.joml.Vector4fc
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicInteger

/** Duck interface mixed into `DynamicGpuDataStorageNonMapped` and `DynamicGpuDataStorageMapped`. */
interface RawChunkSectionWriter {
    fun `vulkanturbo$writeRawSections`(coords: IntArray, visibilities: FloatArray, count: Int): GpuBufferSlice
}

/** Duck interface mixed into `DynamicGpuDataStorageMapped` for indirect draw command upload. */
interface RawChunkCommandWriter {
    fun `vulkanturbo$writeRawCommandGroups`(
        groupData: Array<IntArray>,
        groupDrawCounts: IntArray,
        groupIndices: IntArray,
        groupCount: Int,
        reverseDraws: BooleanArray,
        outSlices: Array<GpuBufferSlice?>,
    )
}

/** Duck interface mixed into `DynamicGpuDataStorageMapped` for zero-allocation Transform / TerrainTransform writes. */
interface RawTransformWriter {
    fun `vulkanturbo$writeRawTransform`(
        modelView: Matrix4fc,
        colorModulator: Vector4fc,
        modelOffset: Vector3fc,
        textureMatrix: Matrix4fc,
    ): GpuBufferSlice

    fun `vulkanturbo$writeRawTerrainTransform`(
        modelView: Matrix4fc,
        atlasWidth: Int,
        atlasHeight: Int,
    ): GpuBufferSlice
}

/** Scratch holder avoiding `RenderSectionBufferSlice` record allocations per section × layer × frame. */
class MutableSectionSlice {
    @JvmField var vertexBuffer: GpuBuffer? = null
    @JvmField var vertexOffset: Long = 0L
    @JvmField var indexBuffer: GpuBuffer? = null
    @JvmField var indexOffset: Long = 0L
}

/** Duck interface mixed into `UberGpuBuffer` for direct open-addressing lookup, slot tagging, and idle skip. */
interface FastUberGpuBuffer {
    fun `vulkanturbo$getAllocation`(key: Any): com.mojang.blaze3d.vertex.TlsfAllocator.Allocation?
    fun `vulkanturbo$setMeshSlot`(slot: Int)
    fun `vulkanturbo$hasStagedWork`(): Boolean
}

/**
 * Duck interface mixed into `CompiledSectionMesh` so each section caches its 6 TLSF buffer
 * allocations (`layerOrdinal * 2 + isIndex`), 3 `SectionDraw` descriptors, compact vertex strides,
 * and shifted/bucket metadata directly without hash map lookups.
 */
interface CachedSectionMesh {
    fun `vulkanturbo$getAllocation`(slot: Int): com.mojang.blaze3d.vertex.TlsfAllocator.Allocation?
    fun `vulkanturbo$setAllocation`(slot: Int, alloc: com.mojang.blaze3d.vertex.TlsfAllocator.Allocation?)
    fun `vulkanturbo$getDraw`(layerOrdinal: Int): SectionMesh.SectionDraw?
    fun `vulkanturbo$hasBlockEntities`(): Boolean
    fun `vulkanturbo$hasRenderableDraws`(): Boolean
    fun `vulkanturbo$getBuckets`(layerOrdinal: Int): TerrainSectionBuckets?
    fun `vulkanturbo$getVertexStride`(layerOrdinal: Int): Int
    fun `vulkanturbo$getVertexCount`(layerOrdinal: Int): Int
    fun `vulkanturbo$getSectionKey`(): Long
    fun `vulkanturbo$isLayerShifted`(layerOrdinal: Int): Boolean
}

/** Duck interface mixed into `SectionRenderDispatcher` for zero-alloc slice population. */
interface FastSectionDispatcher {
    fun `vulkanturbo$fillSectionSlice`(
        layerOrdinal: Int,
        mesh: SectionMesh,
        needIndex: Boolean,
        out: MutableSectionSlice,
    ): Boolean
}

/** Duck interface mixed into `DynamicGpuData`. */
interface FastDynamicGpuData {
    fun `vulkanturbo$writeRawChunkSections`(coords: IntArray, visibilities: FloatArray, count: Int): GpuBufferSlice
    fun `vulkanturbo$writeRawChunkCommands`(
        groupData: Array<IntArray>,
        groupDrawCounts: IntArray,
        groupIndices: IntArray,
        groupCount: Int,
        reverseDraws: BooleanArray,
        outSlices: Array<GpuBufferSlice?>,
    )
}

/**
 * Zero-churn indirect & region multi-draw chunk preparation with multi-frame plan retention.
 *
 * When `visibleSections`, uploaded chunk meshes, section fade-in state, and per-section directional
 * face visibility boundaries are unchanged since the previous build, `prepareIndirect` reuses the
 * retained `RegionBatchPlan` (including `TRANSLUCENT` region batches and cleanly partitioned
 * `20B` vs `28B` sections) in O(1) without touching `visibleSections` or `SectionRenderDispatcher`.
 */
object ChunkRenderPrepOptimizer {
    private const val AMBIENT_SYNC_COMPILE_BUDGET_NS = 1_000_000L
    private const val MAX_AMBIENT_SYNC_COMPILES_PER_FRAME = 1
    private const val HIGH_END_AMBIENT_SYNC_COMPILE_BUDGET_NS = 2_500_000L
    private const val HIGH_END_MAX_AMBIENT_SYNC_COMPILES_PER_FRAME = 3
    private const val REGION_RESORT_DIST_SQ_THRESHOLD = 64.0

    private var compileFrameStartNs = 0L
    private var ambientSyncCount = 0
    private var lastUpdatePlayerChanged = false

    private val visibleSectionsEpoch = AtomicInteger(1)
    private val meshUploadEpoch = AtomicInteger(1)

    private val LAYERS: Array<ChunkSectionLayer> = ChunkSectionLayer.values()
    private val LAYER_COUNT: Int = LAYERS.size
    private val LAYER_TRANSLUCENT: BooleanArray = BooleanArray(LAYER_COUNT) { i -> LAYERS[i].translucent() }

    private val emptyDrawGroupsPerLayer: EnumMap<ChunkSectionLayer, List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect>> =
        EnumMap<ChunkSectionLayer, List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect>>(ChunkSectionLayer::class.java).apply {
            for (layer in LAYERS) {
                put(layer, emptyList())
            }
        }

    private var sectionCoords = IntArray(4096 * 3)
    private var sectionVis = FloatArray(4096)

    private class DrawGroupScratch {
        var layerIndex: Int = 0
        var vertexBuffer: GpuBuffer? = null
        var vertexSlice: GpuBufferSlice? = null
        var indexBuffer: GpuBuffer? = null
        var indexSlice: GpuBufferSlice? = null
        var indexType: IndexType? = null
        var data = IntArray(512 * 5)
        var drawCount = 0

        fun reset(li: Int, vb: GpuBuffer, ib: GpuBuffer?, it: IndexType?) {
            layerIndex = li
            if (vertexBuffer !== vb) {
                vertexBuffer = vb
                vertexSlice = vb.slice()
            }
            if (indexBuffer !== ib) {
                indexBuffer = ib
                indexSlice = ib?.slice()
            }
            indexType = it
            drawCount = 0
        }

        fun clearRefs() {
            vertexBuffer = null
            vertexSlice = null
            indexBuffer = null
            indexSlice = null
            indexType = null
            drawCount = 0
        }

        fun addDraw(indexCount: Int, firstIndex: Int, baseVertex: Int, baseInstance: Int) {
            val base = drawCount * 5
            if (base + 5 > data.size) {
                data = data.copyOf(data.size * 2)
            }
            data[base] = indexCount
            data[base + 1] = 1
            data[base + 2] = firstIndex
            data[base + 3] = baseVertex
            data[base + 4] = baseInstance
            drawCount++
        }
    }

    private var groups = Array(32) { DrawGroupScratch() }
    private var activeGroupCount = 0

    private val layerGroupIndices = Array(LAYER_COUNT) { IntArray(16) }
    private val layerGroupCounts = IntArray(LAYER_COUNT)

    private var uploadGroupData = Array(32) { IntArray(0) }
    private var uploadGroupDrawCounts = IntArray(32)
    private var uploadGroupOrder = IntArray(32)
    private var uploadReverseDraws = BooleanArray(32)
    private var uploadOutSlices = arrayOfNulls<GpuBufferSlice>(32)
    private var cachedFlatGroupCount = 0
    private val sliceScratch = MutableSectionSlice()

    private val regionPlan = RegionBatchPlan()

    private class MultiDrawSlot {
        var vertexBuffer: GpuBuffer? = null
        var compactVertex: Boolean = false
        var nextSlot: Int = -1
        /** Packed `(indexCount, vertexOffset)` pairs (`2` ints per draw). */
        var data = IntArray(256 * 2)
        var drawCount = 0

        fun addDraw(indexCount: Int, vertexOffset: Int) {
            val base = drawCount shl 1
            if (base + 2 > data.size) {
                data = data.copyOf(data.size * 2)
            }
            data[base] = indexCount
            data[base + 1] = vertexOffset
            drawCount++
        }
    }

    private var mdSlots = Array(256) { MultiDrawSlot() }
    private var mdSlotCount = 0

    private var regKeys = LongArray(2048)
    private var regIndices = IntArray(2048)
    private var regStamps = IntArray(2048)
    private var regMask = 2047
    private var regStampGen = 1

    private var regOriginX = IntArray(256)
    private var regOriginY = IntArray(256)
    private var regOriginZ = IntArray(256)
    private var regDistSq = FloatArray(256)
    private var regSortOrder = IntArray(256)
    private val regLayerHead = Array(2) { IntArray(256) { -1 } }
    private var activeRegionCount = 0

    private var fadingRx = IntArray(64)
    private var fadingRy = IntArray(64)
    private var fadingRz = IntArray(64)
    private var fadingVis = FloatArray(64)
    private var fadingLayer = IntArray(64)
    private var fadingSlot = IntArray(64)
    private var fadingCompact = BooleanArray(64)
    private var fadingCount = 0

    // Translucent candidate indices into visibleSections + batching scratch triplets (firstIdx, idxCount, baseVert)
    private var translucentSectionIndices = IntArray(1024)
    private var translucentCount = 0
    private var translucentTriplets = IntArray(64 * 3)
    private var translucentTripletsCount = 0

    // Retained cache state
    private var cachedValid = false
    private var cachedVisibleEpoch = -1
    private var cachedMeshEpoch = -1
    private var cachedVisibleCount = -1
    private var cachedFirstSection: Any? = null
    private var cachedLastSection: Any? = null
    private var cachedRegionMode = false
    private var cachedCompactMode = false
    private var cachedShaderActive = false
    private var cachedSortTranslucent = false
    private var cachedFadeMs = -1L
    private var cachedHasFade = false
    private var cachedSectionCount = 0
    private var cachedMaxIndicesRequired = 0
    private var cachedBuildCamX = 0.0
    private var cachedBuildCamY = 0.0
    private var cachedBuildCamZ = 0.0
    private var safeMinX = Double.NEGATIVE_INFINITY
    private var safeMaxX = Double.POSITIVE_INFINITY
    private var safeMinY = Double.NEGATIVE_INFINITY
    private var safeMaxY = Double.POSITIVE_INFINITY
    private var safeMinZ = Double.NEGATIVE_INFINITY
    private var safeMaxZ = Double.POSITIVE_INFINITY

    @JvmStatic
    fun isEnabled(): Boolean = VulkanTurbo.fastChunkPrepEnabled && VulkanTurbo.isBackendAllowed()

    @JvmStatic
    fun isMappedChunkSectionsEnabled(): Boolean =
        VulkanTurbo.mappedChunkSectionsEnabled && VulkanTurbo.isBackendAllowed()

    @JvmStatic
    fun bumpVisibleSectionsEpoch() {
        visibleSectionsEpoch.incrementAndGet()
    }

    @JvmStatic
    fun bumpMeshUploadEpoch() {
        meshUploadEpoch.incrementAndGet()
    }

    /**
     * Releases all retained section, slice, and GPU buffer references when a world is unloaded
     * or `LevelRenderer.resetLevelRenderData()` runs.
     */
    @JvmStatic
    fun onWorldReset() {
        cachedValid = false
        cachedFirstSection = null
        cachedLastSection = null
        cachedVisibleCount = -1
        cachedFlatGroupCount = 0
        cachedSectionCount = 0
        cachedMaxIndicesRequired = 0
        activeGroupCount = 0
        mdSlotCount = 0
        activeRegionCount = 0
        fadingCount = 0
        translucentCount = 0
        translucentTripletsCount = 0
        for (l in 0 until LAYER_COUNT) {
            layerGroupCounts[l] = 0
        }
        for (g in groups) {
            g.clearRefs()
        }
        for (s in mdSlots) {
            s.vertexBuffer = null
            s.compactVertex = false
            s.nextSlot = -1
            s.drawCount = 0
        }
        uploadOutSlices.fill(null)
        sliceScratch.vertexBuffer = null
        sliceScratch.indexBuffer = null
        regionPlan.clearAllBufferReferences()
        ChunkPipelineOptimizer.clearAllCaches()
        RenderStats.logAndReset("world reset")
        visibleSectionsEpoch.incrementAndGet()
        meshUploadEpoch.incrementAndGet()
    }

    /**
     * Closes triple-buffered GPU indirect buffers and clears retained references before
     * `VulkanDevice.close()` destroys the command encoder and VMA allocator.
     */
    @JvmStatic
    fun onDeviceClosing() {
        onWorldReset()
        regionPlan.closeGpuResources()
    }

    internal fun invalidateRetainedPlanForTesting() {
        cachedValid = false
    }

    @JvmStatic
    fun beginCompileFrame() {
        compileFrameStartNs = System.nanoTime()
        ambientSyncCount = 0
        lastUpdatePlayerChanged = false
    }

    @JvmStatic
    fun recordSectionPlayerChanged(playerChanged: Boolean): Boolean {
        lastUpdatePlayerChanged = playerChanged
        return playerChanged
    }

    @JvmStatic
    fun dispatchCompileSection(
        section: SectionRenderDispatcher.RenderSection,
        region: RenderSectionRegion,
    ) {
        if (!isEnabled() || lastUpdatePlayerChanged) {
            section.compileSync(region)
            return
        }
        val elapsedNs = System.nanoTime() - compileFrameStartNs
        if (shouldAllowAmbientSyncCompile(ambientSyncCount, elapsedNs)) {
            ambientSyncCount++
            section.compileSync(region)
        } else {
            section.compileAsync(region)
        }
    }

    internal fun shouldAllowAmbientSyncCompile(usedSyncCount: Int, elapsedNs: Long): Boolean {
        val highEnd = VulkanTurbo.isHighEndGpu
        val maxCompiles = if (highEnd) HIGH_END_MAX_AMBIENT_SYNC_COMPILES_PER_FRAME else MAX_AMBIENT_SYNC_COMPILES_PER_FRAME
        val budgetNs = if (highEnd) HIGH_END_AMBIENT_SYNC_COMPILE_BUDGET_NS else AMBIENT_SYNC_COMPILE_BUDGET_NS
        return usedSyncCount < maxCompiles && elapsedNs < budgetNs
    }

    internal fun isPointInsideOpenBounds(
        x: Double,
        y: Double,
        z: Double,
        minX: Double,
        maxX: Double,
        minY: Double,
        maxY: Double,
        minZ: Double,
        maxZ: Double,
    ): Boolean = x > minX && x < maxX && y > minY && y < maxY && z > minZ && z < maxZ

    /**
     * Computes the exact 7-bit directional visibility mask for a section at `(ox, oy, oz)`
     * where `faceLimits` may be stored in either section-local (`[0..16]`) or region-shifted
     * (`(sectionPos & 3) * 16 + [0..16]`) coordinates.
     */
    internal fun computeSectionVisMask(
        sectionCamX: Float,
        sectionCamY: Float,
        sectionCamZ: Float,
        bCounts: IntArray,
        limits: FloatArray,
    ): Int {
        var visMask = 0
        if (bCounts[0] > 0 && sectionCamY < limits[0]) visMask = visMask or 1
        if (bCounts[1] > 0 && sectionCamY > limits[1]) visMask = visMask or 2
        if (bCounts[2] > 0 && sectionCamZ < limits[2]) visMask = visMask or 4
        if (bCounts[3] > 0 && sectionCamZ > limits[3]) visMask = visMask or 8
        if (bCounts[4] > 0 && sectionCamX < limits[4]) visMask = visMask or 16
        if (bCounts[5] > 0 && sectionCamX > limits[5]) visMask = visMask or 32
        if (bCounts[6] > 0) visMask = visMask or 64
        return visMask
    }

    private fun updateAxisBounds(
        ox: Int,
        oy: Int,
        oz: Int,
        camX: Double,
        camY: Double,
        camZ: Double,
        bCounts: IntArray,
        limits: FloatArray,
    ) {
        if (bCounts[0] > 0) {
            val plane = oy.toDouble() + limits[0].toDouble()
            if (camY < plane) {
                if (plane < safeMaxY) safeMaxY = plane
            } else {
                if (plane > safeMinY) safeMinY = plane
            }
        }
        if (bCounts[1] > 0) {
            val plane = oy.toDouble() + limits[1].toDouble()
            if (camY > plane) {
                if (plane > safeMinY) safeMinY = plane
            } else {
                if (plane < safeMaxY) safeMaxY = plane
            }
        }
        if (bCounts[2] > 0) {
            val plane = oz.toDouble() + limits[2].toDouble()
            if (camZ < plane) {
                if (plane < safeMaxZ) safeMaxZ = plane
            } else {
                if (plane > safeMinZ) safeMinZ = plane
            }
        }
        if (bCounts[3] > 0) {
            val plane = oz.toDouble() + limits[3].toDouble()
            if (camZ > plane) {
                if (plane > safeMinZ) safeMinZ = plane
            } else {
                if (plane < safeMaxZ) safeMaxZ = plane
            }
        }
        if (bCounts[4] > 0) {
            val plane = ox.toDouble() + limits[4].toDouble()
            if (camX < plane) {
                if (plane < safeMaxX) safeMaxX = plane
            } else {
                if (plane > safeMinX) safeMinX = plane
            }
        }
        if (bCounts[5] > 0) {
            val plane = ox.toDouble() + limits[5].toDouble()
            if (camX > plane) {
                if (plane > safeMinX) safeMinX = plane
            } else {
                if (plane < safeMaxX) safeMaxX = plane
            }
        }
    }

    @JvmStatic
    fun prepareIndirect(
        textureManager: TextureManager,
        optionsRenderState: OptionsRenderState,
        visibleSections: ObjectArrayList<SectionRenderDispatcher.RenderSection>,
        dispatcher: SectionRenderDispatcher?,
        terrainTransform: Matrix4fc,
        sortTranslucent: Boolean,
        camX: Double,
        camY: Double,
        camZ: Double,
    ): ChunkSectionsToRender {
        val atlasView = textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).textureView
        val atlasW = atlasView.getWidth(0)
        val atlasH = atlasView.getHeight(0)

        val regionMode = TerrainRegionPipelines.isRegionBatchingActive()
        val compactMode = TerrainRegionPipelines.isCompactVertexActive()
        val shaderActive = TurboShadersBridge.active
        val fadeMs = Util.toMillis(optionsRenderState.chunkSectionFadeInTime)
        val curVisibleEpoch = visibleSectionsEpoch.get()
        val curMeshEpoch = meshUploadEpoch.get()
        val visibleCount = visibleSections.size
        @Suppress("UNCHECKED_CAST")
        val visibleRaw = visibleSections.elements() as Array<Any?>
        val firstSection = if (visibleCount > 0) visibleRaw[0] else null
        val lastSection = if (visibleCount > 0) visibleRaw[visibleCount - 1] else null

        val dynData = RenderSystem.getDynamicUniforms()
        val fastDyn = dynData as FastDynamicGpuData

        if (regionMode &&
            cachedValid &&
            !cachedHasFade &&
            cachedRegionMode == regionMode &&
            cachedCompactMode == compactMode &&
            cachedShaderActive == shaderActive &&
            cachedSortTranslucent == sortTranslucent &&
            cachedFadeMs == fadeMs &&
            cachedVisibleEpoch == curVisibleEpoch &&
            cachedMeshEpoch == curMeshEpoch &&
            cachedVisibleCount == visibleCount &&
            cachedFirstSection === firstSection &&
            cachedLastSection === lastSection &&
            isPointInsideOpenBounds(camX, camY, camZ, safeMinX, safeMaxX, safeMinY, safeMaxY, safeMinZ, safeMaxZ)
        ) {
            val dx = camX - cachedBuildCamX
            val dy = camY - cachedBuildCamY
            val dz = camZ - cachedBuildCamZ
            if (dx * dx + dy * dy + dz * dz < REGION_RESORT_DIST_SQ_THRESHOLD) {
                return buildCachedResult(dynData, fastDyn, terrainTransform, atlasW, atlasH)
            }
        }

        var maxIndicesRequired = 0
        var sectionCount = 0
        var anyFading = false
        activeGroupCount = 0
        translucentCount = 0
        for (l in 0 until LAYER_COUNT) {
            layerGroupCounts[l] = 0
        }
        if (regionMode) {
            regionPlan.reset()
            for (l in 0 until RegionBatchPlan.LAYER_COUNT) {
                regionPlan.setLayerCompact(l, compactMode)
            }
            mdSlotCount = 0
            activeRegionCount = 0
            fadingCount = 0
            safeMinX = Double.NEGATIVE_INFINITY
            safeMaxX = Double.POSITIVE_INFINITY
            safeMinY = Double.NEGATIVE_INFINITY
            safeMaxY = Double.POSITIVE_INFINITY
            safeMinZ = Double.NEGATIVE_INFINITY
            safeMaxZ = Double.POSITIVE_INFINITY
            if (++regStampGen == 0) {
                regStamps.fill(0)
                regStampGen = 1
            }
        }

        if (dispatcher != null) {
            val fastDispatcher = dispatcher as FastSectionDispatcher
            val slice = sliceScratch
            dispatcher.lock()
            try {
                val nowMs = Util.getMillis()

                for (si in 0 until visibleCount) {
                    val section = visibleRaw[si] as? SectionRenderDispatcher.RenderSection ?: continue
                    val mesh = section.getSectionMesh()
                    val cachedMesh = mesh as? CachedSectionMesh
                    if (cachedMesh != null) {
                        if (!cachedMesh.`vulkanturbo$hasRenderableDraws`()) continue
                    } else if (!mesh.hasRenderableLayers()) {
                        continue
                    }

                    val origin = section.renderOrigin
                    val ox = origin.x
                    val oy = origin.y
                    val oz = origin.z
                    val lx = (camX - ox).toFloat()
                    val ly = (camY - oy).toFloat()
                    val lz = (camZ - oz).toFloat()
                    val rx = ox and -64
                    val ry = oy and -64
                    val rz = oz and -64
                    var vis = -1.0f
                    var indirectSectionIdx = -1
                    var sectionRegionIdx = -1

                    for (li in 0 until LAYER_COUNT) {
                        val draw = cachedMesh?.`vulkanturbo$getDraw`(li) ?: mesh.getSectionDraw(LAYERS[li])
                        if (draw == null) continue

                        if (LAYER_TRANSLUCENT[li]) {
                            if (translucentCount >= translucentSectionIndices.size) {
                                translucentSectionIndices = translucentSectionIndices.copyOf(translucentSectionIndices.size * 2)
                            }
                            translucentSectionIndices[translucentCount++] = si
                            continue
                        }

                        val buckets = if (regionMode && !draw.hasCustomIndexBuffer()) {
                            cachedMesh?.`vulkanturbo$getBuckets`(li)
                        } else {
                            null
                        }
                        if (buckets != null) {
                            val bStarts = buckets.bucketVertexStarts
                            val bCounts = buckets.bucketIndexCounts
                            val limits = buckets.faceLimits

                            val visMask = if (li == 0) {
                                updateAxisBounds(ox, oy, oz, camX, camY, camZ, bCounts, limits)
                                computeSectionVisMask(lx, ly, lz, bCounts, limits)
                            } else {
                                // CUTOUT blocks (foliage, panes, bars, doors, vines, torches) are double-sided
                                // or cast screen-space/RT shadows from both sides; include all non-empty buckets
                                // so contiguous buckets coalesce into a single draw call without dropping faces.
                                var mask = 0
                                for (b in 0 until TerrainBucketBuilder.BUCKET_COUNT) {
                                    if (bCounts[b] > 0) mask = mask or (1 shl b)
                                }
                                mask
                            }
                            if (visMask == 0) continue

                            if (!fastDispatcher.`vulkanturbo$fillSectionSlice`(li, mesh, false, slice)) {
                                continue
                            }

                            if (vis < 0.0f) {
                                vis = if (fadeMs > 0L) section.getVisibility(nowMs, fadeMs) else 1.0f
                                if (vis < 1.0f) anyFading = true
                            }
                            val vb = slice.vertexBuffer!!
                            val stride = resolvedVertexStride(cachedMesh, li, compactMode)
                            val sectionCompact = stride == TerrainRegionPipelines.COMPACT_VERTEX_STRIDE
                            val targetSlotIdx = if (vis >= 1.0f) {
                                var rIdx = sectionRegionIdx
                                if (rIdx == -1) {
                                    rIdx = findOrCreateRegion(rx, ry, rz, camX, camY, camZ)
                                    sectionRegionIdx = rIdx
                                }
                                findOrCreateRegionLayerSlot(li, rIdx, vb, sectionCompact)
                            } else {
                                allocFadingSlot(rx, ry, rz, vis, li, vb, sectionCompact)
                            }
                            val targetSlot = mdSlots[targetSlotIdx]
                            val baseVertex = (slice.vertexOffset / stride).toInt()

                            var runStartVert = -1
                            var runEndVert = -1
                            var runIndices = 0
                            for (b in 0 until TerrainBucketBuilder.BUCKET_COUNT) {
                                if ((visMask and (1 shl b)) == 0) continue
                                val cnt = bCounts[b]
                                val vStart = baseVertex + bStarts[b]
                                val vCount = (cnt / 6) shl 2
                                if (runIndices == 0) {
                                    runStartVert = vStart
                                    runEndVert = vStart + vCount
                                    runIndices = cnt
                                } else if (vStart == runEndVert) {
                                    runEndVert += vCount
                                    runIndices += cnt
                                } else {
                                    if (runIndices > maxIndicesRequired) maxIndicesRequired = runIndices
                                    targetSlot.addDraw(runIndices, runStartVert)
                                    runStartVert = vStart
                                    runEndVert = vStart + vCount
                                    runIndices = cnt
                                }
                            }
                            if (runIndices > 0) {
                                if (runIndices > maxIndicesRequired) maxIndicesRequired = runIndices
                                targetSlot.addDraw(runIndices, runStartVert)
                            }
                            continue
                        }

                        val customIdx = draw.hasCustomIndexBuffer()
                        if (!fastDispatcher.`vulkanturbo$fillSectionSlice`(li, mesh, customIdx, slice)) {
                            continue
                        }
                        if (regionMode) {
                            regionPlan.markFallback(li)
                        }

                        if (indirectSectionIdx == -1) {
                            indirectSectionIdx = sectionCount
                            if (indirectSectionIdx >= sectionVis.size) {
                                val newCap = sectionVis.size * 2
                                sectionVis = sectionVis.copyOf(newCap)
                                sectionCoords = sectionCoords.copyOf(newCap * 3)
                            }
                            if (vis < 0.0f) {
                                vis = if (fadeMs > 0L) section.getVisibility(nowMs, fadeMs) else 1.0f
                                if (vis < 1.0f) anyFading = true
                            }
                            val coordBase = indirectSectionIdx * 3
                            sectionCoords[coordBase] = ox
                            sectionCoords[coordBase + 1] = oy
                            sectionCoords[coordBase + 2] = oz
                            sectionVis[indirectSectionIdx] = vis
                            sectionCount++
                        }

                        val vb = slice.vertexBuffer!!
                        val ib: GpuBuffer?
                        val it: IndexType?
                        val firstIndex: Int
                        if (!customIdx) {
                            if (draw.indexCount() > maxIndicesRequired) {
                                maxIndicesRequired = draw.indexCount()
                            }
                            ib = null
                            it = null
                            firstIndex = 0
                        } else {
                            ib = slice.indexBuffer
                            it = draw.indexType()
                            firstIndex = (slice.indexOffset ushr (it.bytes ushr 1)).toInt()
                        }
                        val stride = resolvedVertexStride(cachedMesh, li, compactMode)
                        val baseVertex = (slice.vertexOffset / stride).toInt()
                        val groupIdx = findOrCreateLayerGroup(li, vb, ib, it)
                        groups[groupIdx].addDraw(draw.indexCount(), firstIndex, baseVertex, indirectSectionIdx)
                    }
                }

                // Layer 2 (TRANSLUCENT) region batching or ordered fallback
                if (translucentCount > 0) {
                    val result = emitTranslucentBatches(
                        visibleRaw = visibleRaw,
                        fastDispatcher = fastDispatcher,
                        slice = slice,
                        regionMode = regionMode,
                        compactMode = compactMode,
                        sortTranslucent = sortTranslucent,
                        fadeMs = fadeMs,
                        nowMs = nowMs,
                        initialSectionCount = sectionCount,
                        initialMaxIndices = maxIndicesRequired,
                    )
                    sectionCount = result.sectionCount
                    maxIndicesRequired = result.maxIndices
                    if (result.anyFading) anyFading = true
                }
            } finally {
                sliceScratch.vertexBuffer = null
                sliceScratch.indexBuffer = null
                dispatcher.unlock()
            }
        }

        if (regionMode) {
            populateRegionPlan()
        }

        val totalGroups = activeGroupCount
        if (totalGroups > uploadOutSlices.size) {
            val newCap = Integer.highestOneBit(totalGroups - 1) shl 1
            uploadGroupData = Array(newCap) { IntArray(0) }
            uploadGroupDrawCounts = IntArray(newCap)
            uploadGroupOrder = IntArray(newCap)
            uploadReverseDraws = BooleanArray(newCap)
            uploadOutSlices = arrayOfNulls(newCap)
        }

        var flatIdx = 0
        for (li in 0 until LAYER_COUNT) {
            val cnt = layerGroupCounts[li]
            val indices = layerGroupIndices[li]
            // emitTranslucentBatches() already emits translucent groups/draws in back-to-front order.
            for (i in 0 until cnt) {
                val g = groups[indices[i]]
                uploadGroupData[flatIdx] = g.data
                uploadGroupDrawCounts[flatIdx] = g.drawCount
                uploadGroupOrder[flatIdx] = flatIdx
                uploadReverseDraws[flatIdx] = false
                flatIdx++
            }
        }
        cachedFlatGroupCount = flatIdx

        // Release stale buffer references in unused scratch slots
        for (i in activeGroupCount until groups.size) {
            val g = groups[i]
            if (g.vertexBuffer == null && g.indexBuffer == null) break
            g.clearRefs()
        }

        if (regionMode) {
            cachedValid = true
            cachedVisibleEpoch = curVisibleEpoch
            cachedMeshEpoch = curMeshEpoch
            cachedVisibleCount = visibleCount
            cachedFirstSection = firstSection
            cachedLastSection = lastSection
            cachedRegionMode = regionMode
            cachedCompactMode = compactMode
            cachedShaderActive = shaderActive
            cachedSortTranslucent = sortTranslucent
            cachedFadeMs = fadeMs
            cachedHasFade = anyFading
            cachedSectionCount = sectionCount
            cachedMaxIndicesRequired = maxIndicesRequired
            cachedBuildCamX = camX
            cachedBuildCamY = camY
            cachedBuildCamZ = camZ
        } else {
            cachedValid = false
        }

        return buildDrawIndirectObject(
            dynData = dynData,
            fastDyn = fastDyn,
            terrainTransform = terrainTransform,
            atlasW = atlasW,
            atlasH = atlasH,
            regionMode = regionMode,
            flatIdx = flatIdx,
            sectionCount = sectionCount,
            maxIndicesRequired = maxIndicesRequired,
            sortTranslucent = sortTranslucent,
        )
    }

    private data class TranslucentEmitResult(
        val sectionCount: Int,
        val maxIndices: Int,
        val anyFading: Boolean,
    )

    private fun emitTranslucentBatches(
        visibleRaw: Array<Any?>,
        fastDispatcher: FastSectionDispatcher,
        slice: MutableSectionSlice,
        regionMode: Boolean,
        compactMode: Boolean,
        sortTranslucent: Boolean,
        fadeMs: Long,
        nowMs: Long,
        initialSectionCount: Int,
        initialMaxIndices: Int,
    ): TranslucentEmitResult {
        val li = ChunkSectionLayer.TRANSLUCENT.ordinal
        var sectionCount = initialSectionCount
        var maxIndicesRequired = initialMaxIndices
        var anyFading = false

        var runBatchIdx = -1
        var runRx = Int.MIN_VALUE
        var runRy = Int.MIN_VALUE
        var runRz = Int.MIN_VALUE
        var runVis = Float.NaN
        var runVb: GpuBuffer? = null
        var runIb: GpuBuffer? = null
        var runIt: IndexType? = null
        var runCompact = false
        translucentTripletsCount = 0

        var lastFbGroupIdx = -1
        var lastFbVb: GpuBuffer? = null
        var lastFbIb: GpuBuffer? = null
        var lastFbIt: IndexType? = null

        val start = if (sortTranslucent) translucentCount - 1 else 0
        val end = if (sortTranslucent) -1 else translucentCount
        val step = if (sortTranslucent) -1 else 1

        var idxPtr = start
        while (idxPtr != end) {
            val si = translucentSectionIndices[idxPtr]
            idxPtr += step
            val section = visibleRaw[si] as? SectionRenderDispatcher.RenderSection ?: continue
            val mesh = section.getSectionMesh()
            val cachedMesh = mesh as? CachedSectionMesh
            val draw = cachedMesh?.`vulkanturbo$getDraw`(li) ?: mesh.getSectionDraw(LAYERS[li]) ?: continue

            val customIdx = draw.hasCustomIndexBuffer()
            if (!fastDispatcher.`vulkanturbo$fillSectionSlice`(li, mesh, customIdx, slice)) {
                continue
            }

            val vb = slice.vertexBuffer!!
            val ib: GpuBuffer?
            val it: IndexType?
            val firstIndex: Int
            if (!customIdx) {
                if (draw.indexCount() > maxIndicesRequired) {
                    maxIndicesRequired = draw.indexCount()
                }
                ib = null
                it = null
                firstIndex = 0
            } else {
                ib = slice.indexBuffer
                it = draw.indexType()
                firstIndex = (slice.indexOffset ushr (it.bytes ushr 1)).toInt()
            }

            val vis = if (fadeMs > 0L) section.getVisibility(nowMs, fadeMs) else 1.0f
            if (vis < 1.0f) anyFading = true

            val origin = section.renderOrigin
            val ox = origin.x
            val oy = origin.y
            val oz = origin.z
            val rx = ox and -64
            val ry = oy and -64
            val rz = oz and -64
            val stride = resolvedVertexStride(cachedMesh, li, compactMode)
            val sectionCompact = stride == TerrainRegionPipelines.COMPACT_VERTEX_STRIDE
            val baseVertex = (slice.vertexOffset / stride).toInt()
            val shifted = cachedMesh?.`vulkanturbo$isLayerShifted`(li) == true

            if (regionMode && shifted) {
                // Consecutive sections sharing (region, vb, ib, indexType, vis, compactVertex) coalesce into 1 RegionBatchPlan batch!
                if (runBatchIdx == -1 ||
                    rx != runRx || ry != runRy || rz != runRz ||
                    vb !== runVb || ib !== runIb || it != runIt ||
                    vis != runVis || sectionCompact != runCompact
                ) {
                    if (runBatchIdx != -1 && translucentTripletsCount > 0) {
                        regionPlan.appendBatchDraws(li, runBatchIdx, translucentTriplets, translucentTripletsCount)
                        translucentTripletsCount = 0
                    }
                    runBatchIdx = regionPlan.beginBatch(
                        layerOrdinal = li,
                        regionOriginX = rx,
                        regionOriginY = ry,
                        regionOriginZ = rz,
                        visibility = vis,
                        vertexBuffer = vb,
                        indexBuffer = ib,
                        indexType = it,
                        compactVertex = sectionCompact,
                    )
                    runRx = rx; runRy = ry; runRz = rz
                    runVb = vb; runIb = ib; runIt = it; runVis = vis; runCompact = sectionCompact
                }
                val tBase = translucentTripletsCount * 3
                if (tBase + 3 > translucentTriplets.size) {
                    translucentTriplets = translucentTriplets.copyOf(translucentTriplets.size * 2)
                }
                translucentTriplets[tBase] = firstIndex
                translucentTriplets[tBase + 1] = draw.indexCount()
                translucentTriplets[tBase + 2] = baseVertex
                translucentTripletsCount++
                continue
            }

            // Fallback path for non-region/unshifted translucent sections
            if (regionMode) {
                regionPlan.markFallback(li)
            }
            val secIdx = sectionCount
            if (secIdx >= sectionVis.size) {
                val newCap = sectionVis.size * 2
                sectionVis = sectionVis.copyOf(newCap)
                sectionCoords = sectionCoords.copyOf(newCap * 3)
            }
            val cBase = secIdx * 3
            sectionCoords[cBase] = if (shifted) rx else ox
            sectionCoords[cBase + 1] = if (shifted) ry else oy
            sectionCoords[cBase + 2] = if (shifted) rz else oz
            sectionVis[secIdx] = vis
            sectionCount++

            val gIdx = if (sortTranslucent) {
                if (lastFbGroupIdx != -1 && lastFbVb === vb && lastFbIb === ib && lastFbIt == it) {
                    lastFbGroupIdx
                } else {
                    val created = allocGroup(li, vb, ib, it)
                    lastFbGroupIdx = created
                    lastFbVb = vb; lastFbIb = ib; lastFbIt = it
                    created
                }
            } else {
                findOrCreateLayerGroup(li, vb, ib, it)
            }
            groups[gIdx].addDraw(draw.indexCount(), firstIndex, baseVertex, secIdx)
        }

        if (runBatchIdx != -1 && translucentTripletsCount > 0) {
            regionPlan.appendBatchDraws(li, runBatchIdx, translucentTriplets, translucentTripletsCount)
            translucentTripletsCount = 0
        }
        return TranslucentEmitResult(sectionCount, maxIndicesRequired, anyFading)
    }

    private fun resolvedVertexStride(cachedMesh: CachedSectionMesh?, layerIdx: Int, compactDefault: Boolean): Int {
        val stored = cachedMesh?.`vulkanturbo$getVertexStride`(layerIdx) ?: 0
        if (stored > 0) return stored
        return if (compactDefault) TerrainRegionPipelines.COMPACT_VERTEX_STRIDE else TerrainRegionPipelines.DEFAULT_VERTEX_STRIDE
    }

    private fun buildCachedResult(
        dynData: DynamicGpuData,
        fastDyn: FastDynamicGpuData,
        terrainTransform: Matrix4fc,
        atlasW: Int,
        atlasH: Int,
    ): ChunkSectionsToRender {
        val terrainTransformUbo = dynData.writeTerrainTransform(terrainTransform, atlasW, atlasH)
        if (cachedMaxIndicesRequired != 0) {
            RenderSystem.getSequentialBuffer(com.mojang.renderpearl.api.pipeline.PrimitiveTopology.QUADS)
                .requestIndexCount(cachedMaxIndicesRequired)
        }
        val flatCount = cachedFlatGroupCount
        val secCount = cachedSectionCount
        if (flatCount == 0 && secCount == 0) {
            val renderResult = ChunkSectionsToRender.DrawIndirect(
                terrainTransformUbo,
                emptyDrawGroupsPerLayer,
                cachedMaxIndicesRequired,
                terrainTransformUbo,
            )
            (renderResult as Any as RegionBackedDrawIndirect).`vulkanturbo$setRegionPlan`(regionPlan)
            return renderResult
        }
        return buildDrawIndirectObject(
            dynData = dynData,
            fastDyn = fastDyn,
            terrainTransform = terrainTransform,
            atlasW = atlasW,
            atlasH = atlasH,
            regionMode = cachedRegionMode,
            flatIdx = flatCount,
            sectionCount = secCount,
            maxIndicesRequired = cachedMaxIndicesRequired,
            sortTranslucent = cachedSortTranslucent,
            preBuiltTransformUbo = terrainTransformUbo,
        )
    }

    private fun buildDrawIndirectObject(
        dynData: DynamicGpuData,
        fastDyn: FastDynamicGpuData,
        terrainTransform: Matrix4fc,
        atlasW: Int,
        atlasH: Int,
        regionMode: Boolean,
        flatIdx: Int,
        sectionCount: Int,
        maxIndicesRequired: Int,
        sortTranslucent: Boolean,
        preBuiltTransformUbo: GpuBufferSlice? = null,
    ): ChunkSectionsToRender {
        if (flatIdx > 0) {
            fastDyn.`vulkanturbo$writeRawChunkCommands`(
                uploadGroupData,
                uploadGroupDrawCounts,
                uploadGroupOrder,
                flatIdx,
                uploadReverseDraws,
                uploadOutSlices,
            )
        }

        val drawGroupsPerLayer: EnumMap<ChunkSectionLayer, List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect>> =
            if (flatIdx == 0) {
                emptyDrawGroupsPerLayer
            } else {
                val map = EnumMap<ChunkSectionLayer, List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect>>(
                    ChunkSectionLayer::class.java,
                )
                var sliceCursor = 0
                for (li in 0 until LAYER_COUNT) {
                    val layer = LAYERS[li]
                    val cnt = layerGroupCounts[li]
                    if (cnt == 0) {
                        map[layer] = emptyList()
                        continue
                    }
                    val list = ArrayList<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect>(cnt)
                    val indices = layerGroupIndices[li]
                    // emitTranslucentBatches() already emits translucent groups/draws in back-to-front order.
                    for (i in 0 until cnt) {
                        val g = groups[indices[i]]
                        val cmdSlice = uploadOutSlices[sliceCursor]!!
                        uploadOutSlices[sliceCursor] = null
                        sliceCursor++
                        list.add(
                            ChunkSectionsToRender.GpuMultiDrawIndexedIndirect(
                                g.vertexSlice!!,
                                g.indexSlice,
                                g.indexType,
                                cmdSlice,
                                g.drawCount,
                            ),
                        )
                    }
                    map[layer] = list
                }
                map
            }

        val terrainTransformUbo = preBuiltTransformUbo ?: dynData.writeTerrainTransform(terrainTransform, atlasW, atlasH)
        val sectionInfosSlice = if (sectionCount > 0) {
            fastDyn.`vulkanturbo$writeRawChunkSections`(sectionCoords, sectionVis, sectionCount)
        } else {
            terrainTransformUbo
        }
        if (maxIndicesRequired != 0 && preBuiltTransformUbo == null) {
            RenderSystem.getSequentialBuffer(com.mojang.renderpearl.api.pipeline.PrimitiveTopology.QUADS)
                .requestIndexCount(maxIndicesRequired)
        }

        val renderResult = ChunkSectionsToRender.DrawIndirect(
            terrainTransformUbo,
            drawGroupsPerLayer,
            maxIndicesRequired,
            sectionInfosSlice,
        )
        if (regionMode) {
            (renderResult as Any as RegionBackedDrawIndirect).`vulkanturbo$setRegionPlan`(regionPlan)
        }
        return renderResult
    }

    private fun packRegionKey(rx: Int, ry: Int, rz: Int): Long {
        val px = ((rx shr 6).toLong() and 0x3FFFFFL) shl 42
        val py = ((ry shr 6).toLong() and 0xFFFFFL) shl 22
        val pz = (rz shr 6).toLong() and 0x3FFFFFL
        return px or py or pz
    }

    private fun findOrCreateRegion(
        rx: Int,
        ry: Int,
        rz: Int,
        camX: Double,
        camY: Double,
        camZ: Double,
    ): Int {
        val key = packRegionKey(rx, ry, rz)
        val mask = regMask
        var slot = ((key xor (key ushr 32)).toInt() * -1640531527)
        slot = (slot xor (slot ushr 16)) and mask
        val gen = regStampGen
        while (regStamps[slot] == gen) {
            if (regKeys[slot] == key) {
                return regIndices[slot]
            }
            slot = (slot + 1) and mask
        }

        val rIdx = activeRegionCount++
        if (rIdx >= regOriginX.size) {
            val newCap = regOriginX.size * 2
            regOriginX = regOriginX.copyOf(newCap)
            regOriginY = regOriginY.copyOf(newCap)
            regOriginZ = regOriginZ.copyOf(newCap)
            regDistSq = regDistSq.copyOf(newCap)
            regSortOrder = regSortOrder.copyOf(newCap)
            for (li in 0..1) {
                val oldSize = regLayerHead[li].size
                val grown = regLayerHead[li].copyOf(newCap)
                java.util.Arrays.fill(grown, oldSize, newCap, -1)
                regLayerHead[li] = grown
            }
        }
        regOriginX[rIdx] = rx
        regOriginY[rIdx] = ry
        regOriginZ[rIdx] = rz
        val dx = (rx + 32.0 - camX).toFloat()
        val dy = (ry + 32.0 - camY).toFloat()
        val dz = (rz + 32.0 - camZ).toFloat()
        regDistSq[rIdx] = dx * dx + dy * dy + dz * dz
        regSortOrder[rIdx] = rIdx
        regLayerHead[0][rIdx] = -1
        regLayerHead[1][rIdx] = -1

        regStamps[slot] = gen
        regKeys[slot] = key
        regIndices[slot] = rIdx

        if (activeRegionCount * 2 >= regKeys.size) {
            rehashRegions()
        }
        return rIdx
    }

    private fun rehashRegions() {
        val newSize = regKeys.size * 2
        val newMask = newSize - 1
        val newKeys = LongArray(newSize)
        val newIndices = IntArray(newSize)
        val newStamps = IntArray(newSize)
        val gen = regStampGen
        for (i in regKeys.indices) {
            if (regStamps[i] != gen) continue
            val key = regKeys[i]
            var slot = ((key xor (key ushr 32)).toInt() * -1640531527)
            slot = (slot xor (slot ushr 16)) and newMask
            while (newStamps[slot] == gen) {
                slot = (slot + 1) and newMask
            }
            newStamps[slot] = gen
            newKeys[slot] = key
            newIndices[slot] = regIndices[i]
        }
        regKeys = newKeys
        regIndices = newIndices
        regStamps = newStamps
        regMask = newMask
    }

    private fun allocMdSlot(vb: GpuBuffer, compactVertex: Boolean, next: Int): Int {
        val idx = mdSlotCount++
        if (idx >= mdSlots.size) {
            val oldSize = mdSlots.size
            mdSlots = Array(oldSize * 2) { i -> if (i < oldSize) mdSlots[i] else MultiDrawSlot() }
        }
        val s = mdSlots[idx]
        s.vertexBuffer = vb
        s.compactVertex = compactVertex
        s.nextSlot = next
        s.drawCount = 0
        return idx
    }

    private fun findOrCreateRegionLayerSlot(li: Int, rIdx: Int, vb: GpuBuffer, compactVertex: Boolean): Int {
        var cur = regLayerHead[li][rIdx]
        while (cur != -1) {
            val s = mdSlots[cur]
            if (s.vertexBuffer === vb && s.compactVertex == compactVertex) return cur
            cur = s.nextSlot
        }
        val created = allocMdSlot(vb, compactVertex, regLayerHead[li][rIdx])
        regLayerHead[li][rIdx] = created
        return created
    }

    private fun allocFadingSlot(
        rx: Int,
        ry: Int,
        rz: Int,
        vis: Float,
        li: Int,
        vb: GpuBuffer,
        compactVertex: Boolean,
    ): Int {
        val fIdx = fadingCount++
        if (fIdx >= fadingRx.size) {
            val newCap = fadingRx.size * 2
            fadingRx = fadingRx.copyOf(newCap)
            fadingRy = fadingRy.copyOf(newCap)
            fadingRz = fadingRz.copyOf(newCap)
            fadingVis = fadingVis.copyOf(newCap)
            fadingLayer = fadingLayer.copyOf(newCap)
            fadingSlot = fadingSlot.copyOf(newCap)
            fadingCompact = fadingCompact.copyOf(newCap)
        }
        val slot = allocMdSlot(vb, compactVertex, -1)
        fadingRx[fIdx] = rx
        fadingRy[fIdx] = ry
        fadingRz[fIdx] = rz
        fadingVis[fIdx] = vis
        fadingLayer[fIdx] = li
        fadingSlot[fIdx] = slot
        fadingCompact[fIdx] = compactVertex
        return slot
    }

    /**
     * Populates `RegionBatchPlan` for `SOLID` and `CUTOUT` layers while grouping `compactVertex == true`
     * (20B) batches before `compactVertex == false` (28B fallback) batches within each layer so mixed
     * 20B/28B regions never corrupt vertex strides or ping-pong `VkPipeline` binds.
     */
    private fun populateRegionPlan() {
        val rCount = activeRegionCount
        for (i in 1 until rCount) {
            val rIdx = regSortOrder[i]
            val dist = regDistSq[rIdx]
            var j = i - 1
            while (j >= 0 && regDistSq[regSortOrder[j]] > dist) {
                regSortOrder[j + 1] = regSortOrder[j]
                j--
            }
            regSortOrder[j + 1] = rIdx
        }

        val compactModes = booleanArrayOf(true, false)
        for (li in 0..1) {
            val headArr = regLayerHead[li]
            for (wantCompact in compactModes) {
                for (i in 0 until rCount) {
                    val rIdx = regSortOrder[i]
                    var slotIdx = headArr[rIdx]
                    if (slotIdx == -1) continue
                    val rx = regOriginX[rIdx]
                    val ry = regOriginY[rIdx]
                    val rz = regOriginZ[rIdx]
                    while (slotIdx != -1) {
                        val slot = mdSlots[slotIdx]
                        if (slot.drawCount > 0 && slot.compactVertex == wantCompact) {
                            val bIdx = regionPlan.beginBatch(
                                layerOrdinal = li,
                                regionOriginX = rx,
                                regionOriginY = ry,
                                regionOriginZ = rz,
                                visibility = 1.0f,
                                vertexBuffer = slot.vertexBuffer!!,
                                compactVertex = slot.compactVertex,
                            )
                            regionPlan.appendBatchDrawsPacked2(li, bIdx, slot.data, slot.drawCount)
                        }
                        slotIdx = slot.nextSlot
                    }
                }

                for (i in 0 until fadingCount) {
                    if (fadingLayer[i] != li || fadingCompact[i] != wantCompact) continue
                    val slot = mdSlots[fadingSlot[i]]
                    if (slot.drawCount > 0) {
                        val bIdx = regionPlan.beginBatch(
                            layerOrdinal = li,
                            regionOriginX = fadingRx[i],
                            regionOriginY = fadingRy[i],
                            regionOriginZ = fadingRz[i],
                            visibility = fadingVis[i],
                            vertexBuffer = slot.vertexBuffer!!,
                            compactVertex = fadingCompact[i],
                        )
                        regionPlan.appendBatchDrawsPacked2(li, bIdx, slot.data, slot.drawCount)
                    }
                }
            }
        }

        for (i in mdSlotCount until mdSlots.size) {
            val s = mdSlots[i]
            if (s.vertexBuffer == null) break
            s.vertexBuffer = null
        }
        regionPlan.releaseStaleBuffers()
    }

    private fun findOrCreateLayerGroup(li: Int, vb: GpuBuffer, ib: GpuBuffer?, it: IndexType?): Int {
        val cnt = layerGroupCounts[li]
        val indices = layerGroupIndices[li]
        for (i in 0 until cnt) {
            val gIdx = indices[i]
            val g = groups[gIdx]
            if (g.vertexBuffer === vb && g.indexBuffer === ib && g.indexType == it) {
                return gIdx
            }
        }
        return allocGroup(li, vb, ib, it)
    }

    private fun allocGroup(li: Int, vb: GpuBuffer, ib: GpuBuffer?, it: IndexType?): Int {
        val gIdx = activeGroupCount++
        if (gIdx >= groups.size) {
            val oldSize = groups.size
            groups = Array(oldSize * 2) { idx -> if (idx < oldSize) groups[idx] else DrawGroupScratch() }
        }
        groups[gIdx].reset(li, vb, ib, it)

        val lCnt = layerGroupCounts[li]
        var lArr = layerGroupIndices[li]
        if (lCnt >= lArr.size) {
            lArr = lArr.copyOf(lArr.size * 2)
            layerGroupIndices[li] = lArr
        }
        lArr[lCnt] = gIdx
        layerGroupCounts[li] = lCnt + 1
        return gIdx
    }
}
