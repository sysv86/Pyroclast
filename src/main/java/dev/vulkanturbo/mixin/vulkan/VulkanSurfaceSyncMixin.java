package dev.vulkanturbo.mixin.vulkan;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import dev.vulkanturbo.core.FrameProfiler;
import dev.vulkanturbo.core.VulkanSyncOptimizer;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanGpuSurface.class)
public class VulkanSurfaceSyncMixin {
    @Inject(
        method = "blitFromTexture",
        slice = @Slice(
            to = @At(
                value = "INVOKE",
                target = "Lorg/lwjgl/vulkan/VK12;vkCmdBlitImage(Lorg/lwjgl/vulkan/VkCommandBuffer;JIJILorg/lwjgl/vulkan/VkImageBlit$Buffer;I)V"
            )
        ),
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/KHRSynchronization2;vkCmdPipelineBarrier2KHR(Lorg/lwjgl/vulkan/VkCommandBuffer;Lorg/lwjgl/vulkan/VkDependencyInfo;)V"
        )
    )
    private void vulkanturbo$countPreBlitBarrier(CallbackInfo ci) {
        if (FrameProfiler.isEnabled()) {
            FrameProfiler.countBarrier();
        }
    }

    @WrapOperation(
        method = "blitFromTexture",
        slice = @Slice(
            from = @At(
                value = "INVOKE",
                target = "Lorg/lwjgl/vulkan/VK12;vkCmdBlitImage(Lorg/lwjgl/vulkan/VkCommandBuffer;JIJILorg/lwjgl/vulkan/VkImageBlit$Buffer;I)V"
            )
        ),
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/KHRSynchronization2;vkCmdPipelineBarrier2KHR(Lorg/lwjgl/vulkan/VkCommandBuffer;Lorg/lwjgl/vulkan/VkDependencyInfo;)V"
        )
    )
    private void vulkanturbo$narrowPresentBarrier(
        VkCommandBuffer cmd,
        VkDependencyInfo depInfo,
        Operation<Void> original
    ) {
        if (FrameProfiler.isEnabled()) {
            FrameProfiler.countBarrier();
        }
        if (VulkanSyncOptimizer.isEnabled()) {
            VulkanSyncOptimizer.narrowSwapchainPresentBarrier(cmd, depInfo);
        } else {
            original.call(cmd, depInfo);
        }
    }

    @ModifyArg(
        method = "blitFromTexture",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;waitSemaphore(JJJ)V"
        ),
        index = 2
    )
    private long vulkanturbo$narrowAcquireWaitStage(long stageMask) {
        return VulkanSyncOptimizer.narrowAcquireWaitStage(stageMask);
    }
}
