package dev.vulkanturbo.mixin.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.EntityRenderOptimizer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Detects the active RenderPearl backend once initialized, records the Render thread, and disables the mod on non-Vulkan backends. */
@Mixin(RenderSystem.class)
public class RenderSystemMixin {
    @Inject(method = "initRenderer", at = @At("RETURN"))
    private static void vulkanturbo$detectGraphicsBackend(GpuDevice device, CallbackInfo ci) {
        EntityRenderOptimizer.markRenderThread();
        VulkanTurbo.onRendererInitialized(device);
    }
}
