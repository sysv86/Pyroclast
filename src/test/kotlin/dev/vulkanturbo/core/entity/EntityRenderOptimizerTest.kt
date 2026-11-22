package dev.vulkanturbo.core

import org.lwjgl.system.MemoryUtil
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class EntityRenderOptimizerTest {
    @Test
    fun `packEntityNormal and NormalCache packNormal match signed 7-bit little-endian layout`() {
        assertEquals(0x00007F, EntityRenderOptimizer.packEntityNormal(1.0f, 0.0f, 0.0f))
        assertEquals(0x7F8100, EntityRenderOptimizer.packEntityNormal(0.0f, -1.0f, 1.0f))
        assertEquals(0x7F817F, EntityRenderOptimizer.packEntityNormal(2.5f, -3.0f, 1.2f))

        assertEquals(0x00007F, NormalCache.packNormal(1.0f, 0.0f, 0.0f))
        assertEquals(0x7F8100, NormalCache.packNormal(0.0f, -1.0f, 1.0f))
        assertEquals(0x7F817F, NormalCache.packNormal(2.5f, -3.0f, 1.2f))

        val buf = MemoryUtil.memAlloc(4).order(ByteOrder.nativeOrder())
        try {
            val addr = MemoryUtil.memAddress(buf)
            NormalCache.write(addr, 0.0f, -1.0f, 1.0f)
            assertEquals(0x00.toByte(), buf.get(0))
            assertEquals(0x81.toByte(), buf.get(1))
            assertEquals(0x7F.toByte(), buf.get(2))
        } finally {
            MemoryUtil.memFree(buf)
        }
    }

    @Test
    fun `writeParticleQuadAtAddress writes 112-byte DefaultVertexFormat PARTICLE layout`() {
        val buf = MemoryUtil.memAlloc(112).order(ByteOrder.nativeOrder())
        try {
            val addr = MemoryUtil.memAddress(buf)
            val argb = 0xAA112233.toInt()
            val expectedAbgr = 0xAA332211.toInt()
            val light = 0x00F000F0
            EntityRenderOptimizer.writeParticleQuadAtAddress(
                addr,
                1f, 2f, 3f,
                4f, 5f, 6f,
                7f, 8f, 9f,
                10f, 11f, 12f,
                0.25f, 0.75f, 0.125f, 0.875f,
                argb,
                light,
            )

            assertEquals(1f, buf.getFloat(0))
            assertEquals(2f, buf.getFloat(4))
            assertEquals(3f, buf.getFloat(8))
            assertEquals(0.75f, buf.getFloat(12))
            assertEquals(0.875f, buf.getFloat(16))
            assertEquals(expectedAbgr, buf.getInt(20))
            assertEquals(light, buf.getInt(24))

            assertEquals(7f, buf.getFloat(56))
            assertEquals(8f, buf.getFloat(60))
            assertEquals(9f, buf.getFloat(64))
            assertEquals(0.25f, buf.getFloat(68))
            assertEquals(0.125f, buf.getFloat(72))
            assertEquals(expectedAbgr, buf.getInt(76))
            assertEquals(light, buf.getInt(80))
        } finally {
            MemoryUtil.memFree(buf)
        }
    }

    @Test
    fun `writeGlyphQuadAtAddress writes both 24B GUI and 28B world glyph vertex layouts`() {
        val buf = MemoryUtil.memAlloc(112).order(ByteOrder.nativeOrder())
        try {
            val addr = MemoryUtil.memAddress(buf)
            val argb = 0xFF102030.toInt()
            val expectedAbgr = 0xFF302010.toInt()
            val light = 0x00E000E0

            EntityRenderOptimizer.writeGlyphQuadAtAddress(
                addr,
                hasLightmap = true,
                1f, 2f, 0.5f,
                1f, 10f, 0.5f,
                8f, 10f, 0.5f,
                8f, 2f, 0.5f,
                0.1f, 0.9f, 0.2f, 0.8f,
                argb,
                light,
            )
            assertEquals(1f, buf.getFloat(0))
            assertEquals(2f, buf.getFloat(4))
            assertEquals(0.5f, buf.getFloat(8))
            assertEquals(0.1f, buf.getFloat(12))
            assertEquals(0.2f, buf.getFloat(16))
            assertEquals(light, buf.getInt(20))
            assertEquals(expectedAbgr, buf.getInt(24))

            EntityRenderOptimizer.writeGlyphQuadAtAddress(
                addr,
                hasLightmap = false,
                1f, 2f, 0f,
                1f, 10f, 0f,
                8f, 10f, 0f,
                8f, 2f, 0f,
                0.1f, 0.9f, 0.2f, 0.8f,
                argb,
                light,
            )
            assertEquals(8f, buf.getFloat(48))
            assertEquals(10f, buf.getFloat(52))
            assertEquals(0f, buf.getFloat(56))
            assertEquals(0.9f, buf.getFloat(60))
            assertEquals(0.8f, buf.getFloat(64))
            assertEquals(expectedAbgr, buf.getInt(68))
        } finally {
            MemoryUtil.memFree(buf)
        }
    }

    @Test
    fun `writeGuiColoredQuadAtAddress writes 64-byte POSITION_COLOR layout`() {
        val buf = MemoryUtil.memAlloc(64).order(ByteOrder.nativeOrder())
        try {
            val addr = MemoryUtil.memAddress(buf)
            val col1 = 0xFF112233.toInt()
            val col2 = 0x80445566.toInt()
            EntityRenderOptimizer.writeGuiColoredQuadAtAddress(
                addr,
                10f, 20f,
                10f, 40f,
                50f, 40f,
                50f, 20f,
                col1,
                col2,
            )
            assertEquals(10f, buf.getFloat(0))
            assertEquals(20f, buf.getFloat(4))
            assertEquals(0f, buf.getFloat(8))
            assertEquals(0xFF332211.toInt(), buf.getInt(12))

            assertEquals(10f, buf.getFloat(16))
            assertEquals(40f, buf.getFloat(20))
            assertEquals(0f, buf.getFloat(24))
            assertEquals(0x80665544.toInt(), buf.getInt(28))
        } finally {
            MemoryUtil.memFree(buf)
        }
    }
}
