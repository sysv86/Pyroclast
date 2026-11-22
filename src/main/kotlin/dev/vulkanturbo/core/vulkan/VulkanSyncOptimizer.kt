package dev.vulkanturbo.core

import dev.vulkanturbo.VulkanTurbo
import dev.vulkanturbo.api.shader.TurboShadersBridge
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.KHRSynchronization2
import org.lwjgl.vulkan.VK12
import org.lwjgl.vulkan.VK13
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkDependencyInfo
import org.lwjgl.vulkan.VkMemoryBarrier2

/**
 * Replaces global `ALL_COMMANDS -> ALL_COMMANDS` pipeline barriers in Mojang's Vulkan backend
 * with stage- and access-scoped barriers while tracking accumulated `lastWriteStageMask` and
 * `lastWriteAccessMask` across operations. Automatically falls back to a full `ALL_COMMANDS`
 * barrier whenever an unknown/untracked pass (such as OIT, custom compute, or external shader
 * dispatch) is recorded.
 */
object VulkanSyncOptimizer {
    const val BARRIER_GLOBAL = 0
    const val BARRIER_RENDER_PASS = 1
    const val BARRIER_BUFFER_TRANSFER = 2
    const val BARRIER_IMAGE_TRANSFER = 3
    const val BARRIER_UNTRACKED_HAZARD = 4

    internal const val RENDER_PASS_SRC_STAGES_DEFAULT =
        VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT or
            VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT or
            VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT

    internal const val RENDER_PASS_SRC_STAGES_SHADERS =
        RENDER_PASS_SRC_STAGES_DEFAULT or
            VK13.VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT or
            VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT

    internal const val RENDER_PASS_SRC_ACCESS_DEFAULT =
        VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT or
            VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT

    internal const val RENDER_PASS_SRC_ACCESS_SHADERS =
        RENDER_PASS_SRC_ACCESS_DEFAULT or
            VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT or
            VK13.VK_ACCESS_2_SHADER_WRITE_BIT

    internal const val RENDER_PASS_DST_STAGES =
        VK13.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT or
            VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT or
            VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT or
            VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT or
            VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT or
            VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT or
            VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT

    internal const val RENDER_PASS_DST_ACCESS =
        VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT or
            VK13.VK_ACCESS_2_SHADER_READ_BIT or
            VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT or
            VK13.VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT or
            VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT or
            VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT or
            VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT or
            VK13.VK_ACCESS_2_TRANSFER_READ_BIT or
            VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT

    internal const val BUFFER_DST_STAGES =
        VK13.VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT or
            VK13.VK_PIPELINE_STAGE_2_INDEX_INPUT_BIT or
            VK13.VK_PIPELINE_STAGE_2_VERTEX_ATTRIBUTE_INPUT_BIT or
            VK13.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT or
            VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT or
            VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT or
            VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT or
            VK13.VK_PIPELINE_STAGE_2_HOST_BIT

    internal const val VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR = 0x00200000L
    internal const val VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR = 0x02000000L
    internal const val VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR = 0x00200000L

    internal const val BUFFER_DST_ACCESS =
        VK13.VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT or
            VK13.VK_ACCESS_2_INDEX_READ_BIT or
            VK13.VK_ACCESS_2_VERTEX_ATTRIBUTE_READ_BIT or
            VK13.VK_ACCESS_2_UNIFORM_READ_BIT or
            VK13.VK_ACCESS_2_SHADER_READ_BIT or
            VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT or
            VK13.VK_ACCESS_2_TRANSFER_READ_BIT or
            VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT or
            VK13.VK_ACCESS_2_HOST_READ_BIT

    internal fun effectiveBufferDstStages(): Long =
        if (TurboShadersBridge.accelStructSupported) {
            BUFFER_DST_STAGES or VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
        } else {
            BUFFER_DST_STAGES
        }

    internal fun effectiveBufferDstAccess(): Long =
        if (TurboShadersBridge.accelStructSupported) {
            BUFFER_DST_ACCESS or VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR
        } else {
            BUFFER_DST_ACCESS
        }

    internal fun effectiveRenderPassDstStages(): Long =
        if (TurboShadersBridge.rayTracingPipelineSupported) {
            RENDER_PASS_DST_STAGES or VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR
        } else {
            RENDER_PASS_DST_STAGES
        }

    internal fun effectiveImageDstStages(): Long =
        if (TurboShadersBridge.rayTracingPipelineSupported) {
            IMAGE_DST_STAGES or VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR
        } else {
            IMAGE_DST_STAGES
        }

    internal const val IMAGE_DST_STAGES =
        VK13.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT or
            VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT or
            VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT or
            VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT or
            VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT or
            VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT or
            VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT or
            VK13.VK_PIPELINE_STAGE_2_HOST_BIT

    internal const val IMAGE_DST_ACCESS =
        VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT or
            VK13.VK_ACCESS_2_SHADER_READ_BIT or
            VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT or
            VK13.VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT or
            VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT or
            VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT or
            VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT or
            VK13.VK_ACCESS_2_TRANSFER_READ_BIT or
            VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT or
            VK13.VK_ACCESS_2_HOST_READ_BIT

    /**
     * Per-`VulkanCommandEncoder` mutable synchronization hazard and barrier-kind state.
     * Kept per encoder instance so concurrent or interleaved encoders never corrupt each other's
     * barrier masks.
     */
    class EncoderSyncState {
        @JvmField var barrierKind: Int = BARRIER_GLOBAL
        @JvmField var computedSrcStage: Long = 0L
        @JvmField var computedSrcAccess: Long = 0L
        @JvmField var computedDstStage: Long = 0L
        @JvmField var computedDstAccess: Long = 0L
        private var accumulatedSrcStages: Long = 0L
        private var accumulatedSrcAccess: Long = 0L
        private var accumulatedDstStages: Long = 0L
        private var accumulatedDstAccess: Long = 0L
        private var hasUntrackedHazard: Boolean = false

        fun recordRenderPassAccess(passLabel: String?, colorTargetCount: Int, hasDepth: Boolean) {
            val shadersActive = TurboShadersBridge.active
            if (passLabel != null && (passLabel.contains("OIT", ignoreCase = true) ||
                    passLabel.contains("Transmittance", ignoreCase = true) ||
                    passLabel.contains("Depth Bounds", ignoreCase = true) ||
                    colorTargetCount > 4)
            ) {
                hasUntrackedHazard = true
                return
            }
            var srcStage = 0L
            var srcAccess = 0L
            if (colorTargetCount > 0) {
                srcStage = srcStage or VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT
                srcAccess = srcAccess or VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT
            }
            if (hasDepth) {
                srcStage = srcStage or VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT or
                    VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT
                srcAccess = srcAccess or VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
            }
            if (shadersActive) {
                srcStage = srcStage or RENDER_PASS_SRC_STAGES_SHADERS
                srcAccess = srcAccess or RENDER_PASS_SRC_ACCESS_SHADERS
            }
            if (srcStage == 0L) {
                srcStage = RENDER_PASS_SRC_STAGES_DEFAULT
                srcAccess = RENDER_PASS_SRC_ACCESS_DEFAULT
            }
            accumulatedSrcStages = accumulatedSrcStages or srcStage
            accumulatedSrcAccess = accumulatedSrcAccess or srcAccess
            accumulatedDstStages = accumulatedDstStages or effectiveRenderPassDstStages()
            accumulatedDstAccess = accumulatedDstAccess or RENDER_PASS_DST_ACCESS
        }

        fun recordBufferTransfer() {
            accumulatedSrcStages = accumulatedSrcStages or VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT
            accumulatedSrcAccess = accumulatedSrcAccess or VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT
            accumulatedDstStages = accumulatedDstStages or effectiveBufferDstStages()
            accumulatedDstAccess = accumulatedDstAccess or effectiveBufferDstAccess()
        }

        fun recordImageTransfer() {
            accumulatedSrcStages = accumulatedSrcStages or VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT
            accumulatedSrcAccess = accumulatedSrcAccess or VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT
            accumulatedDstStages = accumulatedDstStages or effectiveImageDstStages()
            accumulatedDstAccess = accumulatedDstAccess or IMAGE_DST_ACCESS
        }

        fun markUntrackedHazard() {
            hasUntrackedHazard = true
        }

        fun resetTracking() {
            accumulatedSrcStages = 0L
            accumulatedSrcAccess = 0L
            accumulatedDstStages = 0L
            accumulatedDstAccess = 0L
            hasUntrackedHazard = false
        }

        fun computeBarrierMasks(kind: Int, shadersActive: Boolean) {
            if (hasUntrackedHazard || kind == BARRIER_GLOBAL || kind == BARRIER_UNTRACKED_HAZARD) {
                val allAccess = VK13.VK_ACCESS_2_MEMORY_READ_BIT or VK13.VK_ACCESS_2_MEMORY_WRITE_BIT
                computedSrcStage = VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT
                computedSrcAccess = allAccess
                computedDstStage = VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT
                computedDstAccess = allAccess
                return
            }
            val baseSrcStage: Long
            val baseSrcAccess: Long
            val baseDstStage: Long
            val baseDstAccess: Long
            when (kind) {
                BARRIER_RENDER_PASS -> {
                    baseSrcStage = if (shadersActive) RENDER_PASS_SRC_STAGES_SHADERS else RENDER_PASS_SRC_STAGES_DEFAULT
                    baseSrcAccess = if (shadersActive) RENDER_PASS_SRC_ACCESS_SHADERS else RENDER_PASS_SRC_ACCESS_DEFAULT
                    baseDstStage = effectiveRenderPassDstStages()
                    baseDstAccess = RENDER_PASS_DST_ACCESS
                }
                BARRIER_BUFFER_TRANSFER -> {
                    baseSrcStage = VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT
                    baseSrcAccess = VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT
                    baseDstStage = effectiveBufferDstStages()
                    baseDstAccess = effectiveBufferDstAccess()
                }
                BARRIER_IMAGE_TRANSFER -> {
                    baseSrcStage = VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT
                    baseSrcAccess = VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT
                    baseDstStage = effectiveImageDstStages()
                    baseDstAccess = IMAGE_DST_ACCESS
                }
                else -> {
                    val allAccess = VK13.VK_ACCESS_2_MEMORY_READ_BIT or VK13.VK_ACCESS_2_MEMORY_WRITE_BIT
                    computedSrcStage = VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT
                    computedSrcAccess = allAccess
                    computedDstStage = VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT
                    computedDstAccess = allAccess
                    return
                }
            }
            computedSrcStage = baseSrcStage or accumulatedSrcStages
            computedSrcAccess = baseSrcAccess or accumulatedSrcAccess
            computedDstStage = baseDstStage or accumulatedDstStages
            computedDstAccess = baseDstAccess or accumulatedDstAccess
        }

        fun emitScopedBarrier(cmd: VkCommandBuffer, stack: MemoryStack, kind: Int) {
            computeBarrierMasks(kind, TurboShadersBridge.active)
            resetTracking()
            emitBarrier(
                cmd = cmd,
                stack = stack,
                srcStage = computedSrcStage,
                srcAccess = computedSrcAccess,
                dstStage = computedDstStage,
                dstAccess = computedDstAccess,
            )
            if (FrameProfiler.isEnabled()) {
                FrameProfiler.countBarrier()
            }
        }

        fun onSubmitRenderPass(passLabel: String?, colorTargetCount: Int, hasDepth: Boolean) {
            recordRenderPassAccess(passLabel, colorTargetCount, hasDepth)
            barrierKind = BARRIER_RENDER_PASS
        }

        fun onBufferTransfer() {
            recordBufferTransfer()
            barrierKind = BARRIER_BUFFER_TRANSFER
        }

        fun onImageTransfer() {
            recordImageTransfer()
            barrierKind = BARRIER_IMAGE_TRANSFER
        }

        fun onMemoryBarrier(cmd: VkCommandBuffer, stack: MemoryStack): Boolean {
            val kind = barrierKind
            barrierKind = BARRIER_GLOBAL
            return if (isEnabled() && kind != BARRIER_GLOBAL) {
                emitScopedBarrier(cmd, stack, kind)
                true
            } else {
                resetTracking()
                false
            }
        }

        fun onReset() {
            barrierKind = BARRIER_GLOBAL
            resetTracking()
        }
    }

    // Backwards-compatible default tracker for standalone unit test helpers.
    private val defaultState = EncoderSyncState()

    @JvmStatic
    fun createEncoderState(): EncoderSyncState = EncoderSyncState()

    @JvmStatic
    fun isEnabled(): Boolean = VulkanTurbo.scopedBarriersEnabled

    /**
     * Records a render pass completion into the default hazard tracker. If [passLabel] indicates
     * OIT or an unknown multi-pass algorithm, marks `hasUntrackedHazard = true` so the next barrier
     * falls back to `ALL_COMMANDS_BIT`.
     */
    @JvmStatic
    fun recordRenderPassAccess(passLabel: String?, colorTargetCount: Int, hasDepth: Boolean) {
        defaultState.recordRenderPassAccess(passLabel, colorTargetCount, hasDepth)
    }

    @JvmStatic
    fun recordBufferTransfer() {
        defaultState.recordBufferTransfer()
    }

    @JvmStatic
    fun recordImageTransfer() {
        defaultState.recordImageTransfer()
    }

    @JvmStatic
    fun markUntrackedHazard() {
        defaultState.markUntrackedHazard()
    }

    @JvmStatic
    fun resetTracking() {
        defaultState.resetTracking()
    }

    internal fun computeBarrierMasks(kind: Int, shadersActive: Boolean): EncoderSyncState {
        defaultState.computeBarrierMasks(kind, shadersActive)
        return defaultState
    }

    @JvmStatic
    fun emitScopedBarrier(cmd: VkCommandBuffer, stack: MemoryStack, kind: Int) {
        defaultState.emitScopedBarrier(cmd, stack, kind)
    }

    @JvmStatic
    fun narrowSwapchainPresentBarrier(cmd: VkCommandBuffer, depInfo: VkDependencyInfo) {
        if (TurboShadersBridge.active || TurboShadersBridge.swapchainOverrideActive) {
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, depInfo)
            return
        }
        val imgBarriers = depInfo.pImageMemoryBarriers()
        if (imgBarriers != null && imgBarriers.remaining() > 0) {
            imgBarriers.get(0)
                .dstStageMask(VK13.VK_PIPELINE_STAGE_2_NONE)
                .dstAccessMask(0L)
        }
        depInfo.pMemoryBarriers(null)
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, depInfo)
    }

    @JvmStatic
    fun narrowAcquireWaitStage(originalStage: Long): Long =
        if (isEnabled() && !TurboShadersBridge.active && !TurboShadersBridge.swapchainOverrideActive) {
            VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT
        } else {
            originalStage
        }

    /**
     * Binds a single Vulkan vertex buffer using stack scratch pointers instead of
     * `MemoryStack.longs`, eliminating two `DirectLongBufferU` wrapper allocations per bind.
     */
    @JvmStatic
    fun bindVertexBuffer(cmd: VkCommandBuffer, slot: Int, vkBuffer: Long, offset: Long) {
        MemoryStack.stackPush().use { stack ->
            val pBuffers = stack.nmalloc(8, 8)
            val pOffsets = stack.nmalloc(8, 8)
            MemoryUtil.memPutLong(pBuffers, vkBuffer)
            MemoryUtil.memPutLong(pOffsets, offset)
            VK12.nvkCmdBindVertexBuffers(cmd, slot, 1, pBuffers, pOffsets)
        }
    }

    private fun emitBarrier(
        cmd: VkCommandBuffer,
        stack: MemoryStack,
        srcStage: Long,
        srcAccess: Long,
        dstStage: Long,
        dstAccess: Long,
    ) {
        val barrier = VkMemoryBarrier2.calloc(1, stack).`sType$Default`()
        barrier.srcStageMask(srcStage)
        barrier.srcAccessMask(srcAccess)
        barrier.dstStageMask(dstStage)
        barrier.dstAccessMask(dstAccess)

        val dep = VkDependencyInfo.calloc(stack).`sType$Default`()
        dep.pMemoryBarriers(barrier)
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep)
    }
}
