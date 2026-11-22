package dev.vulkanturbo.mixin.chunk;

import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.ChunkPipelineOptimizer;
import dev.vulkanturbo.core.ChunkRenderPrepOptimizer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces `LevelExtractor.extractVisibleBlockEntities` section iteration with an allocation-free
 * indexed scan that skips sections without block entities via `CachedSectionMesh`.
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorBlockEntitiesMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private LevelRenderer levelRenderer;

    @Shadow
    private ClientLevel level;

    @Inject(method = "extractVisibleBlockEntities", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$fastExtractBlockEntities(
        Camera camera,
        float partialTick,
        LevelRenderState renderState,
        CallbackInfo ci
    ) {
        if (!VulkanTurbo.fastChunkPrepEnabled) {
            return;
        }
        Vec3 camPos = camera.position();
        long fadeInMs = Util.toMillis(this.minecraft.options.chunkSectionFadeInTime().get());
        ChunkPipelineOptimizer.extractVisibleBlockEntitiesFast(
            this.level,
            this.levelRenderer.blockEntityRenderDispatcher(),
            this.levelRenderer.visibleSections(),
            camPos.x,
            camPos.y,
            camPos.z,
            fadeInMs,
            partialTick,
            renderState.blockEntityRenderStates
        );
        ci.cancel();
    }

    @Inject(method = "applyFrustum", at = @At("RETURN"))
    private void vulkanturbo$onApplyFrustum(Frustum frustum, CallbackInfo ci) {
        if (VulkanTurbo.fastChunkPrepEnabled) {
            ChunkRenderPrepOptimizer.bumpVisibleSectionsEpoch();
        }
    }
}
