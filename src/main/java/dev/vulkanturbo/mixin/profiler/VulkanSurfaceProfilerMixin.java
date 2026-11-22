package dev.vulkanturbo.mixin.profiler;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import dev.vulkanturbo.core.FrameProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanGpuSurface.class)
public class VulkanSurfaceProfilerMixin {
    @Inject(method = "acquireNextTexture", at = @At("HEAD"))
    private void vulkanturbo$beginAcquire(CallbackInfo ci) {
        if (FrameProfiler.isEnabled()) {
            FrameProfiler.beginAcquire();
        }
    }

    @Inject(method = "acquireNextTexture", at = @At("RETURN"))
    private void vulkanturbo$endAcquire(CallbackInfo ci) {
        if (FrameProfiler.isEnabled()) {
            FrameProfiler.endAcquire();
        }
    }

    @Inject(method = "blitFromTexture", at = @At("HEAD"))
    private void vulkanturbo$beginBlit(CallbackInfo ci) {
        if (FrameProfiler.isEnabled()) {
            FrameProfiler.beginBlit();
        }
    }

    @Inject(method = "blitFromTexture", at = @At("RETURN"))
    private void vulkanturbo$endBlit(CallbackInfo ci) {
        if (FrameProfiler.isEnabled()) {
            FrameProfiler.endBlit();
        }
    }

    @Inject(method = "present", at = @At("HEAD"))
    private void vulkanturbo$beginPresent(CallbackInfo ci) {
        if (FrameProfiler.isEnabled()) {
            FrameProfiler.beginPresent();
        }
    }

    @Inject(method = "present", at = @At("RETURN"))
    private void vulkanturbo$endPresent(CallbackInfo ci) {
        if (FrameProfiler.isEnabled()) {
            FrameProfiler.endPresent();
        }
    }
}
