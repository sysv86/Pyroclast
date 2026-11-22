package dev.vulkanturbo.mixin.vertex;

import com.mojang.blaze3d.vertex.CompactVectorArray;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.BucketedMeshData;
import dev.vulkanturbo.core.ChunkPipelineOptimizer;
import dev.vulkanturbo.core.TerrainSectionBuckets;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * Stores worker-computed directional bucket layout, packed `SectionPos` coordinate key, and
 * shifted/compact metadata on `MeshData` for `CompiledSectionMesh`, and accelerates
 * `decodeQuadCentroids` via direct native reads.
 */
@Mixin(MeshData.class)
public abstract class MeshDataMixin implements BucketedMeshData {
    @Shadow
    @Final
    @Mutable
    private MeshData.DrawState drawState;

    @Unique
    private TerrainSectionBuckets vulkanturbo$buckets;

    @Unique
    private boolean vulkanturbo$regionShifted;

    @Unique
    private long vulkanturbo$sectionKey;

    @Override
    public TerrainSectionBuckets vulkanturbo$getBuckets() {
        return this.vulkanturbo$buckets;
    }

    @Override
    public void vulkanturbo$setBuckets(TerrainSectionBuckets buckets) {
        this.vulkanturbo$buckets = buckets;
    }

    @Override
    public boolean vulkanturbo$isRegionShifted() {
        return this.vulkanturbo$regionShifted;
    }

    @Override
    public void vulkanturbo$setRegionShifted(boolean shifted) {
        this.vulkanturbo$regionShifted = shifted;
    }

    @Override
    public long vulkanturbo$getSectionKey() {
        return this.vulkanturbo$sectionKey;
    }

    @Override
    public void vulkanturbo$setSectionKey(long sectionKey) {
        this.vulkanturbo$sectionKey = sectionKey;
    }

    @Override
    public void vulkanturbo$setDrawState(MeshData.DrawState drawState) {
        this.drawState = drawState;
    }

    @Inject(method = "decodeQuadCentroids", at = @At("HEAD"), cancellable = true)
    private static void vulkanturbo$fastDecodeQuadCentroids(
        ByteBuffer vertexBuffer,
        int vertexCount,
        VertexFormat format,
        CompactVectorArray output,
        int outputIndex,
        CallbackInfo ci
    ) {
        if (!VulkanTurbo.fastChunkPrepEnabled) {
            return;
        }
        VertexFormatElement positionElement = format.getElement("Position");
        if (positionElement == null) {
            return;
        }
        if (ChunkPipelineOptimizer.decodeQuadCentroidsFast(
            vertexBuffer,
            vertexCount,
            positionElement.offset(),
            format.getVertexSize(),
            output,
            outputIndex
        )) {
            ci.cancel();
        }
    }
}
