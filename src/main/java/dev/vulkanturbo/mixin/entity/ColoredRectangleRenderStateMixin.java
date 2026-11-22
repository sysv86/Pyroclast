package dev.vulkanturbo.mixin.entity;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.EntityRenderOptimizer;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import org.joml.Matrix3x2fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Batches `ColoredRectangleRenderState.buildVertices` into a single 64-byte (`4 * 16B`) native
 * reservation on `BufferBuilder` (`DefaultVertexFormat.POSITION_COLOR`).
 */
@Mixin(ColoredRectangleRenderState.class)
public class ColoredRectangleRenderStateMixin {
    @Shadow
    @Final
    private Matrix3x2fc pose;

    @Shadow
    @Final
    private int x0;

    @Shadow
    @Final
    private int y0;

    @Shadow
    @Final
    private int x1;

    @Shadow
    @Final
    private int y1;

    @Shadow
    @Final
    private int col1;

    @Shadow
    @Final
    private int col2;

    @Inject(method = "buildVertices", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$fastBuildColoredRectVertices(VertexConsumer vertexConsumer, CallbackInfo ci) {
        if (!VulkanTurbo.fastEntityRenderEnabled) {
            return;
        }
        EntityRenderOptimizer.emitGuiColoredRectQuadFast(
            vertexConsumer,
            this.pose,
            this.x0, this.y0,
            this.x1, this.y1,
            this.col1, this.col2
        );
        ci.cancel();
    }
}
