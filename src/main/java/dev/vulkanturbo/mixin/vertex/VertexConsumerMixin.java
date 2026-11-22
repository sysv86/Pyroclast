package dev.vulkanturbo.mixin.vertex;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.EntityRenderOptimizer;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * Reuses thread-local scratch vectors in `VertexConsumer` default transform methods instead of
 * allocating a fresh `Vector3f` or `Vector2f` per vertex (used across flames, GUI, text, items,
 * and entity overlays). Each method uses an isolated scratch instance so re-entrant delegations
 * (e.g. `putBakedQuad` calling `addVertex` and `setNormal`) never overwrite an active vector.
 */
@Mixin(VertexConsumer.class)
public interface VertexConsumerMixin {
    @WrapOperation(
        method = "addVertex(Lorg/joml/Matrix4fc;FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
        at = @At(value = "NEW", target = "()Lorg/joml/Vector3f;")
    )
    private Vector3f vulkanturbo$reusePosVec3(Operation<Vector3f> original) {
        if (!VulkanTurbo.fastEntityRenderEnabled) {
            return original.call();
        }
        return EntityRenderOptimizer.scratchPos();
    }

    @WrapOperation(
        method = "setNormal(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
        at = @At(value = "NEW", target = "()Lorg/joml/Vector3f;")
    )
    private Vector3f vulkanturbo$reuseNormalVec3(Operation<Vector3f> original) {
        if (!VulkanTurbo.fastEntityRenderEnabled) {
            return original.call();
        }
        return EntityRenderOptimizer.scratchNormal();
    }

    @WrapOperation(
        method = "addVertexWith2DPose(Lorg/joml/Matrix3x2fc;FF)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
        at = @At(value = "NEW", target = "()Lorg/joml/Vector2f;")
    )
    private Vector2f vulkanturbo$reusePosVec2(Operation<Vector2f> original) {
        if (!VulkanTurbo.fastEntityRenderEnabled) {
            return original.call();
        }
        return EntityRenderOptimizer.scratchVec2();
    }

    @WrapOperation(
        method = "putBakedQuad(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V",
        slice = @Slice(
            to = @At(
                value = "INVOKE",
                target = "Lcom/mojang/blaze3d/vertex/PoseStack$Pose;transformNormal(Lorg/joml/Vector3fc;Lorg/joml/Vector3f;)Lorg/joml/Vector3f;"
            )
        ),
        at = @At(value = "NEW", target = "()Lorg/joml/Vector3f;")
    )
    private Vector3f vulkanturbo$reuseBakedQuadNormal(Operation<Vector3f> original) {
        if (!VulkanTurbo.fastEntityRenderEnabled) {
            return original.call();
        }
        return EntityRenderOptimizer.scratchQuadNormal();
    }

    @WrapOperation(
        method = "putBakedQuad(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V",
        slice = @Slice(
            from = @At(
                value = "INVOKE",
                target = "Lcom/mojang/blaze3d/vertex/PoseStack$Pose;transformNormal(Lorg/joml/Vector3fc;Lorg/joml/Vector3f;)Lorg/joml/Vector3f;"
            )
        ),
        at = @At(value = "NEW", target = "()Lorg/joml/Vector3f;")
    )
    private Vector3f vulkanturbo$reuseBakedQuadPos(Operation<Vector3f> original) {
        if (!VulkanTurbo.fastEntityRenderEnabled) {
            return original.call();
        }
        return EntityRenderOptimizer.scratchQuadPos();
    }
}
