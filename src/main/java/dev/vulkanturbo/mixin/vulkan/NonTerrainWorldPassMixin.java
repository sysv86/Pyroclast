package dev.vulkanturbo.mixin.vulkan;

import com.mojang.renderpearl.api.commands.RenderPass;
import dev.vulkanturbo.api.shader.TurboShadersBridge;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fires the non-terrain world-pass SPI hook at the exact points where Mojang stops drawing terrain
 * and starts executing entity / block-entity / particle feature submits inside the main world
 * {@link RenderPass}.
 *
 * `LevelRenderer.executeSolid` draws opaque terrain via `ChunkSectionsToRender.renderGroup` and
 * then calls `PreparedFrame.executeSolid`. `LevelRenderer.executeClassicTransparency` calls
 * `PreparedFrame.executeTranslucent` before translucent terrain and
 * `PreparedFrame.executeTranslucentAfterTerrain` after it. Both target methods hold the live world
 * RenderPass, so the hook can bind a shader engine's 4-MRT color views and `VsMotion` UBO through
 * {@link TurboShadersBridge#overridePendingPassColorViews}. Entity/particle draws never pass
 * through `onBeginRegionTerrainPass`, which is why this separate hook is required.
 */
@Mixin(LevelRenderer.class)
public class NonTerrainWorldPassMixin {
    @Inject(
        method = "executeSolid(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeSolid(Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
            shift = At.Shift.BEFORE
        )
    )
    private void vulkanturbo$beforeSolidFeatures(
        ChunkSectionsToRender sections,
        FeatureRenderDispatcher.PreparedFrame frame,
        RenderPass renderPass,
        CallbackInfo ci
    ) {
        TurboShadersBridge.onBeginNonTerrainWorldPass(renderPass);
    }

    @Inject(
        method = "executeClassicTransparency(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeTranslucent(Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
            shift = At.Shift.BEFORE
        )
    )
    private void vulkanturbo$beforeTranslucentFeatures(
        ChunkSectionsToRender sections,
        FeatureRenderDispatcher.PreparedFrame frame,
        RenderPass renderPass,
        CallbackInfo ci
    ) {
        TurboShadersBridge.onBeginNonTerrainWorldPass(renderPass);
    }

    @Inject(
        method = "executeClassicTransparency(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeTranslucentAfterTerrain(Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
            shift = At.Shift.BEFORE
        )
    )
    private void vulkanturbo$beforeTranslucentAfterTerrainFeatures(
        ChunkSectionsToRender sections,
        FeatureRenderDispatcher.PreparedFrame frame,
        RenderPass renderPass,
        CallbackInfo ci
    ) {
        TurboShadersBridge.onBeginNonTerrainWorldPass(renderPass);
    }
}
