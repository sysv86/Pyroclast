package dev.vulkanturbo.core

import dev.vulkanturbo.VulkanTurbo
import org.lwjgl.system.MemoryUtil
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerrainRegionRendererTest {
    @Test
    fun `classifies cardinal CCW quads into expected directional buckets`() {
        // +Y (UP)
        assertEquals(
            TerrainBucketBuilder.BUCKET_UP,
            TerrainBucketBuilder.classifyQuadDirection(
                0f, 1f, 0f,
                0f, 1f, 1f,
                1f, 1f, 1f,
                1f, 1f, 0f,
            ),
        )
        // -Y (DOWN)
        assertEquals(
            TerrainBucketBuilder.BUCKET_DOWN,
            TerrainBucketBuilder.classifyQuadDirection(
                0f, 0f, 1f,
                0f, 0f, 0f,
                1f, 0f, 0f,
                1f, 0f, 1f,
            ),
        )
        // -Z (NORTH)
        assertEquals(
            TerrainBucketBuilder.BUCKET_NORTH,
            TerrainBucketBuilder.classifyQuadDirection(
                1f, 1f, 0f,
                1f, 0f, 0f,
                0f, 0f, 0f,
                0f, 1f, 0f,
            ),
        )
        // +Z (SOUTH)
        assertEquals(
            TerrainBucketBuilder.BUCKET_SOUTH,
            TerrainBucketBuilder.classifyQuadDirection(
                0f, 1f, 1f,
                0f, 0f, 1f,
                1f, 0f, 1f,
                1f, 1f, 1f,
            ),
        )
        // -X (WEST)
        assertEquals(
            TerrainBucketBuilder.BUCKET_WEST,
            TerrainBucketBuilder.classifyQuadDirection(
                0f, 1f, 0f,
                0f, 0f, 0f,
                0f, 0f, 1f,
                0f, 1f, 1f,
            ),
        )
        // +X (EAST)
        assertEquals(
            TerrainBucketBuilder.BUCKET_EAST,
            TerrainBucketBuilder.classifyQuadDirection(
                1f, 1f, 1f,
                1f, 0f, 1f,
                1f, 0f, 0f,
                1f, 1f, 0f,
            ),
        )
    }

    @Test
    fun `routes tilted and degenerate quads to unassigned bucket`() {
        // 45-degree sloped quad
        assertEquals(
            TerrainBucketBuilder.BUCKET_UNASSIGNED,
            TerrainBucketBuilder.classifyQuadDirection(
                0f, 0f, 0f,
                0f, 1f, 1f,
                1f, 1f, 1f,
                1f, 0f, 0f,
            ),
        )
        // Collapsed degenerate quad
        assertEquals(
            TerrainBucketBuilder.BUCKET_UNASSIGNED,
            TerrainBucketBuilder.classifyQuadDirection(
                0f, 0f, 0f,
                0f, 0f, 0f,
                0f, 0f, 0f,
                0f, 0f, 0f,
            ),
        )
    }

    @Test
    fun `region origin plus baked section offset reconstructs negative and positive coordinates`() {
        for (secCoord in -12..12) {
            val blockOrigin = secCoord shl 4
            val regionOrigin = blockOrigin and -64
            val sectionOffset = (secCoord and 3) * 16
            assertEquals(blockOrigin, regionOrigin + sectionOffset)
        }
    }

    @Test
    fun `universal vertex alignment lcm divides both compact 20B and standard 28B strides cleanly`() {
        val align = TerrainRegionPipelines.lcm(
            TerrainRegionPipelines.COMPACT_VERTEX_STRIDE,
            TerrainRegionPipelines.DEFAULT_VERTEX_STRIDE,
        )
        assertEquals(140, align)
        assertEquals(0, align % 20)
        assertEquals(0, align % 28)

        val pbr40 = TerrainRegionPipelines.lcm(align, TerrainRegionPipelines.EXTENDED_VERTEX_STRIDE)
        assertEquals(280, pbr40)
        assertEquals(0, pbr40 % 20)
        assertEquals(0, pbr40 % 28)
        assertEquals(0, pbr40 % 40)
    }

    @Test
    fun `compact 20B vertex format preserves quantized coordinates, exact UV0, color, and lightmap UV2`() {
        assertEquals(20, TerrainRegionPipelines.COMPACT_TERRAIN_20B.getVertexSize())

        val src = MemoryUtil.memAlloc(28)
        val dst = MemoryUtil.memAlloc(20)
        try {
            val sAddr = MemoryUtil.memAddress(src)
            val dAddr = MemoryUtil.memAddress(dst)

            val localX = 3.0625f
            val localY = 12.5f
            val localZ = -1.25f
            val regOffX = 48.0f
            val regOffY = 16.0f
            val regOffZ = 32.0f
            val colorAbgr = 0xFF80C040.toInt()
            val u = 0.3125f
            val v = 0.78125f
            val uv2Block: Short = 224
            val uv2Sky: Short = 240

            MemoryUtil.memPutFloat(sAddr, localX)
            MemoryUtil.memPutFloat(sAddr + 4L, localY)
            MemoryUtil.memPutFloat(sAddr + 8L, localZ)
            MemoryUtil.memPutInt(sAddr + 12L, colorAbgr)
            MemoryUtil.memPutFloat(sAddr + 16L, u)
            MemoryUtil.memPutFloat(sAddr + 20L, v)
            MemoryUtil.memPutShort(sAddr + 24L, uv2Block)
            MemoryUtil.memPutShort(sAddr + 26L, uv2Sky)

            TerrainBucketBuilder.writeCompactVertex(sAddr, dAddr, regOffX, regOffY, regOffZ)

            val packedXY = MemoryUtil.memGetInt(dAddr)
            val packedZW = MemoryUtil.memGetInt(dAddr + 4L)
            val outColor = MemoryUtil.memGetInt(dAddr + 8L)
            val outU = MemoryUtil.memGetFloat(dAddr + 12L)
            val outV = MemoryUtil.memGetFloat(dAddr + 16L)

            val qx = packedXY and 0xFFFF
            val qy = (packedXY ushr 16) and 0xFFFF
            val qz = packedZW and 0xFFFF
            val packedUv2 = (packedZW ushr 16) and 0xFFFF

            assertTrue(abs((localX + regOffX) - TerrainBucketBuilder.unpackCompactCoord(qx)) < 1e-4f)
            assertTrue(abs((localY + regOffY) - TerrainBucketBuilder.unpackCompactCoord(qy)) < 1e-4f)
            assertTrue(abs((localZ + regOffZ) - TerrainBucketBuilder.unpackCompactCoord(qz)) < 1e-4f)
            assertEquals(uv2Block.toInt(), packedUv2 and 0xFF)
            assertEquals(uv2Sky.toInt(), (packedUv2 ushr 8) and 0xFF)
            assertEquals(colorAbgr, outColor)
            assertEquals(u, outU)
            assertEquals(v, outV)
        } finally {
            MemoryUtil.memFree(src)
            MemoryUtil.memFree(dst)
        }
    }

    @Test
    fun `writeIndirectCommands emits 20-byte VkDrawIndexedIndirectCommand structs for VK_INDIRECT fallback`() {
        val multiDrawInts = MemoryUtil.memAllocInt(6)
        val outIndirect = MemoryUtil.memAlloc(40)
        try {
            multiDrawInts.put(0, 12)   // firstIndex
            multiDrawInts.put(1, 360)  // indexCount
            multiDrawInts.put(2, 1024) // vertexOffset
            multiDrawInts.put(3, 0)
            multiDrawInts.put(4, 96)
            multiDrawInts.put(5, 2048)

            val outAddr = MemoryUtil.memAddress(outIndirect)
            RegionBatchPlan.writeIndirectCommands(multiDrawInts, 0, 2, outAddr)

            assertEquals(360, MemoryUtil.memGetInt(outAddr))
            assertEquals(1, MemoryUtil.memGetInt(outAddr + 4L))
            assertEquals(12, MemoryUtil.memGetInt(outAddr + 8L))
            assertEquals(1024, MemoryUtil.memGetInt(outAddr + 12L))
            assertEquals(0, MemoryUtil.memGetInt(outAddr + 16L))

            assertEquals(96, MemoryUtil.memGetInt(outAddr + 20L))
            assertEquals(1, MemoryUtil.memGetInt(outAddr + 24L))
            assertEquals(0, MemoryUtil.memGetInt(outAddr + 28L))
            assertEquals(2048, MemoryUtil.memGetInt(outAddr + 32L))
            assertEquals(0, MemoryUtil.memGetInt(outAddr + 36L))
        } finally {
            MemoryUtil.memFree(multiDrawInts)
            MemoryUtil.memFree(outIndirect)
        }
    }

    @Test
    fun `retained plan cache open-bounds check invalidates immediately on boundary touch`() {
        assertTrue(ChunkRenderPrepOptimizer.isPointInsideOpenBounds(5.5, 64.2, -3.1, 5.0, 6.0, 64.0, 65.0, -4.0, -3.0))
        assertFalse(ChunkRenderPrepOptimizer.isPointInsideOpenBounds(5.0, 64.2, -3.1, 5.0, 6.0, 64.0, 65.0, -4.0, -3.0))
        assertFalse(ChunkRenderPrepOptimizer.isPointInsideOpenBounds(6.0, 64.2, -3.1, 5.0, 6.0, 64.0, 65.0, -4.0, -3.0))
    }

    @Test
    fun `status chat message applies red-orange gradient brand and exposes public Pyroclast and TEPHRA codename`() {
        assertEquals("vulkanturbo", VulkanTurbo.MOD_ID)
        assertEquals("VulkanTurbo", VulkanTurbo.INTERNAL_NAME)
        assertEquals("Pyroclast", VulkanTurbo.PUBLIC_NAME)
        assertEquals("TEPHRA", VulkanTurbo.CODENAME)

        val openglMsg = VulkanTurbo.buildStatusMessage(
            isVulkan = false,
            backendName = "OpenGL",
            deviceName = "Mesa",
            configEnabled = true,
        )
        assertEquals("VulkanTurbo: OpenGL rendering is NOT SUPPORTED — mod disabled.", openglMsg.string)
        val firstColor = openglMsg.siblings.first().style.color!!.value
        val brandEndColor = openglMsg.siblings[10].style.color!!.value
        val bodyColor = openglMsg.siblings.last().style.color!!.value
        assertEquals(0xFF2A00, firstColor)
        assertEquals(0xFF8C00, brandEndColor)
        assertEquals(0xFFFFFF, bodyColor)

        val vulkanMsg = VulkanTurbo.buildStatusMessage(
            isVulkan = true,
            backendName = "Vulkan",
            deviceName = "RTX 3050",
            configEnabled = true,
        )
        assertEquals("VulkanTurbo: Vulkan backend active (RTX 3050) — optimizations enabled.", vulkanMsg.string)
    }
}
