package dev.vulkanturbo.mixin.entity;

import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.EntityRenderOptimizer;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeLayer;
import net.minecraft.world.attribute.SpatialAttributeInterpolator;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Caches positional environment attribute layers into a flat array on `ValueSampler` creation so
 * per-entity/per-frame biome/fog/sky sampling does not allocate `ImmutableList$ListItr`.
 */
@Mixin(targets = "net.minecraft.world.attribute.EnvironmentAttributeSystem$ValueSampler")
public class EnvironmentAttributeValueSamplerMixin {
    @Shadow
    @Final
    private EnvironmentAttribute<Object> attribute;

    @Shadow
    @Final
    private Object baseValue;

    @Shadow
    @Final
    private List<EnvironmentAttributeLayer<Object>> layers;

    @Shadow
    private int cacheTickId;

    @Unique
    private EnvironmentAttributeLayer<Object>[] vulkanturbo$layerArray;

    @SuppressWarnings("unchecked")
    @Inject(method = "<init>", at = @At("RETURN"))
    private void vulkanturbo$cacheLayersArray(CallbackInfo ci) {
        this.vulkanturbo$layerArray = this.layers.toArray(new EnvironmentAttributeLayer[0]);
    }

    @Inject(method = "computeValuePositional", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$computeValuePositionalFast(
        Vec3 pos,
        SpatialAttributeInterpolator interpolator,
        CallbackInfoReturnable<Object> cir
    ) {
        if (VulkanTurbo.modEnabled && this.vulkanturbo$layerArray != null) {
            cir.setReturnValue(
                EntityRenderOptimizer.evaluatePositionalAttributesFast(
                    this.attribute,
                    this.baseValue,
                    this.vulkanturbo$layerArray,
                    this.cacheTickId,
                    pos,
                    interpolator
                )
            );
        }
    }
}
