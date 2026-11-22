package dev.vulkanturbo.mixin.chunk;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.AsyncOcclusionCoordinator;
import dev.vulkanturbo.core.ChunkPipelineOptimizer;
import dev.vulkanturbo.core.ChunkRenderPrepOptimizer;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import net.minecraft.core.BlockPos;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Optimizes `SectionOcclusionGraph`:
 * 1. Eliminates `Vector3d` (`checkPos`, `step`) and per-step `BlockPos.containing` heap allocations
 *    inside the distant-section ray-march loop of `runUpdates` without fragile ordinals.
 * 2. Runs both full and partial BFS visibility updates asynchronously off the render thread via
 *    `AsyncOcclusionCoordinator` with immutable camera/chunk snapshots and race-free render-thread
 *    handoff when `asyncOcclusionCulling` is enabled.
 * 3. Invalidates retained region draw plans whenever frustum visibility sets are rebuilt.
 */
@Mixin(SectionOcclusionGraph.class)
public class SectionOcclusionGraphMixin {
    @Shadow
    private boolean needsFullUpdate;

    @Shadow
    private ViewArea viewArea;

    @Shadow
    @Final
    private AtomicReference<?> currentGraph;

    @Shadow
    @Final
    private AtomicBoolean needsFrustumUpdate;

    @Shadow
    @Final
    private LongOpenHashSet emptySections;

    @Shadow
    @Final
    private LongOpenHashSet loadedChunks;

    @Unique
    private AsyncOcclusionCoordinator vulkanturbo$asyncCoordinator;

    @Unique
    private boolean vulkanturbo$wasAsyncEnabled;

    @Unique
    private AsyncOcclusionCoordinator vulkanturbo$coordinator() {
        AsyncOcclusionCoordinator c = this.vulkanturbo$asyncCoordinator;
        if (c == null) {
            c = new AsyncOcclusionCoordinator();
            this.vulkanturbo$asyncCoordinator = c;
        }
        return c;
    }

    @Inject(method = "waitAndReset", at = @At("HEAD"))
    private void vulkanturbo$resetAsyncCoordinator(ViewArea area, CallbackInfo ci) {
        AsyncOcclusionCoordinator c = this.vulkanturbo$asyncCoordinator;
        if (c != null) {
            c.waitAndReset();
        }
    }

    @Inject(method = "updateEmptySections", at = @At("RETURN"))
    private void vulkanturbo$markEmptySectionsDirty(
        LongOpenHashSet added,
        LongOpenHashSet removed,
        CallbackInfo ci
    ) {
        if ((!added.isEmpty() || !removed.isEmpty()) && VulkanTurbo.asyncOcclusionCullingEnabled) {
            this.vulkanturbo$coordinator().markEmptyDirty();
        }
    }

    @Inject(method = "updateLoadedChunks", at = @At("RETURN"))
    private void vulkanturbo$markLoadedChunksDirty(
        LongOpenHashSet added,
        LongOpenHashSet removed,
        CallbackInfo ci
    ) {
        if ((!added.isEmpty() || !removed.isEmpty()) && VulkanTurbo.asyncOcclusionCullingEnabled) {
            this.vulkanturbo$coordinator().markLoadedDirty();
        }
    }

    @Inject(method = "update", at = @At("HEAD"))
    private void vulkanturbo$syncAsyncModeAndDrain(
        CameraRenderState camera,
        int fov,
        ChunkLoadingRenderState chunkLoading,
        CallbackInfo ci
    ) {
        boolean asyncEnabled = VulkanTurbo.asyncOcclusionCullingEnabled;
        if (asyncEnabled != this.vulkanturbo$wasAsyncEnabled) {
            this.vulkanturbo$wasAsyncEnabled = asyncEnabled;
            if (this.vulkanturbo$asyncCoordinator != null) {
                this.vulkanturbo$asyncCoordinator.waitAndReset();
            }
            this.needsFullUpdate = true;
        }
        if (asyncEnabled) {
            this.vulkanturbo$coordinator().drainCompleted(this.currentGraph, this.needsFrustumUpdate);
        }
    }

    @Inject(method = "scheduleFullUpdate", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$scheduleAsyncFullUpdate(CameraRenderState camera, CallbackInfo ci) {
        if (!VulkanTurbo.asyncOcclusionCullingEnabled) {
            return;
        }
        this.needsFullUpdate = false;
        this.vulkanturbo$coordinator().enqueueFull(
            (SectionOcclusionGraph) (Object) this,
            this.viewArea,
            camera,
            this.emptySections,
            this.loadedChunks
        );
        ci.cancel();
    }

    @Inject(method = "runPartialUpdate", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$scheduleAsyncPartialUpdate(
        CameraRenderState camera,
        LongSet loadedExpectedChunks,
        CallbackInfo ci
    ) {
        if (!VulkanTurbo.asyncOcclusionCullingEnabled) {
            return;
        }
        this.vulkanturbo$coordinator().enqueuePartial(
            (SectionOcclusionGraph) (Object) this,
            this.viewArea,
            camera,
            loadedExpectedChunks,
            this.emptySections,
            this.loadedChunks,
            this.currentGraph.get()
        );
        ci.cancel();
    }

    @Inject(method = "schedulePropagationFrom", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$enqueueAsyncPropagation(SectionRenderDispatcher.RenderSection section, CallbackInfo ci) {
        if (VulkanTurbo.asyncOcclusionCullingEnabled && section != null) {
            this.vulkanturbo$coordinator().enqueuePropagation(section);
            ci.cancel();
        }
    }

    @Inject(method = "consumeFrustumUpdate", at = @At("HEAD"))
    private void vulkanturbo$drainBeforeConsumeFrustum(CallbackInfoReturnable<Boolean> cir) {
        if (VulkanTurbo.asyncOcclusionCullingEnabled) {
            this.vulkanturbo$coordinator().drainCompleted(this.currentGraph, this.needsFrustumUpdate);
        }
    }

    @Inject(method = "expectedChunks", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$getPublishedExpectedChunks(CallbackInfoReturnable<LongCollection> cir) {
        if (VulkanTurbo.asyncOcclusionCullingEnabled) {
            AsyncOcclusionCoordinator c = this.vulkanturbo$coordinator();
            c.drainCompleted(this.currentGraph, this.needsFrustumUpdate);
            cir.setReturnValue(c.expectedChunksSnapshot());
        }
    }

    @Inject(method = "getNode", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$getAsyncWorkerNode(
        SectionRenderDispatcher.RenderSection section,
        CallbackInfoReturnable<SectionOcclusionGraph.Node> cir
    ) {
        if (VulkanTurbo.asyncOcclusionCullingEnabled && section != null) {
            SectionOcclusionGraph.Node node = this.vulkanturbo$coordinator().getWorkerNode(section);
            if (node != null) {
                cir.setReturnValue(node);
            }
        }
    }

    @Inject(method = "addSectionsInFrustum", at = @At("HEAD"))
    private void vulkanturbo$drainBeforeAddSections(
        Frustum frustum,
        List<SectionRenderDispatcher.RenderSection> visibleSections,
        List<SectionRenderDispatcher.RenderSection> nearbyVisibleSections,
        CallbackInfo ci
    ) {
        if (VulkanTurbo.asyncOcclusionCullingEnabled) {
            this.vulkanturbo$coordinator().drainCompleted(this.currentGraph, this.needsFrustumUpdate);
        }
    }

    @Inject(method = "addSectionsInFrustum", at = @At("RETURN"))
    private void vulkanturbo$onAddSectionsInFrustum(
        Frustum frustum,
        List<SectionRenderDispatcher.RenderSection> visibleSections,
        List<SectionRenderDispatcher.RenderSection> nearbyVisibleSections,
        CallbackInfo ci
    ) {
        if (VulkanTurbo.fastChunkPrepEnabled) {
            ChunkRenderPrepOptimizer.bumpVisibleSectionsEpoch();
        }
    }

    @WrapOperation(
        method = "runUpdates",
        at = @At(value = "NEW", target = "(DDD)Lorg/joml/Vector3d;")
    )
    private Vector3d vulkanturbo$reuseRayVectors(
        double x,
        double y,
        double z,
        Operation<Vector3d> original
    ) {
        if (!VulkanTurbo.fastChunkPrepEnabled) {
            return original.call(x, y, z);
        }
        return ChunkPipelineOptimizer.reuseOcclusionVec3dPair(x, y, z);
    }

    @WrapOperation(
        method = "runUpdates",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;containing(DDD)Lnet/minecraft/core/BlockPos;"
        )
    )
    private BlockPos vulkanturbo$reuseRayMarchPos(
        double x,
        double y,
        double z,
        Operation<BlockPos> original
    ) {
        if (!VulkanTurbo.fastChunkPrepEnabled) {
            return original.call(x, y, z);
        }
        return ChunkPipelineOptimizer.reuseOcclusionBlockPos(x, y, z);
    }
}
