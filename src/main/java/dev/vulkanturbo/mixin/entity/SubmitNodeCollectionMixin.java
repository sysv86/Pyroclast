package dev.vulkanturbo.mixin.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.EntityRenderOptimizer;
import net.minecraft.client.renderer.SubmitNodeCollection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Borrows reusable `PoseStack.Pose` instances from a frame-scoped render-thread pool instead of
 * allocating `Pose` + `Matrix4f` + `Matrix3f` on every submitted entity, item, shadow, or text.
 */
@Mixin(SubmitNodeCollection.class)
public class SubmitNodeCollectionMixin {
    @Redirect(
        method = {
            "submitFlame",
            "submitModel",
            "submitCrumblingOverlay",
            "submitBlockModel",
            "submitBreakingBlockModel",
            "submitShapeOutline",
            "submitItem",
            "submitCustomGeometry"
        },
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack$Pose;copy()Lcom/mojang/blaze3d/vertex/PoseStack$Pose;"
        )
    )
    private PoseStack.Pose vulkanturbo$borrowPooledPose(PoseStack.Pose source) {
        if (!VulkanTurbo.fastEntityRenderEnabled) {
            return source.copy();
        }
        return EntityRenderOptimizer.borrowCopiedPose(source);
    }
}
