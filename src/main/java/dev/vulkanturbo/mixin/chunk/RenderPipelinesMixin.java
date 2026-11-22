package dev.vulkanturbo.mixin.chunk;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.vulkanturbo.core.TerrainRegionPipelines;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Registers VulkanTurbo's region push-constant terrain pipelines alongside Mojang pipelines. */
@Mixin(RenderPipelines.class)
public abstract class RenderPipelinesMixin {
    @Shadow
    @Final
    private static RenderPipeline.Snippet GENERIC_BLOCKS_SNIPPET;

    @Shadow
    @Final
    private static RenderPipeline.Snippet LIT_BLOCKS_SNIPPET;

    @Shadow
    @Final
    private static RenderPipeline WIREFRAME_MULTIDRAW;

    @Shadow
    private static RenderPipeline registerOptional(RenderPipeline pipeline) {
        throw new AssertionError();
    }

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void vulkanturbo$registerRegionPipelines(CallbackInfo ci) {
        TerrainRegionPipelines.registerAll(
            GENERIC_BLOCKS_SNIPPET,
            LIT_BLOCKS_SNIPPET,
            WIREFRAME_MULTIDRAW,
            RenderPipelinesMixin::registerOptional
        );
    }
}
