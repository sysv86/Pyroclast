package dev.vulkanturbo.mixin.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.EntityRenderOptimizer;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

/**
 * Replaces per-frame `ModelPart` child map iterators, cube list iterators, and transient
 * `Quaternionf` allocations with eagerly published flat arrays and direct ZYX matrix rotation.
 */
@Mixin(ModelPart.class)
public abstract class ModelPartMixin {
    @Shadow
    public boolean visible;

    @Shadow
    public boolean skipDraw;

    @Shadow
    @Final
    private List<ModelPart.Cube> cubes;

    @Shadow
    @Final
    private Map<String, ModelPart> children;

    @Shadow
    public abstract void translateAndRotate(PoseStack poseStack);

    @Shadow
    protected abstract void compile(PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay, int color);

    @Unique
    private ModelPart[] vulkanturbo$childrenArray;

    @Unique
    private ModelPart.Cube[] vulkanturbo$cubesArray;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void vulkanturbo$initArrays(
        List<ModelPart.Cube> cubes,
        Map<String, ModelPart> children,
        CallbackInfo ci
    ) {
        this.vulkanturbo$cubesArray = EntityRenderOptimizer.buildCubesArray(cubes);
        this.vulkanturbo$childrenArray = EntityRenderOptimizer.buildChildrenArray(children);
    }

    @Inject(method = "translateAndRotate", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$translateAndRotateFast(PoseStack poseStack, CallbackInfo ci) {
        if (!VulkanTurbo.fastEntityRenderEnabled) {
            return;
        }
        EntityRenderOptimizer.translateAndRotateFast((ModelPart) (Object) this, poseStack);
        ci.cancel();
    }

    @Inject(
        method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void vulkanturbo$renderFast(
        PoseStack poseStack,
        VertexConsumer consumer,
        int light,
        int overlay,
        int color,
        CallbackInfo ci
    ) {
        if (!VulkanTurbo.fastEntityRenderEnabled) {
            return;
        }
        if (!this.visible) {
            ci.cancel();
            return;
        }
        ModelPart.Cube[] cubesArr = vulkanturbo$getCubesArray();
        ModelPart[] childrenArr = vulkanturbo$getChildrenArray();
        if (cubesArr.length == 0 && childrenArr.length == 0) {
            ci.cancel();
            return;
        }
        poseStack.pushPose();
        this.translateAndRotate(poseStack);
        if (!this.skipDraw && cubesArr.length > 0) {
            this.compile(poseStack.last(), consumer, light, overlay, color);
        }
        if (childrenArr.length > 0) {
            EntityRenderOptimizer.renderChildren(childrenArr, poseStack, consumer, light, overlay, color);
        }
        poseStack.popPose();
        ci.cancel();
    }

    @Inject(method = "compile", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$compileFast(
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
        ModelPart.Cube[] cubesArr = vulkanturbo$getCubesArray();
        EntityRenderOptimizer.compileCubes(cubesArr, pose, consumer, light, overlay, color);
        ci.cancel();
    }

    @Unique
    private ModelPart[] vulkanturbo$getChildrenArray() {
        ModelPart[] cached = this.vulkanturbo$childrenArray;
        if (cached == null || cached.length != this.children.size()) {
            cached = EntityRenderOptimizer.buildChildrenArray(this.children);
            this.vulkanturbo$childrenArray = cached;
        }
        return cached;
    }

    @Unique
    private ModelPart.Cube[] vulkanturbo$getCubesArray() {
        ModelPart.Cube[] cached = this.vulkanturbo$cubesArray;
        if (cached == null || cached.length != this.cubes.size()) {
            cached = EntityRenderOptimizer.buildCubesArray(this.cubes);
            this.vulkanturbo$cubesArray = cached;
        }
        return cached;
    }
}
