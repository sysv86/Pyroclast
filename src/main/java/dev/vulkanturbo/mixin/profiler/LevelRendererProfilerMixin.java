package dev.vulkanturbo.mixin.profiler;

import dev.vulkanturbo.core.FrameProfiler;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LevelRenderer.class, priority = 500)
public class LevelRendererProfilerMixin {
    @Inject(method = "prepareChunkRendersIndirect", at = @At("HEAD"))
    private void vulkanturbo$beginChunkPrep(Matrix4fc modelViewMatrix, boolean sortTranslucent, CallbackInfoReturnable<ChunkSectionsToRender> cir) {
        if (FrameProfiler.isEnabled()) {
            FrameProfiler.beginChunkPrep();
        }
    }

    @Inject(method = "prepareChunkRendersIndirect", at = @At("RETURN"))
    private void vulkanturbo$endChunkPrep(Matrix4fc modelViewMatrix, boolean sortTranslucent, CallbackInfoReturnable<ChunkSectionsToRender> cir) {
        if (FrameProfiler.isEnabled()) {
            FrameProfiler.endChunkPrep();
        }
    }
}
