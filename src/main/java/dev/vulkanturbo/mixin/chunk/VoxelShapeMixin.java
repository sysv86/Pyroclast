package dev.vulkanturbo.mixin.chunk;

import dev.vulkanturbo.VulkanTurbo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caches the immutable `AABB` returned by `VoxelShape.bounds()` instead of recomputing
 * `CubePointRange` bounds repeatedly (notably in `ShadowFeatureRenderer.prepare`).
 */
@Mixin(VoxelShape.class)
public abstract class VoxelShapeMixin {
    @Unique
    private volatile AABB vulkanturbo$cachedBounds;

    @Inject(method = "bounds", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$getCachedBounds(CallbackInfoReturnable<AABB> cir) {
        if (!VulkanTurbo.cacheVoxelBoundsEnabled) {
            return;
        }
        AABB cached = this.vulkanturbo$cachedBounds;
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "bounds", at = @At("RETURN"))
    private void vulkanturbo$storeCachedBounds(CallbackInfoReturnable<AABB> cir) {
        if (VulkanTurbo.cacheVoxelBoundsEnabled && this.vulkanturbo$cachedBounds == null) {
            this.vulkanturbo$cachedBounds = cir.getReturnValue();
        }
    }
}
