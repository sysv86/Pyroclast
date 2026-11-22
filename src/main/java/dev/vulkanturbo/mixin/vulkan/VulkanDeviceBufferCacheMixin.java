package dev.vulkanturbo.mixin.vulkan;

import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.api.shader.TurboShadersBridge;
import dev.vulkanturbo.core.ChunkRenderPrepOptimizer;
import dev.vulkanturbo.core.FrameProfiler;
import dev.vulkanturbo.core.VulkanBufferViewCache;
import dev.vulkanturbo.core.VulkanPipelineCacheManager;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Releases retained VMA indirect buffers and GPU query pools before `VulkanCommandEncoder.destroy()`
 * drains the deferred destroy queue, and destroys cached `VkBufferView` / `VkPipelineCache` handles
 * right before `vkDestroyDevice`.
 */
@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceBufferCacheMixin {
    @Shadow
    @Final
    private VkDevice vkDevice;

    @Shadow
    public abstract DeviceInfo getDeviceInfo();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void vulkanturbo$recordDeviceCapabilities(CallbackInfo ci) {
        VulkanTurbo.classifyVulkanDevice(this.vkDevice.getPhysicalDevice(), this.getDeviceInfo().name());
        if (!TurboShadersBridge.bdaCapabilityResolvedByExtension) {
            // Pyroclast is running without an extension intercepting vkCreateDevice to enable
            // BDA/RT features on the logical VkDevice; assuming support from physical-device
            // extensions alone would risk validation errors.
            TurboShadersBridge.bdaSupported = false;
            TurboShadersBridge.accelStructSupported = false;
            TurboShadersBridge.rayTracingPipelineSupported = false;
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void vulkanturbo$releaseGpuResourcesBeforeEncoderDestroy(CallbackInfo ci) {
        TurboShadersBridge.bdaSupported = false;
        TurboShadersBridge.bdaCapabilityResolvedByExtension = false;
        TurboShadersBridge.accelStructSupported = false;
        TurboShadersBridge.rayTracingPipelineSupported = false;
        TurboShadersBridge.swapchainOverrideActive = false;
        ChunkRenderPrepOptimizer.onDeviceClosing();
        FrameProfiler.onDeviceClosing();
    }

    @Inject(
        method = "close",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkDestroyDevice(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkAllocationCallbacks;)V"
        )
    )
    private void vulkanturbo$flushDeviceCaches(CallbackInfo ci) {
        VulkanBufferViewCache.onDeviceClosing(this.vkDevice);
        VulkanPipelineCacheManager.saveAndDestroy(this.vkDevice);
    }
}
