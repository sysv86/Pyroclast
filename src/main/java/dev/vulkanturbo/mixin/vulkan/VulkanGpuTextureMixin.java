package dev.vulkanturbo.mixin.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import dev.vulkanturbo.core.VulkanTrackedTexture;
import org.lwjgl.vulkan.VK10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Tracks each texture's current `VkImageLayout` (initialized to `VK_IMAGE_LAYOUT_GENERAL` on
 * construction by `VulkanGpuTexture.<init>`).
 */
@Mixin(VulkanGpuTexture.class)
public class VulkanGpuTextureMixin implements VulkanTrackedTexture {
    @Unique
    private int vulkanturbo$vkLayout = VK10.VK_IMAGE_LAYOUT_GENERAL;

    @Override
    public int vtGetLayout() {
        return this.vulkanturbo$vkLayout;
    }

    @Override
    public void vtSetLayout(int layout) {
        this.vulkanturbo$vkLayout = layout;
    }
}
