package dev.vulkanturbo.core

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.vertex.VertexFormat
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.system.MemoryUtil
import java.nio.ByteOrder
import java.util.BitSet

class ChunkPipelineOptimizerTest {
    @Test
    fun `vertex format layout validators accept vanilla formats and reject mutated offsets`() {
        assertTrue(ChunkPipelineOptimizer.isVerifiedBlockHeaderLayout(DefaultVertexFormat.BLOCK))
        assertTrue(ChunkPipelineOptimizer.isVerifiedBlockHeaderLayout(TerrainRegionPipelines.EXTENDED_TERRAIN_40B))
        assertFalse(ChunkPipelineOptimizer.isVerifiedBlockHeaderLayout(TerrainRegionPipelines.COMPACT_TERRAIN_20B))
        assertTrue(ChunkPipelineOptimizer.isVerifiedEntity36Layout(DefaultVertexFormat.ENTITY))
        assertTrue(ChunkPipelineOptimizer.isVerifiedParticle28Layout(DefaultVertexFormat.PARTICLE))
        assertTrue(ChunkPipelineOptimizer.isVerifiedPosTexColor24Layout(DefaultVertexFormat.POSITION_TEX_COLOR))
        assertTrue(ChunkPipelineOptimizer.isVerifiedPosTexLightColor28Layout(DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR))
        assertTrue(ChunkPipelineOptimizer.isVerifiedPosColor16Layout(DefaultVertexFormat.POSITION_COLOR))

        // Swapped Color and UV0 order in a modded 28B format must be rejected!
        val mutatedBlock = VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGB32_FLOAT)
            .addAttribute("UV0", GpuFormat.RG32_FLOAT)
            .addAttribute("Color", GpuFormat.RGBA8_UNORM)
            .addAttribute("UV2", GpuFormat.RG16_SINT)
            .build()
        assertFalse(ChunkPipelineOptimizer.isVerifiedBlockHeaderLayout(mutatedBlock))
    }

    @Test
    fun `occlusion table and lighter flat cache invalidate immediately on clearAllCaches`() {
        val table = ChunkPipelineOptimizer.OcclusionTable()
        val cache = ChunkPipelineOptimizer.LighterFlatCache()
        val a = Any()
        val b = Any()
        var occCount = 0
        var lightCount = 0

        assertTrue(table.lookupOrCompute(a, b) { _, _ -> occCount++; true })
        assertTrue(table.lookupOrCompute(a, b) { _, _ -> occCount++; false })
        assertEquals(1, occCount)

        assertEquals(100, cache.getOrComputeColor(42L) { lightCount++; 100 })
        assertEquals(100, cache.getOrComputeColor(42L) { lightCount++; 200 })
        assertEquals(1, lightCount)

        ChunkPipelineOptimizer.clearAllCaches()

        assertFalse(table.lookupOrCompute(a, b) { _, _ -> occCount++; false })
        assertEquals(2, occCount)
        assertEquals(200, cache.getOrComputeColor(42L) { lightCount++; 200 })
        assertEquals(2, lightCount)
    }

    @Test
    fun `occlusion table caches asymmetric shape pairs without false hits`() {
        val table = ChunkPipelineOptimizer.OcclusionTable()
        val a = Any()
        val b = Any()
        var evalCount = 0

        val abFirst = table.lookupOrCompute(a, b) { _, _ ->
            evalCount++
            true
        }
        val abSecond = table.lookupOrCompute(a, b) { _, _ ->
            evalCount++
            false
        }
        val baFirst = table.lookupOrCompute(b, a) { _, _ ->
            evalCount++
            false
        }

        assertTrue(abFirst)
        assertTrue(abSecond)
        assertFalse(baFirst)
        assertEquals(2, evalCount)
    }

    @Test
    fun `occlusion table handles collisions via 2-way associativity and round-robin replacement`() {
        val table = ChunkPipelineOptimizer.OcclusionTable()
        val keys = Array(64) { Any() }
        for (i in keys.indices) {
            val expected = (i and 1) == 0
            val res = table.lookupOrCompute(keys[i], keys[keys.size - 1 - i]) { _, _ -> expected }
            assertEquals(expected, res)
        }
    }

    @Test
    fun `lighter flat cache caches color and shade brightness and invalidates in O(1) on clear`() {
        val cache = ChunkPipelineOptimizer.LighterFlatCache()
        var colorComputeCount = 0
        var shadeComputeCount = 0

        val c1 = cache.getOrComputeColor(12345L) {
            colorComputeCount++
            0x00F00080
        }
        val c2 = cache.getOrComputeColor(12345L) {
            colorComputeCount++
            0
        }
        assertEquals(0x00F00080, c1)
        assertEquals(0x00F00080, c2)
        assertEquals(1, colorComputeCount)

        val s1 = cache.getOrComputeShade(12345L) {
            shadeComputeCount++
            0.85f
        }
        val s2 = cache.getOrComputeShade(12345L) {
            shadeComputeCount++
            0.2f
        }
        assertEquals(0.85f, s1)
        assertEquals(0.85f, s2)
        assertEquals(1, shadeComputeCount)

        cache.clear()
        val cAfterClear = cache.getOrComputeColor(12345L) {
            colorComputeCount++
            0x00E000E0
        }
        val sAfterClear = cache.getOrComputeShade(12345L) {
            shadeComputeCount++
            0.5f
        }
        assertEquals(0x00E000E0, cAfterClear)
        assertEquals(0.5f, sAfterClear)
        assertEquals(2, colorComputeCount)
        assertEquals(2, shadeComputeCount)
    }

    @Test
    fun `argbToAbgr swaps red and blue while preserving alpha and green`() {
        val argb = 0xAA112233.toInt()
        val expectedAbgr = 0xAA332211.toInt()
        assertEquals(expectedAbgr, ChunkPipelineOptimizer.argbToAbgr(argb))
    }

    @Test
    fun `packLongLE packs two 32-bit words in little-endian layout`() {
        val lo = -0x76543210
        val hi = 0x12345678
        val packed = ChunkPipelineOptimizer.packLongLE(lo, hi)
        assertEquals(lo, packed.toInt())
        assertEquals(hi, (packed ushr 32).toInt())
    }

    @Test
    fun `storage out of bounds helper throws descriptive IllegalArgumentException`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            ChunkPipelineOptimizer.throwStorageOutOfBounds(0L, 4095L, 4096L)
        }
        assertTrue(ex.message!!.contains("4096"))
    }

    @Test
    fun `swapRemoveAt removes middle and tail entries cleanly without shifting`() {
        val list = ObjectArrayList<Any?>(arrayOf("a", "b", "c", "d"))
        val raw = list.elements()

        val removedMid = ChunkPipelineOptimizer.swapRemoveAt(raw, list, list.size, 1)
        assertEquals("b", removedMid)
        assertEquals(3, list.size)
        assertEquals("d", list[1])
        assertNull(raw[3])

        val removedTail = ChunkPipelineOptimizer.swapRemoveAt(raw, list, list.size, 2)
        assertEquals("c", removedTail)
        assertEquals(2, list.size)
        assertNull(raw[2])
    }

    @Test
    fun `ambient sync compile budget caps per-frame render-thread stalls`() {
        assertTrue(ChunkRenderPrepOptimizer.shouldAllowAmbientSyncCompile(usedSyncCount = 0, elapsedNs = 500_000L))
        assertFalse(ChunkRenderPrepOptimizer.shouldAllowAmbientSyncCompile(usedSyncCount = 1, elapsedNs = 500_000L))
        assertFalse(ChunkRenderPrepOptimizer.shouldAllowAmbientSyncCompile(usedSyncCount = 0, elapsedNs = 1_500_000L))
    }

    @Test
    fun `computeVisibilityBitmask handles sparse, full, and partitioned sections`() {
        val queue = IntArray(4096)

        val sparse = BitSet(4096)
        assertEquals((1L shl 36) - 1L, ChunkPipelineOptimizer.computeVisibilityBitmask(sparse, 4096, queue))

        val full = BitSet(4096).apply { set(0, 4096) }
        assertEquals(0L, ChunkPipelineOptimizer.computeVisibilityBitmask(full, 0, queue))

        val split = BitSet(4096)
        var opaqueCount = 0
        for (y in 7..8) {
            for (z in 0 until 16) {
                for (x in 0 until 16) {
                    split.set(x or (y shl 8) or (z shl 4))
                    opaqueCount++
                }
            }
        }
        val mask = ChunkPipelineOptimizer.computeVisibilityBitmask(split, 4096 - opaqueCount, queue)
        assertEquals(0L, mask and (1L shl (0 + 1 * 6)))
        assertEquals(0L, mask and (1L shl (1 + 0 * 6)))
        assertTrue((mask and (1L shl (0 + 2 * 6))) != 0L)
        assertTrue((mask and (1L shl (1 + 3 * 6))) != 0L)
    }

    @Test
    fun `sortDescendingFloatKeys sorts positive negative zero and tied floats stably`() {
        val keys = floatArrayOf(-10.5f, 3.25f, 0.0f, 3.25f, -0.0f, 100.0f, -0.5f)
        val order = ChunkPipelineOptimizer.sortDescendingFloatKeys(keys, keys.size)
        assertArrayEquals(intArrayOf(5, 1, 3, 2, 4, 6, 0), order)
    }

    @Test
    fun `writeSortedQuadIndicesToPointer emits exact SHORT and INT quad index sequences`() {
        val startIndices = intArrayOf(2, 0, 5)
        val shortBuf = MemoryUtil.memAlloc(startIndices.size * 6 * 2).order(ByteOrder.nativeOrder())
        val intBuf = MemoryUtil.memAlloc(startIndices.size * 6 * 4).order(ByteOrder.nativeOrder())
        try {
            ChunkPipelineOptimizer.writeSortedQuadIndicesToPointer(
                startIndices,
                MemoryUtil.memAddress(shortBuf),
                isShort = true,
            )
            ChunkPipelineOptimizer.writeSortedQuadIndicesToPointer(
                startIndices,
                MemoryUtil.memAddress(intBuf),
                isShort = false,
            )

            val expected = intArrayOf(
                8, 9, 10, 10, 11, 8,
                0, 1, 2, 2, 3, 0,
                20, 21, 22, 22, 23, 20,
            )
            for (i in expected.indices) {
                assertEquals(expected[i], shortBuf.getShort(i * 2).toInt() and 0xFFFF)
                assertEquals(expected[i], intBuf.getInt(i * 4))
            }
        } finally {
            MemoryUtil.memFree(shortBuf)
            MemoryUtil.memFree(intBuf)
        }
    }

    @Test
    fun `writeFluidFace emits front and reversed back-face BLOCK vertices`() {
        val buf = MemoryUtil.memAlloc(224).order(ByteOrder.nativeOrder())
        try {
            val addr = MemoryUtil.memAddress(buf)
            val argb = 0xCC112233.toInt()
            val expectedAbgr = 0xCC332211.toInt()
            val light = 0x00F00080

            ChunkPipelineOptimizer.writeFluidFace(
                addr,
                0f, 0.8f, 0f, 0.1f, 0.2f,
                0f, 0.8f, 1f, 0.1f, 0.9f,
                1f, 0.8f, 1f, 0.9f, 0.9f,
                1f, 0.8f, 0f, 0.9f, 0.2f,
                argb,
                light,
                addBackFace = true,
            )

            assertEquals(0f, buf.getFloat(28))
            assertEquals(0.8f, buf.getFloat(32))
            assertEquals(1f, buf.getFloat(36))
            assertEquals(expectedAbgr, buf.getInt(40))
            assertEquals(0.1f, buf.getFloat(44))
            assertEquals(0.9f, buf.getFloat(48))
            assertEquals(light, buf.getInt(52))

            assertEquals(1f, buf.getFloat(140))
            assertEquals(0.8f, buf.getFloat(144))
            assertEquals(0f, buf.getFloat(148))
            assertEquals(expectedAbgr, buf.getInt(152))
            assertEquals(0.9f, buf.getFloat(156))
            assertEquals(0.2f, buf.getFloat(160))
            assertEquals(light, buf.getInt(164))
        } finally {
            MemoryUtil.memFree(buf)
        }
    }
}
