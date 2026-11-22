package dev.vulkanturbo.mixin.vulkan;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import dev.vulkanturbo.core.VulkanLayoutTracker;
import dev.vulkanturbo.core.VulkanTrackedRenderPass;
import dev.vulkanturbo.core.VulkanTrackedTexture;
import it.unimi.dsi.fastutil.objects.ReferenceList;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.List;

/**
 * Transitions sampled textures bound in `VulkanRenderPass` into `SHADER_READ_ONLY_OPTIMAL`,
 * suspends/resumes dynamic rendering if a mid-pass descriptor change needs a transition, and starts
 * deferred `vkCmdBeginRenderingKHR` before the first draw call across all 8 `RenderPassBackend` draw overloads.
 */
@Mixin(VulkanRenderPass.class)
public abstract class VulkanRenderPassLayoutMixin implements VulkanTrackedRenderPass {
    @Shadow
    @Final
    private VkCommandBuffer commandBuffer;

    @Shadow
    protected VulkanRenderPipeline pipeline;

    @Shadow
    private boolean anyDescriptorDirty;

    @Shadow
    @Final
    protected ReferenceList<Object> uniforms;

    @Unique
    private boolean vulkanturbo$deferEnabled;
    @Unique
    private boolean vulkanturbo$renderingStarted;
    @Unique
    private boolean vulkanturbo$hasBegunOnce;
    @Unique
    private RenderPassDescriptor vulkanturbo$descriptor;
    @Unique
    private VulkanGpuTextureView[] vulkanturbo$colorViews;
    @Unique
    private VulkanGpuTextureView vulkanturbo$depthView;
    @Unique
    private int[] vulkanturbo$samplerLayouts = new int[16];
    @Unique
    private int vulkanturbo$samplerCursor;

    @Override
    public void vtSetPendingPass(
        RenderPassDescriptor descriptor,
        VulkanGpuTextureView[] colorViews,
        VulkanGpuTextureView depthView
    ) {
        this.vulkanturbo$descriptor = descriptor;
        this.vulkanturbo$colorViews = colorViews;
        this.vulkanturbo$depthView = depthView;
        this.vulkanturbo$deferEnabled = true;
        this.vulkanturbo$renderingStarted = false;
        this.vulkanturbo$hasBegunOnce = false;
    }

    @Override
    public int vtColorAttachmentCount() {
        return this.vulkanturbo$colorViews != null ? this.vulkanturbo$colorViews.length : 1;
    }

    @Override
    public boolean vtIsDeferredEnabled() {
        return this.vulkanturbo$deferEnabled;
    }

    @Override
    public boolean vtIsRenderingStarted() {
        return this.vulkanturbo$renderingStarted;
    }

    @Override
    public boolean vtHasClear() {
        return VulkanLayoutTracker.descriptorHasClear(this.vulkanturbo$descriptor);
    }

    @Override
    public void vtEnsureRenderingStarted() {
        if (!this.vulkanturbo$deferEnabled || this.vulkanturbo$renderingStarted || this.vulkanturbo$descriptor == null) {
            return;
        }
        VulkanLayoutTracker.beginRendering(
            this.commandBuffer,
            this.vulkanturbo$descriptor,
            this.vulkanturbo$colorViews,
            this.vulkanturbo$depthView,
            this.vulkanturbo$pipeline(),
            this.uniforms,
            this.vulkanturbo$hasBegunOnce
        );
        this.vulkanturbo$renderingStarted = true;
        this.vulkanturbo$hasBegunOnce = true;
    }

    @Unique
    private VulkanRenderPipeline vulkanturbo$pipeline() {
        return this.pipeline;
    }

    @Override
    public boolean vtContainsAttachment(VulkanGpuTexture texture) {
        if (this.vulkanturbo$depthView != null && this.vulkanturbo$depthView.texture() == texture) {
            return true;
        }
        VulkanGpuTextureView[] colors = this.vulkanturbo$colorViews;
        if (colors != null) {
            for (VulkanGpuTextureView cv : colors) {
                if (cv != null && cv.texture() == texture) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public int vtLayoutForImageView(long vkImageView) {
        return VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    }

    @Override
    public boolean vtOverrideColorViews(VulkanGpuTextureView[] colorViews) {
        if (!this.vulkanturbo$deferEnabled || colorViews == null) {
            return false;
        }
        if (Arrays.equals(this.vulkanturbo$colorViews, colorViews)) {
            return true;
        }
        if (this.vulkanturbo$renderingStarted) {
            KHRDynamicRendering.vkCmdEndRenderingKHR(this.commandBuffer);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long attachmentStages = VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT
                    | VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT
                    | VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT;
                VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
                barrier.get(0)
                    .sType$Default()
                    .srcStageMask(attachmentStages)
                    .srcAccessMask(
                        VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT
                            | VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
                    )
                    .dstStageMask(attachmentStages)
                    .dstAccessMask(
                        VK13.VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT
                            | VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT
                            | VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT
                            | VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
                    );
                VkDependencyInfo depInfo = VkDependencyInfo.calloc(stack)
                    .sType$Default()
                    .pMemoryBarriers(barrier);
                KHRSynchronization2.vkCmdPipelineBarrier2KHR(this.commandBuffer, depInfo);
            }
            this.vulkanturbo$renderingStarted = false;
        }
        this.vulkanturbo$colorViews = colorViews;
        return true;
    }

    @Inject(method = "pushDescriptors", at = @At("HEAD"))
    private void vulkanturbo$prepareSampledTextures(CallbackInfo ci) {
        this.vulkanturbo$samplerCursor = 0;
        if (!this.vulkanturbo$deferEnabled || !VulkanLayoutTracker.isEnabled() || !this.anyDescriptorDirty || this.pipeline == null) {
            return;
        }
        // Single pass computes target layouts for descriptor writes and counts needed transitions.
        List<BindGroupLayout.UniformDescription> descs = this.pipeline.uniforms();
        int samplerIdx = 0;
        int dirtyCount = 0;
        int limit = Math.min(descs.size(), this.uniforms.size());
        for (int i = 0; i < limit; i++) {
            if (descs.get(i).type() == UniformType.COMBINED_IMAGE_SAMPLER) {
                Object val = this.uniforms.get(i);
                if (val instanceof TextureViewAndSampler tvs) {
                    if (samplerIdx >= this.vulkanturbo$samplerLayouts.length) {
                        this.vulkanturbo$samplerLayouts = Arrays.copyOf(this.vulkanturbo$samplerLayouts, samplerIdx * 2);
                    }
                    int targetLayout = VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
                    if (tvs.view().texture() instanceof VulkanGpuTexture tex) {
                        if (vtContainsAttachment(tex)) {
                            targetLayout = VK10.VK_IMAGE_LAYOUT_GENERAL;
                        }
                        if (((VulkanTrackedTexture) tex).vtGetLayout() != targetLayout) {
                            dirtyCount++;
                        }
                    }
                    this.vulkanturbo$samplerLayouts[samplerIdx++] = targetLayout;
                }
            }
        }
        if (dirtyCount > 0) {
            this.vulkanturbo$renderingStarted = VulkanLayoutTracker.transitionSampledTextures(
                this.commandBuffer,
                this.pipeline,
                this.uniforms,
                this.vulkanturbo$samplerLayouts,
                dirtyCount,
                this.vulkanturbo$colorViews,
                this.vulkanturbo$depthView,
                this.vulkanturbo$descriptor,
                this.vulkanturbo$renderingStarted
            );
        }
    }

    @Redirect(
        method = "pushDescriptors",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkDescriptorImageInfo$Buffer;imageLayout(I)Lorg/lwjgl/vulkan/VkDescriptorImageInfo$Buffer;"
        )
    )
    private VkDescriptorImageInfo.Buffer vulkanturbo$useTrackedSampledLayout(
        VkDescriptorImageInfo.Buffer info,
        int layout
    ) {
        if (!this.vulkanturbo$deferEnabled || !VulkanLayoutTracker.isEnabled()) {
            return info.imageLayout(layout);
        }
        int cursor = this.vulkanturbo$samplerCursor++;
        int tracked = cursor < this.vulkanturbo$samplerLayouts.length
            ? this.vulkanturbo$samplerLayouts[cursor]
            : VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        return info.imageLayout(tracked);
    }

    @Inject(
        method = {
            "drawIndexed",
            "multiDrawIndexed(Ljava/nio/IntBuffer;III)V",
            "multiDrawIndexed(Lorg/lwjgl/PointerBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V",
            "drawIndexedIndirect",
            "draw",
            "multiDraw(Ljava/nio/IntBuffer;III)V",
            "multiDraw(Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V",
            "drawIndirect"
        },
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanRenderPass;pushDescriptors()V",
            shift = At.Shift.AFTER
        )
    )
    private void vulkanturbo$beginDeferredRenderingBeforeDraw(CallbackInfo ci) {
        vtEnsureRenderingStarted();
    }
}
