package dev.vulkanturbo.mixin.entity;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.EntityRenderOptimizer;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GlyphRenderState;
import org.joml.Matrix3x2fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Eliminates `~20,000+` `TextureSetup` record allocations per frame during `GuiRenderer`
 * `ELEMENT_SORT_COMPARATOR` sorting by caching `TextureSetup` on each `GlyphRenderState`,
 * and reuses a scratch `Matrix4f` in `buildVertices` instead of allocating `new Matrix4f()` per glyph.
 */
@Mixin(GlyphRenderState.class)
public class GlyphRenderStateMixin {
    @Shadow
    @Final
    private Matrix3x2fc pose;

    @Shadow
    @Final
    private TextRenderable renderable;

    @Unique
    private TextureSetup vulkanturbo$cachedTextureSetup;

    @Inject(method = "textureSetup", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$cacheTextureSetup(CallbackInfoReturnable<TextureSetup> cir) {
        if (!VulkanTurbo.fastEntityRenderEnabled) {
            return;
        }
        TextureSetup cached = this.vulkanturbo$cachedTextureSetup;
        if (cached == null) {
            cached = TextureSetup.singleTextureWithLightmap(
                this.renderable.textureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
            );
            this.vulkanturbo$cachedTextureSetup = cached;
        }
        cir.setReturnValue(cached);
    }

    @Inject(method = "buildVertices", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$fastBuildVertices(VertexConsumer vertexConsumer, CallbackInfo ci) {
        if (!VulkanTurbo.fastEntityRenderEnabled) {
            return;
        }
        this.renderable.render(
            EntityRenderOptimizer.scratchGuiPoseMatrix(this.pose),
            vertexConsumer,
            15728880,
            true
        );
        ci.cancel();
    }
}
