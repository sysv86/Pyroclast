package dev.vulkanturbo.mixin.chunk;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.vulkanturbo.core.RegionBackedDrawIndirect;
import dev.vulkanturbo.core.RegionBatchPlan;
import dev.vulkanturbo.core.TerrainRegionPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Executes `SOLID`, `CUTOUT`, and `TRANSLUCENT` layers via region push constants and either
 * `multiDrawIndexed` (`VK_EXT_multi_draw`) or retained GPU `drawIndexedIndirect` (`VK_INDIRECT`).
 */
@Mixin(ChunkSectionsToRender.DrawIndirect.class)
public class ChunkSectionsToRenderDrawIndirectMixin implements RegionBackedDrawIndirect {
    @Unique
    private RegionBatchPlan vulkanturbo$regionPlan;

    @Override
    public void vulkanturbo$setRegionPlan(RegionBatchPlan plan) {
        this.vulkanturbo$regionPlan = plan;
    }

    @Override
    public RegionBatchPlan vulkanturbo$getRegionPlan() {
        return this.vulkanturbo$regionPlan;
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$renderRegionMultiDraw(
        ChunkSectionLayer layer,
        RenderPass renderPass,
        GpuBuffer quadIndexBuffer,
        IndexType indexType,
        RenderPipeline separateOverride,
        RenderPipeline multidrawOverride,
        CallbackInfo ci
    ) {
        RegionBatchPlan plan = this.vulkanturbo$regionPlan;
        if (plan != null && TerrainRegionPipelines.acceptsRegionOverride(layer, multidrawOverride)) {
            if (plan.hasLayerBatches(layer.ordinal())) {
                RegionBatchPlan.render(plan, layer, renderPass, quadIndexBuffer, indexType, multidrawOverride);
            }
            if (!plan.hasFallback(layer)) {
                ci.cancel();
            }
        }
    }
}
