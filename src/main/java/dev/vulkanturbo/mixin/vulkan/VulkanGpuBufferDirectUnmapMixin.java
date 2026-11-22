package dev.vulkanturbo.mixin.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import dev.vulkanturbo.core.VulkanPersistentlyMappedBuffer;
import org.lwjgl.util.vma.Vma;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Skips per-frame `vmaUnmapMemory` when the owning `VulkanGpuBuffer.Direct` holds a cached mapping. */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer$Direct$1")
public class VulkanGpuBufferDirectUnmapMixin {
    @Shadow
    @Final
    VulkanGpuBuffer.Direct this$0;

    @Redirect(
        method = "run",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/util/vma/Vma;vmaUnmapMemory(JJ)V"
        )
    )
    private void vulkanturbo$skipUnmapIfPersisted(long allocator, long allocation) {
        if (this.this$0 instanceof VulkanPersistentlyMappedBuffer persisted
            && persisted.vulkanturbo$isPersistentlyMapped()) {
            return;
        }
        Vma.vmaUnmapMemory(allocator, allocation);
    }
}
