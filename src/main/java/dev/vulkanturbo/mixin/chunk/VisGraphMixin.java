package dev.vulkanturbo.mixin.chunk;

import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.ChunkPipelineOptimizer;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.BitSet;

/**
 * Replaces `VisGraph.resolve()` flood-fill with a zero-allocation primitive ring-queue BFS and
 * precomputed 6-bit boundary face masks, eliminating `IntArrayFIFOQueue`, `EnumSet`, and nested
 * iterator allocations during chunk compilation.
 */
@Mixin(VisGraph.class)
public class VisGraphMixin {
    @Shadow
    @Final
    private BitSet bitSet;

    @Shadow
    private int empty;

    @Inject(method = "resolve", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$resolveFast(CallbackInfoReturnable<VisibilitySet> cir) {
        if (!VulkanTurbo.fastChunkPrepEnabled) {
            return;
        }
        cir.setReturnValue(ChunkPipelineOptimizer.resolveVisGraph(this.bitSet, this.empty));
    }
}
