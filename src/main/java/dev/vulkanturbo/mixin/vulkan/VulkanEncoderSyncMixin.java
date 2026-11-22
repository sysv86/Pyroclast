package dev.vulkanturbo.mixin.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import dev.vulkanturbo.core.VulkanSyncOptimizer;
import dev.vulkanturbo.core.VulkanTrackedRenderPass;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanEncoderSyncMixin {
    @Shadow
    public abstract VkCommandBuffer commandBuffer();

    @Shadow
    private VulkanRenderPass currentRenderPass;

    @Unique
    private final VulkanSyncOptimizer.EncoderSyncState vulkanturbo$syncState =
        VulkanSyncOptimizer.createEncoderState();

    @Inject(method = "submitRenderPass", at = @At("HEAD"))
    private void vulkanturbo$markRenderPassBarrier(CallbackInfo ci) {
        if (this.currentRenderPass instanceof VulkanTrackedRenderPass tracked
            && tracked.vtIsDeferredEnabled()
            && !tracked.vtIsRenderingStarted()
            && !tracked.vtHasClear()) {
            // Empty render pass will be elided completely; do not overwrite barrierKind.
            return;
        }
        String label = null;
        if (this.currentRenderPass != null) {
            Supplier<String> sup = this.currentRenderPass.getLabel();
            if (sup != null) {
                try {
                    label = sup.get();
                } catch (Throwable ignored) {
                }
            }
        }
        int colorCount = this.currentRenderPass instanceof VulkanTrackedRenderPass trackedPass
            ? Math.max(1, trackedPass.vtColorAttachmentCount())
            : 1;
        this.vulkanturbo$syncState.onSubmitRenderPass(label, colorCount, true);
    }

    @Inject(method = {"writeToBuffer", "copyToBuffer"}, at = @At("HEAD"))
    private void vulkanturbo$markBufferTransfer(CallbackInfo ci) {
        this.vulkanturbo$syncState.onBufferTransfer();
    }

    @Inject(
        method = {
            "clearColorTexture",
            "clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V",
            "clearDepthTexture",
            "writeToTexture",
            "copyBufferToTexture",
            "copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V",
            "copyTextureToTexture"
        },
        at = @At("HEAD")
    )
    private void vulkanturbo$markImageTransfer(CallbackInfo ci) {
        this.vulkanturbo$syncState.onImageTransfer();
    }

    @Inject(method = "memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$emitScopedMemoryBarrier(MemoryStack stack, CallbackInfo ci) {
        if (this.vulkanturbo$syncState.onMemoryBarrier(this.commandBuffer(), stack)) {
            ci.cancel();
        }
    }

    @Inject(method = {"submit", "execute"}, at = @At("HEAD"))
    private void vulkanturbo$resetSyncTracking(CallbackInfo ci) {
        this.vulkanturbo$syncState.onReset();
    }
}
