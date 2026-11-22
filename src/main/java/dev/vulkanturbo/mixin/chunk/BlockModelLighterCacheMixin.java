package dev.vulkanturbo.mixin.chunk;

import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.ChunkPipelineOptimizer;
import it.unimi.dsi.fastutil.longs.Long2FloatLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces `BlockModelLighter.Cache`'s `Long2IntLinkedOpenHashMap` and `Long2FloatLinkedOpenHashMap`
 * LRU node-unlinking and 8 KB per-section `clear()` zeroing with `ChunkPipelineOptimizer.LighterFlatCache`
 * (256-slot 2-way set-associative L1 cache with O(1) stamp invalidation).
 */
@Mixin(BlockModelLighter.Cache.class)
public class BlockModelLighterCacheMixin {
    @Shadow
    private boolean enabled;

    @Shadow
    @Final
    private Long2IntLinkedOpenHashMap colorCache;

    @Shadow
    @Final
    private Long2FloatLinkedOpenHashMap brightnessCache;

    @Shadow
    @Final
    @Mutable
    private LightCoordsUtil.BrightnessGetter cachedBrightnessGetter;

    @Unique
    private ChunkPipelineOptimizer.LighterFlatCache vulkanturbo$flatCache;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void vulkanturbo$initFlatCache(CallbackInfo ci) {
        ChunkPipelineOptimizer.LighterFlatCache flat = new ChunkPipelineOptimizer.LighterFlatCache();
        this.vulkanturbo$flatCache = flat;
        LightCoordsUtil.BrightnessGetter fallbackGetter = this.cachedBrightnessGetter;
        this.cachedBrightnessGetter = (level, pos) -> {
            if (VulkanTurbo.fastChunkPrepEnabled && this.enabled) {
                return flat.getPackedBrightness(level, pos);
            }
            return fallbackGetter.packedBrightness(level, pos);
        };
    }

    @Inject(method = "getShadeBrightness", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$fastGetShadeBrightness(
        BlockState state,
        BlockAndTintGetter level,
        BlockPos pos,
        CallbackInfoReturnable<Float> cir
    ) {
        if (!VulkanTurbo.fastChunkPrepEnabled) {
            return;
        }
        if (!this.enabled) {
            cir.setReturnValue(state.getShadeBrightness(level, pos));
            return;
        }
        cir.setReturnValue(this.vulkanturbo$flatCache.getShadeBrightness(state, level, pos));
    }

    @Inject(method = "disable", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$fastDisable(CallbackInfo ci) {
        if (this.vulkanturbo$flatCache != null) {
            this.vulkanturbo$flatCache.clear();
        }
        if (!VulkanTurbo.fastChunkPrepEnabled) {
            return;
        }
        this.enabled = false;
        if (!this.colorCache.isEmpty()) {
            this.colorCache.clear();
        }
        if (!this.brightnessCache.isEmpty()) {
            this.brightnessCache.clear();
        }
        ci.cancel();
    }
}
