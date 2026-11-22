package dev.vulkanturbo.core

import com.mojang.renderpearl.api.commands.RenderPassDescriptor
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.renderpearl.backend.vulkan.VulkanConst
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline
import com.mojang.renderpearl.backend.vulkan.VulkanUtils
import com.mojang.renderpearl.util.TextureViewAndSampler
import dev.vulkanturbo.VulkanTurbo
import dev.vulkanturbo.api.shader.TurboShadersBridge
import it.unimi.dsi.fastutil.objects.ReferenceList
import org.joml.Vector4fc
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.KHRDynamicRendering
import org.lwjgl.vulkan.KHRSynchronization2
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK13
import org.lwjgl.vulkan.VkClearColorValue
import org.lwjgl.vulkan.VkClearValue
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkDependencyInfo
import org.lwjgl.vulkan.VkImageMemoryBarrier2
import org.lwjgl.vulkan.VkRect2D
import org.lwjgl.vulkan.VkRenderingAttachmentInfo
import org.lwjgl.vulkan.VkRenderingInfo
import java.util.Optional
import java.util.OptionalDouble

/** Accessor mixed onto `VulkanGpuTexture` to track its current `VkImageLayout`. */
interface VulkanTrackedTexture {
    fun vtGetLayout(): Int
    fun vtSetLayout(layout: Int)
}

/** Deferred-rendering state mixed onto `VulkanRenderPass`. */
interface VulkanTrackedRenderPass {
    fun vtSetPendingPass(
        descriptor: RenderPassDescriptor,
        colorViews: Array<VulkanGpuTextureView?>,
        depthView: VulkanGpuTextureView?,
    )

    fun vtIsDeferredEnabled(): Boolean
    fun vtIsRenderingStarted(): Boolean
    fun vtHasClear(): Boolean
    fun vtEnsureRenderingStarted()
    fun vtContainsAttachment(texture: VulkanGpuTexture): Boolean
    fun vtLayoutForImageView(vkImageView: Long): Int
    fun vtOverrideColorViews(colorViews: Array<VulkanGpuTextureView?>): Boolean
    fun vtColorAttachmentCount(): Int
}

/** Exposes backend `RenderPassBackend` and MRT color-attachment overrides on `FrontendRenderPass`. */
interface VulkanFrontendRenderPassAccessor {
    fun vtBackendRenderPass(): com.mojang.renderpearl.backend.api.RenderPassBackend
    fun vtApplyColorViewsOverride(colorViews: Array<VulkanGpuTextureView?>): Boolean
    fun vtResetBoundPipeline()
}

/**
 * Pure Vulkan synchronization-2 stage/access mask mapping for `VkImageLayout` transitions.
 * Includes explicit masks for `DEPTH_STENCIL_READ_ONLY_OPTIMAL` (shadow maps / composite depth
 * sampling) and `GENERAL` (compute / storage images used by `TurboShaders`).
 */
internal object LayoutTransitionMasks {
    private const val VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR = 0x00200000L

    data class StageAccess(
        val stageMask: Long,
        val accessMask: Long,
    )

    fun sourceMasks(oldLayout: Int): StageAccess = when (oldLayout) {
        VK10.VK_IMAGE_LAYOUT_UNDEFINED -> StageAccess(
            stageMask = VK13.VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
            accessMask = 0L,
        )
        VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> StageAccess(
            stageMask = VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
            accessMask = VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
        )
        VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL -> StageAccess(
            stageMask = VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT or
                VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
            accessMask = VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
        )
        VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL -> StageAccess(
            stageMask = VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT or
                VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT or
                VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT or
                VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            accessMask = 0L,
        )
        VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> StageAccess(
            stageMask = VK13.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT or
                VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT or
                VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            accessMask = 0L,
        )
        VK10.VK_IMAGE_LAYOUT_GENERAL -> {
            var stageMask = VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT or
                VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT or
                VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT or
                VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT or
                VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT or
                VK13.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT
            if (TurboShadersBridge.rayTracingPipelineSupported) {
                stageMask = stageMask or VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR
            }
            StageAccess(
                stageMask = stageMask,
                accessMask = VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT or
                    VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT or
                    VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT or
                    VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
            )
        }
        VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> StageAccess(
            stageMask = VK13.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
            accessMask = 0L,
        )
        VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> StageAccess(
            stageMask = VK13.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
            accessMask = VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
        )
        else -> StageAccess(
            stageMask = VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
            accessMask = VK13.VK_ACCESS_2_MEMORY_WRITE_BIT,
        )
    }

    fun destMasks(newLayout: Int): StageAccess = when (newLayout) {
        VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> StageAccess(
            stageMask = VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
            accessMask = VK13.VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT or
                VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
        )
        VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL -> StageAccess(
            stageMask = VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT or
                VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
            accessMask = VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT or
                VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
        )
        VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL -> StageAccess(
            stageMask = VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT or
                VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT or
                VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT or
                VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            accessMask = VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT or
                VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
        )
        VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> StageAccess(
            stageMask = VK13.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT or
                VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT or
                VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            accessMask = VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
        )
        VK10.VK_IMAGE_LAYOUT_GENERAL -> StageAccess(
            stageMask = VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT or
                VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT or
                VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT or
                VK13.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT or
                VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT or
                VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT or
                VK13.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT or
                (if (TurboShadersBridge.rayTracingPipelineSupported) {
                    VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR
                } else {
                    0L
                }),
            accessMask = VK13.VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT or
                VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT or
                VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT or
                VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT or
                VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT or
                VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT or
                VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT or
                VK13.VK_ACCESS_2_TRANSFER_READ_BIT or
                VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
        )
        VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> StageAccess(
            stageMask = VK13.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
            accessMask = VK13.VK_ACCESS_2_TRANSFER_READ_BIT,
        )
        VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> StageAccess(
            stageMask = VK13.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
            accessMask = VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
        )
        else -> StageAccess(
            stageMask = VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
            accessMask = VK13.VK_ACCESS_2_MEMORY_READ_BIT or VK13.VK_ACCESS_2_MEMORY_WRITE_BIT,
        )
    }
}

/**
 * Tracks each `VulkanGpuTexture`'s `VkImageLayout` and transitions textures into optimal
 * usage-specific layouts (`COLOR_ATTACHMENT_OPTIMAL`, `DEPTH_STENCIL_ATTACHMENT_OPTIMAL`,
 * `SHADER_READ_ONLY_OPTIMAL`, `TRANSFER_SRC_OPTIMAL`, `TRANSFER_DST_OPTIMAL`) instead of keeping
 * everything permanently in `VK_IMAGE_LAYOUT_GENERAL`.
 *
 * Enabling optimal image layouts allows GPUs (especially NVIDIA Ampere+ DCC/HiZ and tile-based
 * architectures) to keep lossless framebuffer and depth compression active.
 */
object VulkanLayoutTracker {
    @JvmStatic
    fun isEnabled(): Boolean = VulkanTurbo.optimalImageLayoutsEnabled

    @JvmStatic
    fun shouldDeferRenderPass(): Boolean =
        VulkanTurbo.optimalImageLayoutsEnabled ||
            VulkanTurbo.scopedBarriersEnabled ||
            dev.vulkanturbo.api.shader.TurboShadersBridge.active

    @JvmStatic
    fun descriptorHasClear(descriptor: RenderPassDescriptor?): Boolean {
        if (descriptor == null) return false
        val colors = descriptor.colorAttachments()
        for (i in 0 until colors.size) {
            val att = colors[i] ?: continue
            val clearOpt = att.clearValue() as? Optional<*>
            if (clearOpt != null && clearOpt.isPresent) return true
        }
        val depth = descriptor.depthAttachment() ?: return false
        val depthClear = depth.clearValue() as? OptionalDouble
        return depthClear != null && depthClear.isPresent
    }

    @JvmStatic
    fun transition(cmd: VkCommandBuffer, tex: VulkanGpuTexture, targetLayout: Int): Int {
        val tracked = tex as VulkanTrackedTexture
        val oldLayout = tracked.vtGetLayout()
        if (targetLayout == VK10.VK_IMAGE_LAYOUT_UNDEFINED || targetLayout == VK10.VK_IMAGE_LAYOUT_PREINITIALIZED) {
            return oldLayout
        }
        if (oldLayout == targetLayout) {
            return targetLayout
        }
        MemoryStack.stackPush().use { stack ->
            val barriers = VkImageMemoryBarrier2.calloc(1, stack)
            fillBarrier(barriers.get(0), tex, oldLayout, targetLayout)
            val depInfo = VkDependencyInfo.calloc(stack).`sType$Default`()
                .pImageMemoryBarriers(barriers)
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, depInfo)
        }
        tracked.vtSetLayout(targetLayout)
        return targetLayout
    }

    /**
     * Transitions all non-null textures in [textures] that are not already in [targetLayout]
     * using a single `vkCmdPipelineBarrier2KHR` call. Returns `true` if a barrier was emitted.
     */
    @JvmStatic
    fun transitionBatch(
        cmd: VkCommandBuffer,
        textures: Array<VulkanGpuTexture?>,
        targetLayout: Int,
    ): Boolean {
        if (textures.isEmpty() ||
            targetLayout == VK10.VK_IMAGE_LAYOUT_UNDEFINED ||
            targetLayout == VK10.VK_IMAGE_LAYOUT_PREINITIALIZED
        ) {
            return false
        }
        var count = 0
        for (i in textures.indices) {
            val tex = textures[i] ?: continue
            if ((tex as VulkanTrackedTexture).vtGetLayout() != targetLayout) {
                count++
            }
        }
        if (count == 0) {
            return false
        }
        MemoryStack.stackPush().use { stack ->
            val barriers = VkImageMemoryBarrier2.calloc(count, stack)
            var idx = 0
            for (i in textures.indices) {
                val tex = textures[i] ?: continue
                val tracked = tex as VulkanTrackedTexture
                val oldLayout = tracked.vtGetLayout()
                if (oldLayout != targetLayout) {
                    fillBarrier(barriers.get(idx++), tex, oldLayout, targetLayout)
                    tracked.vtSetLayout(targetLayout)
                }
            }
            if (idx > 0) {
                barriers.limit(idx)
                val depInfo = VkDependencyInfo.calloc(stack).`sType$Default`()
                    .pImageMemoryBarriers(barriers)
                KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, depInfo)
                return true
            }
        }
        return false
    }

    @JvmStatic
    fun transitionTransferPair(cmd: VkCommandBuffer, src: VulkanGpuTexture, dst: VulkanGpuTexture) {
        if (src === dst) {
            transition(cmd, src, VK10.VK_IMAGE_LAYOUT_GENERAL)
            return
        }
        val srcTracked = src as VulkanTrackedTexture
        val dstTracked = dst as VulkanTrackedTexture
        val srcNeed = srcTracked.vtGetLayout() != VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
        val dstNeed = dstTracked.vtGetLayout() != VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
        if (!srcNeed && !dstNeed) {
            return
        }
        MemoryStack.stackPush().use { stack ->
            val count = (if (srcNeed) 1 else 0) + (if (dstNeed) 1 else 0)
            val barriers = VkImageMemoryBarrier2.calloc(count, stack)
            var idx = 0
            if (srcNeed) {
                fillBarrier(barriers.get(idx++), src, srcTracked.vtGetLayout(), VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                srcTracked.vtSetLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
            }
            if (dstNeed) {
                fillBarrier(barriers.get(idx), dst, dstTracked.vtGetLayout(), VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                dstTracked.vtSetLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
            }
            val depInfo = VkDependencyInfo.calloc(stack).`sType$Default`()
                .pImageMemoryBarriers(barriers)
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, depInfo)
        }
    }

    /**
     * Ensures every `COMBINED_IMAGE_SAMPLER` texture currently bound on [pass] is in
     * `SHADER_READ_ONLY_OPTIMAL` (or `GENERAL` if simultaneously attached to the active pass).
     */
    @JvmStatic
    fun prepareSampledTextures(
        pass: VulkanRenderPass,
        cmd: VkCommandBuffer,
        pipeline: VulkanRenderPipeline,
        uniforms: ReferenceList<Any?>,
        colorViews: Array<VulkanGpuTextureView?>?,
        depthView: VulkanGpuTextureView?,
        descriptor: RenderPassDescriptor?,
        renderingStarted: Boolean,
    ): Boolean {
        val uniformDescs = pipeline.uniforms()
        val trackedPass = pass as VulkanTrackedRenderPass

        var dirtyCount = 0
        val maxScan = minOf(uniformDescs.size, uniforms.size)
        for (i in 0 until maxScan) {
            if (uniformDescs[i].type() == UniformType.COMBINED_IMAGE_SAMPLER) {
                val tvs = uniforms[i] as? TextureViewAndSampler ?: continue
                val tex = tvs.view().texture() as? VulkanGpuTexture ?: continue
                val target = if (trackedPass.vtContainsAttachment(tex)) {
                    VK10.VK_IMAGE_LAYOUT_GENERAL
                } else {
                    VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                }
                if ((tex as VulkanTrackedTexture).vtGetLayout() != target) {
                    dirtyCount++
                }
            }
        }
        if (dirtyCount == 0) {
            return renderingStarted
        }

        val wasRenderingStarted = renderingStarted
        if (wasRenderingStarted) {
            KHRDynamicRendering.vkCmdEndRenderingKHR(cmd)
        }

        MemoryStack.stackPush().use { stack ->
            val barriers = VkImageMemoryBarrier2.calloc(dirtyCount, stack)
            var idx = 0
            for (i in 0 until maxScan) {
                if (uniformDescs[i].type() != UniformType.COMBINED_IMAGE_SAMPLER) continue
                val tvs = uniforms[i] as? TextureViewAndSampler ?: continue
                val tex = tvs.view().texture() as? VulkanGpuTexture ?: continue
                val target = if (trackedPass.vtContainsAttachment(tex)) {
                    VK10.VK_IMAGE_LAYOUT_GENERAL
                } else {
                    VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                }
                val tracked = tex as VulkanTrackedTexture
                val old = tracked.vtGetLayout()
                if (old != target) {
                    fillBarrier(barriers.get(idx++), tex, old, target)
                    tracked.vtSetLayout(target)
                }
            }
            if (idx > 0) {
                barriers.limit(idx)
                val depInfo = VkDependencyInfo.calloc(stack).`sType$Default`()
                    .pImageMemoryBarriers(barriers)
                KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, depInfo)
            }
        }

        if (wasRenderingStarted && descriptor != null && colorViews != null) {
            beginRendering(
                cmd = cmd,
                descriptor = descriptor,
                colorViews = colorViews,
                depthView = depthView,
                pipeline = pipeline,
                uniforms = uniforms,
                forceLoad = true,
            )
            return true
        }
        return false
    }

    /**
     * Transitions sampled textures using precomputed target layouts from [samplerLayouts],
     * avoiding redundant `vtContainsAttachment` checks on dirty draws.
     */
    @JvmStatic
    fun transitionSampledTextures(
        cmd: VkCommandBuffer,
        pipeline: VulkanRenderPipeline,
        uniforms: ReferenceList<Any?>,
        samplerLayouts: IntArray,
        dirtyCount: Int,
        colorViews: Array<VulkanGpuTextureView?>?,
        depthView: VulkanGpuTextureView?,
        descriptor: RenderPassDescriptor?,
        renderingStarted: Boolean,
    ): Boolean {
        if (dirtyCount <= 0) {
            return renderingStarted
        }

        val wasRenderingStarted = renderingStarted
        if (wasRenderingStarted) {
            KHRDynamicRendering.vkCmdEndRenderingKHR(cmd)
        }

        val uniformDescs = pipeline.uniforms()
        val maxScan = minOf(uniformDescs.size, uniforms.size)
        MemoryStack.stackPush().use { stack ->
            val barriers = VkImageMemoryBarrier2.calloc(dirtyCount, stack)
            var idx = 0
            var samplerIdx = 0
            for (i in 0 until maxScan) {
                if (uniformDescs[i].type() != UniformType.COMBINED_IMAGE_SAMPLER) continue
                val tvs = uniforms[i] as? TextureViewAndSampler ?: continue
                val target = if (samplerIdx < samplerLayouts.size) {
                    samplerLayouts[samplerIdx]
                } else {
                    VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                }
                samplerIdx++
                val tex = tvs.view().texture() as? VulkanGpuTexture ?: continue
                val tracked = tex as VulkanTrackedTexture
                val old = tracked.vtGetLayout()
                if (old != target) {
                    fillBarrier(barriers.get(idx++), tex, old, target)
                    tracked.vtSetLayout(target)
                }
            }
            if (idx > 0) {
                barriers.limit(idx)
                val depInfo = VkDependencyInfo.calloc(stack).`sType$Default`()
                    .pImageMemoryBarriers(barriers)
                KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, depInfo)
            }
        }

        if (wasRenderingStarted && descriptor != null && colorViews != null) {
            beginRendering(
                cmd = cmd,
                descriptor = descriptor,
                colorViews = colorViews,
                depthView = depthView,
                pipeline = pipeline,
                uniforms = uniforms,
                forceLoad = true,
            )
            return true
        }
        return false
    }

    @JvmStatic
    fun beginRendering(
        cmd: VkCommandBuffer,
        descriptor: RenderPassDescriptor,
        colorViews: Array<VulkanGpuTextureView?>,
        depthView: VulkanGpuTextureView?,
        pipeline: VulkanRenderPipeline?,
        uniforms: ReferenceList<Any?>,
        forceLoad: Boolean,
    ) {
        val useOptimalLayouts = isEnabled()
        MemoryStack.stackPush().use { stack ->
            if (useOptimalLayouts) {
                var barrierCount = 0
                for (cv in colorViews) {
                    if (cv == null) continue
                    val tex = cv.texture() as VulkanGpuTexture
                    val target = attachmentTargetLayout(tex, isDepth = false, pipeline, uniforms)
                    if ((tex as VulkanTrackedTexture).vtGetLayout() != target) {
                        barrierCount++
                    }
                }
                if (depthView != null) {
                    val tex = depthView.texture() as VulkanGpuTexture
                    val target = attachmentTargetLayout(tex, isDepth = true, pipeline, uniforms)
                    if ((tex as VulkanTrackedTexture).vtGetLayout() != target) {
                        barrierCount++
                    }
                }

                if (barrierCount > 0) {
                    val barriers = VkImageMemoryBarrier2.calloc(barrierCount, stack)
                    var idx = 0
                    for (cv in colorViews) {
                        if (cv == null) continue
                        val tex = cv.texture() as VulkanGpuTexture
                        val target = attachmentTargetLayout(tex, isDepth = false, pipeline, uniforms)
                        val tracked = tex as VulkanTrackedTexture
                        val old = tracked.vtGetLayout()
                        if (old != target) {
                            fillBarrier(barriers.get(idx++), tex, old, target)
                            tracked.vtSetLayout(target)
                        }
                    }
                    if (depthView != null) {
                        val tex = depthView.texture() as VulkanGpuTexture
                        val target = attachmentTargetLayout(tex, isDepth = true, pipeline, uniforms)
                        val tracked = tex as VulkanTrackedTexture
                        val old = tracked.vtGetLayout()
                        if (old != target) {
                            fillBarrier(barriers.get(idx++), tex, old, target)
                            tracked.vtSetLayout(target)
                        }
                    }
                    if (idx > 0) {
                        barriers.limit(idx)
                        val depInfo = VkDependencyInfo.calloc(stack).`sType$Default`()
                            .pImageMemoryBarriers(barriers)
                        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, depInfo)
                    }
                }
            }

            val area = descriptor.renderArea()
            val rect = VkRect2D.calloc(stack)
            rect.offset().set(area.x(), area.y())
            rect.extent().set(area.width(), area.height())

            val colorAttachmentsDesc = descriptor.colorAttachments()
            val vkColorBuf = VkRenderingAttachmentInfo.calloc(colorViews.size, stack)
            for (i in colorViews.indices) {
                val att = vkColorBuf.position(i).`sType$Default`()
                val view = colorViews[i]
                if (view != null) {
                    val tex = view.texture() as VulkanGpuTexture
                    val layout = if (useOptimalLayouts) {
                        (tex as VulkanTrackedTexture).vtGetLayout()
                    } else {
                        VK10.VK_IMAGE_LAYOUT_GENERAL
                    }
                    att.imageView(view.vkImageView())
                    att.imageLayout(layout)
                    att.storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE)

                    @Suppress("UNCHECKED_CAST")
                    val clearOpt = if (i < colorAttachmentsDesc.size) {
                        colorAttachmentsDesc[i]?.clearValue() as? Optional<Vector4fc>
                    } else {
                        null
                    }
                    if (!forceLoad && clearOpt != null && clearOpt.isPresent) {
                        val clearColor = VkClearColorValue.calloc(stack)
                        VulkanUtils.putArgb(clearColor, clearOpt.get())
                        att.loadOp(VK10.VK_ATTACHMENT_LOAD_OP_CLEAR)
                        att.clearValue(VkClearValue.calloc(stack).color(clearColor))
                    } else {
                        att.loadOp(VK10.VK_ATTACHMENT_LOAD_OP_LOAD)
                    }
                } else {
                    att.imageView(0L)
                    att.imageLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED)
                    att.storeOp(VK10.VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    att.loadOp(2)
                }
            }
            vkColorBuf.position(0)

            val renderingInfo = VkRenderingInfo.calloc(stack).`sType$Default`()
                .renderArea(rect)
                .layerCount(1)
                .viewMask(0)
                .pColorAttachments(vkColorBuf)

            val depthDesc = descriptor.depthAttachment()
            if (depthDesc != null && depthView != null) {
                val depthTex = depthView.texture() as VulkanGpuTexture
                val depthLayout = if (useOptimalLayouts) {
                    (depthTex as VulkanTrackedTexture).vtGetLayout()
                } else {
                    VK10.VK_IMAGE_LAYOUT_GENERAL
                }
                val vkDepth = VkRenderingAttachmentInfo.calloc(stack).`sType$Default`()
                    .imageView(depthView.vkImageView())
                    .imageLayout(depthLayout)
                    .storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE)
                val clearDouble = depthDesc.clearValue() as? OptionalDouble
                if (!forceLoad && clearDouble != null && clearDouble.isPresent) {
                    val dsVal = org.lwjgl.vulkan.VkClearDepthStencilValue.calloc(stack)
                        .depth(clearDouble.asDouble.toFloat())
                    vkDepth.loadOp(VK10.VK_ATTACHMENT_LOAD_OP_CLEAR)
                    vkDepth.clearValue(VkClearValue.calloc(stack).depthStencil(dsVal))
                } else {
                    vkDepth.loadOp(VK10.VK_ATTACHMENT_LOAD_OP_LOAD)
                }
                renderingInfo.pDepthAttachment(vkDepth)
            }

            KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, renderingInfo)
        }
    }

    private fun attachmentTargetLayout(
        tex: VulkanGpuTexture,
        isDepth: Boolean,
        pipeline: VulkanRenderPipeline?,
        uniforms: ReferenceList<Any?>,
    ): Int {
        if (isBoundAsSampledTexture(tex, pipeline, uniforms)) {
            return VK10.VK_IMAGE_LAYOUT_GENERAL
        }
        return if (isDepth) {
            VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
        } else {
            VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
        }
    }

    private fun isBoundAsSampledTexture(
        tex: VulkanGpuTexture,
        pipeline: VulkanRenderPipeline?,
        uniforms: ReferenceList<Any?>,
    ): Boolean {
        val descs = pipeline?.uniforms() ?: return false
        val max = minOf(descs.size, uniforms.size)
        for (i in 0 until max) {
            if (descs[i].type() == UniformType.COMBINED_IMAGE_SAMPLER) {
                val tvs = uniforms[i] as? TextureViewAndSampler ?: continue
                if (tvs.view().texture() === tex) {
                    return true
                }
            }
        }
        return false
    }

    private fun fillBarrier(
        barrier: VkImageMemoryBarrier2,
        tex: VulkanGpuTexture,
        oldLayout: Int,
        newLayout: Int,
    ) {
        val src = LayoutTransitionMasks.sourceMasks(oldLayout)
        val dst = LayoutTransitionMasks.destMasks(newLayout)
        barrier.`sType$Default`()
            .srcStageMask(src.stageMask)
            .srcAccessMask(src.accessMask)
            .dstStageMask(dst.stageMask)
            .dstAccessMask(dst.accessMask)
            .oldLayout(oldLayout)
            .newLayout(newLayout)
            .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
            .image(tex.vkImage())
        barrier.subresourceRange()
            .aspectMask(VulkanConst.formatAspectMask(tex.getFormat()))
            .baseMipLevel(0)
            .levelCount(VK10.VK_REMAINING_MIP_LEVELS)
            .baseArrayLayer(0)
            .layerCount(VK10.VK_REMAINING_ARRAY_LAYERS)
    }
}
