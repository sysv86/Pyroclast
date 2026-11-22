package dev.vulkanturbo.mixin.chunk;

import dev.vulkanturbo.VulkanTurbo;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.BitSet;
import java.util.Set;

/**
 * Mirrors `VisibilitySet`'s 6×6 direction connectivity matrix into a single primitive 64-bit mask,
 * replacing `BitSet.get(...)` pointer chasing inside `SectionOcclusionGraph.runUpdates` with a single
 * bit test.
 */
@Mixin(VisibilitySet.class)
public class VisibilitySetMixin {
    @Shadow
    @Final
    private static int FACINGS;

    @Shadow
    @Final
    private BitSet data;

    @Unique
    private long vulkanturbo$bits;

    @Inject(method = "add", at = @At("HEAD"))
    private void vulkanturbo$addFast(Set<Direction> directions, CallbackInfo ci) {
        if (directions.isEmpty()) return;
        int mask = 0;
        for (Direction d : directions) {
            mask |= 1 << d.ordinal();
        }
        long bits = this.vulkanturbo$bits;
        int m1 = mask;
        while (m1 != 0) {
            int i = Integer.numberOfTrailingZeros(m1);
            m1 &= m1 - 1;
            int rowBase = i * FACINGS;
            int m2 = mask;
            while (m2 != 0) {
                int j = Integer.numberOfTrailingZeros(m2);
                m2 &= m2 - 1;
                bits |= (1L << (j + rowBase)) | (1L << (i + j * FACINGS));
            }
        }
        this.vulkanturbo$bits = bits;
    }

    @Inject(method = "set", at = @At("HEAD"))
    private void vulkanturbo$setFast(Direction from, Direction to, boolean value, CallbackInfo ci) {
        int o1 = from.ordinal();
        int o2 = to.ordinal();
        long mask = (1L << (o1 + o2 * FACINGS)) | (1L << (o2 + o1 * FACINGS));
        if (value) {
            this.vulkanturbo$bits |= mask;
        } else {
            this.vulkanturbo$bits &= ~mask;
        }
    }

    @Inject(method = "setAll", at = @At("HEAD"))
    private void vulkanturbo$setAllFast(boolean value, CallbackInfo ci) {
        if (value) {
            int total = FACINGS * FACINGS;
            this.vulkanturbo$bits = total >= 64 ? -1L : ((1L << total) - 1L);
        } else {
            this.vulkanturbo$bits = 0L;
        }
    }

    @Inject(method = "visibilityBetween", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$fastVisibilityBetween(
        Direction from,
        Direction to,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (VulkanTurbo.fastChunkPrepEnabled) {
            int bit = from.ordinal() + to.ordinal() * FACINGS;
            cir.setReturnValue(((this.vulkanturbo$bits >>> bit) & 1L) != 0L);
        }
    }
}
