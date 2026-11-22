package dev.vulkanturbo.mixin.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.EntityRenderOptimizer;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Eliminates the per-cube `Vector3f` allocation and performs scalar affine transforms in local
 * registers for `ModelPart.Cube.compile`.
 */
@Mixin(ModelPart.Cube.class)
public class ModelPartCubeMixin {
    @Inject(method = "compile", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$compileCubeFast(
        PoseStack.Pose pose,
        VertexConsumer consumer,
        int light,
        int overlay,
        int color,
        CallbackInfo ci
    ) {
        if (!VulkanTurbo.fastEntityRenderEnabled) {
            return;
        }
        EntityRenderOptimizer.compileCubeFast((ModelPart.Cube) (Object) this, pose, consumer, light, overlay, color);
        ci.cancel();
    }
}
