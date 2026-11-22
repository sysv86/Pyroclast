package dev.vulkanturbo.mixin.profiler;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import dev.vulkanturbo.api.shader.TurboShadersBridge;
import dev.vulkanturbo.core.BenchmarkRecorder;
import dev.vulkanturbo.core.EntityRenderOptimizer;
import dev.vulkanturbo.core.FrameProfiler;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame boundary for the benchmark sampler and post-world / pre-GUI hook for `TurboShadersBridge`.
 *
 * `GameRenderer.renderLevel` is only called when the world itself is rendered, so loading screens and
 * the title screen cannot pollute the sample. Immediately before `render3dHud(...)` at the end of
 * `renderLevel()` (BEFORE `render3dHud` calls `clearDepthTexture(mainRenderTarget.getDepthTexture(), 0.0)`
 * to draw the first-person hand and BEFORE `guiRenderer.render()`), `mainRenderTarget` holds the
 * complete HUD-less world color texture AND intact scene depth buffer, which is forwarded to
 * {@link TurboShadersBridge#notifyAfterWorldRenderBeforeGui}. The 3D hand then renders on top of
 * the post-processed world.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @org.spongepowered.asm.mixin.Unique
    private static boolean vulkanturbo$loggedPostHookError;

    @org.spongepowered.asm.mixin.Unique
    private static boolean vulkanturbo$loggedGuiHookError;

    @Shadow
    @Final
    private RenderTarget mainRenderTarget;

    @Shadow
    @Final
    private GameRenderState gameRenderState;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void vulkanturbo$onFrame(CallbackInfo ci) {
        BenchmarkRecorder.onFrame();
    }

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V",
            shift = At.Shift.BEFORE
        )
    )
    private void vulkanturbo$afterWorldRenderBeforeGui(CallbackInfo ci) {
        if (!TurboShadersBridge.active || !this.gameRenderState.shouldRenderLevel) {
            return;
        }
        if (!(this.mainRenderTarget.getColorTexture() instanceof VulkanGpuTexture colorTex)
            || !(this.mainRenderTarget.getColorTextureView() instanceof VulkanGpuTextureView colorView)
            || !(this.mainRenderTarget.getDepthTexture() instanceof VulkanGpuTexture depthTex)
            || !(this.mainRenderTarget.getDepthTextureView() instanceof VulkanGpuTextureView depthView)) {
            return;
        }
        try {
            var device = RenderSystem.getDevice();
            if (device != null
                && device.createCommandEncoder() instanceof FrontendCommandEncoder frontendEncoder
                && frontendEncoder.backend() instanceof VulkanCommandEncoder vkEncoder) {
                VkCommandBuffer cmd = vkEncoder.allocateAndBeginTransientCommandBuffer();
                boolean hookOk = false;
                try {
                    TurboShadersBridge.notifyAfterWorldRenderBeforeGui(
                        cmd,
                        colorTex,
                        colorView,
                        depthTex,
                        depthView,
                        this.mainRenderTarget.width,
                        this.mainRenderTarget.height
                    );
                    hookOk = true;
                } finally {
                    int endRes = VK12.vkEndCommandBuffer(cmd);
                    if (hookOk && endRes == org.lwjgl.vulkan.VK10.VK_SUCCESS) {
                        vkEncoder.execute(cmd);
                    }
                }
            }
        } catch (Throwable t) {
            if (!vulkanturbo$loggedPostHookError) {
                vulkanturbo$loggedPostHookError = true;
                dev.vulkanturbo.diagnostics.VulkanDiagnosticLog.recordWarning("GameRenderer", "VulkanTurbo post-world shader hook failed cleanly: " + t, null, t);
            }
        }
    }

    /**
     * UI-aware FrameGen hook: fires once in {@code GameRenderer.render()} immediately after
     * {@code GuiRenderer.render()} has composited the GUI over the HUD-less world color and before
     * the frame is presented. {@code mainRenderTarget}'s color texture now holds the composited UI
     * color buffer that must be delivered to {@code RES_UI_COLOR} separately from
     * {@code RES_HUDLESS_COLOR}.
     */
    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V",
            shift = At.Shift.AFTER
        )
    )
    private void vulkanturbo$afterGuiRender(CallbackInfo ci) {
        boolean swapchainOverride = TurboShadersBridge.swapchainOverrideActive;
        if (!TurboShadersBridge.active && !swapchainOverride) {
            return;
        }
        if (!this.gameRenderState.shouldRenderLevel && !swapchainOverride) {
            return;
        }
        if (!TurboShadersBridge.needsAfterGuiPass()) {
            return;
        }
        if (!(this.mainRenderTarget.getColorTexture() instanceof VulkanGpuTexture colorTex)
            || !(this.mainRenderTarget.getColorTextureView() instanceof VulkanGpuTextureView colorView)) {
            return;
        }
        try {
            var device = RenderSystem.getDevice();
            if (device != null
                && device.createCommandEncoder() instanceof FrontendCommandEncoder frontendEncoder
                && frontendEncoder.backend() instanceof VulkanCommandEncoder vkEncoder) {
                VkCommandBuffer cmd = vkEncoder.allocateAndBeginTransientCommandBuffer();
                boolean hookRan = false;
                boolean recordedTransition = false;
                try {
                    if (dev.vulkanturbo.core.VulkanLayoutTracker.isEnabled()) {
                        int prevLayout = TurboShadersBridge.getTrackedImageLayout(colorTex);
                        dev.vulkanturbo.core.VulkanLayoutTracker.transition(
                            cmd,
                            colorTex,
                            org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL
                        );
                        if (prevLayout != org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL) {
                            recordedTransition = true;
                        }
                    }
                    hookRan = TurboShadersBridge.notifyAfterGuiRender(
                        cmd,
                        colorTex,
                        colorView,
                        this.mainRenderTarget.width,
                        this.mainRenderTarget.height
                    );
                } finally {
                    int endRes = VK12.vkEndCommandBuffer(cmd);
                    if ((hookRan || recordedTransition) && endRes == org.lwjgl.vulkan.VK10.VK_SUCCESS) {
                        vkEncoder.execute(cmd);
                    }
                }
            }
        } catch (Throwable t) {
            if (!vulkanturbo$loggedGuiHookError) {
                vulkanturbo$loggedGuiHookError = true;
                dev.vulkanturbo.diagnostics.VulkanDiagnosticLog.recordWarning("GameRenderer", "VulkanTurbo post-GUI shader hook failed cleanly: " + t, null, t);
            }
        }
    }

    @Inject(method = "extract", at = @At("HEAD"))
    private void vulkanturbo$beginExtract(CallbackInfo ci) {
        if (FrameProfiler.isEnabled()) {
            FrameProfiler.beginExtract();
        }
    }

    @Inject(method = "extract", at = @At("RETURN"))
    private void vulkanturbo$endExtract(CallbackInfo ci) {
        if (FrameProfiler.isEnabled()) {
            FrameProfiler.endExtract();
        }
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void vulkanturbo$beginRender(CallbackInfo ci) {
        if (FrameProfiler.isEnabled()) {
            FrameProfiler.beginRender();
            FrameProfiler.onFrameStartTimestamp();
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void vulkanturbo$endRender(CallbackInfo ci) {
        EntityRenderOptimizer.resetPosePool();
        if (FrameProfiler.isEnabled()) {
            FrameProfiler.onBeforeSubmitTimestamp();
            FrameProfiler.endRender();
        }
    }
}
