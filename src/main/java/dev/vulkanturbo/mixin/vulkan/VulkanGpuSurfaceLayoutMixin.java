package dev.vulkanturbo.mixin.vulkan;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import dev.vulkanturbo.api.shader.SwapchainConfigOverride;
import dev.vulkanturbo.api.shader.TurboShadersBridge;
import dev.vulkanturbo.core.VulkanLayoutTracker;
import it.unimi.dsi.fastutil.longs.LongList;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

/**
 * Integrates `VulkanGpuSurface` with `VulkanLayoutTracker` and `TurboShadersBridge`:
 * 1. Customizes `VkSwapchainCreateInfoKHR` for HDR formats/color-spaces and compute `STORAGE_BIT`.
 * 2. Notifies `TurboShadersBridge.onSwapchainCreated` with all swapchain image handles.
 * 3. Intercepts `vkAcquireNextImageKHR` and `vkQueuePresentKHR` when FrameGen / `PresentThread` is active.
 * 4. Transitions the source offscreen texture to `TRANSFER_SRC_OPTIMAL` before `vkCmdBlitImage`.
 */
@Mixin(VulkanGpuSurface.class)
public class VulkanGpuSurfaceLayoutMixin {
    @Shadow
    @Final
    private VulkanDevice device;

    @Shadow
    @Final
    private long surface;

    @Shadow
    private long swapchain;

    @Shadow
    private int swapchainWidth;

    @Shadow
    private int swapchainHeight;

    @Shadow
    @Final
    private int swapchainImageFormat;

    @Shadow
    @Final
    private LongList swapchainImages;

    @Shadow
    private int currentImageIndex;

    @Unique
    private VulkanGpuTexture vulkanturbo$blitSrcTex;

    @Unique
    private int vulkanturbo$activeFormat;

    @Unique
    private int vulkanturbo$activeColorSpace;

    @Redirect(
        method = "configure",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkCreateSwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"
        )
    )
    private int vulkanturbo$createSwapchainWithShaderOverride(
        VkDevice vkDevice,
        VkSwapchainCreateInfoKHR createInfo,
        VkAllocationCallbacks allocator,
        LongBuffer pSwapchain
    ) {
        int fmt = createInfo.imageFormat();
        int cs = createInfo.imageColorSpace();
        int usage = createInfo.imageUsage();
        int mode = createInfo.presentMode();
        int w = createInfo.imageExtent().width();
        int h = createInfo.imageExtent().height();

        SwapchainConfigOverride override = TurboShadersBridge.onConfigureSwapchain(
            this.surface, w, h, fmt, cs, usage, mode
        );
        if (override != null) {
            fmt = override.getImageFormat();
            cs = override.getColorSpace();
            usage |= override.getExtraUsageFlags();
            mode = override.getPresentMode();
            createInfo.imageFormat(fmt);
            createInfo.imageColorSpace(cs);
            createInfo.imageUsage(usage);
            createInfo.presentMode(mode);
        }
        this.vulkanturbo$activeFormat = fmt;
        this.vulkanturbo$activeColorSpace = cs;
        TurboShadersBridge.swapchainOverrideActive = (cs != 0);
        return KHRSwapchain.vkCreateSwapchainKHR(vkDevice, createInfo, allocator, pSwapchain);
    }

    @Inject(method = "configure", at = @At("RETURN"))
    private void vulkanturbo$notifySwapchainCreated(GpuSurface.Configuration config, CallbackInfo ci) {
        if (this.swapchain != 0L && !this.swapchainImages.isEmpty()) {
            long[] imgs = this.swapchainImages.toLongArray();
            int fmt = this.vulkanturbo$activeFormat != 0 ? this.vulkanturbo$activeFormat : this.swapchainImageFormat;
            TurboShadersBridge.onSwapchainCreated(
                this.swapchain,
                imgs,
                fmt,
                this.vulkanturbo$activeColorSpace,
                this.swapchainWidth,
                this.swapchainHeight
            );
        }
    }

    @Redirect(
        method = "acquireNextTexture",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkAcquireNextImageKHR(Lorg/lwjgl/vulkan/VkDevice;JJJJLjava/nio/IntBuffer;)I"
        )
    )
    private int vulkanturbo$interceptAcquireNextImage(
        VkDevice vkDevice,
        long swapchainHandle,
        long timeout,
        long semaphore,
        long fence,
        IntBuffer pImageIndex
    ) {
        if (TurboShadersBridge.active) {
            int interceptedIdx = TurboShadersBridge.interceptAcquireNextImage(
                swapchainHandle, timeout, semaphore, fence
            );
            if (interceptedIdx >= 0) {
                pImageIndex.put(0, interceptedIdx);
                return VK10.VK_SUCCESS;
            }
        }
        return KHRSwapchain.vkAcquireNextImageKHR(vkDevice, swapchainHandle, timeout, semaphore, fence, pImageIndex);
    }

    @Inject(method = "blitFromTexture", at = @At("HEAD"))
    private void vulkanturbo$captureBlitSource(CommandEncoderBackend encoder, GpuTextureView view, CallbackInfo ci) {
        this.vulkanturbo$blitSrcTex = view.texture() instanceof VulkanGpuTexture vkTex
            ? vkTex
            : null;
    }

    @Redirect(
        method = "blitFromTexture",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCmdBlitImage(Lorg/lwjgl/vulkan/VkCommandBuffer;JIJILorg/lwjgl/vulkan/VkImageBlit$Buffer;I)V"
        )
    )
    private void vulkanturbo$blitWithTrackedSourceLayout(
        VkCommandBuffer cmd,
        long srcImage,
        int srcLayout,
        long dstImage,
        int dstLayout,
        VkImageBlit.Buffer regions,
        int filter
    ) {
        VulkanGpuTexture srcTex = this.vulkanturbo$blitSrcTex;
        this.vulkanturbo$blitSrcTex = null;
        int actualSrcLayout = srcLayout;
        if (srcTex != null && VulkanLayoutTracker.isEnabled()) {
            actualSrcLayout = VulkanLayoutTracker.transition(cmd, srcTex, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        }
        // Invoke interceptSurfaceBlit even in main menu when an HDR swapchain override is active
        // so sRGB UI is converted cleanly into linear scRGB / HDR10 without blow-out.
        if ((TurboShadersBridge.active || this.vulkanturbo$activeColorSpace != 0) &&
            TurboShadersBridge.interceptSurfaceBlit(
                cmd, srcTex, srcImage, actualSrcLayout, dstImage, dstLayout, regions, filter)) {
            if (srcTex != null) {
                TurboShadersBridge.notifyExternalImageLayout(srcTex, VK10.VK_IMAGE_LAYOUT_GENERAL);
            }
            return;
        }
        VK12.vkCmdBlitImage(cmd, srcImage, actualSrcLayout, dstImage, dstLayout, regions, filter);
    }

    @Redirect(
        method = "present",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"
        )
    )
    private int vulkanturbo$interceptQueuePresent(VkQueue queue, VkPresentInfoKHR presentInfo) throws SurfaceException {
        if (TurboShadersBridge.active) {
            LongBuffer pSwapchains = presentInfo.pSwapchains();
            IntBuffer pIndices = presentInfo.pImageIndices();
            LongBuffer pWaitSems = presentInfo.pWaitSemaphores();
            long sc = (pSwapchains != null && pSwapchains.remaining() > 0) ? pSwapchains.get(0) : this.swapchain;
            int idx = (pIndices != null && pIndices.remaining() > 0) ? pIndices.get(0) : this.currentImageIndex;
            long waitSem = (pWaitSems != null && pWaitSems.remaining() > 0) ? pWaitSems.get(0) : 0L;
            if (TurboShadersBridge.interceptQueuePresent(queue.address(), sc, idx, waitSem)) {
                return VK10.VK_SUCCESS;
            }
        }
        return KHRSwapchain.vkQueuePresentKHR(queue, presentInfo);
    }
}
