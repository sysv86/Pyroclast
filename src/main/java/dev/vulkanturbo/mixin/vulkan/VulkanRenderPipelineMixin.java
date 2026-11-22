package dev.vulkanturbo.mixin.vulkan;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline.CreateInfo;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import dev.vulkanturbo.api.shader.TurboShadersBridge;
import dev.vulkanturbo.core.VulkanPipelineCacheManager;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.LongBuffer;

/**
 * Feeds the persistent disk-backed `VkPipelineCache` into `VulkanRenderPipeline.compile` instead
 * of passing `VK_NULL_HANDLE` (`0L`), and appends custom shader `VkDescriptorSetLayout`s (Set 1
 * `WorldUBO`, Set 2 `BindlessTextureTable`) to `VkPipelineLayoutCreateInfo` when `TurboShaders`
 * provides them.
 */
@Mixin(VulkanRenderPipeline.class)
public class VulkanRenderPipelineMixin {
    @WrapOperation(
        method = "compile",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCreatePipelineLayout(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkPipelineLayoutCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"
        )
    )
    private static int vulkanturbo$createPipelineLayoutWithCustomSets(
        VkDevice vkDevice,
        VkPipelineLayoutCreateInfo createInfo,
        VkAllocationCallbacks allocator,
        LongBuffer pPipelineLayout,
        Operation<Integer> original,
        @Local(argsOnly = true) CreateInfo pipelineInfo
    ) {
        String pipelineName = pipelineInfo != null ? pipelineInfo.name() : null;
        if (!TurboShadersBridge.shouldAttachCustomDescriptorSets(pipelineName)) {
            return original.call(vkDevice, createInfo, allocator, pPipelineLayout);
        }
        long[] extraLayouts = TurboShadersBridge.getCustomDescriptorSetLayouts(vkDevice.address());
        LongBuffer baseLayouts = createInfo.pSetLayouts();
        if (extraLayouts != null && extraLayouts.length > 0 && baseLayouts != null && baseLayouts.remaining() > 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                int baseCount = baseLayouts.remaining();
                LongBuffer combined = stack.mallocLong(baseCount + extraLayouts.length);
                for (int i = 0; i < baseCount; i++) {
                    combined.put(i, baseLayouts.get(baseLayouts.position() + i));
                }
                for (int i = 0; i < extraLayouts.length; i++) {
                    combined.put(baseCount + i, extraLayouts[i]);
                }
                createInfo.pSetLayouts(combined);
                try {
                    int result = original.call(vkDevice, createInfo, allocator, pPipelineLayout);
                    if (result >= 0) {
                        long handle = pPipelineLayout.get(pPipelineLayout.position());
                        TurboShadersBridge.registerPipelineLayoutWithCustomSets(handle, baseCount);
                    }
                    return result;
                } finally {
                    createInfo.pSetLayouts(baseLayouts);
                }
            }
        }
        return original.call(vkDevice, createInfo, allocator, pPipelineLayout);
    }

    @Redirect(
        method = "compile",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCreateGraphicsPipelines(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkGraphicsPipelineCreateInfo$Buffer;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"
        )
    )
    private static int vulkanturbo$createGraphicsPipelinesCached(
        VkDevice vkDevice,
        long ignoredCache,
        VkGraphicsPipelineCreateInfo.Buffer createInfos,
        VkAllocationCallbacks allocator,
        LongBuffer pPipelines
    ) {
        long cache = VulkanPipelineCacheManager.isEnabled()
            ? VulkanPipelineCacheManager.getOrCreateCache(vkDevice)
            : ignoredCache;
        int res = VK12.vkCreateGraphicsPipelines(vkDevice, cache, createInfos, allocator, pPipelines);
        if (cache != 0L) {
            VulkanPipelineCacheManager.notifyCompiled(vkDevice);
        }
        return res;
    }

    /**
     * Removes the pipeline layout from the custom-set registry when Mojang destroys it, so stale
     * handles can never trigger custom descriptor binding on a recreated layout.
     */
    @Redirect(
        method = "destroy",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkDestroyPipelineLayout(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkAllocationCallbacks;)V"
        )
    )
    private static void vulkanturbo$unregisterPipelineLayoutOnDestroy(
        VkDevice vkDevice,
        long pipelineLayout,
        VkAllocationCallbacks allocator
    ) {
        TurboShadersBridge.unregisterPipelineLayoutWithCustomSets(pipelineLayout);
        VK12.vkDestroyPipelineLayout(vkDevice, pipelineLayout, allocator);
    }
}
