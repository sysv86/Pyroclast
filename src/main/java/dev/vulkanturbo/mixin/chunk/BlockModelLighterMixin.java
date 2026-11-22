package dev.vulkanturbo.mixin.chunk;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.QuadInstance;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.ChunkPipelineOptimizer;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optimizes `BlockModelLighter.prepareQuadAmbientOcclusion` without relying on fragile bytecode
 * instruction ordinals:
 * 1. Reuses a thread-local mutable scratch `BlockPos` instead of allocating via `pos.relative(direction)`.
 * 2. Skips the dead `cache.getLightCoords(state, level, centerPosition)` lookup on cubic faces (identified
 *    by `pos != this.scratchPos`) where `lightCenter` is immediately overwritten by the neighbor lookup.
 * 3. Reuses the already-fetched `nextState` (`this.scratchPos` lookup) on cubic faces instead of querying
 *    `level.getBlockState(basePosition)` a second time for `shadeCenter`.
 * 4. Reuses the `state` parameter on non-cubic faces instead of calling `level.getBlockState(centerPosition)`.
 */
@Mixin(BlockModelLighter.class)
public class BlockModelLighterMixin {
    @Shadow
    private boolean faceCubic;

    @Shadow
    @Final
    private BlockPos.MutableBlockPos scratchPos;

    @Unique
    private BlockState vulkanturbo$centerState;

    @Unique
    private BlockPos vulkanturbo$centerPos;

    @Unique
    private BlockState vulkanturbo$cachedNextState;
    @Unique
    private long vulkanturbo$cachedNextPosLong = Long.MIN_VALUE;

    @Inject(method = "prepareQuadAmbientOcclusion", at = @At("HEAD"))
    private void vulkanturbo$captureCenterContext(
        BlockAndTintGetter level,
        BlockState state,
        BlockPos centerPosition,
        BakedQuad quad,
        QuadInstance outputInstance,
        CallbackInfo ci
    ) {
        this.vulkanturbo$centerState = state;
        this.vulkanturbo$centerPos = centerPosition;
        this.vulkanturbo$cachedNextState = null;
    }

    @Inject(method = "prepareQuadAmbientOcclusion", at = @At("RETURN"))
    private void vulkanturbo$clearCenterContext(CallbackInfo ci) {
        this.vulkanturbo$centerState = null;
        this.vulkanturbo$centerPos = null;
        this.vulkanturbo$cachedNextState = null;
    }

    @WrapOperation(
        method = "prepareQuadAmbientOcclusion",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;relative(Lnet/minecraft/core/Direction;)Lnet/minecraft/core/BlockPos;"
        )
    )
    private BlockPos vulkanturbo$reuseFacePos(BlockPos pos, Direction direction, Operation<BlockPos> original) {
        if (!VulkanTurbo.fastChunkPrepEnabled) {
            return original.call(pos, direction);
        }
        return ChunkPipelineOptimizer.reuseAoFacePos(pos, direction);
    }

    @WrapOperation(
        method = "prepareQuadAmbientOcclusion",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/BlockModelLighter$Cache;getLightCoords(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)I"
        )
    )
    private int vulkanturbo$skipDeadCubicCenterLight(
        BlockModelLighter.Cache cache,
        BlockState state,
        BlockAndTintGetter level,
        BlockPos pos,
        Operation<Integer> original
    ) {
        // Every neighbor lookup uses this.scratchPos; only the center lookup passes centerPosition.
        if (VulkanTurbo.fastChunkPrepEnabled && this.faceCubic && pos != this.scratchPos && pos == this.vulkanturbo$centerPos) {
            return 0;
        }
        return original.call(cache, state, level, pos);
    }

    @WrapOperation(
        method = "prepareQuadAmbientOcclusion",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        )
    )
    private BlockState vulkanturbo$reuseCenterAndBaseState(
        BlockAndTintGetter level,
        BlockPos pos,
        Operation<BlockState> original
    ) {
        if (!VulkanTurbo.fastChunkPrepEnabled) {
            return original.call(level, pos);
        }
        if (pos == this.scratchPos) {
            BlockState state = original.call(level, pos);
            this.vulkanturbo$cachedNextState = state;
            this.vulkanturbo$cachedNextPosLong = pos.asLong();
            return state;
        }
        if (this.faceCubic) {
            BlockState cached = this.vulkanturbo$cachedNextState;
            this.vulkanturbo$cachedNextState = null;
            if (cached != null && pos.asLong() == this.vulkanturbo$cachedNextPosLong) {
                return cached;
            }
        } else if (pos == this.vulkanturbo$centerPos && this.vulkanturbo$centerState != null) {
            return this.vulkanturbo$centerState;
        }
        return original.call(level, pos);
    }
}
