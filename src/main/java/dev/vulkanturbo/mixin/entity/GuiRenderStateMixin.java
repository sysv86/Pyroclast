package dev.vulkanturbo.mixin.entity;

import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.EntityRenderOptimizer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.ScreenArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Replaces `List.iterator()` and `ScreenRectangle.intersects` dispatch in
 * `GuiRenderState.hasIntersection` with an indexed, scalar bounds loop.
 */
@Mixin(GuiRenderState.class)
public class GuiRenderStateMixin {
    @Inject(method = "hasIntersection", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$fastHasIntersection(
        ScreenRectangle rect,
        List<ScreenArea> list,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (VulkanTurbo.modEnabled) {
            cir.setReturnValue(EntityRenderOptimizer.guiHasIntersectionFast(rect, list));
        }
    }
}
