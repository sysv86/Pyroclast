package dev.vulkanturbo.mixin.chunk;

import com.mojang.blaze3d.vertex.StagingBuffer;
import com.mojang.blaze3d.vertex.TlsfAllocator;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.CachedSectionMesh;
import dev.vulkanturbo.core.FastSectionDispatcher;
import dev.vulkanturbo.core.FastUberGpuBuffer;
import dev.vulkanturbo.core.MutableSectionSlice;
import dev.vulkanturbo.core.TerrainRegionPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Caches per-`ChunkSectionLayer` vertex/index `UberGpuBuffer` references on `SectionRenderDispatcher`,
 * aligns vertex heaps to the least common multiple of compact 20B, standard 28B, and active custom
 * strides (`140B` default) with larger 32-chunk-friendly heaps, skips idle GPU upload transactions,
 * and populates slice scratch without allocating records.
 */
@Mixin(SectionRenderDispatcher.class)
public abstract class SectionRenderDispatcherSliceMixin implements FastSectionDispatcher {
    @Shadow
    @Final
    private Map<ChunkSectionLayer, ?> chunkUberBuffers;

    @Unique
    private UberGpuBuffer<?>[] vulkanturbo$vertexBuffers;

    @Unique
    private UberGpuBuffer<?>[] vulkanturbo$indexBuffers;

    @Redirect(
        method = "lambda$new$0",
        at = @At(
            value = "NEW",
            target = "com/mojang/blaze3d/vertex/UberGpuBuffer",
            ordinal = 0
        )
    )
    private UberGpuBuffer<?> vulkanturbo$createSizedVertexUberBuffer(
        String name,
        int usage,
        int heapSize,
        int alignSize,
        StagingBuffer stagingBuffer,
        ChunkSectionLayer layer
    ) {
        if (!VulkanTurbo.isBackendAllowed() || !VulkanTurbo.fastChunkPrepEnabled) {
            return new UberGpuBuffer<>(name, usage, heapSize, alignSize, stagingBuffer);
        }
        int tunedAlign = TerrainRegionPipelines.recommendedVertexAlignment(layer, alignSize);
        int tunedHeap = Math.max(heapSize, TerrainRegionPipelines.recommendedHeapSizeBytes(layer));
        return new UberGpuBuffer<>(name, usage, tunedHeap, tunedAlign, stagingBuffer);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void vulkanturbo$initOnConstruct(CallbackInfo ci) {
        this.vulkanturbo$initLayerBuffers();
    }

    @Inject(method = "uploadTerrainBuffersToGpu", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$skipIdleTerrainUpload(CallbackInfo ci) {
        if (!VulkanTurbo.fastChunkPrepEnabled) {
            return;
        }
        if (this.vulkanturbo$vertexBuffers == null) {
            this.vulkanturbo$initLayerBuffers();
        }
        UberGpuBuffer<?>[] vbs = this.vulkanturbo$vertexBuffers;
        UberGpuBuffer<?>[] ibs = this.vulkanturbo$indexBuffers;
        for (int i = 0; i < vbs.length; i++) {
            UberGpuBuffer<?> vb = vbs[i];
            if (vb != null && ((FastUberGpuBuffer) vb).vulkanturbo$hasStagedWork()) {
                return;
            }
            UberGpuBuffer<?> ib = ibs[i];
            if (ib != null && ((FastUberGpuBuffer) ib).vulkanturbo$hasStagedWork()) {
                return;
            }
        }
        ci.cancel();
    }

    @Unique
    private void vulkanturbo$initLayerBuffers() {
        ChunkSectionLayer[] layers = ChunkSectionLayer.values();
        UberGpuBuffer<?>[] vbs = new UberGpuBuffer<?>[layers.length];
        UberGpuBuffer<?>[] ibs = new UberGpuBuffer<?>[layers.length];
        Method vbMethod = null;
        Method ibMethod = null;
        try {
            for (int i = 0; i < layers.length; i++) {
                Object rec = this.chunkUberBuffers.get(layers[i]);
                if (rec == null) continue;
                if (vbMethod == null) {
                    Class<?> cls = rec.getClass();
                    vbMethod = cls.getDeclaredMethod("vertexBuffer");
                    vbMethod.setAccessible(true);
                    ibMethod = cls.getDeclaredMethod("indexBuffer");
                    ibMethod.setAccessible(true);
                }
                vbs[i] = (UberGpuBuffer<?>) vbMethod.invoke(rec);
                ibs[i] = (UberGpuBuffer<?>) ibMethod.invoke(rec);
                if (vbs[i] != null) ((FastUberGpuBuffer) vbs[i]).vulkanturbo$setMeshSlot(i << 1);
                if (ibs[i] != null) ((FastUberGpuBuffer) ibs[i]).vulkanturbo$setMeshSlot((i << 1) | 1);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to resolve SectionUberBuffers fields", e);
        }
        this.vulkanturbo$vertexBuffers = vbs;
        this.vulkanturbo$indexBuffers = ibs;
    }

    @Override
    public boolean vulkanturbo$fillSectionSlice(
        int layerOrdinal,
        SectionMesh mesh,
        boolean needIndex,
        MutableSectionSlice out
    ) {
        if (this.vulkanturbo$vertexBuffers == null) {
            this.vulkanturbo$initLayerBuffers();
        }
        UberGpuBuffer<?> vb = this.vulkanturbo$vertexBuffers[layerOrdinal];
        CachedSectionMesh cached = mesh instanceof CachedSectionMesh c ? c : null;
        TlsfAllocator.Allocation vAlloc = cached != null
            ? cached.vulkanturbo$getAllocation(layerOrdinal << 1)
            : ((FastUberGpuBuffer) vb).vulkanturbo$getAllocation(mesh);
        if (vAlloc == null) {
            return false;
        }
        if (needIndex) {
            UberGpuBuffer<?> ib = this.vulkanturbo$indexBuffers[layerOrdinal];
            TlsfAllocator.Allocation iAlloc = cached != null
                ? cached.vulkanturbo$getAllocation((layerOrdinal << 1) | 1)
                : ((FastUberGpuBuffer) ib).vulkanturbo$getAllocation(mesh);
            if (iAlloc == null) {
                return false;
            }
            out.indexBuffer = ib.getGpuBuffer(iAlloc);
            out.indexOffset = iAlloc.getOffsetFromHeap();
        } else {
            out.indexBuffer = null;
            out.indexOffset = 0L;
        }
        out.vertexBuffer = vb.getGpuBuffer(vAlloc);
        out.vertexOffset = vAlloc.getOffsetFromHeap();
        return true;
    }
}
