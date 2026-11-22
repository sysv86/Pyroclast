package dev.vulkanturbo.core

import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK13
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VulkanGpuOptimizerTest {
    @Test
    fun `pipeline cache header accepts matching vendor device version and uuid`() {
        val uuid = ByteArray(16) { (it + 1).toByte() }
        val blob = ByteArray(48)
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.nativeOrder())
        buf.putInt(0, 32) // headerSize
        buf.putInt(4, 1) // VK_PIPELINE_CACHE_HEADER_VERSION_ONE
        buf.putInt(8, 0x10DE) // NVIDIA
        buf.putInt(12, 0x25A2) // RTX 3050 Mobile
        for (i in 0 until 16) {
            blob[16 + i] = uuid[i]
        }

        assertTrue(PipelineCacheHeader.isCompatible(blob, 0x10DE, 0x25A2, uuid))
        assertFalse(PipelineCacheHeader.isCompatible(blob, 0x8086, 0x25A2, uuid))
        assertFalse(PipelineCacheHeader.isCompatible(blob, 0x10DE, 0x9999, uuid))

        val badUuid = uuid.copyOf()
        badUuid[7] = 0x7F
        assertFalse(PipelineCacheHeader.isCompatible(blob, 0x10DE, 0x25A2, badUuid))
        assertFalse(PipelineCacheHeader.isCompatible(ByteArray(16), 0x10DE, 0x25A2, uuid))
    }

    @Test
    fun `layout transition masks flush writes on write-to-read transitions`() {
        val colorSrc = LayoutTransitionMasks.sourceMasks(VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
        assertEquals(VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, colorSrc.stageMask)
        assertEquals(VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT, colorSrc.accessMask)

        val sampledDst = LayoutTransitionMasks.destMasks(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
        assertEquals(
            VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
            sampledDst.accessMask,
        )

        val sampledSrc = LayoutTransitionMasks.sourceMasks(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
        assertEquals(0L, sampledSrc.accessMask)

        val xferDst = LayoutTransitionMasks.sourceMasks(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
        assertEquals(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT, xferDst.accessMask)
    }

    @Test
    fun `scoped barrier hazard tracker accumulates access and falls back to ALL_COMMANDS on OIT or untracked pass`() {
        VulkanSyncOptimizer.resetTracking()
        VulkanSyncOptimizer.recordBufferTransfer()
        VulkanSyncOptimizer.recordRenderPassAccess("Main", 1, true)
        val scopedState = VulkanSyncOptimizer.computeBarrierMasks(VulkanSyncOptimizer.BARRIER_RENDER_PASS, shadersActive = false)
        assertTrue((scopedState.computedSrcStage and VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT) != 0L)
        assertTrue((scopedState.computedSrcStage and VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT) != 0L)

        // Recording an OIT pass must force an ALL_COMMANDS barrier fallback
        VulkanSyncOptimizer.recordRenderPassAccess("OIT Composite", 1, true)
        val fallbackState = VulkanSyncOptimizer.computeBarrierMasks(VulkanSyncOptimizer.BARRIER_RENDER_PASS, shadersActive = false)
        assertEquals(VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, fallbackState.computedSrcStage)
        assertEquals(VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, fallbackState.computedDstStage)
        VulkanSyncOptimizer.resetTracking()
    }

    @Test
    fun `multiple encoder sync states remain isolated across interleaved operations and resets`() {
        val encoderA = VulkanSyncOptimizer.createEncoderState()
        val encoderB = VulkanSyncOptimizer.createEncoderState()

        encoderA.onSubmitRenderPass("OIT Composite", 1, true)
        encoderB.onBufferTransfer()

        assertEquals(VulkanSyncOptimizer.BARRIER_RENDER_PASS, encoderA.barrierKind)
        assertEquals(VulkanSyncOptimizer.BARRIER_BUFFER_TRANSFER, encoderB.barrierKind)

        encoderA.computeBarrierMasks(encoderA.barrierKind, shadersActive = false)
        encoderB.computeBarrierMasks(encoderB.barrierKind, shadersActive = false)

        assertEquals(VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, encoderA.computedSrcStage)
        assertEquals(VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT, encoderB.computedSrcStage)
        assertTrue((encoderB.computedSrcStage and VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT) == 0L)

        // Resetting encoderA must not disturb encoderB's accumulated state.
        encoderA.onReset()
        assertEquals(VulkanSyncOptimizer.BARRIER_GLOBAL, encoderA.barrierKind)
        assertEquals(VulkanSyncOptimizer.BARRIER_BUFFER_TRANSFER, encoderB.barrierKind)
        encoderB.computeBarrierMasks(encoderB.barrierKind, shadersActive = false)
        assertEquals(VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT, encoderB.computedSrcStage)
    }
}
