package dev.vulkanturbo.mixin.vertex;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.vulkanturbo.core.RawChunkSectionWriter;
import net.minecraft.client.renderer.DynamicGpuDataStorageNonMapped;
import net.minecraft.util.Mth;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Mixin(DynamicGpuDataStorageNonMapped.class)
public abstract class DynamicGpuDataStorageNonMappedMixin implements RawChunkSectionWriter {
    @Unique
    private static final boolean vulkanturbo$NATIVE_LITTLE_ENDIAN =
        ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
    @Shadow
    @Final
    private int blockSize;

    @Shadow
    private GpuBuffer gpuBuffer;

    @Shadow
    private ByteBuffer cpuBuffer;

    @Shadow
    private int nextBlock;

    @Shadow
    private int capacity;

    @Shadow
    protected abstract void resizeBuffers(int newCapacity);

    @Override
    public GpuBufferSlice vulkanturbo$writeRawSections(int[] coords, float[] visibilities, int count) {
        if (count == 0) {
            return new GpuBufferSlice(null, 0L, 0L);
        }
        if (this.nextBlock + count > this.capacity) {
            int newCapacity = Mth.smallestEncompassingPowerOfTwo(Math.max(this.capacity + 1, this.nextBlock + count));
            this.resizeBuffers(newCapacity);
        }

        int stride = this.blockSize;
        int byteOffset = this.nextBlock * stride;
        int totalBytes = count * stride;
        ByteBuffer buf = this.cpuBuffer;
        if (vulkanturbo$NATIVE_LITTLE_ENDIAN && buf.isDirect()) {
            long ptr = MemoryUtil.memAddress(buf, byteOffset);
            int c = 0;
            for (int i = 0; i < count; i++) {
                long lo = (coords[c] & 0xFFFFFFFFL) | ((long) coords[c + 1] << 32);
                long hi = (coords[c + 2] & 0xFFFFFFFFL)
                    | ((long) Float.floatToRawIntBits(visibilities[i]) << 32);
                MemoryUtil.memPutLong(ptr, lo);
                MemoryUtil.memPutLong(ptr + 8L, hi);
                ptr += stride;
                c += 3;
            }
        } else {
            if (buf.order() != ByteOrder.LITTLE_ENDIAN) {
                buf.order(ByteOrder.LITTLE_ENDIAN);
            }
            int p = byteOffset;
            int c = 0;
            for (int i = 0; i < count; i++) {
                buf.putInt(p, coords[c]);
                buf.putInt(p + 4, coords[c + 1]);
                buf.putInt(p + 8, coords[c + 2]);
                buf.putFloat(p + 12, visibilities[i]);
                p += stride;
                c += 3;
            }
        }

        ByteBuffer cpuSlice = buf.slice(byteOffset, totalBytes);
        GpuBufferSlice gpuSlice = this.gpuBuffer.slice(byteOffset, totalBytes);
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(gpuSlice, cpuSlice);
        this.nextBlock += count;
        return gpuSlice;
    }
}
