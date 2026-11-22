package dev.vulkanturbo.mixin.vulkan;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import dev.vulkanturbo.api.shader.TurboShadersBridge;
import dev.vulkanturbo.core.FrameProfiler;
import dev.vulkanturbo.core.RenderStats;
import dev.vulkanturbo.core.VulkanRenderPassStateCache;
import dev.vulkanturbo.core.VulkanSyncOptimizer;
import it.unimi.dsi.fastutil.objects.ReferenceList;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * Drops graphics state changes that are already in effect, including pipeline binds, vertex/index
 * buffer binds, scissor state, and identical descriptor slot bindings, while exposing
 * {@link VulkanRenderPassStateCache#vtInvalidateStateCache()} and invoking
 * {@link TurboShadersBridge#onBindCustomDescriptorSets} when a shader pack is active.
 */
@Mixin(VulkanRenderPass.class)
public abstract class VulkanRenderPassMixin implements VulkanRenderPassStateCache {
    @Shadow
    public abstract VkCommandBuffer commandBuffer();

    @Shadow
    protected VulkanRenderPipeline pipeline;

    @Shadow
    @Final
    protected ReferenceList<Object> uniforms;

    @Unique
    private long vulkanturbo$boundPipeline = 0L;

    @Unique
    private long vulkanturbo$lastBoundCustomLayout = 0L;

    @Unique
    private long vulkanturbo$lastBoundBindlessSet = 0L;

    @Unique
    private long vulkanturbo$lastBoundPackSet = 0L;

    @Unique
    private final long[] vulkanturbo$boundVboHandles = new long[4];

    @Unique
    private final long[] vulkanturbo$boundVboOffsets = new long[4];

    @Unique
    private long vulkanturbo$boundIboHandle = 0L;

    @Unique
    private int vulkanturbo$boundIboType = -1;

    @Unique
    private boolean vulkanturbo$scissorKnown = false;

    @Unique
    private boolean vulkanturbo$scissorEnabled = false;

    @Unique
    private int vulkanturbo$scissorX;
    @Unique
    private int vulkanturbo$scissorY;
    @Unique
    private int vulkanturbo$scissorWidth;
    @Unique
    private int vulkanturbo$scissorHeight;

    @Override
    public void vtInvalidateStateCache() {
        this.vulkanturbo$boundPipeline = 0L;
        this.vulkanturbo$lastBoundCustomLayout = 0L;
        this.vulkanturbo$lastBoundBindlessSet = 0L;
        this.vulkanturbo$lastBoundPackSet = 0L;
        Arrays.fill(this.vulkanturbo$boundVboHandles, 0L);
        Arrays.fill(this.vulkanturbo$boundVboOffsets, 0L);
        this.vulkanturbo$boundIboHandle = 0L;
        this.vulkanturbo$boundIboType = -1;
        this.vulkanturbo$scissorKnown = false;
    }

    @Inject(method = "close", at = @At("HEAD"), require = 0)
    private void vulkanturbo$resetCustomLayoutOnClose(CallbackInfo ci) {
        this.vulkanturbo$lastBoundCustomLayout = 0L;
        this.vulkanturbo$lastBoundBindlessSet = 0L;
        this.vulkanturbo$lastBoundPackSet = 0L;
    }

    @Redirect(
        method = "setPipeline",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCmdBindPipeline(Lorg/lwjgl/vulkan/VkCommandBuffer;IJ)V"
        )
    )
    private void vulkanturbo$bindPipeline(VkCommandBuffer commandBuffer, int bindPoint, long pipeline) {
        boolean redundant = pipeline == vulkanturbo$boundPipeline;
        RenderStats.pipelineBind(redundant);

        if (redundant && RenderStats.dedupEnabled()) {
            return;
        }

        vulkanturbo$boundPipeline = pipeline;
        VK12.vkCmdBindPipeline(commandBuffer, bindPoint, pipeline);
        if (TurboShadersBridge.active && this.pipeline != null) {
            long pipelineLayout = this.pipeline.pipelineLayout();
            // Never bind custom descriptors on vanilla GUI/post pipelines, whose layouts were
            // compiled without the extra sets. The bridge derives the exact firstSet from the
            // registry populated at pipeline creation time.
            if (TurboShadersBridge.isPipelineLayoutRegistered(pipelineLayout)) {
                long bindlessSet = TurboShadersBridge.cachedBindlessDescriptorSet;
                if (bindlessSet != 0L) {
                    long packSet = TurboShadersBridge.cachedPackDescriptorSet;
                    if (pipelineLayout != this.vulkanturbo$lastBoundCustomLayout
                        || bindlessSet != this.vulkanturbo$lastBoundBindlessSet
                        || packSet != this.vulkanturbo$lastBoundPackSet) {
                        int firstSet = TurboShadersBridge.getFirstSetForPipelineLayout(pipelineLayout);
                        try (MemoryStack stack = MemoryStack.stackPush()) {
                            LongBuffer sets = packSet != 0L
                                ? stack.longs(bindlessSet, packSet)
                                : stack.longs(bindlessSet);
                            VK12.vkCmdBindDescriptorSets(
                                commandBuffer,
                                bindPoint,
                                pipelineLayout,
                                firstSet,
                                sets,
                                null
                            );
                        }
                        this.vulkanturbo$lastBoundCustomLayout = pipelineLayout;
                        this.vulkanturbo$lastBoundBindlessSet = bindlessSet;
                        this.vulkanturbo$lastBoundPackSet = packSet;
                    }
                } else {
                    this.vulkanturbo$lastBoundCustomLayout = 0L;
                    this.vulkanturbo$lastBoundBindlessSet = 0L;
                    this.vulkanturbo$lastBoundPackSet = 0L;
                    TurboShadersBridge.onBindCustomDescriptorSets(
                        commandBuffer.address(),
                        pipelineLayout,
                        bindPoint,
                        TurboShadersBridge.PASS_KIND_GENERAL
                    );
                }
            } else {
                this.vulkanturbo$lastBoundCustomLayout = 0L;
                this.vulkanturbo$lastBoundBindlessSet = 0L;
                this.vulkanturbo$lastBoundPackSet = 0L;
            }
        }
    }

    @Inject(method = "setUniform", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$dedupUniform(int index, Object value, CallbackInfo ci) {
        if (!RenderStats.dedupEnabled()) {
            return;
        }
        if (index >= 0 && index < this.uniforms.size() && Objects.equals(this.uniforms.get(index), value)) {
            ci.cancel();
        }
    }

    @Inject(method = "enableScissor", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$enableScissor(int x, int y, int width, int height, CallbackInfo ci) {
        boolean redundant = vulkanturbo$scissorKnown
            && vulkanturbo$scissorEnabled
            && vulkanturbo$scissorX == x
            && vulkanturbo$scissorY == y
            && vulkanturbo$scissorWidth == width
            && vulkanturbo$scissorHeight == height;

        RenderStats.scissorCall(redundant);

        if (redundant && RenderStats.dedupEnabled()) {
            ci.cancel();
            return;
        }

        vulkanturbo$scissorKnown = true;
        vulkanturbo$scissorEnabled = true;
        vulkanturbo$scissorX = x;
        vulkanturbo$scissorY = y;
        vulkanturbo$scissorWidth = width;
        vulkanturbo$scissorHeight = height;
    }

    @Inject(method = "disableScissor", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$disableScissor(CallbackInfo ci) {
        boolean redundant = vulkanturbo$scissorKnown && !vulkanturbo$scissorEnabled;
        RenderStats.scissorCall(redundant);

        if (redundant && RenderStats.dedupEnabled()) {
            ci.cancel();
            return;
        }

        vulkanturbo$scissorKnown = true;
        vulkanturbo$scissorEnabled = false;
    }

    @Inject(method = "setVertexBuffer", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$fastBindVertexBuffer(int slot, GpuBufferSlice slice, CallbackInfo ci) {
        if (slice == null || slice.buffer() == null || !RenderStats.dedupEnabled()) {
            return;
        }
        long vkBuf = ((VulkanGpuBuffer) slice.buffer()).vkBuffer();
        long offset = slice.offset();
        if (slot >= 0 && slot < 4) {
            if (this.vulkanturbo$boundVboHandles[slot] == vkBuf && this.vulkanturbo$boundVboOffsets[slot] == offset) {
                ci.cancel();
                return;
            }
            this.vulkanturbo$boundVboHandles[slot] = vkBuf;
            this.vulkanturbo$boundVboOffsets[slot] = offset;
        }
        VulkanSyncOptimizer.bindVertexBuffer(this.commandBuffer(), slot, vkBuf, offset);
        ci.cancel();
    }

    @Inject(method = "setIndexBuffer", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$dedupIndexBuffer(GpuBuffer buffer, IndexType type, CallbackInfo ci) {
        if (buffer == null || type == null || !RenderStats.dedupEnabled()) {
            return;
        }
        long vkBuf = ((VulkanGpuBuffer) buffer).vkBuffer();
        int typeOrd = type.ordinal();
        if (this.vulkanturbo$boundIboHandle == vkBuf && this.vulkanturbo$boundIboType == typeOrd) {
            ci.cancel();
            return;
        }
        this.vulkanturbo$boundIboHandle = vkBuf;
        this.vulkanturbo$boundIboType = typeOrd;
    }

    @Inject(
        method = {
            "drawIndexed",
            "multiDrawIndexed(Ljava/nio/IntBuffer;III)V",
            "multiDrawIndexed(Lorg/lwjgl/PointerBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V",
            "drawIndexedIndirect",
            "draw",
            "multiDraw(Ljava/nio/IntBuffer;III)V",
            "multiDraw(Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V",
            "drawIndirect"
        },
        at = @At("HEAD")
    )
    private void vulkanturbo$countDraw(CallbackInfo ci) {
        if (FrameProfiler.isEnabled()) {
            FrameProfiler.countDraw();
        }
    }
}
