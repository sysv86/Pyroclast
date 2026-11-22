package dev.vulkanturbo.mixin.chunk;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import dev.vulkanturbo.core.ChunkPipelineOptimizer;
import dev.vulkanturbo.core.TerrainRegionPipelines;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Resolves bundled `vulkanturbo:*` shader sources cleanly without Fabric Resource Loader
 * and invalidates thread-local model/lighting/occlusion caches on resource reload (`F3+T`).
 */
@Mixin(ShaderManager.Configs.class)
public class ShaderManagerConfigsMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void vulkanturbo$invalidateCachesOnResourceReload(CallbackInfo ci) {
        ChunkPipelineOptimizer.clearAllCaches();
    }

    @Inject(method = "getShader", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$resolveBundledShader(
        Identifier id,
        ShaderType type,
        CallbackInfoReturnable<String> cir
    ) {
        String source = TerrainRegionPipelines.resolveShader(id, type);
        if (source != null) {
            cir.setReturnValue(source);
        }
    }
}
