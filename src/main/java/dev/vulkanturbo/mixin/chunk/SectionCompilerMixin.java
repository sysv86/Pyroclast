package dev.vulkanturbo.mixin.chunk;

import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.vulkanturbo.api.shader.TurboShadersBridge;
import dev.vulkanturbo.core.TerrainBucketBuilder;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Classifies `SOLID` and `CUTOUT` section quads into directional buckets on worker threads and
 * forwards active `BlockState`/`FluidState` context and custom terrain vertex formats to `TurboShaders`.
 */
@Mixin(SectionCompiler.class)
public class SectionCompilerMixin {
    @Redirect(
        method = "getOrBeginLayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;vertexFormat()Lcom/mojang/renderpearl/api/vertex/VertexFormat;"
        )
    )
    private VertexFormat vulkanturbo$resolveTerrainVertexFormat(ChunkSectionLayer layer) {
        VertexFormat defaultFormat = layer.vertexFormat();
        if (!TurboShadersBridge.active) {
            return defaultFormat;
        }
        return TurboShadersBridge.resolveTerrainVertexFormat(layer, defaultFormat);
    }

    @Redirect(
        method = "compile",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        )
    )
    private BlockState vulkanturbo$trackBlockContext(RenderSectionRegion region, BlockPos pos) {
        BlockState state = region.getBlockState(pos);
        if (TurboShadersBridge.active && !state.isAir()) {
            TurboShadersBridge.onBeginBlockTesselation(state, pos);
            var fluidState = state.getFluidState();
            if (!fluidState.isEmpty()) {
                TurboShadersBridge.onBeginFluidTesselation(fluidState, state, pos);
            }
        }
        return state;
    }

    @Inject(method = "compile", at = @At("RETURN"))
    private void vulkanturbo$bucketCompiledSection(
        SectionPos sectionPos,
        RenderSectionRegion region,
        VertexSorting sorting,
        SectionBufferBuilderPack pack,
        CallbackInfoReturnable<SectionCompiler.Results> cir
    ) {
        if (TurboShadersBridge.active) {
            TurboShadersBridge.onEndSectionTesselation();
        }
        SectionCompiler.Results results = cir.getReturnValue();
        if (results != null) {
            TerrainBucketBuilder.bucketAndShiftResults(sectionPos, results);
        }
    }
}
