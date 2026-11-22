package dev.vulkanturbo.mixin.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.api.shader.TurboShadersBridge;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.vulkan.VK12;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Enables `VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT` when creating the VMA allocator for a
 * `VulkanDevice` on Vulkan 1.2+ GPUs with Buffer Device Address support (allowing zero-copy
 * BLAS construction and BDA buffer access).
 */
@Mixin(VulkanBackend.class)
public class VulkanBackendVmaMixin {
    @ModifyArg(
        method = "createVma(Lorg/lwjgl/vulkan/VkDevice;)J",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/util/vma/Vma;vmaCreateAllocator(Lorg/lwjgl/util/vma/VmaAllocatorCreateInfo;Lorg/lwjgl/PointerBuffer;)I"
        ),
        index = 0
    )
    private static VmaAllocatorCreateInfo vulkanturbo$enableVmaBufferDeviceAddress(
        VmaAllocatorCreateInfo createInfo
    ) {
        if (VulkanTurbo.isBackendAllowed()
            && TurboShadersBridge.bdaSupported
            && createInfo.vulkanApiVersion() >= VK12.VK_API_VERSION_1_2) {
            createInfo.flags(createInfo.flags() | Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT);
        }
        return createInfo;
    }
}
