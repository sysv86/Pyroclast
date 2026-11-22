package dev.vulkanturbo.core

import com.mojang.blaze3d.vertex.CompactVectorArray
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.QuadInstance
import com.mojang.blaze3d.vertex.VertexSorting
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.pipeline.IndexType
import com.mojang.renderpearl.api.vertex.VertexFormat
import dev.vulkanturbo.VulkanTurbo
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.block.BlockAndTintGetter
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.chunk.CompiledSectionMesh
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher
import net.minecraft.client.renderer.chunk.VisibilitySet
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.resources.model.geometry.BakedQuad
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.LightCoordsUtil
import net.minecraft.util.Mth
import net.minecraft.util.Util
import net.minecraft.world.level.BlockAndLightGetter
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.BooleanOp
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.joml.Vector3d
import org.lwjgl.system.MemoryUtil
import java.lang.Float.floatToRawIntBits
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.BitSet
import java.util.concurrent.atomic.AtomicInteger

/** Bridge to `SectionTaskDynamicQueue` internals for allocation-free task polling. */
interface SectionTaskQueueAccess {
    fun `vulkanturbo$getRecompileQuota`(): Int
    fun `vulkanturbo$setRecompileQuota`(quota: Int)
    fun `vulkanturbo$isTaskCancelled`(task: SectionRenderDispatcher.RenderSection.SectionTask): Boolean
}

/**
 * Clean-room chunk, culling, fluid/block vertex-emission, translucency-sorting, and block-storage hot-path helpers.
 */
object ChunkPipelineOptimizer {
    private const val TABLE_BUCKETS = 128
    private const val TABLE_SLOTS = TABLE_BUCKETS * 2
    private const val BLOCK_VERTEX_STRIDE = 28L
    private const val CHUNK_VISIBILITY_THRESHOLD = 0.3f
    private const val MAX_RECOMPILE_QUOTA = 2
    private const val HIGH_END_MAX_RECOMPILE_QUOTA = 6

    private fun activeMaxRecompileQuota(): Int =
        if (VulkanTurbo.isHighEndGpu) HIGH_END_MAX_RECOMPILE_QUOTA else MAX_RECOMPILE_QUOTA

    private val NATIVE_LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN

    /** Global cache invalidation epoch bumped on world change and resource pack / model reload (`F3+T`). */
    private val cacheInvalidateEpoch = AtomicInteger(1)

    /**
     * Precomputed 1,352 boundary voxel indices in exact `VisGraph.INDEX_OF_EDGES` traversal order
     * (`x << 0 | y << 8 | z << 4`), plus precomputed 6-bit boundary face masks (`1 shl Direction.ordinal`)
     * for all 4,096 voxels in a 16x16x16 section.
     */
    internal val VIS_EDGE_INDICES = IntArray(1352)
    internal val VIS_EDGE_FACE_MASK = IntArray(4096)

    init {
        var edgeCursor = 0
        for (x in 0 until 16) {
            for (y in 0 until 16) {
                for (z in 0 until 16) {
                    val idx = x or (y shl 8) or (z shl 4)
                    var mask = 0
                    if (y == 0) mask = mask or (1 shl 0)      // DOWN (ordinal 0)
                    else if (y == 15) mask = mask or (1 shl 1) // UP (ordinal 1)
                    if (z == 0) mask = mask or (1 shl 2)      // NORTH (ordinal 2)
                    else if (z == 15) mask = mask or (1 shl 3) // SOUTH (ordinal 3)
                    if (x == 0) mask = mask or (1 shl 4)      // WEST (ordinal 4)
                    else if (x == 15) mask = mask or (1 shl 5) // EAST (ordinal 5)
                    VIS_EDGE_FACE_MASK[idx] = mask
                    if (mask != 0) {
                        VIS_EDGE_INDICES[edgeCursor++] = idx
                    }
                }
            }
        }
    }

    /**
     * Invalidates all thread-local `OcclusionTable` and `LighterFlatCache` instances across all
     * worker and render threads on resource reload (`F3+T`) or world reset.
     */
    @JvmStatic
    fun clearAllCaches() {
        cacheInvalidateEpoch.incrementAndGet()
    }

    @JvmStatic
    fun currentCacheEpoch(): Int = cacheInvalidateEpoch.get()

    /**
     * Verifies that [format] has the exact attribute names, byte offsets, and `GpuFormat`s expected
     * by `VulkanTurbo`'s off-heap 28B block header writer (`Position` @ 0 `RGB32_FLOAT`,
     * `Color` @ 12 `RGBA8_UNORM`, `UV0` @ 16 `RG32_FLOAT`, `UV2` @ 24 `RG16_SINT`).
     * Prevents memory corruption if another mod injects or reorders attributes in `DefaultVertexFormat.BLOCK`.
     */
    @JvmStatic
    fun isVerifiedBlockHeaderLayout(format: VertexFormat?): Boolean {
        if (format == null || format.vertexSize < 28) return false
        val pos = format.getElement("Position") ?: return false
        val col = format.getElement("Color") ?: return false
        val uv0 = format.getElement("UV0") ?: return false
        val uv2 = format.getElement("UV2") ?: return false
        return pos.offset() == 0 && pos.format() == GpuFormat.RGB32_FLOAT &&
            col.offset() == 12 && col.format() == GpuFormat.RGBA8_UNORM &&
            uv0.offset() == 16 && uv0.format() == GpuFormat.RG32_FLOAT &&
            uv2.offset() == 24 && uv2.format() == GpuFormat.RG16_SINT
    }

    @JvmStatic
    fun isVerifiedExtendedBlockLayout(format: VertexFormat): Boolean {
        if (format.vertexSize != 40 || !isVerifiedBlockHeaderLayout(format)) return false
        val normal = format.getElement("Normal") ?: return false
        val tangent = format.getElement("Tangent") ?: return false
        val midUv = format.getElement("MidUV") ?: return false
        return normal.offset() == 28 && normal.format() == GpuFormat.RGBA8_SNORM &&
            tangent.offset() == 32 && tangent.format() == GpuFormat.RGBA8_SNORM &&
            midUv.offset() == 36 && midUv.format() == GpuFormat.RG16_FLOAT
    }

    /**
     * Verifies that [format] matches `DefaultVertexFormat.ENTITY` (36 bytes:
     * `Position` @ 0, `Color` @ 12, `UV0` @ 16, `UV1` @ 24, `UV2` @ 28, `Normal` @ 32).
     */
    @JvmStatic
    fun isVerifiedEntity36Layout(format: VertexFormat?): Boolean {
        if (format == null || format.vertexSize != 36) return false
        val pos = format.getElement("Position") ?: return false
        val col = format.getElement("Color") ?: return false
        val uv0 = format.getElement("UV0") ?: return false
        val uv1 = format.getElement("UV1") ?: return false
        val uv2 = format.getElement("UV2") ?: return false
        val nrm = format.getElement("Normal") ?: return false
        return pos.offset() == 0 && pos.format() == GpuFormat.RGB32_FLOAT &&
            col.offset() == 12 && col.format() == GpuFormat.RGBA8_UNORM &&
            uv0.offset() == 16 && uv0.format() == GpuFormat.RG32_FLOAT &&
            uv1.offset() == 24 && uv1.format() == GpuFormat.RG16_SINT &&
            uv2.offset() == 28 && uv2.format() == GpuFormat.RG16_SINT &&
            nrm.offset() == 32 && nrm.format() == GpuFormat.RGBA8_SNORM
    }

    /**
     * Verifies that [format] matches `DefaultVertexFormat.PARTICLE` (28 bytes:
     * `Position` @ 0, `UV0` @ 12, `Color` @ 20, `UV2` @ 24).
     */
    @JvmStatic
    fun isVerifiedParticle28Layout(format: VertexFormat?): Boolean {
        if (format == null || format.vertexSize != 28) return false
        val pos = format.getElement("Position") ?: return false
        val uv0 = format.getElement("UV0") ?: return false
        val col = format.getElement("Color") ?: return false
        val uv2 = format.getElement("UV2") ?: return false
        return pos.offset() == 0 && pos.format() == GpuFormat.RGB32_FLOAT &&
            uv0.offset() == 12 && uv0.format() == GpuFormat.RG32_FLOAT &&
            col.offset() == 20 && col.format() == GpuFormat.RGBA8_UNORM &&
            uv2.offset() == 24 && uv2.format() == GpuFormat.RG16_SINT
    }

    /**
     * Verifies that [format] matches `DefaultVertexFormat.POSITION_TEX_COLOR` (24 bytes:
     * `Position` @ 0, `UV0` @ 12, `Color` @ 20).
     */
    @JvmStatic
    fun isVerifiedPosTexColor24Layout(format: VertexFormat?): Boolean {
        if (format == null || format.vertexSize != 24) return false
        val pos = format.getElement("Position") ?: return false
        val uv0 = format.getElement("UV0") ?: return false
        val col = format.getElement("Color") ?: return false
        return pos.offset() == 0 && pos.format() == GpuFormat.RGB32_FLOAT &&
            uv0.offset() == 12 && uv0.format() == GpuFormat.RG32_FLOAT &&
            col.offset() == 20 && col.format() == GpuFormat.RGBA8_UNORM
    }

    /**
     * Verifies that [format] matches `DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR` (28 bytes:
     * `Position` @ 0, `UV0` @ 12, `UV2` @ 20, `Color` @ 24).
     */
    @JvmStatic
    fun isVerifiedPosTexLightColor28Layout(format: VertexFormat?): Boolean {
        if (format == null || format.vertexSize != 28) return false
        val pos = format.getElement("Position") ?: return false
        val uv0 = format.getElement("UV0") ?: return false
        val uv2 = format.getElement("UV2") ?: return false
        val col = format.getElement("Color") ?: return false
        return pos.offset() == 0 && pos.format() == GpuFormat.RGB32_FLOAT &&
            uv0.offset() == 12 && uv0.format() == GpuFormat.RG32_FLOAT &&
            uv2.offset() == 20 && uv2.format() == GpuFormat.RG16_SINT &&
            col.offset() == 24 && col.format() == GpuFormat.RGBA8_UNORM
    }

    /**
     * Verifies that [format] matches `DefaultVertexFormat.POSITION_COLOR` (16 bytes:
     * `Position` @ 0, `Color` @ 12).
     */
    @JvmStatic
    fun isVerifiedPosColor16Layout(format: VertexFormat?): Boolean {
        if (format == null || format.vertexSize != 16) return false
        val pos = format.getElement("Position") ?: return false
        val col = format.getElement("Color") ?: return false
        return pos.offset() == 0 && pos.format() == GpuFormat.RGB32_FLOAT &&
            col.offset() == 12 && col.format() == GpuFormat.RGBA8_UNORM
    }

    /**
     * Thread-local 256-entry 2-way set-associative cache keyed by object identity of two shapes.
     * Eliminates `Block$ShapePairKey` heap allocations and `Object2ByteLinkedOpenHashMap` node
     * shuffling in `Block.shouldRenderFace`, and automatically clears stale `VoxelShape` references
     * when `clearAllCaches()` increments [cacheInvalidateEpoch].
     */
    internal class OcclusionTable {
        val first = arrayOfNulls<Any>(TABLE_SLOTS)
        val second = arrayOfNulls<Any>(TABLE_SLOTS)
        val result = ByteArray(TABLE_SLOTS) { -1 }
        val rr = ByteArray(TABLE_BUCKETS)
        var observedEpoch = cacheInvalidateEpoch.get()

        fun clear() {
            first.fill(null)
            second.fill(null)
            result.fill(-1)
            rr.fill(0)
            observedEpoch = cacheInvalidateEpoch.get()
        }

        inline fun <T : Any> lookupOrCompute(a: T, b: T, compute: (T, T) -> Boolean): Boolean {
            val curEpoch = currentCacheEpoch()
            if (observedEpoch != curEpoch) {
                clear()
            }
            val h = (System.identityHashCode(a) * -1640531527) xor System.identityHashCode(b)
            val set = (h xor (h ushr 16)) and (TABLE_BUCKETS - 1)
            val s0 = set shl 1
            val s1 = s0 or 1
            if (first[s0] === a && second[s0] === b) {
                return result[s0] != 0.toByte()
            }
            if (first[s1] === a && second[s1] === b) {
                return result[s1] != 0.toByte()
            }
            val value = compute(a, b)
            val way = rr[set].toInt() and 1
            rr[set] = (way xor 1).toByte()
            val slot = s0 + way
            first[slot] = a
            second[slot] = b
            result[slot] = if (value) 1 else 0
            return value
        }
    }

    /**
     * Flat 2-way set-associative L1-resident cache for `BlockModelLighter.Cache` with O(1)
     * generation-stamp invalidation and strict per-thread isolation.
     *
     * Replaces fastutil's `Long2IntLinkedOpenHashMap` and `Long2FloatLinkedOpenHashMap` to avoid
     * doubly-linked-list node unlinking (`removeFirstInt`/`removeFirstFloat`), hash-chain shifting,
     * and 8 KB of array zeroing on `Cache.disable()` at the end of every chunk section compile.
     */
    class LighterFlatCache {
        private val ownerThreadId: Long = Thread.currentThread().threadId()
        private val colorKeys = LongArray(TABLE_SLOTS)
        private val colorVals = IntArray(TABLE_SLOTS)
        private val colorStamps = IntArray(TABLE_SLOTS)
        private val colorRr = ByteArray(TABLE_BUCKETS)

        private val shadeKeys = LongArray(TABLE_SLOTS)
        private val shadeVals = FloatArray(TABLE_SLOTS)
        private val shadeStamps = IntArray(TABLE_SLOTS)
        private val shadeRr = ByteArray(TABLE_BUCKETS)

        private var stampGen = 1
        private var observedEpoch = cacheInvalidateEpoch.get()

        private fun resolveThreadSafeInstance(): LighterFlatCache =
            if (Thread.currentThread().threadId() == ownerThreadId) this else fallbackThreadLocalFlatCache.get()

        fun clear() {
            val target = resolveThreadSafeInstance()
            target.clearLocal()
        }

        private fun clearLocal() {
            observedEpoch = cacheInvalidateEpoch.get()
            if (++stampGen == 0) {
                colorStamps.fill(0)
                shadeStamps.fill(0)
                stampGen = 1
            }
        }

        private fun checkEpoch() {
            val curEpoch = cacheInvalidateEpoch.get()
            if (observedEpoch != curEpoch) {
                clearLocal()
            }
        }

        internal inline fun getOrComputeColor(key: Long, compute: () -> Int): Int {
            val inst = resolveThreadSafeInstance()
            inst.checkEpoch()
            val h = ((key xor (key ushr 32)).toInt() * -1640531527)
            val set = (h xor (h ushr 16)) and (TABLE_BUCKETS - 1)
            val s0 = set shl 1
            val s1 = s0 or 1
            val gen = inst.stampGen
            if (inst.colorStamps[s0] == gen && inst.colorKeys[s0] == key) {
                return inst.colorVals[s0]
            }
            if (inst.colorStamps[s1] == gen && inst.colorKeys[s1] == key) {
                return inst.colorVals[s1]
            }
            val value = compute()
            val way = inst.colorRr[set].toInt() and 1
            inst.colorRr[set] = (way xor 1).toByte()
            val slot = s0 + way
            inst.colorStamps[slot] = gen
            inst.colorKeys[slot] = key
            inst.colorVals[slot] = value
            return value
        }

        internal inline fun getOrComputeShade(key: Long, compute: () -> Float): Float {
            val inst = resolveThreadSafeInstance()
            inst.checkEpoch()
            val h = ((key xor (key ushr 32)).toInt() * -1640531527)
            val set = (h xor (h ushr 16)) and (TABLE_BUCKETS - 1)
            val s0 = set shl 1
            val s1 = s0 or 1
            val gen = inst.stampGen
            if (inst.shadeStamps[s0] == gen && inst.shadeKeys[s0] == key) {
                return inst.shadeVals[s0]
            }
            if (inst.shadeStamps[s1] == gen && inst.shadeKeys[s1] == key) {
                return inst.shadeVals[s1]
            }
            val value = compute()
            val way = inst.shadeRr[set].toInt() and 1
            inst.shadeRr[set] = (way xor 1).toByte()
            val slot = s0 + way
            inst.shadeStamps[slot] = gen
            inst.shadeKeys[slot] = key
            inst.shadeVals[slot] = value
            return value
        }

        fun getPackedBrightness(level: BlockAndLightGetter, pos: BlockPos): Int =
            getOrComputeColor(pos.asLong()) {
                LightCoordsUtil.BrightnessGetter.DEFAULT.packedBrightness(level, pos)
            }

        fun getShadeBrightness(state: BlockState, level: BlockAndTintGetter, pos: BlockPos): Float =
            getOrComputeShade(pos.asLong()) {
                state.getShadeBrightness(level, pos)
            }
    }

    private class FluidScratchRing {
        val ring = Array(8) { BlockPos.MutableBlockPos() }
        var cursor = 0

        fun nextOffset(pos: BlockPos, direction: Direction): BlockPos.MutableBlockPos {
            val slot = ring[cursor and 7]
            cursor++
            return slot.setWithOffset(pos, direction)
        }
    }

    private class OcclusionRayScratch {
        val vecA = Vector3d()
        val vecB = Vector3d()
        var nextSlot = 0
        val pos = BlockPos.MutableBlockPos()
    }

    private val occlusionTables: ThreadLocal<OcclusionTable> = ThreadLocal.withInitial(::OcclusionTable)
    private val fallbackThreadLocalFlatCache: ThreadLocal<LighterFlatCache> =
        ThreadLocal.withInitial(::LighterFlatCache)
    private val aoScratchPos: ThreadLocal<BlockPos.MutableBlockPos> =
        ThreadLocal.withInitial(BlockPos::MutableBlockPos)
    private val fluidScratchRing: ThreadLocal<FluidScratchRing> =
        ThreadLocal.withInitial(::FluidScratchRing)
    private val occlusionRayScratch: ThreadLocal<OcclusionRayScratch> =
        ThreadLocal.withInitial(::OcclusionRayScratch)
    private val visQueueScratch: ThreadLocal<IntArray> = ThreadLocal.withInitial { IntArray(4096) }
    private val sortPackedScratch: ThreadLocal<LongArray> = ThreadLocal.withInitial { LongArray(1024) }

    /**
     * Evaluates `Shapes.joinIsNotEmpty(faceShape, neighborShape, BooleanOp.ONLY_FIRST)` using the
     * allocation-free per-thread 2-way identity cache.
     */
    @JvmStatic
    fun shouldRenderFaceCached(faceShape: VoxelShape, neighborShape: VoxelShape): Boolean {
        return occlusionTables.get().lookupOrCompute(faceShape, neighborShape) { a, b ->
            Shapes.joinIsNotEmpty(a, b, BooleanOp.ONLY_FIRST)
        }
    }

    /**
     * Thread-safe per-worker scratch `MutableBlockPos` for `BlockModelLighter.prepareQuadAmbientOcclusion`.
     */
    @JvmStatic
    fun reuseAoFacePos(pos: BlockPos, direction: Direction): BlockPos =
        aoScratchPos.get().setWithOffset(pos, direction)

    /**
     * 8-slot thread-local `MutableBlockPos` ring for `FluidRenderer`, allowing chained offset calls
     * like `pos.relative(NORTH).relative(EAST)` followed by `cornerPos.above()` without aliasing.
     */
    @JvmStatic
    fun reuseFluidOffsetPos(pos: BlockPos, direction: Direction): BlockPos =
        fluidScratchRing.get().nextOffset(pos, direction)

    @JvmStatic
    fun reuseOcclusionVec3dPair(x: Double, y: Double, z: Double): Vector3d {
        val scratch = occlusionRayScratch.get()
        val slot = scratch.nextSlot
        scratch.nextSlot = slot xor 1
        return if (slot == 0) scratch.vecA.set(x, y, z) else scratch.vecB.set(x, y, z)
    }

    @JvmStatic
    fun reuseOcclusionVec3dA(x: Double, y: Double, z: Double): Vector3d =
        occlusionRayScratch.get().vecA.set(x, y, z)

    @JvmStatic
    fun reuseOcclusionVec3dB(x: Double, y: Double, z: Double): Vector3d =
        occlusionRayScratch.get().vecB.set(x, y, z)

    @JvmStatic
    fun reuseOcclusionBlockPos(x: Double, y: Double, z: Double): BlockPos =
        occlusionRayScratch.get().pos.set(Mth.floor(x), Mth.floor(y), Mth.floor(z))

    /**
     * Zero-allocation BFS flood-fill for `VisGraph.resolve()`.
     */
    internal fun computeVisibilityBitmask(bitSet: BitSet, emptyCount: Int, queue: IntArray): Long {
        if (4096 - emptyCount < 256) {
            return (1L shl 36) - 1L
        }
        if (emptyCount == 0) {
            return 0L
        }
        var pairMask = 0L
        val edgeIndices = VIS_EDGE_INDICES
        val faceMasks = VIS_EDGE_FACE_MASK
        for (i in edgeIndices.indices) {
            val startIdx = edgeIndices[i]
            if (bitSet.get(startIdx)) continue

            var faces = 0
            var head = 0
            var tail = 0
            bitSet.set(startIdx, true)
            queue[tail++] = startIdx

            while (head < tail) {
                val idx = queue[head++]
                faces = faces or faceMasks[idx]

                if ((idx and 0xF00) != 0) {
                    val n = idx - 256
                    if (!bitSet.get(n)) {
                        bitSet.set(n, true)
                        queue[tail++] = n
                    }
                }
                if ((idx and 0xF00) != 0xF00) {
                    val n = idx + 256
                    if (!bitSet.get(n)) {
                        bitSet.set(n, true)
                        queue[tail++] = n
                    }
                }
                if ((idx and 0x0F0) != 0) {
                    val n = idx - 16
                    if (!bitSet.get(n)) {
                        bitSet.set(n, true)
                        queue[tail++] = n
                    }
                }
                if ((idx and 0x0F0) != 0x0F0) {
                    val n = idx + 16
                    if (!bitSet.get(n)) {
                        bitSet.set(n, true)
                        queue[tail++] = n
                    }
                }
                if ((idx and 0x00F) != 0) {
                    val n = idx - 1
                    if (!bitSet.get(n)) {
                        bitSet.set(n, true)
                        queue[tail++] = n
                    }
                }
                if ((idx and 0x00F) != 0x00F) {
                    val n = idx + 1
                    if (!bitSet.get(n)) {
                        bitSet.set(n, true)
                        queue[tail++] = n
                    }
                }
            }

            var m1 = faces
            while (m1 != 0) {
                val d1 = Integer.numberOfTrailingZeros(m1)
                m1 = m1 and (m1 - 1)
                var m2 = faces
                while (m2 != 0) {
                    val d2 = Integer.numberOfTrailingZeros(m2)
                    m2 = m2 and (m2 - 1)
                    pairMask = pairMask or (1L shl (d1 + d2 * 6))
                }
            }
        }
        return pairMask
    }

    @JvmStatic
    fun resolveVisGraph(bitSet: BitSet, emptyCount: Int): VisibilitySet {
        val visibilitySet = VisibilitySet()
        if (4096 - emptyCount < 256) {
            visibilitySet.setAll(true)
            return visibilitySet
        }
        if (emptyCount == 0) {
            visibilitySet.setAll(false)
            return visibilitySet
        }
        val pairMask = computeVisibilityBitmask(bitSet, emptyCount, visQueueScratch.get())
        if (pairMask != 0L) {
            val dirs = Direction.entries
            for (d1 in 0 until 6) {
                val dir1 = dirs[d1]
                for (d2 in d1 until 6) {
                    if ((pairMask and (1L shl (d1 + d2 * 6))) != 0L) {
                        visibilitySet.set(dir1, dirs[d2], true)
                    }
                }
            }
        }
        return visibilitySet
    }

    internal fun floatToDescendingSortableInt(value: Float): Int {
        val bits = floatToRawIntBits(if (value == 0.0f) 0.0f else value)
        val asc = bits xor ((bits shr 31) and 0x7FFFFFFF)
        return asc.inv()
    }

    private fun ensureSortScratch(size: Int): LongArray {
        var arr = sortPackedScratch.get()
        if (arr.size < size) {
            val cap = Integer.highestOneBit(size - 1) shl 1
            arr = LongArray(cap)
            sortPackedScratch.set(arr)
        }
        return arr
    }

    internal fun sortDescendingFloatKeys(keys: FloatArray, size: Int): IntArray {
        val out = IntArray(size)
        if (size <= 1) {
            return out
        }
        val packed = ensureSortScratch(size)
        for (i in 0 until size) {
            val descKey = floatToDescendingSortableInt(keys[i])
            packed[i] = (descKey.toLong() shl 32) or (i.toLong() and 0xFFFFFFFFL)
        }
        Arrays.sort(packed, 0, size)
        for (i in 0 until size) {
            out[i] = packed[i].toInt()
        }
        return out
    }

    @JvmStatic
    fun sortCentroidsByDistance(centroids: CompactVectorArray, ox: Float, oy: Float, oz: Float): IntArray {
        val size = centroids.size()
        val out = IntArray(size)
        if (size <= 1) {
            return out
        }
        val packed = ensureSortScratch(size)
        for (i in 0 until size) {
            val dx = centroids.getX(i) - ox
            val dy = centroids.getY(i) - oy
            val dz = centroids.getZ(i) - oz
            val distSq = Math.fma(dx, dx, Math.fma(dy, dy, dz * dz))
            val descKey = floatToDescendingSortableInt(distSq)
            packed[i] = (descKey.toLong() shl 32) or (i.toLong() and 0xFFFFFFFFL)
        }
        Arrays.sort(packed, 0, size)
        for (i in 0 until size) {
            out[i] = packed[i].toInt()
        }
        return out
    }

    @JvmStatic
    fun sortCentroidsByOrthographicZ(centroids: CompactVectorArray): IntArray {
        val size = centroids.size()
        val out = IntArray(size)
        if (size <= 1) {
            return out
        }
        val packed = ensureSortScratch(size)
        for (i in 0 until size) {
            val descKey = floatToDescendingSortableInt(-centroids.getZ(i))
            packed[i] = (descKey.toLong() shl 32) or (i.toLong() and 0xFFFFFFFFL)
        }
        Arrays.sort(packed, 0, size)
        for (i in 0 until size) {
            out[i] = packed[i].toInt()
        }
        return out
    }

    @JvmStatic
    fun createFastDistanceSorting(ox: Float, oy: Float, oz: Float): VertexSorting =
        VertexSorting { centroids -> sortCentroidsByDistance(centroids, ox, oy, oz) }

    @JvmStatic
    fun decodeQuadCentroidsFast(
        vertexBuffer: ByteBuffer,
        vertexCount: Int,
        positionOffsetInVertex: Int,
        vertexStride: Int,
        output: CompactVectorArray,
        outputIndex: Int,
    ): Boolean {
        if (!NATIVE_LITTLE_ENDIAN || !vertexBuffer.isDirect()) {
            return false
        }
        val quadCount = vertexCount / 4
        val quadStride = vertexStride.toLong() * 4L
        val v2Offset = vertexStride.toLong() * 2L
        var ptr = MemoryUtil.memAddress(vertexBuffer) + positionOffsetInVertex.toLong()
        for (i in 0 until quadCount) {
            val x0 = MemoryUtil.memGetFloat(ptr)
            val y0 = MemoryUtil.memGetFloat(ptr + 4L)
            val z0 = MemoryUtil.memGetFloat(ptr + 8L)
            val p2 = ptr + v2Offset
            val x1 = MemoryUtil.memGetFloat(p2)
            val y1 = MemoryUtil.memGetFloat(p2 + 4L)
            val z1 = MemoryUtil.memGetFloat(p2 + 8L)
            output.set(outputIndex + i, (x0 + x1) * 0.5f, (y0 + y1) * 0.5f, (z0 + z1) * 0.5f)
            ptr += quadStride
        }
        return true
    }

    @JvmStatic
    fun writeSortedQuadIndicesToPointer(startIndices: IntArray, pointer: Long, isShort: Boolean) {
        if (!NATIVE_LITTLE_ENDIAN) {
            var ptr = pointer
            if (isShort) {
                for (i in startIndices.indices) {
                    val v0 = startIndices[i] shl 2
                    MemoryUtil.memPutShort(ptr, v0.toShort())
                    MemoryUtil.memPutShort(ptr + 2L, (v0 + 1).toShort())
                    MemoryUtil.memPutShort(ptr + 4L, (v0 + 2).toShort())
                    MemoryUtil.memPutShort(ptr + 6L, (v0 + 2).toShort())
                    MemoryUtil.memPutShort(ptr + 8L, (v0 + 3).toShort())
                    MemoryUtil.memPutShort(ptr + 10L, v0.toShort())
                    ptr += 12L
                }
            } else {
                for (i in startIndices.indices) {
                    val v0 = startIndices[i] shl 2
                    MemoryUtil.memPutInt(ptr, v0)
                    MemoryUtil.memPutInt(ptr + 4L, v0 + 1)
                    MemoryUtil.memPutInt(ptr + 8L, v0 + 2)
                    MemoryUtil.memPutInt(ptr + 12L, v0 + 2)
                    MemoryUtil.memPutInt(ptr + 16L, v0 + 3)
                    MemoryUtil.memPutInt(ptr + 20L, v0)
                    ptr += 24L
                }
            }
            return
        }

        var ptr = pointer
        if (isShort) {
            for (i in startIndices.indices) {
                val v0 = startIndices[i] shl 2
                val s0 = (v0 and 0xFFFF).toLong()
                val s1 = ((v0 + 1) and 0xFFFF).toLong()
                val s2 = ((v0 + 2) and 0xFFFF).toLong()
                val s3 = (v0 + 3) and 0xFFFF
                val w0 = s0 or (s1 shl 16) or (s2 shl 32) or (s2 shl 48)
                val w1 = s3 or ((v0 and 0xFFFF) shl 16)
                MemoryUtil.memPutLong(ptr, w0)
                MemoryUtil.memPutInt(ptr + 8L, w1)
                ptr += 12L
            }
        } else {
            for (i in startIndices.indices) {
                val v0 = startIndices[i] shl 2
                MemoryUtil.memPutLong(ptr, packLongLE(v0, v0 + 1))
                MemoryUtil.memPutLong(ptr + 8L, packLongLE(v0 + 2, v0 + 2))
                MemoryUtil.memPutLong(ptr + 16L, packLongLE(v0 + 3, v0))
                ptr += 24L
            }
        }
    }

    @JvmStatic
    fun writeSortedQuadIndicesToBuffer(startIndices: IntArray, target: ByteBuffer, indexType: IndexType) {
        val isShort = indexType == IndexType.SHORT
        val totalBytes = startIndices.size * 6 * indexType.bytes
        if (NATIVE_LITTLE_ENDIAN && target.isDirect()) {
            val ptr = MemoryUtil.memAddress(target)
            writeSortedQuadIndicesToPointer(startIndices, ptr, isShort)
            target.position(target.position() + totalBytes)
            return
        }
        if (isShort) {
            for (i in startIndices.indices) {
                val v0 = startIndices[i] shl 2
                target.putShort(v0.toShort())
                target.putShort((v0 + 1).toShort())
                target.putShort((v0 + 2).toShort())
                target.putShort((v0 + 2).toShort())
                target.putShort((v0 + 3).toShort())
                target.putShort(v0.toShort())
            }
        } else {
            for (i in startIndices.indices) {
                val v0 = startIndices[i] shl 2
                target.putInt(v0)
                target.putInt(v0 + 1)
                target.putInt(v0 + 2)
                target.putInt(v0 + 2)
                target.putInt(v0 + 3)
                target.putInt(v0)
            }
        }
    }

    internal fun argbToAbgr(argb: Int): Int =
        (argb and -0xFF0100) or ((argb and 0x00FF0000) ushr 16) or ((argb and 0x000000FF) shl 16)

    internal fun packLongLE(lo: Int, hi: Int): Long =
        (lo.toLong() and 0xFFFFFFFFL) or (hi.toLong() shl 32)

    /**
     * Emits 4 (or 8 when [addBackFace] is true) `DefaultVertexFormat.BLOCK` (28B/vertex) fluid
     * vertices into a single native reservation (`112` or `224` bytes), reusing the packed 64-bit
     * words for the reversed back-face quad (`v0, v3, v2, v1`).
     */
    @JvmStatic
    fun writeFluidFace(
        basePtr: Long,
        x0: Float, y0: Float, z0: Float, u0: Float, v0: Float,
        x1: Float, y1: Float, z1: Float, u1: Float, v1: Float,
        x2: Float, y2: Float, z2: Float, u2: Float, v2: Float,
        x3: Float, y3: Float, z3: Float, u3: Float, v3: Float,
        color: Int,
        lightCoords: Int,
        addBackFace: Boolean,
    ) {
        writeFluidFaceExtended(
            basePtr,
            28,
            x0, y0, z0, u0, v0,
            x1, y1, z1, u1, v1,
            x2, y2, z2, u2, v2,
            x3, y3, z3, u3, v3,
            color,
            lightCoords,
            addBackFace,
        )
    }

    /**
     * Generalized fluid quad writer supporting both 28-byte standard stride and extended
     * `TurboShaders` vertex strides (`stride >= 28`).
     */
    @JvmStatic
    fun writeFluidFaceExtended(
        basePtr: Long,
        vertexStride: Int,
        x0: Float, y0: Float, z0: Float, u0: Float, v0: Float,
        x1: Float, y1: Float, z1: Float, u1: Float, v1: Float,
        x2: Float, y2: Float, z2: Float, u2: Float, v2: Float,
        x3: Float, y3: Float, z3: Float, u3: Float, v3: Float,
        color: Int,
        lightCoords: Int,
        addBackFace: Boolean,
    ) {
        val stride = vertexStride.toLong()
        val abgr = argbToAbgr(color)

        val v0w0 = packLongLE(floatToRawIntBits(x0), floatToRawIntBits(y0))
        val v0w1 = packLongLE(floatToRawIntBits(z0), abgr)
        val v0uv = packLongLE(floatToRawIntBits(u0), floatToRawIntBits(v0))

        val v1w0 = packLongLE(floatToRawIntBits(x1), floatToRawIntBits(y1))
        val v1w1 = packLongLE(floatToRawIntBits(z1), abgr)
        val v1uv = packLongLE(floatToRawIntBits(u1), floatToRawIntBits(v1))

        val v2w0 = packLongLE(floatToRawIntBits(x2), floatToRawIntBits(y2))
        val v2w1 = packLongLE(floatToRawIntBits(z2), abgr)
        val v2uv = packLongLE(floatToRawIntBits(u2), floatToRawIntBits(v2))

        val v3w0 = packLongLE(floatToRawIntBits(x3), floatToRawIntBits(y3))
        val v3w1 = packLongLE(floatToRawIntBits(z3), abgr)
        val v3uv = packLongLE(floatToRawIntBits(u3), floatToRawIntBits(v3))

        // Front face: v0, v1, v2, v3
        MemoryUtil.memPutLong(basePtr, v0w0)
        MemoryUtil.memPutLong(basePtr + 8L, v0w1)
        MemoryUtil.memPutLong(basePtr + 16L, v0uv)
        MemoryUtil.memPutInt(basePtr + 24L, lightCoords)

        val p1 = basePtr + stride
        MemoryUtil.memPutLong(p1, v1w0)
        MemoryUtil.memPutLong(p1 + 8L, v1w1)
        MemoryUtil.memPutLong(p1 + 16L, v1uv)
        MemoryUtil.memPutInt(p1 + 24L, lightCoords)

        val p2 = basePtr + stride * 2L
        MemoryUtil.memPutLong(p2, v2w0)
        MemoryUtil.memPutLong(p2 + 8L, v2w1)
        MemoryUtil.memPutLong(p2 + 16L, v2uv)
        MemoryUtil.memPutInt(p2 + 24L, lightCoords)

        val p3 = basePtr + stride * 3L
        MemoryUtil.memPutLong(p3, v3w0)
        MemoryUtil.memPutLong(p3 + 8L, v3w1)
        MemoryUtil.memPutLong(p3 + 16L, v3uv)
        MemoryUtil.memPutInt(p3 + 24L, lightCoords)

        if (addBackFace) {
            // Back face: v0, v3, v2, v1
            val p4 = basePtr + stride * 4L
            MemoryUtil.memPutLong(p4, v0w0)
            MemoryUtil.memPutLong(p4 + 8L, v0w1)
            MemoryUtil.memPutLong(p4 + 16L, v0uv)
            MemoryUtil.memPutInt(p4 + 24L, lightCoords)

            val p5 = basePtr + stride * 5L
            MemoryUtil.memPutLong(p5, v3w0)
            MemoryUtil.memPutLong(p5 + 8L, v3w1)
            MemoryUtil.memPutLong(p5 + 16L, v3uv)
            MemoryUtil.memPutInt(p5 + 24L, lightCoords)

            val p6 = basePtr + stride * 6L
            MemoryUtil.memPutLong(p6, v2w0)
            MemoryUtil.memPutLong(p6 + 8L, v2w1)
            MemoryUtil.memPutLong(p6 + 16L, v2uv)
            MemoryUtil.memPutInt(p6 + 24L, lightCoords)

            val p7 = basePtr + stride * 7L
            MemoryUtil.memPutLong(p7, v1w0)
            MemoryUtil.memPutLong(p7 + 8L, v1w1)
            MemoryUtil.memPutLong(p7 + 16L, v1uv)
            MemoryUtil.memPutInt(p7 + 24L, lightCoords)
        }
    }

    /**
     * Emits all 4 vertices of a `DefaultVertexFormat.BLOCK` quad into a pre-reserved 112-byte
     * native buffer region (`4 * 28` bytes, little-endian) using unrolled 64-bit native stores.
     */
    @JvmStatic
    fun writeBlockBakedQuad(
        basePtr: Long,
        x: Float,
        y: Float,
        z: Float,
        quad: BakedQuad,
        instance: QuadInstance,
    ) {
        writeBlockBakedQuadExtended(basePtr, 28, x, y, z, quad, instance)
    }

    /**
     * Emits the 28-byte standard block vertex header for all 4 vertices of a `BakedQuad` using
     * custom [vertexStride] (`>= 28`) so `TurboShaders` can append extended PBR attributes at
     * offsets `28 ..< vertexStride`.
     */
    @JvmStatic
    fun writeBlockBakedQuadExtended(
        basePtr: Long,
        vertexStride: Int,
        x: Float,
        y: Float,
        z: Float,
        quad: BakedQuad,
        instance: QuadInstance,
    ) {
        val stride = vertexStride.toLong()
        val emission = quad.materialInfo().lightEmission()

        // Vertex 0
        val p0 = quad.position(0)
        val uv0 = java.lang.Long.rotateLeft(quad.packedUV(0), 32)
        val c0 = argbToAbgr(instance.getColor(0))
        val l0 = instance.getLightCoordsWithEmission(0, emission)
        MemoryUtil.memPutLong(basePtr, packLongLE(floatToRawIntBits(p0.x() + x), floatToRawIntBits(p0.y() + y)))
        MemoryUtil.memPutLong(basePtr + 8L, packLongLE(floatToRawIntBits(p0.z() + z), c0))
        MemoryUtil.memPutLong(basePtr + 16L, uv0)
        MemoryUtil.memPutInt(basePtr + 24L, l0)

        // Vertex 1
        val ptr1 = basePtr + stride
        val p1 = quad.position(1)
        val uv1 = java.lang.Long.rotateLeft(quad.packedUV(1), 32)
        val c1 = argbToAbgr(instance.getColor(1))
        val l1 = instance.getLightCoordsWithEmission(1, emission)
        MemoryUtil.memPutLong(ptr1, packLongLE(floatToRawIntBits(p1.x() + x), floatToRawIntBits(p1.y() + y)))
        MemoryUtil.memPutLong(ptr1 + 8L, packLongLE(floatToRawIntBits(p1.z() + z), c1))
        MemoryUtil.memPutLong(ptr1 + 16L, uv1)
        MemoryUtil.memPutInt(ptr1 + 24L, l1)

        // Vertex 2
        val ptr2 = basePtr + stride * 2L
        val p2 = quad.position(2)
        val uv2 = java.lang.Long.rotateLeft(quad.packedUV(2), 32)
        val c2 = argbToAbgr(instance.getColor(2))
        val l2 = instance.getLightCoordsWithEmission(2, emission)
        MemoryUtil.memPutLong(ptr2, packLongLE(floatToRawIntBits(p2.x() + x), floatToRawIntBits(p2.y() + y)))
        MemoryUtil.memPutLong(ptr2 + 8L, packLongLE(floatToRawIntBits(p2.z() + z), c2))
        MemoryUtil.memPutLong(ptr2 + 16L, uv2)
        MemoryUtil.memPutInt(ptr2 + 24L, l2)

        // Vertex 3
        val ptr3 = basePtr + stride * 3L
        val p3 = quad.position(3)
        val uv3 = java.lang.Long.rotateLeft(quad.packedUV(3), 32)
        val c3 = argbToAbgr(instance.getColor(3))
        val l3 = instance.getLightCoordsWithEmission(3, emission)
        MemoryUtil.memPutLong(ptr3, packLongLE(floatToRawIntBits(p3.x() + x), floatToRawIntBits(p3.y() + y)))
        MemoryUtil.memPutLong(ptr3 + 8L, packLongLE(floatToRawIntBits(p3.z() + z), c3))
        MemoryUtil.memPutLong(ptr3 + 16L, uv3)
        MemoryUtil.memPutInt(ptr3 + 24L, l3)
    }

    @JvmStatic
    fun extractVisibleBlockEntitiesFast(
        level: ClientLevel,
        dispatcher: BlockEntityRenderDispatcher,
        visibleSections: ObjectArrayList<SectionRenderDispatcher.RenderSection>,
        camX: Double,
        camY: Double,
        camZ: Double,
        fadeInMs: Long,
        partialTick: Float,
        outStates: MutableList<BlockEntityRenderState>,
    ) {
        val count = visibleSections.size
        if (count == 0) return

        @Suppress("UNCHECKED_CAST")
        val elements = visibleSections.elements() as Array<Any?>
        val destructionMap = level.destructionProgress()
        val hasDestruction = !destructionMap.isEmpty()
        var poseStack: PoseStack? = null
        var nowMs = 0L

        for (i in 0 until count) {
            val section = elements[i] as? SectionRenderDispatcher.RenderSection ?: continue
            val mesh = section.getSectionMesh()
            if (mesh === CompiledSectionMesh.EMPTY || mesh === CompiledSectionMesh.UNCOMPILED) {
                continue
            }
            if (mesh is CachedSectionMesh && !mesh.`vulkanturbo$hasBlockEntities`()) {
                continue
            }
            val blockEntities = mesh.renderableBlockEntities
            if (blockEntities.isEmpty()) {
                continue
            }
            if (fadeInMs > 0L) {
                if (nowMs == 0L) {
                    nowMs = Util.getMillis()
                }
                if (section.getVisibility(nowMs, fadeInMs) < CHUNK_VISIBILITY_THRESHOLD) {
                    continue
                }
            }
            for (j in 0 until blockEntities.size) {
                val blockEntity = blockEntities[j]
                var overlay: ModelFeatureRenderer.CrumblingOverlay? = null
                if (hasDestruction) {
                    val pos = blockEntity.blockPos
                    val set = destructionMap.get(pos.asLong())
                    if (set != null && !set.isEmpty()) {
                        val ps = poseStack ?: PoseStack().also { poseStack = it }
                        ps.pushPose()
                        ps.translate(pos.x.toDouble() - camX, pos.y.toDouble() - camY, pos.z.toDouble() - camZ)
                        overlay = ModelFeatureRenderer.CrumblingOverlay(set.last().progress, ps.last())
                        ps.popPose()
                    }
                }
                val state = dispatcher.tryExtractRenderState<BlockEntity, BlockEntityRenderState>(
                    blockEntity,
                    partialTick,
                    overlay,
                    false,
                )
                if (state != null) {
                    outStates.add(state)
                }
            }
        }
    }

    @JvmStatic
    fun throwStorageOutOfBounds(min: Long, max: Long, value: Long) {
        throw IllegalArgumentException(
            "The value $value is not in the specified inclusive range of $min to $max",
        )
    }

    @JvmStatic
    fun isFastQueuePollEnabled(): Boolean = VulkanTurbo.fastChunkPrepEnabled

    @JvmStatic
    fun pollNearestSectionTask(
        queue: SectionTaskQueueAccess,
        list: ObjectArrayList<SectionRenderDispatcher.RenderSection.SectionTask>,
        cameraPos: Vec3,
    ): SectionRenderDispatcher.RenderSection.SectionTask? {
        val originalSize = list.size
        if (originalSize == 0) {
            queue.`vulkanturbo$setRecompileQuota`(activeMaxRecompileQuota())
            return null
        }

        @Suppress("UNCHECKED_CAST")
        val raw = list.elements() as Array<Any?>
        var len = originalSize
        var i = 0
        var nonRecompileIdx = -1
        var recompileIdx = -1
        var minNonRecompileDist = Double.MAX_VALUE
        var minRecompileDist = Double.MAX_VALUE
        val camX = cameraPos.x
        val camY = cameraPos.y
        val camZ = cameraPos.z

        while (i < len) {
            val task = raw[i] as SectionRenderDispatcher.RenderSection.SectionTask
            if (queue.`vulkanturbo$isTaskCancelled`(task)) {
                len--
                raw[i] = raw[len]
                raw[len] = null
                continue
            }
            val origin = task.getRenderOrigin()
            val dx = origin.x + 0.5 - camX
            val dy = origin.y + 0.5 - camY
            val dz = origin.z + 0.5 - camZ
            val dist = dx * dx + dy * dy + dz * dz
            if (task.isRecompile()) {
                if (dist < minRecompileDist) {
                    minRecompileDist = dist
                    recompileIdx = i
                }
            } else if (dist < minNonRecompileDist) {
                minNonRecompileDist = dist
                nonRecompileIdx = i
            }
            i++
        }

        val hasRecompile = recompileIdx >= 0
        val hasNonRecompile = nonRecompileIdx >= 0
        val chosenIdx: Int
        if (hasRecompile && (!hasNonRecompile || (queue.`vulkanturbo$getRecompileQuota`() > 0 && minRecompileDist < minNonRecompileDist))) {
            queue.`vulkanturbo$setRecompileQuota`(queue.`vulkanturbo$getRecompileQuota`() - 1)
            chosenIdx = recompileIdx
        } else {
            queue.`vulkanturbo$setRecompileQuota`(activeMaxRecompileQuota())
            chosenIdx = nonRecompileIdx
        }

        if (chosenIdx < 0) {
            if (len != originalSize) {
                list.size(len)
            }
            return null
        }

        @Suppress("UNCHECKED_CAST")
        return swapRemoveAt(raw, list as ObjectArrayList<Any?>, len, chosenIdx)
            as SectionRenderDispatcher.RenderSection.SectionTask
    }

    internal fun swapRemoveAt(
        raw: Array<Any?>,
        list: ObjectArrayList<Any?>,
        len: Int,
        index: Int,
    ): Any? {
        val removed = raw[index]
        val newLen = len - 1
        if (index != newLen) {
            raw[index] = raw[newLen]
        }
        raw[newLen] = null
        list.size(newLen)
        return removed
    }
}
