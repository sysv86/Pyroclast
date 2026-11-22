package dev.vulkanturbo.mixin.chunk;

import dev.vulkanturbo.core.ChunkPipelineOptimizer;
import dev.vulkanturbo.core.SectionTaskQueueAccess;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionTaskDynamicQueue;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Replaces linear `ListIterator` + `O(N)` `ArrayList.remove` shifts in `SectionTaskDynamicQueue.poll`
 * with indexed array scans and `O(1)` swap-remove under `synchronized`, preventing worker contention
 * and render-thread `add()` stalls when many sections are queued.
 */
@Mixin(SectionTaskDynamicQueue.class)
public abstract class SectionTaskDynamicQueueMixin implements SectionTaskQueueAccess {
    @Shadow
    private int recompileQuota;

    @Shadow
    @Final
    private List<SectionRenderDispatcher.RenderSection.SectionTask> tasks;

    @Override
    public int vulkanturbo$getRecompileQuota() {
        return this.recompileQuota;
    }

    @Override
    public void vulkanturbo$setRecompileQuota(int quota) {
        this.recompileQuota = quota;
    }

    @Override
    public boolean vulkanturbo$isTaskCancelled(SectionRenderDispatcher.RenderSection.SectionTask task) {
        return ((ChunkSectionTaskAccessor) (Object) task).vulkanturbo$isCancelled().get();
    }

    @Inject(method = "poll", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$fastPoll(
        Vec3 cameraPos,
        CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.SectionTask> cir
    ) {
        if (!ChunkPipelineOptimizer.isFastQueuePollEnabled()) {
            return;
        }
        if (this.tasks instanceof ObjectArrayList<SectionRenderDispatcher.RenderSection.SectionTask> list) {
            cir.setReturnValue(ChunkPipelineOptimizer.pollNearestSectionTask(this, list, cameraPos));
        }
    }
}
