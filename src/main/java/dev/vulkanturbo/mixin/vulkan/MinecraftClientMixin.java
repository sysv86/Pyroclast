package dev.vulkanturbo.mixin.vulkan;

import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.diagnostics.VulkanDiagnosticLog;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Delivers the once-per-session VulkanTurbo backend status banner, ticks unified diagnostic
 * notices, and marks clean client shutdown on {@code stop}/{@code close}.
 */
@Mixin(Minecraft.class)
public class MinecraftClientMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void vulkanturbo$flushBackendChatNotice(CallbackInfo ci) {
        VulkanTurbo.onClientTick((Minecraft) (Object) this);
    }

    @Inject(method = "stop", at = @At("HEAD"), require = 0)
    private void vulkanturbo$onStopHead(CallbackInfo ci) {
        VulkanDiagnosticLog.markCleanShutdown();
    }

    @Inject(method = "close", at = @At("HEAD"), require = 0)
    private void vulkanturbo$onCloseHead(CallbackInfo ci) {
        VulkanDiagnosticLog.markCleanShutdown();
    }
}
