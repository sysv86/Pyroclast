package dev.vulkanturbo.mixin.entity;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.EntityRenderOptimizer;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Batches `WeatherEffectRenderer.prepareInstances` rain and snow column quads into single
 * 112-byte `DefaultVertexFormat.PARTICLE` reservations and hoists `Mth.floor(cameraPos)`.
 */
@Mixin(WeatherEffectRenderer.class)
public class WeatherEffectRendererMixin {
    @Shadow
    @Final
    private float[] columnSizeX;

    @Shadow
    @Final
    private float[] columnSizeZ;

    @Inject(method = "prepareInstances", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$fastPrepareWeatherInstances(
        VertexConsumer builder,
        List<WeatherEffectRenderer.ColumnInstance> columns,
        Vec3 cameraPos,
        float maxAlpha,
        int radius,
        float intensity,
        CallbackInfo ci
    ) {
        if (!VulkanTurbo.fastEntityRenderEnabled) {
            return;
        }
        EntityRenderOptimizer.prepareWeatherColumnsFast(
            builder,
            columns,
            cameraPos,
            maxAlpha,
            radius,
            intensity,
            this.columnSizeX,
            this.columnSizeZ
        );
        ci.cancel();
    }
}
