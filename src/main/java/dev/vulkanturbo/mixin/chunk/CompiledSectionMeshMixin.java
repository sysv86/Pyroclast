package dev.vulkanturbo.mixin.chunk;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.TlsfAllocator;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.BucketedMeshData;
import dev.vulkanturbo.core.CachedSectionMesh;
import dev.vulkanturbo.core.ChunkRenderPrepOptimizer;
import dev.vulkanturbo.core.TerrainRegionPipelines;
import dev.vulkanturbo.core.TerrainSectionBuckets;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stores direct per-layer `SectionDraw` and TLSF buffer `Allocation` references on
 * `CompiledSectionMesh`, turning per-section hash lookups into flat array/field reads,
 * and invalidates cached state cleanly when `close()` runs.
 *
 * Dynamically sizes all per-layer arrays to `ChunkSectionLayer.values().length` so custom
 * mod-added `ChunkSectionLayer` values never trigger `ArrayIndexOutOfBoundsException`.
 */
@Mixin(CompiledSectionMesh.class)
public abstract class CompiledSectionMeshMixin implements CachedSectionMesh {
    @Unique
    private static final int VULKANTURBO_LAYER_COUNT = Math.max(3, ChunkSectionLayer.values().length);

    @Shadow
    @Final
    private List<BlockEntity> renderableBlockEntities;

    @Shadow
    @Final
    private Map<ChunkSectionLayer, SectionMesh.SectionDraw> draws;

    @Shadow
    @Final
    private Map<ChunkSectionLayer, AtomicBoolean> vertexBufferUploaded;

    @Shadow
    @Final
    private Map<ChunkSectionLayer, AtomicBoolean> indexBufferUploaded;

    @Unique
    private final TlsfAllocator.Allocation[] vulkanturbo$allocations =
        new TlsfAllocator.Allocation[VULKANTURBO_LAYER_COUNT * 2];

    @Unique
    private final SectionMesh.SectionDraw[] vulkanturbo$cachedDraws =
        new SectionMesh.SectionDraw[VULKANTURBO_LAYER_COUNT];

    @Unique
    private final TerrainSectionBuckets[] vulkanturbo$buckets = new TerrainSectionBuckets[2];

    @Unique
    private final int[] vulkanturbo$vertexStrides = new int[VULKANTURBO_LAYER_COUNT];

    @Unique
    private final int[] vulkanturbo$vertexCounts = new int[VULKANTURBO_LAYER_COUNT];

    @Unique
    private long vulkanturbo$sectionKey;

    @Unique
    private int vulkanturbo$shiftedMask;

    @Unique
    private boolean vulkanturbo$hasBlockEntities;

    @Unique
    private boolean vulkanturbo$hasRenderableDraws;

    @Unique
    private volatile boolean vulkanturbo$closed;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void vulkanturbo$cacheDraws(
        TranslucencyPointOfView pointOfView,
        SectionCompiler.Results results,
        long compileStartTimeNs,
        CallbackInfo ci
    ) {
        ChunkSectionLayer[] layers = ChunkSectionLayer.values();
        boolean anyDraw = false;
        int shifted = 0;
        long sectionKey = 0L;
        int limit = Math.min(layers.length, this.vulkanturbo$cachedDraws.length);
        for (int i = 0; i < limit; i++) {
            ChunkSectionLayer layer = layers[i];
            SectionMesh.SectionDraw draw = this.draws.get(layer);
            this.vulkanturbo$cachedDraws[i] = draw;
            if (draw != null) {
                anyDraw = true;
                MeshData md = results.renderedLayers.get(layer);
                if (md != null) {
                    this.vulkanturbo$vertexStrides[i] = md.drawState().format().getVertexSize();
                    this.vulkanturbo$vertexCounts[i] = md.drawState().vertexCount();
                    if (md instanceof BucketedMeshData bucketed) {
                        sectionKey = bucketed.vulkanturbo$getSectionKey();
                        if (i < this.vulkanturbo$buckets.length) {
                            this.vulkanturbo$buckets[i] = bucketed.vulkanturbo$getBuckets();
                        }
                        if (bucketed.vulkanturbo$isRegionShifted() && i < 31) {
                            shifted |= (1 << i);
                        }
                    }
                } else {
                    this.vulkanturbo$vertexStrides[i] = TerrainRegionPipelines.DEFAULT_VERTEX_STRIDE;
                    this.vulkanturbo$vertexCounts[i] = 0;
                }
            }
        }
        this.vulkanturbo$sectionKey = sectionKey;
        this.vulkanturbo$shiftedMask = shifted;
        this.vulkanturbo$hasRenderableDraws = anyDraw;
        this.vulkanturbo$hasBlockEntities = !this.renderableBlockEntities.isEmpty();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void vulkanturbo$invalidateOnClose(CallbackInfo ci) {
        boolean hadDraws = this.vulkanturbo$hasRenderableDraws;
        this.vulkanturbo$closed = true;
        this.vulkanturbo$hasRenderableDraws = false;
        this.vulkanturbo$hasBlockEntities = false;
        this.vulkanturbo$shiftedMask = 0;
        this.vulkanturbo$sectionKey = 0L;
        Arrays.fill(this.vulkanturbo$cachedDraws, null);
        Arrays.fill(this.vulkanturbo$allocations, null);
        Arrays.fill(this.vulkanturbo$buckets, null);
        Arrays.fill(this.vulkanturbo$vertexStrides, 0);
        Arrays.fill(this.vulkanturbo$vertexCounts, 0);
        if (hadDraws && VulkanTurbo.fastChunkPrepEnabled) {
            ChunkRenderPrepOptimizer.bumpMeshUploadEpoch();
        }
    }

    @Inject(method = "isVertexBufferUploaded", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$guardClosedIsVertexUploaded(
        ChunkSectionLayer layer,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (this.vulkanturbo$closed) {
            cir.setReturnValue(false);
            return;
        }
        AtomicBoolean flag = this.vertexBufferUploaded.get(layer);
        if (flag == null) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isIndexBufferUploaded", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$guardClosedIsIndexUploaded(
        ChunkSectionLayer layer,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (this.vulkanturbo$closed) {
            cir.setReturnValue(false);
            return;
        }
        AtomicBoolean flag = this.indexBufferUploaded.get(layer);
        if (flag == null) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "setVertexBufferUploaded", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$guardClosedVertexUpload(ChunkSectionLayer layer, CallbackInfo ci) {
        if (this.vulkanturbo$closed || this.vertexBufferUploaded.get(layer) == null) {
            ci.cancel();
        }
    }

    @Inject(method = "setIndexBufferUploaded", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$guardClosedIndexUpload(ChunkSectionLayer layer, CallbackInfo ci) {
        if (this.vulkanturbo$closed || this.indexBufferUploaded.get(layer) == null) {
            ci.cancel();
        }
    }

    @Inject(method = "getSectionDraw", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$fastGetSectionDraw(
        ChunkSectionLayer layer,
        CallbackInfoReturnable<SectionMesh.SectionDraw> cir
    ) {
        if (this.vulkanturbo$closed) {
            cir.setReturnValue(null);
            return;
        }
        int ord = layer.ordinal();
        if (VulkanTurbo.fastChunkPrepEnabled && ord >= 0 && ord < this.vulkanturbo$cachedDraws.length) {
            cir.setReturnValue(this.vulkanturbo$cachedDraws[ord]);
        }
    }

    @Inject(method = "hasRenderableLayers", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$fastHasRenderableLayers(CallbackInfoReturnable<Boolean> cir) {
        if (this.vulkanturbo$closed) {
            cir.setReturnValue(false);
            return;
        }
        if (VulkanTurbo.fastChunkPrepEnabled) {
            cir.setReturnValue(this.vulkanturbo$hasRenderableDraws);
        }
    }

    @Override
    public TlsfAllocator.Allocation vulkanturbo$getAllocation(int slot) {
        if (this.vulkanturbo$closed || slot < 0 || slot >= this.vulkanturbo$allocations.length) {
            return null;
        }
        return this.vulkanturbo$allocations[slot];
    }

    @Override
    public void vulkanturbo$setAllocation(int slot, TlsfAllocator.Allocation alloc) {
        if (!this.vulkanturbo$closed && slot >= 0 && slot < this.vulkanturbo$allocations.length) {
            this.vulkanturbo$allocations[slot] = alloc;
        }
    }

    @Override
    public SectionMesh.SectionDraw vulkanturbo$getDraw(int layerOrdinal) {
        if (this.vulkanturbo$closed || layerOrdinal < 0 || layerOrdinal >= this.vulkanturbo$cachedDraws.length) {
            return null;
        }
        return this.vulkanturbo$cachedDraws[layerOrdinal];
    }

    @Override
    public boolean vulkanturbo$hasBlockEntities() {
        return !this.vulkanturbo$closed && this.vulkanturbo$hasBlockEntities;
    }

    @Override
    public boolean vulkanturbo$hasRenderableDraws() {
        return !this.vulkanturbo$closed && this.vulkanturbo$hasRenderableDraws;
    }

    @Override
    public TerrainSectionBuckets vulkanturbo$getBuckets(int layerOrdinal) {
        return (this.vulkanturbo$closed || layerOrdinal < 0 || layerOrdinal >= this.vulkanturbo$buckets.length)
            ? null
            : this.vulkanturbo$buckets[layerOrdinal];
    }

    @Override
    public int vulkanturbo$getVertexStride(int layerOrdinal) {
        return (this.vulkanturbo$closed || layerOrdinal < 0 || layerOrdinal >= this.vulkanturbo$vertexStrides.length)
            ? TerrainRegionPipelines.DEFAULT_VERTEX_STRIDE
            : this.vulkanturbo$vertexStrides[layerOrdinal];
    }

    @Override
    public int vulkanturbo$getVertexCount(int layerOrdinal) {
        return (this.vulkanturbo$closed || layerOrdinal < 0 || layerOrdinal >= this.vulkanturbo$vertexCounts.length)
            ? 0
            : this.vulkanturbo$vertexCounts[layerOrdinal];
    }

    @Override
    public long vulkanturbo$getSectionKey() {
        return this.vulkanturbo$closed ? 0L : this.vulkanturbo$sectionKey;
    }

    @Override
    public boolean vulkanturbo$isLayerShifted(int layerOrdinal) {
        return !this.vulkanturbo$closed
            && layerOrdinal >= 0
            && layerOrdinal < 31
            && (this.vulkanturbo$shiftedMask & (1 << layerOrdinal)) != 0;
    }
}
