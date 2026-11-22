package dev.vulkanturbo.mixin.profiler;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import dev.vulkanturbo.core.FrameProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanCommandEncoder.class)
public class VulkanEncoderProfilerMixin {
    @Inject(method = "createRenderPass", at = @At("HEAD"))
    private void vulkanturbo$onCreatePass(CallbackInfoReturnable<?> cir) {
        if (FrameProfiler.isEnabled()) {
            FrameProfiler.countPass();
        }
    }

    @Inject(method = "memoryBarrier(Lorg/lwjgl/vulkan/VkCommandBuffer;Lorg/lwjgl/system/MemoryStack;)V", at = @At("HEAD"))
    private static void vulkanturbo$onMemoryBarrier(CallbackInfo ci) {
        if (FrameProfiler.isEnabled()) {
            FrameProfiler.countBarrier();
        }
    }

    @Inject(method = "writeToBuffer", at = @At("HEAD"))
    private void vulkanturbo$onWriteToBuffer(CallbackInfo ci) {
        if (FrameProfiler.isEnabled()) {
            FrameProfiler.countWriteBuffer();
        }
    }

    @Inject(method = "submit", at = @At("HEAD"))
    private void vulkanturbo$beginSubmit(CallbackInfo ci) {
        if (FrameProfiler.isEnabled()) {
            FrameProfiler.beginSubmit();
        }
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void vulkanturbo$endSubmit(CallbackInfo ci) {
        if (FrameProfiler.isEnabled()) {
            FrameProfiler.endSubmit();
        }
    }
}
