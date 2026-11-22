package dev.vulkanturbo.mixin.entity;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.EntityRenderOptimizer;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import org.joml.Matrix3x2fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Batches `BlitRenderState.buildVertices` into a single 96-byte (`4 * 24B`) native reservation
 * on `BufferBuilder` (`DefaultVertexFormat.POSITION_TEX_COLOR`).
 */
@Mixin(BlitRenderState.class)
public class BlitRenderStateMixin {
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
    private float u0;

    @Shadow
    @Final
    private float u1;

    @Shadow
    @Final
    private float v0;

    @Shadow
    @Final
    private float v1;

    @Shadow
    @Final
    private int color;

    @Inject(method = "buildVertices", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$fastBuildBlitVertices(VertexConsumer vertexConsumer, CallbackInfo ci) {
        if (!VulkanTurbo.fastEntityRenderEnabled) {
            return;
        }
        EntityRenderOptimizer.emitGuiBlitQuadFast(
            vertexConsumer,
            this.pose,
            this.x0, this.y0,
            this.x1, this.y1,
            this.u0, this.u1,
            this.v0, this.v1,
            this.color
        );
        ci.cancel();
    }
}
