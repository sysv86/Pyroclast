package dev.vulkanturbo.mixin.vertex;

import com.mojang.blaze3d.vertex.StagingBuffer;
import com.mojang.blaze3d.vertex.TlsfAllocator;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import com.mojang.datafixers.util.Pair;
import com.mojang.renderpearl.api.device.GpuDevice;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.api.shader.TurboShadersBridge;
import dev.vulkanturbo.core.CachedSectionMesh;
import dev.vulkanturbo.core.ChunkRenderPrepOptimizer;
import dev.vulkanturbo.core.FastUberGpuBuffer;
import dev.vulkanturbo.core.TerrainRegionPipelines;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

/**
 * Replaces `UberGpuBuffer`'s node-allocating `HashMap<SectionMesh, Allocation>` with fastutil's
 * open-addressed identity map (`Reference2ObjectOpenHashMap`), short-circuits idle upload passes
 * while ensuring completely-free VRAM heaps are reclaimed, avoids polluting `skippedStagedAllocations`
 * with keys absent from `stagedAllocations`, mirrors live allocations onto `CompiledSectionMesh`,
 * forwards rich section-slice metadata to `TurboShadersBridge`, and bumps `meshUploadEpoch` on mutations.
 */
@Mixin(UberGpuBuffer.class)
public abstract class UberGpuBufferMixin<T> implements FastUberGpuBuffer {
    @Shadow
    @Final
    @Mutable
    private Map<T, TlsfAllocator.Allocation> allocationMap;

    @Shadow
    @Final
    private List<Pair<TlsfAllocator, UberGpuBuffer.UberGpuBufferHeap>> nodes;

    @Shadow
    @Final
    private Object2ObjectOpenHashMap<T, ?> stagedAllocations;

    @Shadow
    @Final
    private ObjectOpenHashSet<T> skippedStagedAllocations;

    @Unique
    private Reference2ObjectOpenHashMap<Object, TlsfAllocator.Allocation> vulkanturbo$fastMap;

    @Unique
    private int vulkanturbo$meshSlot = -1;

    @Unique
    private boolean vulkanturbo$hasFreeHeap;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(method = "<init>", at = @At("RETURN"))
    private void vulkanturbo$useOpenHashMap(CallbackInfo ci) {
        if (!VulkanTurbo.fastChunkPrepEnabled) {
            return;
        }
        Reference2ObjectOpenHashMap<Object, TlsfAllocator.Allocation> map = new Reference2ObjectOpenHashMap<>(256);
        this.vulkanturbo$fastMap = map;
        this.allocationMap = (Map) map;
    }

    @Override
    public void vulkanturbo$setMeshSlot(int slot) {
        this.vulkanturbo$meshSlot = slot;
    }

    @Override
    public boolean vulkanturbo$hasStagedWork() {
        return !this.stagedAllocations.isEmpty()
            || !this.skippedStagedAllocations.isEmpty()
            || this.vulkanturbo$hasFreeHeap;
    }

    @Override
    public TlsfAllocator.Allocation vulkanturbo$getAllocation(Object key) {
        Reference2ObjectOpenHashMap<Object, TlsfAllocator.Allocation> fast = this.vulkanturbo$fastMap;
        return fast != null ? fast.get(key) : this.allocationMap.get(key);
    }

    @Unique
    private boolean vulkanturbo$checkAnyFreeHeap() {
        List<Pair<TlsfAllocator, UberGpuBuffer.UberGpuBufferHeap>> list = this.nodes;
        for (int i = 0, size = list.size(); i < size; i++) {
            if (list.get(i).getFirst().isCompletelyFree()) {
                return true;
            }
        }
        return false;
    }

    @Inject(method = "uploadStagedAllocations", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$skipIdleUpload(
        GpuDevice device,
        StagingBuffer.Uploader uploader,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (VulkanTurbo.fastChunkPrepEnabled && !this.vulkanturbo$hasStagedWork()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "uploadStagedAllocations", at = @At("RETURN"))
    private void vulkanturbo$notifyOnUpload(
        GpuDevice device,
        StagingBuffer.Uploader uploader,
        CallbackInfoReturnable<Boolean> cir
    ) {
        this.vulkanturbo$hasFreeHeap = this.vulkanturbo$checkAnyFreeHeap();
        if (VulkanTurbo.fastChunkPrepEnabled) {
            ChunkRenderPrepOptimizer.bumpMeshUploadEpoch();
        }
    }

    @Redirect(
        method = "removeAllocation",
        at = @At(
            value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/objects/ObjectOpenHashSet;add(Ljava/lang/Object;)Z"
        )
    )
    private boolean vulkanturbo$onlySkipIfStaged(ObjectOpenHashSet<Object> skippedSet, Object key) {
        if (VulkanTurbo.fastChunkPrepEnabled && !this.stagedAllocations.containsKey(key)) {
            return false;
        }
        return skippedSet.add(key);
    }

    @Redirect(
        method = "uploadStagedAllocations",
        at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
    )
    private Object vulkanturbo$putAndCacheAllocation(Map<Object, Object> map, Object key, Object value) {
        TlsfAllocator.Allocation alloc = (TlsfAllocator.Allocation) value;
        CachedSectionMesh cached = key instanceof CachedSectionMesh c ? c : null;
        if (VulkanTurbo.fastChunkPrepEnabled && this.vulkanturbo$meshSlot >= 0 && cached != null) {
            cached.vulkanturbo$setAllocation(this.vulkanturbo$meshSlot, alloc);
        }
        if (TurboShadersBridge.active && this.vulkanturbo$meshSlot >= 0 && (this.vulkanturbo$meshSlot & 1) == 0 && alloc != null) {
            int layerOrdinal = this.vulkanturbo$meshSlot >>> 1;
            @SuppressWarnings("unchecked")
            UberGpuBuffer<?> self = (UberGpuBuffer<?>) (Object) this;
            long sectionKey = cached != null ? cached.vulkanturbo$getSectionKey() : 0L;
            int stride = cached != null
                ? cached.vulkanturbo$getVertexStride(layerOrdinal)
                : TerrainRegionPipelines.DEFAULT_VERTEX_STRIDE;
            int vertexCount = cached != null
                ? cached.vulkanturbo$getVertexCount(layerOrdinal)
                : (int) (alloc.getSize() / Math.max(stride, 1));
            boolean shifted = cached != null && cached.vulkanturbo$isLayerShifted(layerOrdinal);
            TurboShadersBridge.onSectionSliceUploadedDetailed(
                key,
                layerOrdinal,
                sectionKey,
                self.getGpuBuffer(alloc),
                alloc.getOffsetFromHeap(),
                alloc.getSize(),
                stride,
                vertexCount,
                shifted
            );
        }
        return map.put(key, value);
    }

    @Redirect(
        method = "freeAllocation",
        at = @At(value = "INVOKE", target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;")
    )
    private Object vulkanturbo$removeAndClearAllocation(Map<Object, Object> map, Object key) {
        if (VulkanTurbo.fastChunkPrepEnabled && this.vulkanturbo$meshSlot >= 0 && key instanceof CachedSectionMesh cached) {
            cached.vulkanturbo$setAllocation(this.vulkanturbo$meshSlot, null);
        }
        Object removed = map.remove(key);
        if (removed != null) {
            if (VulkanTurbo.fastChunkPrepEnabled) {
                ChunkRenderPrepOptimizer.bumpMeshUploadEpoch();
            }
            if (TurboShadersBridge.INSTANCE.getExtension() != null && this.vulkanturbo$meshSlot >= 0 && (this.vulkanturbo$meshSlot & 1) == 0) {
                TurboShadersBridge.onSectionSliceFreed(key, this.vulkanturbo$meshSlot >>> 1);
            }
        }
        return removed;
    }

    @Inject(method = "freeAllocation", at = @At("RETURN"))
    private void vulkanturbo$trackFreeHeapAfterFree(T allocationKey, CallbackInfo ci) {
        if (!this.vulkanturbo$hasFreeHeap && this.vulkanturbo$checkAnyFreeHeap()) {
            this.vulkanturbo$hasFreeHeap = true;
        }
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void vulkanturbo$onClose(CallbackInfo ci) {
        this.vulkanturbo$hasFreeHeap = false;
        this.skippedStagedAllocations.clear();
    }
}
