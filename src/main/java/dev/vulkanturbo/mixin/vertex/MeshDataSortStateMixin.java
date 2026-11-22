package dev.vulkanturbo.mixin.vertex;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.CompactVectorArray;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.pipeline.IndexType;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.ChunkPipelineOptimizer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

/**
 * Replaces `MeshData.SortState` per-index `IntConsumer` + `MutableLong` index emission with
 * 64-bit native memory writes and fast-paths standard `DISTANCE_TO_ORIGIN` and `ORTHOGRAPHIC_Z`
 * centroid sorting without `Vector3f` / `float[]` / comparator boxing.
 */
@Mixin(MeshData.SortState.class)
public class MeshDataSortStateMixin {
    @Shadow
    @Final
    private CompactVectorArray centroids;

    @Shadow
    @Final
    private IndexType indexType;

    @Unique
    private int[] vulkanturbo$sortCentroids(VertexSorting sorting) {
        if (sorting == VertexSorting.DISTANCE_TO_ORIGIN) {
            return ChunkPipelineOptimizer.sortCentroidsByDistance(this.centroids, 0.0F, 0.0F, 0.0F);
        }
        if (sorting == VertexSorting.ORTHOGRAPHIC_Z) {
            return ChunkPipelineOptimizer.sortCentroidsByOrthographicZ(this.centroids);
        }
        return sorting.sort(this.centroids);
    }

    @Inject(method = "buildSortedIndexBuffer", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$fastBuildSortedIndexBuffer(
        ByteBufferBuilder target,
        VertexSorting sorting,
        CallbackInfoReturnable<ByteBufferBuilder.Result> cir
    ) {
        if (!VulkanTurbo.fastChunkPrepEnabled) {
            return;
        }
        int[] startIndices = this.vulkanturbo$sortCentroids(sorting);
        long pointer = target.reserve(startIndices.length * 6 * this.indexType.bytes);
        ChunkPipelineOptimizer.writeSortedQuadIndicesToPointer(
            startIndices,
            pointer,
            this.indexType == IndexType.SHORT
        );
        cir.setReturnValue(target.build());
    }

    @Inject(method = "writeSortedIndexBuffer", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$fastWriteSortedIndexBuffer(
        ByteBuffer target,
        VertexSorting sorting,
        CallbackInfo ci
    ) {
        if (!VulkanTurbo.fastChunkPrepEnabled) {
            return;
        }
        int[] startIndices = this.vulkanturbo$sortCentroids(sorting);
        ChunkPipelineOptimizer.writeSortedQuadIndicesToBuffer(startIndices, target, this.indexType);
        ci.cancel();
    }
}
