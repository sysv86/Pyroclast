package dev.vulkanturbo.mixin.vulkan;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import dev.vulkanturbo.core.VulkanLayoutTracker;
import dev.vulkanturbo.core.VulkanTrackedRenderPass;
import dev.vulkanturbo.core.VulkanTrackedTexture;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkClearDepthStencilValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Defers `vkCmdBeginRenderingKHR` until sampled textures for the pass have been transitioned to
 * `SHADER_READ_ONLY_OPTIMAL`, and transitions transfer/clear operations into `TRANSFER_SRC_OPTIMAL`
 * and `TRANSFER_DST_OPTIMAL`.
 */
@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanCommandEncoderLayoutMixin {
    @Shadow
    public abstract VkCommandBuffer commandBuffer();

    @Shadow
    private VulkanRenderPass currentRenderPass;

    @Shadow
    private void memoryBarrier(MemoryStack stack) {}

    @Unique
    private static final VulkanGpuTextureView[] EMPTY_COLOR_VIEWS = new VulkanGpuTextureView[0];

    @Unique
    private RenderPassDescriptor vulkanturbo$pendingDescriptor;
    @Unique
    private VulkanGpuTextureView[] vulkanturbo$pendingColorViews;
    @Unique
    private VulkanGpuTextureView vulkanturbo$pendingDepthView;
    @Unique
    private boolean vulkanturbo$deferredPassPending;
    @Unique
    private boolean vulkanturbo$elidedCurrentPass;

    @Unique
    private VulkanGpuTexture vulkanturbo$copySrcTex;
    @Unique
    private VulkanGpuTexture vulkanturbo$copyDstTex;

    @Inject(method = "createRenderPass", at = @At("HEAD"))
    private void vulkanturbo$captureDescriptor(
        RenderPassDescriptor descriptor,
        CallbackInfoReturnable<?> cir
    ) {
        this.vulkanturbo$deferredPassPending = false;
        if (!VulkanLayoutTracker.shouldDeferRenderPass()) {
            this.vulkanturbo$pendingDescriptor = null;
            return;
        }
        var colorAtts = descriptor.colorAttachments();
        VulkanGpuTextureView[] colorViews = colorAtts.isEmpty()
            ? EMPTY_COLOR_VIEWS
            : new VulkanGpuTextureView[colorAtts.size()];
        for (int i = 0; i < colorViews.length; i++) {
            RenderPassDescriptor.Attachment<?> att = colorAtts.get(i);
            colorViews[i] = att != null ? (VulkanGpuTextureView) att.textureView() : null;
        }
        RenderPassDescriptor.Attachment<?> depthAtt = descriptor.depthAttachment();
        this.vulkanturbo$pendingDescriptor = descriptor;
        this.vulkanturbo$pendingColorViews = colorViews;
        this.vulkanturbo$pendingDepthView = depthAtt != null ? (VulkanGpuTextureView) depthAtt.textureView() : null;
    }

    @Redirect(
        method = "createRenderPass",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/KHRDynamicRendering;vkCmdBeginRenderingKHR(Lorg/lwjgl/vulkan/VkCommandBuffer;Lorg/lwjgl/vulkan/VkRenderingInfo;)V"
        )
    )
    private void vulkanturbo$deferBeginRendering(VkCommandBuffer cmd, VkRenderingInfo renderingInfo) {
        if (VulkanLayoutTracker.shouldDeferRenderPass() && this.vulkanturbo$pendingDescriptor != null) {
            this.vulkanturbo$deferredPassPending = true;
            return;
        }
        KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, renderingInfo);
    }

    @Inject(method = "createRenderPass", at = @At("RETURN"))
    private void vulkanturbo$attachDeferredPass(
        RenderPassDescriptor descriptor,
        CallbackInfoReturnable<?> cir
    ) {
        if (this.vulkanturbo$deferredPassPending && this.currentRenderPass != null) {
            ((VulkanTrackedRenderPass) this.currentRenderPass).vtSetPendingPass(
                this.vulkanturbo$pendingDescriptor,
                this.vulkanturbo$pendingColorViews,
                this.vulkanturbo$pendingDepthView
            );
        }
        this.vulkanturbo$pendingDescriptor = null;
        this.vulkanturbo$pendingColorViews = null;
        this.vulkanturbo$pendingDepthView = null;
        this.vulkanturbo$deferredPassPending = false;
    }

    @Inject(
        method = "clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/textures/GpuTexture;DIIIII)V",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCmdClearAttachments(Lorg/lwjgl/vulkan/VkCommandBuffer;Lorg/lwjgl/vulkan/VkClearAttachment$Buffer;Lorg/lwjgl/vulkan/VkClearRect$Buffer;)V"
        )
    )
    private void vulkanturbo$ensureRenderingBeforeClearAttachments(CallbackInfo ci) {
        if (this.currentRenderPass != null) {
            ((VulkanTrackedRenderPass) this.currentRenderPass).vtEnsureRenderingStarted();
        }
    }

    @Inject(method = "submitRenderPass", at = @At("HEAD"))
    private void vulkanturbo$ensureRenderingBeforeEnd(CallbackInfo ci) {
        this.vulkanturbo$elidedCurrentPass = false;
        if (this.currentRenderPass != null) {
            VulkanTrackedRenderPass tracked = (VulkanTrackedRenderPass) this.currentRenderPass;
            if (tracked.vtIsDeferredEnabled() && !tracked.vtIsRenderingStarted() && !tracked.vtHasClear()) {
                this.vulkanturbo$elidedCurrentPass = true;
            } else {
                tracked.vtEnsureRenderingStarted();
            }
        }
    }

    @Redirect(
        method = "submitRenderPass",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/KHRDynamicRendering;vkCmdEndRenderingKHR(Lorg/lwjgl/vulkan/VkCommandBuffer;)V"
        )
    )
    private void vulkanturbo$elideEmptyEndRendering(VkCommandBuffer cmd) {
        if (!this.vulkanturbo$elidedCurrentPass) {
            KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
        }
    }

    @Redirect(
        method = "submitRenderPass",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V"
        )
    )
    private void vulkanturbo$elideEmptyPassBarrier(VulkanCommandEncoder self, MemoryStack stack) {
        boolean elided = this.vulkanturbo$elidedCurrentPass;
        this.vulkanturbo$elidedCurrentPass = false;
        if (!elided) {
            ((VulkanCommandEncoderLayoutMixin) (Object) self).memoryBarrier(stack);
        }
    }

    @Inject(method = "submitRenderPass", at = @At("RETURN"))
    private void vulkanturbo$resetElidedFlag(CallbackInfo ci) {
        this.vulkanturbo$elidedCurrentPass = false;
    }

    @Inject(method = "clearColorTextureUnsynced", at = @At("HEAD"))
    private void vulkanturbo$prepClearColor(MemoryStack stack, GpuTexture texture, Vector4fc color, CallbackInfo ci) {
        if (VulkanLayoutTracker.isEnabled() && texture instanceof VulkanGpuTexture vkTex) {
            VulkanLayoutTracker.transition(this.commandBuffer(), vkTex, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        }
    }

    @Redirect(
        method = "clearColorTextureUnsynced",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCmdClearColorImage(Lorg/lwjgl/vulkan/VkCommandBuffer;JILorg/lwjgl/vulkan/VkClearColorValue;Lorg/lwjgl/vulkan/VkImageSubresourceRange;)V"
        )
    )
    private void vulkanturbo$clearColorImageWithLayout(
        VkCommandBuffer cmd,
        long image,
        int layout,
        VkClearColorValue color,
        VkImageSubresourceRange range
    ) {
        int actual = VulkanLayoutTracker.isEnabled() ? VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL : layout;
        VK12.vkCmdClearColorImage(cmd, image, actual, color, range);
    }

    @Inject(method = "clearDepthTextureUnsynced", at = @At("HEAD"))
    private void vulkanturbo$prepClearDepth(MemoryStack stack, GpuTexture texture, double depth, CallbackInfo ci) {
        if (VulkanLayoutTracker.isEnabled() && texture instanceof VulkanGpuTexture vkTex) {
            VulkanLayoutTracker.transition(this.commandBuffer(), vkTex, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        }
    }

    @Redirect(
        method = "clearDepthTextureUnsynced",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCmdClearDepthStencilImage(Lorg/lwjgl/vulkan/VkCommandBuffer;JILorg/lwjgl/vulkan/VkClearDepthStencilValue;Lorg/lwjgl/vulkan/VkImageSubresourceRange;)V"
        )
    )
    private void vulkanturbo$clearDepthImageWithLayout(
        VkCommandBuffer cmd,
        long image,
        int layout,
        VkClearDepthStencilValue value,
        VkImageSubresourceRange range
    ) {
        int actual = VulkanLayoutTracker.isEnabled() ? VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL : layout;
        VK12.vkCmdClearDepthStencilImage(cmd, image, actual, value, range);
    }

    @Inject(method = "writeToTexture", at = @At("HEAD"))
    private void vulkanturbo$prepWriteToTexture(
        GpuTexture texture,
        ByteBuffer data,
        int mip,
        int depth,
        int x,
        int y,
        int w,
        int h,
        CallbackInfo ci
    ) {
        if (VulkanLayoutTracker.isEnabled() && texture instanceof VulkanGpuTexture vkTex) {
            VulkanLayoutTracker.transition(this.commandBuffer(), vkTex, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        }
    }

    @Inject(method = "copyBufferToTexture", at = @At("HEAD"))
    private void vulkanturbo$prepCopyBufferToTexture(
        GpuBufferSlice src,
        int offsetX,
        int offsetY,
        int rowLen,
        int imgHeight,
        GpuTexture dst,
        int dstX,
        int dstY,
        int w,
        int h,
        int mip,
        int layer,
        CallbackInfo ci
    ) {
        if (VulkanLayoutTracker.isEnabled() && dst instanceof VulkanGpuTexture vkTex) {
            VulkanLayoutTracker.transition(this.commandBuffer(), vkTex, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        }
    }

    @Redirect(
        method = {"writeToTexture", "copyBufferToTexture"},
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCmdCopyBufferToImage(Lorg/lwjgl/vulkan/VkCommandBuffer;JJILorg/lwjgl/vulkan/VkBufferImageCopy$Buffer;)V"
        )
    )
    private void vulkanturbo$copyBufferToImageWithLayout(
        VkCommandBuffer cmd,
        long srcBuf,
        long dstImg,
        int dstLayout,
        VkBufferImageCopy.Buffer regions
    ) {
        int actual = VulkanLayoutTracker.isEnabled() ? VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL : dstLayout;
        VK12.vkCmdCopyBufferToImage(cmd, srcBuf, dstImg, actual, regions);
    }

    @Inject(
        method = "copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V",
        at = @At("HEAD")
    )
    private void vulkanturbo$prepCopyTextureToBuffer(
        GpuTexture src,
        GpuBuffer dst,
        long offset,
        Runnable callback,
        int mip,
        int x,
        int y,
        int w,
        int h,
        CallbackInfo ci
    ) {
        if (VulkanLayoutTracker.isEnabled() && src instanceof VulkanGpuTexture vkTex) {
            VulkanLayoutTracker.transition(this.commandBuffer(), vkTex, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        }
    }

    @Redirect(
        method = "copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCmdCopyImageToBuffer(Lorg/lwjgl/vulkan/VkCommandBuffer;JIJLorg/lwjgl/vulkan/VkBufferImageCopy$Buffer;)V"
        )
    )
    private void vulkanturbo$copyImageToBufferWithLayout(
        VkCommandBuffer cmd,
        long srcImg,
        int srcLayout,
        long dstBuf,
        VkBufferImageCopy.Buffer regions
    ) {
        int actual = VulkanLayoutTracker.isEnabled() ? VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL : srcLayout;
        VK12.vkCmdCopyImageToBuffer(cmd, srcImg, actual, dstBuf, regions);
    }

    @Inject(method = "copyTextureToTexture", at = @At("HEAD"))
    private void vulkanturbo$prepCopyTextureToTexture(
        GpuTexture src,
        GpuTexture dst,
        int mip,
        int dstX,
        int dstY,
        int srcX,
        int srcY,
        int w,
        int h,
        CallbackInfo ci
    ) {
        if (VulkanLayoutTracker.isEnabled() && src instanceof VulkanGpuTexture srcTex && dst instanceof VulkanGpuTexture dstTex) {
            this.vulkanturbo$copySrcTex = srcTex;
            this.vulkanturbo$copyDstTex = dstTex;
            VulkanLayoutTracker.transitionTransferPair(this.commandBuffer(), srcTex, dstTex);
        } else {
            this.vulkanturbo$copySrcTex = null;
            this.vulkanturbo$copyDstTex = null;
        }
    }

    @Redirect(
        method = "copyTextureToTexture",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCmdCopyImage(Lorg/lwjgl/vulkan/VkCommandBuffer;JIJILorg/lwjgl/vulkan/VkImageCopy$Buffer;)V"
        )
    )
    private void vulkanturbo$copyImageWithLayout(
        VkCommandBuffer cmd,
        long srcImg,
        int srcLayout,
        long dstImg,
        int dstLayout,
        VkImageCopy.Buffer regions
    ) {
        int actualSrc = this.vulkanturbo$copySrcTex != null
            ? ((VulkanTrackedTexture) this.vulkanturbo$copySrcTex).vtGetLayout()
            : srcLayout;
        int actualDst = this.vulkanturbo$copyDstTex != null
            ? ((VulkanTrackedTexture) this.vulkanturbo$copyDstTex).vtGetLayout()
            : dstLayout;
        this.vulkanturbo$copySrcTex = null;
        this.vulkanturbo$copyDstTex = null;
        VK12.vkCmdCopyImage(cmd, srcImg, actualSrc, dstImg, actualDst, regions);
    }
}
