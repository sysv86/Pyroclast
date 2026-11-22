package dev.vulkanturbo.mixin.vulkan;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import dev.vulkanturbo.core.RenderStats;
import dev.vulkanturbo.core.VulkanFrontendRenderPassAccessor;
import dev.vulkanturbo.core.VulkanRenderPassStateCache;
import dev.vulkanturbo.core.VulkanTrackedRenderPass;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

/**
 * Skips redundant `setPipeline` work when the requested pipeline is already bound on this
 * `FrontendRenderPass`, reuses existing `TextureViewAndSampler` bindings when identical
 * texture view and sampler references are re-bound across draws, and exposes MRT color-view
 * overrides for active shader engines while restoring original attachments on 1-target pipelines.
 */
@Mixin(FrontendRenderPass.class)
public class FrontendRenderPassMixin implements VulkanFrontendRenderPassAccessor {
    @Shadow
    @Final
    private RenderPassBackend backend;

    @Shadow
    @Final
    @Mutable
    private List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments;

    @Shadow
    private FrontendRenderPipeline boundPipeline;

    @Shadow
    @Final
    protected HashMap<String, Object> uniforms;

    @Shadow
    private boolean constantsPushed;

    @Unique
    private List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> vulkanturbo$originalColorAttachments;

    @Unique
    private VulkanGpuTextureView[] vulkanturbo$originalColorViews;

    /** Attachment list and views of the last applied MRT override, so a later multi-target
     * pipeline (e.g. the translucent G-Buffer after a vanilla sky/cloud draw restored the
     * vanilla attachments) can re-apply them symmetrically. */
    @Unique
    private List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> vulkanturbo$overriddenColorAttachments;

    @Unique
    private VulkanGpuTextureView[] vulkanturbo$overriddenColorViews;

    @Override
    public RenderPassBackend vtBackendRenderPass() {
        return this.backend;
    }

    @Override
    public boolean vtApplyColorViewsOverride(VulkanGpuTextureView[] colorViews) {
        if (colorViews == null || colorViews.length == 0) {
            return false;
        }
        if (this.vulkanturbo$overriddenColorAttachments != null
            && this.colorAttachments == this.vulkanturbo$overriddenColorAttachments
            && this.vulkanturbo$overriddenColorViews != null
            && java.util.Arrays.equals(this.vulkanturbo$overriddenColorViews, colorViews)) {
            return true;
        }
        if (this.vulkanturbo$originalColorAttachments == null) {
            List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> orig = this.colorAttachments;
            this.vulkanturbo$originalColorAttachments = orig;
            VulkanGpuTextureView[] origViews = new VulkanGpuTextureView[orig.size()];
            for (int i = 0; i < orig.size(); i++) {
                RenderPassDescriptor.Attachment<Optional<Vector4fc>> att = orig.get(i);
                origViews[i] = (att != null && att.textureView() instanceof VulkanGpuTextureView vkView) ? vkView : null;
            }
            this.vulkanturbo$originalColorViews = origViews;
        }

        List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> orig = this.vulkanturbo$originalColorAttachments;
        List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> updated = new ArrayList<>(colorViews.length);
        for (int i = 0; i < colorViews.length; i++) {
            VulkanGpuTextureView view = colorViews[i];
            if (i < orig.size() && orig.get(i) != null && orig.get(i).textureView() == view) {
                updated.add(orig.get(i));
            } else if (view != null) {
                updated.add(new RenderPassDescriptor.Attachment<>(view, Optional.empty()));
            } else {
                updated.add(null);
            }
        }
        this.colorAttachments = updated;
        this.vulkanturbo$overriddenColorAttachments = updated;
        this.vulkanturbo$overriddenColorViews = colorViews.clone();
        this.boundPipeline = null;
        if (this.backend instanceof VulkanRenderPassStateCache cache) {
            cache.vtInvalidateStateCache();
        }
        if (this.backend instanceof VulkanTrackedRenderPass tracked) {
            return tracked.vtOverrideColorViews(colorViews);
        }
        return false;
    }

    @Override
    public void vtResetBoundPipeline() {
        this.boundPipeline = null;
    }

    @Inject(method = "setPipeline", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$skipRedundantSetPipeline(CompiledRenderPipeline pipeline, CallbackInfo ci) {
        if (this.vulkanturbo$originalColorAttachments != null && pipeline instanceof FrontendRenderPipeline fp) {
            int targetCount = fp.colorTargetStates().size();
            if (targetCount != this.colorAttachments.size()
                && targetCount == this.vulkanturbo$originalColorAttachments.size()) {
                this.colorAttachments = this.vulkanturbo$originalColorAttachments;
                if (this.backend instanceof VulkanTrackedRenderPass tracked) {
                    tracked.vtOverrideColorViews(this.vulkanturbo$originalColorViews);
                }
                this.boundPipeline = null;
                if (this.backend instanceof VulkanRenderPassStateCache cache) {
                    cache.vtInvalidateStateCache();
                }
            } else if (targetCount != this.colorAttachments.size()
                && this.vulkanturbo$overriddenColorAttachments != null
                && targetCount == this.vulkanturbo$overriddenColorAttachments.size()) {
                // A vanilla pipeline restored the original attachments and now a multi-target
                // G-Buffer pipeline binds again (e.g. translucent terrain after the sky draw):
                // re-apply the last MRT override symmetrically.
                this.colorAttachments = this.vulkanturbo$overriddenColorAttachments;
                this.boundPipeline = null;
                if (this.backend instanceof VulkanTrackedRenderPass tracked) {
                    tracked.vtOverrideColorViews(this.vulkanturbo$overriddenColorViews);
                }
                if (this.backend instanceof VulkanRenderPassStateCache cache) {
                    cache.vtInvalidateStateCache();
                }
            }
        }
        if (RenderStats.dedupEnabled()
            && pipeline != null
            && this.boundPipeline == pipeline
            && (!(pipeline instanceof FrontendRenderPipeline fp2)
                || fp2.colorTargetStates().size() == this.colorAttachments.size())) {
            if (this.boundPipeline.pushConstantSize() > 0) {
                this.constantsPushed = false;
            }
            ci.cancel();
        }
    }

    @Inject(
        method = "setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void vulkanturbo$reuseTextureViewAndSampler(
        String name,
        GpuTextureView textureView,
        GpuSampler sampler,
        CallbackInfo ci
    ) {
        if (!RenderStats.dedupEnabled() || textureView == null || sampler == null) {
            return;
        }
        Object current = this.uniforms.get(name);
        if (current instanceof TextureViewAndSampler tvs
            && tvs.view() == textureView
            && tvs.sampler() == sampler) {
            if (this.boundPipeline != null) {
                int uniformIndex = this.boundPipeline.uniformIndices().getOrDefault(name, -1);
                if (uniformIndex != -1) {
                    this.backend.setUniform(uniformIndex, tvs);
                }
            }
            ci.cancel();
        }
    }
}
