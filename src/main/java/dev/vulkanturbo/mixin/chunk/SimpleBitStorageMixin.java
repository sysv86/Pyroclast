package dev.vulkanturbo.mixin.chunk;

import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.ChunkPipelineOptimizer;
import net.minecraft.util.SimpleBitStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Replaces `Validate.inclusiveBetween` and `cellIndex` virtual/boxing overhead in
 * `SimpleBitStorage` (`get`, `set`, `getAndSet`) across chunk meshing and server ticks.
 */
@Mixin(SimpleBitStorage.class)
public abstract class SimpleBitStorageMixin {
    @Shadow
    @Final
    private int divideMul;

    @Shadow
    @Final
    private int divideAdd;

    @Shadow
    @Final
    private int divideShift;

    @Shadow
    protected abstract int cellIndex(int index);

    @Redirect(
        method = {"get", "set", "getAndSet"},
        at = @At(value = "INVOKE", target = "Lorg/apache/commons/lang3/Validate;inclusiveBetween(JJJ)V")
    )
    private static void vulkanturbo$fastRangeCheck(long min, long max, long value) {
        if (value < min || value > max) {
            ChunkPipelineOptimizer.throwStorageOutOfBounds(min, max, value);
        }
    }

    @Redirect(
        method = {"get", "set", "getAndSet"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/SimpleBitStorage;cellIndex(I)I")
    )
    private int vulkanturbo$fastCellIndex(SimpleBitStorage self, int index) {
        if (!VulkanTurbo.fastChunkPrepEnabled) {
            return this.cellIndex(index);
        }
        long mul = this.divideMul & 0xFFFFFFFFL;
        long add = this.divideAdd & 0xFFFFFFFFL;
        return (int) (((index * mul + add) >> 32) >> this.divideShift);
    }
}
