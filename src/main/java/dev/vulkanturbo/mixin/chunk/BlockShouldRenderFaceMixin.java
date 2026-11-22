package dev.vulkanturbo.mixin.chunk;

import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.ChunkPipelineOptimizer;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

/**
 * Replaces `Block$ShapePairKey` allocation and `Object2ByteLinkedOpenHashMap` probing in
 * `Block.shouldRenderFace` with a flat thread-local 2-way identity cache.
 */
@Mixin(Block.class)
public class BlockShouldRenderFaceMixin {
    @Inject(
        method = "shouldRenderFace",
        at = @At(value = "NEW", target = "net/minecraft/world/level/block/Block$ShapePairKey"),
        cancellable = true,
        locals = LocalCapture.CAPTURE_FAILHARD
    )
    private static void vulkanturbo$fastFaceOcclusion(
        BlockState state,
        BlockState neighborState,
        Direction direction,
        CallbackInfoReturnable<Boolean> cir,
        VoxelShape neighborShape,
        VoxelShape faceShape
    ) {
        if (VulkanTurbo.fastChunkPrepEnabled) {
            cir.setReturnValue(ChunkPipelineOptimizer.shouldRenderFaceCached(faceShape, neighborShape));
        }
    }
}
