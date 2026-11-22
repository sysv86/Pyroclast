package dev.vulkanturbo.mixin.chunk;

import com.mojang.blaze3d.vertex.VertexSorting;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.ChunkPipelineOptimizer;
import dev.vulkanturbo.core.ChunkRenderPrepOptimizer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces `RenderSection.createVertexSorting` with a packed-long primitive distance sorter that
 * avoids `Vector3f`, `float[]`, and `IntArrays.mergeSort` comparator allocations during chunk
 * compilation and translucency resorting, and invalidates retained region draw plans immediately
 * whenever a section swaps or resets its `SectionMesh`.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public class SectionRenderDispatcherRenderSectionMixin {
    @Inject(method = "createVertexSorting", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$fastCreateVertexSorting(
        SectionPos sectionPos,
        Vec3 cameraPos,
        CallbackInfoReturnable<VertexSorting> cir
    ) {
        if (!VulkanTurbo.fastChunkPrepEnabled) {
            return;
        }
        float ox = (float) (cameraPos.x - sectionPos.minBlockX());
        float oy = (float) (cameraPos.y - sectionPos.minBlockY());
        float oz = (float) (cameraPos.z - sectionPos.minBlockZ());
        cir.setReturnValue(ChunkPipelineOptimizer.createFastDistanceSorting(ox, oy, oz));
    }

    @Inject(method = "setSectionMesh", at = @At("RETURN"))
    private void vulkanturbo$onSetSectionMesh(SectionMesh sectionMesh, CallbackInfoReturnable<SectionMesh> cir) {
        if (VulkanTurbo.fastChunkPrepEnabled) {
            ChunkRenderPrepOptimizer.bumpMeshUploadEpoch();
        }
    }

    @Inject(method = "reset", at = @At("RETURN"))
    private void vulkanturbo$onResetSection(CallbackInfo ci) {
        if (VulkanTurbo.fastChunkPrepEnabled) {
            ChunkRenderPrepOptimizer.bumpMeshUploadEpoch();
        }
    }
}
