package dev.vulkanturbo.mixin.chunk;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.vulkanturbo.api.shader.TurboShadersBridge;
import dev.vulkanturbo.core.ChunkRenderPrepOptimizer;
import dev.vulkanturbo.core.FrameProfiler;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SectionUpdateRenderState;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
public class LevelRendererChunkPrepMixin {
    @Shadow
    @Final
    private TextureManager textureManager;

    @Shadow
    @Final
    private OptionsRenderState optionsRenderState;

    @Shadow
    @Final
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

    @Shadow
    private SectionRenderDispatcher sectionRenderDispatcher;

    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @Inject(method = "addSkyPass", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$skipSkyWhenTier3PathTracing(
        FrameGraphBuilder frame,
        CameraRenderState cameraState,
        GpuBufferSlice skyFog,
        CallbackInfo ci
    ) {
        if (TurboShadersBridge.skipWorldRasterizationActive) {
            ci.cancel();
        }
    }

    @Inject(method = "executeSolid", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$skipSolidWhenTier3PathTracing(
        ChunkSectionsToRender chunkSectionsToRender,
        FeatureRenderDispatcher.PreparedFrame featureFrame,
        RenderPass renderPass,
        CallbackInfo ci
    ) {
        if (TurboShadersBridge.skipWorldRasterizationActive) {
            ci.cancel();
        }
    }

    @Inject(method = "executeClassicTransparency", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$skipClassicTransparencyWhenTier3PathTracing(
        ChunkSectionsToRender chunkSectionsToRender,
        FeatureRenderDispatcher.PreparedFrame featureFrame,
        RenderPass renderPass,
        CallbackInfo ci
    ) {
        if (TurboShadersBridge.skipWorldRasterizationActive) {
            ci.cancel();
        }
    }

    @Inject(method = "executeOit", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$skipOitWhenTier3PathTracing(
        ChunkSectionsToRender chunkSectionsToRender,
        FeatureRenderDispatcher.PreparedFrame featureFrame,
        CallbackInfo ci
    ) {
        if (TurboShadersBridge.skipWorldRasterizationActive) {
            ci.cancel();
        }
    }

    @Inject(method = "prepareChunkRendersIndirect", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$fastPrepareIndirect(
        Matrix4fc terrainTransform,
        boolean sortTranslucent,
        CallbackInfoReturnable<ChunkSectionsToRender> cir
    ) {
        if (ChunkRenderPrepOptimizer.isEnabled()) {
            Vec3 camPos = this.levelRenderState.cameraRenderState.pos;
            cir.setReturnValue(
                ChunkRenderPrepOptimizer.prepareIndirect(
                    this.textureManager,
                    this.optionsRenderState,
                    this.visibleSections,
                    this.sectionRenderDispatcher,
                    terrainTransform,
                    sortTranslucent,
                    camPos.x,
                    camPos.y,
                    camPos.z
                )
            );
            if (FrameProfiler.isEnabled()) {
                FrameProfiler.endChunkPrep();
            }
        }
    }

    @Inject(method = "compileSections", at = @At("HEAD"))
    private void vulkanturbo$beginCompileFrame(CameraRenderState camera, CallbackInfo ci) {
        ChunkRenderPrepOptimizer.beginCompileFrame();
    }

    @Inject(method = "clearVisibleSections", at = @At("HEAD"))
    private void vulkanturbo$onClearVisibleSections(CallbackInfo ci) {
        if (ChunkRenderPrepOptimizer.isEnabled()) {
            ChunkRenderPrepOptimizer.bumpVisibleSectionsEpoch();
        }
    }

    @Inject(method = {"resetLevelRenderData", "invalidateCompiledGeometry"}, at = @At("RETURN"))
    private void vulkanturbo$onResetLevelRenderData(CallbackInfo ci) {
        ChunkRenderPrepOptimizer.onWorldReset();
    }

    @Redirect(
        method = "compileSections",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/state/level/SectionUpdateRenderState;playerChanged()Z"
        )
    )
    private boolean vulkanturbo$trackPlayerChanged(SectionUpdateRenderState state) {
        return ChunkRenderPrepOptimizer.recordSectionPlayerChanged(state.playerChanged());
    }

    @Redirect(
        method = "compileSections",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;compileSync(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;)V"
        )
    )
    private void vulkanturbo$paceAmbientSyncCompiles(
        SectionRenderDispatcher.RenderSection section,
        RenderSectionRegion region
    ) {
        ChunkRenderPrepOptimizer.dispatchCompileSection(section, region);
    }
}
