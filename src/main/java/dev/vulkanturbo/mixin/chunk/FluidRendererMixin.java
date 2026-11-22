package dev.vulkanturbo.mixin.chunk;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.api.shader.TurboShadersBridge;
import dev.vulkanturbo.core.ChunkPipelineOptimizer;
import dev.vulkanturbo.core.DirectEntityBufferBuilder;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Optimizes `FluidRenderer`:
 * 1. Batches 4-vertex (112B) and 8-vertex double-sided (224B) `DefaultVertexFormat.BLOCK` (as well
 *    as extended `TurboShaders` water/lava PBR vertex formats) fluid faces into a single off-heap
 *    reservation with 64-bit native stores (`ChunkPipelineOptimizer.writeFluidFace`).
 * 2. Eliminates `new float[2]` heap allocations on every corner in `calculateAverageHeight`.
 * 3. Replaces up to 27 per-block `BlockPos` offset allocations (`relative`, `above`, `below`, `north`,
 *    `south`, `east`, `west`) with a 4-slot thread-local `MutableBlockPos` ring.
 */
@Mixin(FluidRenderer.class)
public abstract class FluidRendererMixin {
    @Shadow
    protected abstract float getHeight(BlockAndTintGetter level, Fluid fluidType, BlockPos pos);

    @Inject(method = "addFace", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$fastAddFluidFace(
        VertexConsumer builder,
        float x0, float y0, float z0, float u0, float v0,
        float x1, float y1, float z1, float u1, float v1,
        float x2, float y2, float z2, float u2, float v2,
        float x3, float y3, float z3, float u3, float v3,
        int color,
        int lightCoords,
        boolean addBackFace,
        CallbackInfo ci
    ) {
        if (!(builder instanceof DirectEntityBufferBuilder direct)) {
            return;
        }
        long basePtr = direct.vulkanturbo$reserveBlockVertices(addBackFace ? 8 : 4);
        if (basePtr == -1L) {
            return;
        }
        int stride = direct.vulkanturbo$getVertexSize();
        if (stride == 28) {
            ChunkPipelineOptimizer.writeFluidFace(
                basePtr,
                x0, y0, z0, u0, v0,
                x1, y1, z1, u1, v1,
                x2, y2, z2, u2, v2,
                x3, y3, z3, u3, v3,
                color,
                lightCoords,
                addBackFace
            );
        } else {
            ChunkPipelineOptimizer.writeFluidFaceExtended(
                basePtr,
                stride,
                x0, y0, z0, u0, v0,
                x1, y1, z1, u1, v1,
                x2, y2, z2, u2, v2,
                x3, y3, z3, u3, v3,
                color,
                lightCoords,
                addBackFace
            );
        }
        if (stride > 28 || TurboShadersBridge.extendedBlockVerticesActive) {
            TurboShadersBridge.populateExtendedFluidFace(
                basePtr,
                stride,
                x0, y0, z0, u0, v0,
                x1, y1, z1, u1, v1,
                x2, y2, z2, u2, v2,
                x3, y3, z3, u3, v3,
                addBackFace
            );
        }
        ci.cancel();
    }

    @Inject(method = "calculateAverageHeight", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$fastCalculateAverageHeight(
        BlockAndTintGetter level,
        Fluid type,
        float heightSelf,
        float height2,
        float height1,
        BlockPos cornerPos,
        CallbackInfoReturnable<Float> cir
    ) {
        if (!VulkanTurbo.fastChunkPrepEnabled) {
            return;
        }
        if (height1 >= 1.0F || height2 >= 1.0F) {
            cir.setReturnValue(1.0F);
            return;
        }
        float sum = 0.0F;
        float weight = 0.0F;
        if (height1 > 0.0F || height2 > 0.0F) {
            float heightCorner = this.getHeight(level, type, cornerPos);
            if (heightCorner >= 1.0F) {
                cir.setReturnValue(1.0F);
                return;
            }
            if (heightCorner >= 0.8F) {
                sum += heightCorner * 10.0F;
                weight += 10.0F;
            } else if (heightCorner >= 0.0F) {
                sum += heightCorner;
                weight += 1.0F;
            }
        }
        if (heightSelf >= 0.8F) {
            sum += heightSelf * 10.0F;
            weight += 10.0F;
        } else if (heightSelf >= 0.0F) {
            sum += heightSelf;
            weight += 1.0F;
        }
        if (height1 >= 0.8F) {
            sum += height1 * 10.0F;
            weight += 10.0F;
        } else if (height1 >= 0.0F) {
            sum += height1;
            weight += 1.0F;
        }
        if (height2 >= 0.8F) {
            sum += height2 * 10.0F;
            weight += 10.0F;
        } else if (height2 >= 0.0F) {
            sum += height2;
            weight += 1.0F;
        }
        cir.setReturnValue(weight > 0.0F ? sum / weight : 0.0F);
    }

    @Redirect(
        method = "tesselate",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;relative(Lnet/minecraft/core/Direction;)Lnet/minecraft/core/BlockPos;"
        )
    )
    private BlockPos vulkanturbo$reuseFluidRelative(BlockPos pos, Direction direction) {
        if (!VulkanTurbo.fastChunkPrepEnabled) {
            return pos.relative(direction);
        }
        return ChunkPipelineOptimizer.reuseFluidOffsetPos(pos, direction);
    }

    @Redirect(
        method = {"tesselate", "getHeight(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/world/level/material/Fluid;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)F", "getLightCoords"},
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;above()Lnet/minecraft/core/BlockPos;"
        )
    )
    private BlockPos vulkanturbo$reuseFluidAbove(BlockPos pos) {
        if (!VulkanTurbo.fastChunkPrepEnabled) {
            return pos.above();
        }
        return ChunkPipelineOptimizer.reuseFluidOffsetPos(pos, Direction.UP);
    }

    @Redirect(
        method = "tesselate",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;below()Lnet/minecraft/core/BlockPos;"
        )
    )
    private BlockPos vulkanturbo$reuseFluidBelow(BlockPos pos) {
        if (!VulkanTurbo.fastChunkPrepEnabled) {
            return pos.below();
        }
        return ChunkPipelineOptimizer.reuseFluidOffsetPos(pos, Direction.DOWN);
    }

    @Redirect(
        method = "tesselate",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;north()Lnet/minecraft/core/BlockPos;"
        )
    )
    private BlockPos vulkanturbo$reuseFluidNorth(BlockPos pos) {
        if (!VulkanTurbo.fastChunkPrepEnabled) {
            return pos.north();
        }
        return ChunkPipelineOptimizer.reuseFluidOffsetPos(pos, Direction.NORTH);
    }

    @Redirect(
        method = "tesselate",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;south()Lnet/minecraft/core/BlockPos;"
        )
    )
    private BlockPos vulkanturbo$reuseFluidSouth(BlockPos pos) {
        if (!VulkanTurbo.fastChunkPrepEnabled) {
            return pos.south();
        }
        return ChunkPipelineOptimizer.reuseFluidOffsetPos(pos, Direction.SOUTH);
    }

    @Redirect(
        method = "tesselate",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;east()Lnet/minecraft/core/BlockPos;"
        )
    )
    private BlockPos vulkanturbo$reuseFluidEast(BlockPos pos) {
        if (!VulkanTurbo.fastChunkPrepEnabled) {
            return pos.east();
        }
        return ChunkPipelineOptimizer.reuseFluidOffsetPos(pos, Direction.EAST);
    }

    @Redirect(
        method = "tesselate",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;west()Lnet/minecraft/core/BlockPos;"
        )
    )
    private BlockPos vulkanturbo$reuseFluidWest(BlockPos pos) {
        if (!VulkanTurbo.fastChunkPrepEnabled) {
            return pos.west();
        }
        return ChunkPipelineOptimizer.reuseFluidOffsetPos(pos, Direction.WEST);
    }
}
