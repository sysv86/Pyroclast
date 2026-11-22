package dev.vulkanturbo.mixin.entity;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.api.shader.TurboShadersBridge;
import dev.vulkanturbo.core.EntityRenderOptimizer;
import java.util.List;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1. Suppresses vanilla entity blob shadows in 1ns when `TurboShaders` real-time shadow mapping is active.
 * 2. Batches each `ShadowPiece` quad (`4 * 36 = 144` bytes) into a single off-heap `BufferBuilder`
 *    reservation (`EntityRenderOptimizer.prepareEntityShadowFast`).
 * 3. Removes the per-vertex `Vector3f` allocation in `ShadowFeatureRenderer.shadowVertex` fallback.
 */
@Mixin(ShadowFeatureRenderer.class)
public class ShadowFeatureRendererMixin {
    @Inject(method = "buildGroup", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$skipBlobShadowsWhenShaderShadowMapActive(
        FeatureFrameContext context,
        List<ShadowFeatureRenderer.Submit> submits,
        CallbackInfo ci
    ) {
        if (TurboShadersBridge.shouldSuppressEntityBlobShadows()) {
            ci.cancel();
        }
    }

    @Inject(method = "prepare", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$prepareEntityShadowFast(
        ShadowFeatureRenderer.Submit submit,
        VertexConsumer builder,
        CallbackInfo ci
    ) {
        if (!VulkanTurbo.fastEntityRenderEnabled) {
            return;
        }
        EntityRenderOptimizer.prepareEntityShadowFast(submit, builder);
        ci.cancel();
    }

    @Inject(method = "shadowVertex", at = @At("HEAD"), cancellable = true)
    private static void vulkanturbo$shadowVertexFast(
        Matrix4fc pose,
        VertexConsumer consumer,
        int color,
        float x,
        float y,
        float z,
        float u,
        float v,
        CallbackInfo ci
    ) {
        if (!VulkanTurbo.fastEntityRenderEnabled) {
            return;
        }
        EntityRenderOptimizer.shadowVertexFast(pose, consumer, color, x, y, z, u, v);
        ci.cancel();
    }
}
