package dev.vulkanturbo.mixin.entity;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.EntityRenderOptimizer;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Batches `BakedSheetGlyph.render` into a single 4-vertex off-heap reservation and hoists the
 * constant `z` column multiplication across all 4 glyph corners.
 */
@Mixin(BakedSheetGlyph.class)
public class BakedSheetGlyphMixin {
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
    private float left;

    @Shadow
    @Final
    private float right;

    @Shadow
    @Final
    private float up;

    @Shadow
    @Final
    private float down;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$fastRenderGlyph(
        boolean italic,
        float x,
        float y,
        float z,
        Matrix4fc pose,
        VertexConsumer builder,
        int color,
        boolean bold,
        int packedLightCoords,
        CallbackInfo ci
    ) {
        if (!VulkanTurbo.fastEntityRenderEnabled) {
            return;
        }
        float x0 = x + this.left;
        float x1 = x + this.right;
        float y0 = y + this.up;
        float y1 = y + this.down;
        float shearY0 = italic ? 1.0F - 0.25F * this.up : 0.0F;
        float shearY1 = italic ? 1.0F - 0.25F * this.down : 0.0F;
        float extraThickness = bold ? 0.1F : 0.0F;

        EntityRenderOptimizer.emitGlyphQuadFast(
            builder,
            pose,
            x0 + shearY0 - extraThickness, y0 - extraThickness,
            x0 + shearY1 - extraThickness, y1 + extraThickness,
            x1 + shearY1 + extraThickness, y1 + extraThickness,
            x1 + shearY0 + extraThickness, y0 - extraThickness,
            z,
            this.u0, this.u1,
            this.v0, this.v1,
            color,
            packedLightCoords
        );
        ci.cancel();
    }
}
