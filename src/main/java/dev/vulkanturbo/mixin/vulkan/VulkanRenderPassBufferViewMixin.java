package dev.vulkanturbo.mixin.vulkan;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.VulkanBufferViewCache;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkBufferViewCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.LongBuffer;

/**
 * Reuses `VkBufferView` handles for `TEXEL_BUFFER` descriptors instead of allocating and destroying
 * a fresh driver view object on every draw call.
 */
@Mixin(VulkanRenderPass.class)
public class VulkanRenderPassBufferViewMixin {
    @Shadow
    @Final
    private VulkanCommandEncoder encoder;

    @Unique
    private boolean vulkanturbo$lastBufferViewCached;

    @Redirect(
        method = "pushDescriptors",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCreateBufferView(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkBufferViewCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"
        )
    )
    private int vulkanturbo$acquireBufferView(
        VkDevice vkDevice,
        VkBufferViewCreateInfo createInfo,
        VkAllocationCallbacks allocator,
        LongBuffer pView
    ) {
        if (!VulkanTurbo.bufferViewCacheEnabled) {
            vulkanturbo$lastBufferViewCached = false;
            return VK12.vkCreateBufferView(vkDevice, createInfo, allocator, pView);
        }
        int res = VulkanBufferViewCache.acquireView(vkDevice, createInfo, allocator, pView, this.encoder);
        vulkanturbo$lastBufferViewCached = (res == org.lwjgl.vulkan.VK10.VK_SUCCESS);
        return res;
    }

    @Redirect(
        method = "pushDescriptors",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;queueForDestroy(Lcom/mojang/renderpearl/backend/vulkan/Destroyable;)V"
        )
    )
    private void vulkanturbo$skipQueuedViewDestroy(VulkanCommandEncoder encoder, Destroyable destroyable) {
        if (vulkanturbo$lastBufferViewCached) {
            vulkanturbo$lastBufferViewCached = false;
            return;
        }
        encoder.queueForDestroy(destroyable);
    }
}
