package dev.vulkanturbo.mixin.entity;

import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.EntityRenderOptimizer;
import net.minecraft.client.particle.SingleQuadParticle;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Reuses a thread-local scratch `Quaternionf` in `SingleQuadParticle.extract` instead of
 * allocating a new quaternion for every extracted particle.
 */
@Mixin(SingleQuadParticle.class)
public class SingleQuadParticleMixin {
    @Redirect(
        method = "extract",
        at = @At(value = "NEW", target = "()Lorg/joml/Quaternionf;")
    )
    private Quaternionf vulkanturbo$reuseQuaternion() {
        if (!VulkanTurbo.fastEntityRenderEnabled) {
            return new Quaternionf();
        }
        return EntityRenderOptimizer.scratchQuat().identity();
    }
}
