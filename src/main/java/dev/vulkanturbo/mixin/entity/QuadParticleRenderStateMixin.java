package dev.vulkanturbo.mixin.entity;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.EntityRenderOptimizer;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Eliminates `Quaternionf` + 4x `Vector3f` allocations per quad particle by computing the rotated
 * quad axes inline in scalar arithmetic.
 */
@Mixin(QuadParticleRenderState.class)
public class QuadParticleRenderStateMixin {
    @Inject(method = "renderRotatedQuad", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$fastRotatedQuad(
        VertexConsumer consumer,
        float x,
        float y,
        float z,
        float qx,
        float qy,
        float qz,
        float qw,
        float scale,
        float u0,
        float u1,
        float v0,
        float v1,
        int packedColor,
        int packedLight,
        CallbackInfo ci
    ) {
        if (!VulkanTurbo.fastEntityRenderEnabled) {
            return;
        }
        EntityRenderOptimizer.renderRotatedQuadFast(
            consumer, x, y, z, qx, qy, qz, qw, scale, u0, u1, v0, v1, packedColor, packedLight
        );
        ci.cancel();
    }
}
