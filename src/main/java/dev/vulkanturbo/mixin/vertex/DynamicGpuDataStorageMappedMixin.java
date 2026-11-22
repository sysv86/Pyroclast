package dev.vulkanturbo.mixin.vertex;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.RawChunkCommandWriter;
import dev.vulkanturbo.core.RawChunkSectionWriter;
import dev.vulkanturbo.core.RawTransformWriter;
import dev.vulkanturbo.core.VulkanPersistentlyMappedBuffer;
import net.minecraft.client.renderer.DynamicGpuDataStorage;
import net.minecraft.client.renderer.DynamicGpuDataStorageMapped;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.util.Mth;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Mixin(DynamicGpuDataStorageMapped.class)
public abstract class DynamicGpuDataStorageMappedMixin<T extends DynamicGpuDataStorage.DynamicGpuData>
    implements RawChunkSectionWriter, RawChunkCommandWriter, RawTransformWriter {
    @Unique
    private static final boolean vulkanturbo$NATIVE_LITTLE_ENDIAN =
        ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

    @Unique
    private static final int vulkanturbo$LAST_NONE = 0;
    @Unique
    private static final int vulkanturbo$LAST_OBJECT = 1;
    @Unique
    private static final int vulkanturbo$LAST_TRANSFORM = 2;
    @Unique
    private static final int vulkanturbo$LAST_TERRAIN = 3;

    @Shadow
    @Final
    private int blockSize;

    @Shadow
    private MappableRingBuffer ringBuffer;

    @Shadow
    private int nextBlock;

    @Shadow
    private int capacity;

    @Shadow
    private T lastData;

    @Shadow
    protected abstract void resizeBuffers(int newCapacity);

    @Unique
    private int vulkanturbo$lastKind = vulkanturbo$LAST_NONE;

    @Unique
    private GpuBufferSlice vulkanturbo$lastSlice;

    @Unique
    private float vt$m00, vt$m01, vt$m02, vt$m03;
    @Unique
    private float vt$m10, vt$m11, vt$m12, vt$m13;
    @Unique
    private float vt$m20, vt$m21, vt$m22, vt$m23;
    @Unique
    private float vt$m30, vt$m31, vt$m32, vt$m33;
    @Unique
    private float vt$tm00, vt$tm01, vt$tm02, vt$tm03;
    @Unique
    private float vt$tm10, vt$tm11, vt$tm12, vt$tm13;
    @Unique
    private float vt$tm20, vt$tm21, vt$tm22, vt$tm23;
    @Unique
    private float vt$tm30, vt$tm31, vt$tm32, vt$tm33;
    @Unique
    private float vt$cr, vt$cg, vt$cb, vt$ca;
    @Unique
    private float vt$ox, vt$oy, vt$oz;
    @Unique
    private int vt$atlasW, vt$atlasH;

    @Inject(method = {"endFrame", "resizeBuffers", "close"}, at = @At("HEAD"))
    private void vulkanturbo$resetCachedSlice(CallbackInfo ci) {
        this.vulkanturbo$lastKind = vulkanturbo$LAST_NONE;
        this.vulkanturbo$lastSlice = null;
    }

    @Inject(
        method = {
            "writeData([Lnet/minecraft/client/renderer/DynamicGpuDataStorage$DynamicGpuData;)[Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;",
            "writeDataBatched(Ljava/util/List;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;",
            "writeDataBatchedMultiple(Ljava/util/List;)[Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"
        },
        at = @At("HEAD")
    )
    private void vulkanturbo$invalidateCachedSliceOnBatchWrite(CallbackInfoReturnable<?> cir) {
        this.vulkanturbo$lastKind = vulkanturbo$LAST_NONE;
        this.vulkanturbo$lastSlice = null;
    }

    @Inject(
        method = "writeData(Lnet/minecraft/client/renderer/DynamicGpuDataStorage$DynamicGpuData;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void vulkanturbo$fastWriteData(T gpuData, CallbackInfoReturnable<GpuBufferSlice> cir) {
        if (!VulkanTurbo.modEnabled) {
            return;
        }
        if (this.vulkanturbo$lastKind == vulkanturbo$LAST_OBJECT
            && this.lastData != null
            && this.vulkanturbo$lastSlice != null
            && this.lastData.equals(gpuData)) {
            cir.setReturnValue(this.vulkanturbo$lastSlice);
            return;
        }
        if (this.nextBlock >= this.capacity) {
            this.resizeBuffers(this.capacity * 2);
        }
        int offset = this.nextBlock * this.blockSize;
        GpuBuffer currentBuf = this.ringBuffer.currentBuffer();
        GpuBufferSlice slice = currentBuf.slice(offset, this.blockSize);
        try (GpuBufferSlice.MappedView view = slice.map(false, true)) {
            gpuData.write(view.data());
        }
        if (currentBuf instanceof VulkanPersistentlyMappedBuffer pmb) {
            pmb.vulkanturbo$flushMappedRange(offset, this.blockSize);
        }
        this.nextBlock++;
        this.lastData = gpuData;
        this.vulkanturbo$lastKind = vulkanturbo$LAST_OBJECT;
        this.vulkanturbo$lastSlice = slice;
        cir.setReturnValue(slice);
    }

    @Override
    public GpuBufferSlice vulkanturbo$writeRawTransform(
        Matrix4fc modelView,
        Vector4fc colorModulator,
        Vector3fc modelOffset,
        Matrix4fc textureMatrix
    ) {
        float m00 = modelView.m00(), m01 = modelView.m01(), m02 = modelView.m02(), m03 = modelView.m03();
        float m10 = modelView.m10(), m11 = modelView.m11(), m12 = modelView.m12(), m13 = modelView.m13();
        float m20 = modelView.m20(), m21 = modelView.m21(), m22 = modelView.m22(), m23 = modelView.m23();
        float m30 = modelView.m30(), m31 = modelView.m31(), m32 = modelView.m32(), m33 = modelView.m33();

        float tm00 = textureMatrix.m00(), tm01 = textureMatrix.m01(), tm02 = textureMatrix.m02(), tm03 = textureMatrix.m03();
        float tm10 = textureMatrix.m10(), tm11 = textureMatrix.m11(), tm12 = textureMatrix.m12(), tm13 = textureMatrix.m13();
        float tm20 = textureMatrix.m20(), tm21 = textureMatrix.m21(), tm22 = textureMatrix.m22(), tm23 = textureMatrix.m23();
        float tm30 = textureMatrix.m30(), tm31 = textureMatrix.m31(), tm32 = textureMatrix.m32(), tm33 = textureMatrix.m33();

        float cr = colorModulator.x(), cg = colorModulator.y(), cb = colorModulator.z(), ca = colorModulator.w();
        float ox = modelOffset.x(), oy = modelOffset.y(), oz = modelOffset.z();

        if (this.vulkanturbo$lastKind == vulkanturbo$LAST_TRANSFORM
            && this.vulkanturbo$lastSlice != null
            && Float.floatToRawIntBits(m00) == Float.floatToRawIntBits(this.vt$m00)
            && Float.floatToRawIntBits(m01) == Float.floatToRawIntBits(this.vt$m01)
            && Float.floatToRawIntBits(m02) == Float.floatToRawIntBits(this.vt$m02)
            && Float.floatToRawIntBits(m03) == Float.floatToRawIntBits(this.vt$m03)
            && Float.floatToRawIntBits(m10) == Float.floatToRawIntBits(this.vt$m10)
            && Float.floatToRawIntBits(m11) == Float.floatToRawIntBits(this.vt$m11)
            && Float.floatToRawIntBits(m12) == Float.floatToRawIntBits(this.vt$m12)
            && Float.floatToRawIntBits(m13) == Float.floatToRawIntBits(this.vt$m13)
            && Float.floatToRawIntBits(m20) == Float.floatToRawIntBits(this.vt$m20)
            && Float.floatToRawIntBits(m21) == Float.floatToRawIntBits(this.vt$m21)
            && Float.floatToRawIntBits(m22) == Float.floatToRawIntBits(this.vt$m22)
            && Float.floatToRawIntBits(m23) == Float.floatToRawIntBits(this.vt$m23)
            && Float.floatToRawIntBits(m30) == Float.floatToRawIntBits(this.vt$m30)
            && Float.floatToRawIntBits(m31) == Float.floatToRawIntBits(this.vt$m31)
            && Float.floatToRawIntBits(m32) == Float.floatToRawIntBits(this.vt$m32)
            && Float.floatToRawIntBits(m33) == Float.floatToRawIntBits(this.vt$m33)
            && Float.floatToRawIntBits(tm00) == Float.floatToRawIntBits(this.vt$tm00)
            && Float.floatToRawIntBits(tm01) == Float.floatToRawIntBits(this.vt$tm01)
            && Float.floatToRawIntBits(tm02) == Float.floatToRawIntBits(this.vt$tm02)
            && Float.floatToRawIntBits(tm03) == Float.floatToRawIntBits(this.vt$tm03)
            && Float.floatToRawIntBits(tm10) == Float.floatToRawIntBits(this.vt$tm10)
            && Float.floatToRawIntBits(tm11) == Float.floatToRawIntBits(this.vt$tm11)
            && Float.floatToRawIntBits(tm12) == Float.floatToRawIntBits(this.vt$tm12)
            && Float.floatToRawIntBits(tm13) == Float.floatToRawIntBits(this.vt$tm13)
            && Float.floatToRawIntBits(tm20) == Float.floatToRawIntBits(this.vt$tm20)
            && Float.floatToRawIntBits(tm21) == Float.floatToRawIntBits(this.vt$tm21)
            && Float.floatToRawIntBits(tm22) == Float.floatToRawIntBits(this.vt$tm22)
            && Float.floatToRawIntBits(tm23) == Float.floatToRawIntBits(this.vt$tm23)
            && Float.floatToRawIntBits(tm30) == Float.floatToRawIntBits(this.vt$tm30)
            && Float.floatToRawIntBits(tm31) == Float.floatToRawIntBits(this.vt$tm31)
            && Float.floatToRawIntBits(tm32) == Float.floatToRawIntBits(this.vt$tm32)
            && Float.floatToRawIntBits(tm33) == Float.floatToRawIntBits(this.vt$tm33)
            && Float.floatToRawIntBits(cr) == Float.floatToRawIntBits(this.vt$cr)
            && Float.floatToRawIntBits(cg) == Float.floatToRawIntBits(this.vt$cg)
            && Float.floatToRawIntBits(cb) == Float.floatToRawIntBits(this.vt$cb)
            && Float.floatToRawIntBits(ca) == Float.floatToRawIntBits(this.vt$ca)
            && Float.floatToRawIntBits(ox) == Float.floatToRawIntBits(this.vt$ox)
            && Float.floatToRawIntBits(oy) == Float.floatToRawIntBits(this.vt$oy)
            && Float.floatToRawIntBits(oz) == Float.floatToRawIntBits(this.vt$oz)) {
            return this.vulkanturbo$lastSlice;
        }

        if (this.nextBlock >= this.capacity) {
            this.resizeBuffers(this.capacity * 2);
        }

        int offset = this.nextBlock * this.blockSize;
        GpuBuffer currentBuf = this.ringBuffer.currentBuffer();
        GpuBufferSlice slice = currentBuf.slice(offset, this.blockSize);

        VulkanPersistentlyMappedBuffer pmb = (vulkanturbo$NATIVE_LITTLE_ENDIAN && currentBuf instanceof VulkanPersistentlyMappedBuffer p)
            ? p
            : null;
        long baseAddr = pmb != null ? pmb.vulkanturbo$getOrMapAddress() : 0L;

        if (baseAddr != 0L) {
            vulkanturbo$putTransformAtAddress(
                baseAddr + offset,
                m00, m01, m02, m03, m10, m11, m12, m13, m20, m21, m22, m23, m30, m31, m32, m33,
                tm00, tm01, tm02, tm03, tm10, tm11, tm12, tm13, tm20, tm21, tm22, tm23, tm30, tm31, tm32, tm33,
                cr, cg, cb, ca, ox, oy, oz
            );
            pmb.vulkanturbo$flushMappedRange(offset, this.blockSize);
        } else {
            try (GpuBufferSlice.MappedView view = slice.map(false, true)) {
                ByteBuffer buf = view.data();
                if (vulkanturbo$NATIVE_LITTLE_ENDIAN && buf.isDirect()) {
                    vulkanturbo$putTransformAtAddress(
                        MemoryUtil.memAddress(buf),
                        m00, m01, m02, m03, m10, m11, m12, m13, m20, m21, m22, m23, m30, m31, m32, m33,
                        tm00, tm01, tm02, tm03, tm10, tm11, tm12, tm13, tm20, tm21, tm22, tm23, tm30, tm31, tm32, tm33,
                        cr, cg, cb, ca, ox, oy, oz
                    );
                } else {
                    if (buf.order() != ByteOrder.LITTLE_ENDIAN) {
                        buf.order(ByteOrder.LITTLE_ENDIAN);
                    }
                    modelView.get(0, buf);
                    textureMatrix.get(64, buf);
                    colorModulator.get(128, buf);
                    modelOffset.get(144, buf);
                }
            }
            if (currentBuf instanceof VulkanPersistentlyMappedBuffer p) {
                p.vulkanturbo$flushMappedRange(offset, this.blockSize);
            }
        }

        this.vt$m00 = m00; this.vt$m01 = m01; this.vt$m02 = m02; this.vt$m03 = m03;
        this.vt$m10 = m10; this.vt$m11 = m11; this.vt$m12 = m12; this.vt$m13 = m13;
        this.vt$m20 = m20; this.vt$m21 = m21; this.vt$m22 = m22; this.vt$m23 = m23;
        this.vt$m30 = m30; this.vt$m31 = m31; this.vt$m32 = m32; this.vt$m33 = m33;
        this.vt$tm00 = tm00; this.vt$tm01 = tm01; this.vt$tm02 = tm02; this.vt$tm03 = tm03;
        this.vt$tm10 = tm10; this.vt$tm11 = tm11; this.vt$tm12 = tm12; this.vt$tm13 = tm13;
        this.vt$tm20 = tm20; this.vt$tm21 = tm21; this.vt$tm22 = tm22; this.vt$tm23 = tm23;
        this.vt$tm30 = tm30; this.vt$tm31 = tm31; this.vt$tm32 = tm32; this.vt$tm33 = tm33;
        this.vt$cr = cr; this.vt$cg = cg; this.vt$cb = cb; this.vt$ca = ca;
        this.vt$ox = ox; this.vt$oy = oy; this.vt$oz = oz;

        this.nextBlock++;
        this.lastData = null;
        this.vulkanturbo$lastKind = vulkanturbo$LAST_TRANSFORM;
        this.vulkanturbo$lastSlice = slice;
        return slice;
    }

    @Override
    public GpuBufferSlice vulkanturbo$writeRawTerrainTransform(
        Matrix4fc modelView,
        int atlasWidth,
        int atlasHeight
    ) {
        float m00 = modelView.m00(), m01 = modelView.m01(), m02 = modelView.m02(), m03 = modelView.m03();
        float m10 = modelView.m10(), m11 = modelView.m11(), m12 = modelView.m12(), m13 = modelView.m13();
        float m20 = modelView.m20(), m21 = modelView.m21(), m22 = modelView.m22(), m23 = modelView.m23();
        float m30 = modelView.m30(), m31 = modelView.m31(), m32 = modelView.m32(), m33 = modelView.m33();

        if (this.vulkanturbo$lastKind == vulkanturbo$LAST_TERRAIN
            && this.vulkanturbo$lastSlice != null
            && this.vt$atlasW == atlasWidth
            && this.vt$atlasH == atlasHeight
            && Float.floatToRawIntBits(m00) == Float.floatToRawIntBits(this.vt$m00)
            && Float.floatToRawIntBits(m01) == Float.floatToRawIntBits(this.vt$m01)
            && Float.floatToRawIntBits(m02) == Float.floatToRawIntBits(this.vt$m02)
            && Float.floatToRawIntBits(m03) == Float.floatToRawIntBits(this.vt$m03)
            && Float.floatToRawIntBits(m10) == Float.floatToRawIntBits(this.vt$m10)
            && Float.floatToRawIntBits(m11) == Float.floatToRawIntBits(this.vt$m11)
            && Float.floatToRawIntBits(m12) == Float.floatToRawIntBits(this.vt$m12)
            && Float.floatToRawIntBits(m13) == Float.floatToRawIntBits(this.vt$m13)
            && Float.floatToRawIntBits(m20) == Float.floatToRawIntBits(this.vt$m20)
            && Float.floatToRawIntBits(m21) == Float.floatToRawIntBits(this.vt$m21)
            && Float.floatToRawIntBits(m22) == Float.floatToRawIntBits(this.vt$m22)
            && Float.floatToRawIntBits(m23) == Float.floatToRawIntBits(this.vt$m23)
            && Float.floatToRawIntBits(m30) == Float.floatToRawIntBits(this.vt$m30)
            && Float.floatToRawIntBits(m31) == Float.floatToRawIntBits(this.vt$m31)
            && Float.floatToRawIntBits(m32) == Float.floatToRawIntBits(this.vt$m32)
            && Float.floatToRawIntBits(m33) == Float.floatToRawIntBits(this.vt$m33)) {
            return this.vulkanturbo$lastSlice;
        }

        if (this.nextBlock >= this.capacity) {
            this.resizeBuffers(this.capacity * 2);
        }

        int offset = this.nextBlock * this.blockSize;
        GpuBuffer currentBuf = this.ringBuffer.currentBuffer();
        GpuBufferSlice slice = currentBuf.slice(offset, this.blockSize);

        VulkanPersistentlyMappedBuffer pmb = (vulkanturbo$NATIVE_LITTLE_ENDIAN && currentBuf instanceof VulkanPersistentlyMappedBuffer p)
            ? p
            : null;
        long baseAddr = pmb != null ? pmb.vulkanturbo$getOrMapAddress() : 0L;

        if (baseAddr != 0L) {
            long ptr = baseAddr + offset;
            vulkanturbo$putMat4AtAddress(
                ptr,
                m00, m01, m02, m03, m10, m11, m12, m13, m20, m21, m22, m23, m30, m31, m32, m33
            );
            MemoryUtil.memPutLong(ptr + 64L, (atlasWidth & 0xFFFFFFFFL) | ((long) atlasHeight << 32));
            pmb.vulkanturbo$flushMappedRange(offset, this.blockSize);
        } else {
            try (GpuBufferSlice.MappedView view = slice.map(false, true)) {
                ByteBuffer buf = view.data();
                if (vulkanturbo$NATIVE_LITTLE_ENDIAN && buf.isDirect()) {
                    long ptr = MemoryUtil.memAddress(buf);
                    vulkanturbo$putMat4AtAddress(
                        ptr,
                        m00, m01, m02, m03, m10, m11, m12, m13, m20, m21, m22, m23, m30, m31, m32, m33
                    );
                    MemoryUtil.memPutLong(ptr + 64L, (atlasWidth & 0xFFFFFFFFL) | ((long) atlasHeight << 32));
                } else {
                    if (buf.order() != ByteOrder.LITTLE_ENDIAN) {
                        buf.order(ByteOrder.LITTLE_ENDIAN);
                    }
                    modelView.get(0, buf);
                    buf.putInt(64, atlasWidth);
                    buf.putInt(68, atlasHeight);
                }
            }
            if (currentBuf instanceof VulkanPersistentlyMappedBuffer p) {
                p.vulkanturbo$flushMappedRange(offset, this.blockSize);
            }
        }

        this.vt$m00 = m00; this.vt$m01 = m01; this.vt$m02 = m02; this.vt$m03 = m03;
        this.vt$m10 = m10; this.vt$m11 = m11; this.vt$m12 = m12; this.vt$m13 = m13;
        this.vt$m20 = m20; this.vt$m21 = m21; this.vt$m22 = m22; this.vt$m23 = m23;
        this.vt$m30 = m30; this.vt$m31 = m31; this.vt$m32 = m32; this.vt$m33 = m33;
        this.vt$atlasW = atlasWidth;
        this.vt$atlasH = atlasHeight;

        this.nextBlock++;
        this.lastData = null;
        this.vulkanturbo$lastKind = vulkanturbo$LAST_TERRAIN;
        this.vulkanturbo$lastSlice = slice;
        return slice;
    }

    @Unique
    private static void vulkanturbo$putMat4AtAddress(
        long ptr,
        float m00, float m01, float m02, float m03,
        float m10, float m11, float m12, float m13,
        float m20, float m21, float m22, float m23,
        float m30, float m31, float m32, float m33
    ) {
        MemoryUtil.memPutFloat(ptr, m00);
        MemoryUtil.memPutFloat(ptr + 4L, m01);
        MemoryUtil.memPutFloat(ptr + 8L, m02);
        MemoryUtil.memPutFloat(ptr + 12L, m03);
        MemoryUtil.memPutFloat(ptr + 16L, m10);
        MemoryUtil.memPutFloat(ptr + 20L, m11);
        MemoryUtil.memPutFloat(ptr + 24L, m12);
        MemoryUtil.memPutFloat(ptr + 28L, m13);
        MemoryUtil.memPutFloat(ptr + 32L, m20);
        MemoryUtil.memPutFloat(ptr + 36L, m21);
        MemoryUtil.memPutFloat(ptr + 40L, m22);
        MemoryUtil.memPutFloat(ptr + 44L, m23);
        MemoryUtil.memPutFloat(ptr + 48L, m30);
        MemoryUtil.memPutFloat(ptr + 52L, m31);
        MemoryUtil.memPutFloat(ptr + 56L, m32);
        MemoryUtil.memPutFloat(ptr + 60L, m33);
    }

    @Unique
    private static void vulkanturbo$putTransformAtAddress(
        long ptr,
        float m00, float m01, float m02, float m03,
        float m10, float m11, float m12, float m13,
        float m20, float m21, float m22, float m23,
        float m30, float m31, float m32, float m33,
        float tm00, float tm01, float tm02, float tm03,
        float tm10, float tm11, float tm12, float tm13,
        float tm20, float tm21, float tm22, float tm23,
        float tm30, float tm31, float tm32, float tm33,
        float cr, float cg, float cb, float ca,
        float ox, float oy, float oz
    ) {
        vulkanturbo$putMat4AtAddress(
            ptr,
            m00, m01, m02, m03, m10, m11, m12, m13, m20, m21, m22, m23, m30, m31, m32, m33
        );
        vulkanturbo$putMat4AtAddress(
            ptr + 64L,
            tm00, tm01, tm02, tm03, tm10, tm11, tm12, tm13, tm20, tm21, tm22, tm23, tm30, tm31, tm32, tm33
        );
        MemoryUtil.memPutFloat(ptr + 128L, cr);
        MemoryUtil.memPutFloat(ptr + 132L, cg);
        MemoryUtil.memPutFloat(ptr + 136L, cb);
        MemoryUtil.memPutFloat(ptr + 140L, ca);
        MemoryUtil.memPutFloat(ptr + 144L, ox);
        MemoryUtil.memPutFloat(ptr + 148L, oy);
        MemoryUtil.memPutFloat(ptr + 152L, oz);
    }

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
        GpuBuffer currentBuf = this.ringBuffer.currentBuffer();
        GpuBufferSlice slice = currentBuf.slice(byteOffset, totalBytes);

        VulkanPersistentlyMappedBuffer pmb = (vulkanturbo$NATIVE_LITTLE_ENDIAN && currentBuf instanceof VulkanPersistentlyMappedBuffer p)
            ? p
            : null;
        long baseAddr = pmb != null ? pmb.vulkanturbo$getOrMapAddress() : 0L;

        if (baseAddr != 0L) {
            long ptr = baseAddr + byteOffset;
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
            pmb.vulkanturbo$flushMappedRange(byteOffset, totalBytes);
        } else {
            try (GpuBufferSlice.MappedView view = slice.map(false, true)) {
                ByteBuffer buf = view.data();
                if (vulkanturbo$NATIVE_LITTLE_ENDIAN && buf.isDirect()) {
                    long ptr = MemoryUtil.memAddress(buf);
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
                    int p = 0;
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
            }
            if (currentBuf instanceof VulkanPersistentlyMappedBuffer p) {
                p.vulkanturbo$flushMappedRange(byteOffset, totalBytes);
            }
        }
        this.nextBlock += count;
        this.lastData = null;
        this.vulkanturbo$lastKind = vulkanturbo$LAST_NONE;
        this.vulkanturbo$lastSlice = null;
        return slice;
    }

    @Override
    public void vulkanturbo$writeRawCommandGroups(
        int[][] groupData,
        int[] groupDrawCounts,
        int[] groupIndices,
        int groupCount,
        boolean[] reverseDraws,
        GpuBufferSlice[] outSlices
    ) {
        if (groupCount == 0) {
            return;
        }
        int totalDraws = 0;
        for (int i = 0; i < groupCount; i++) {
            totalDraws += groupDrawCounts[groupIndices[i]];
        }
        if (totalDraws == 0) {
            return;
        }
        if (this.nextBlock + totalDraws > this.capacity) {
            int newCapacity = Mth.smallestEncompassingPowerOfTwo(Math.max(this.capacity + 1, this.nextBlock + totalDraws));
            this.resizeBuffers(newCapacity);
        }

        int stride = this.blockSize;
        int baseByteOffset = this.nextBlock * stride;
        int totalBytes = totalDraws * stride;
        GpuBuffer buffer = this.ringBuffer.currentBuffer();

        VulkanPersistentlyMappedBuffer pmb = (vulkanturbo$NATIVE_LITTLE_ENDIAN && buffer instanceof VulkanPersistentlyMappedBuffer p)
            ? p
            : null;
        long baseAddr = pmb != null ? pmb.vulkanturbo$getOrMapAddress() : 0L;

        if (baseAddr != 0L) {
            long ptr = baseAddr + baseByteOffset;
            long gpuOffset = baseByteOffset;
            for (int i = 0; i < groupCount; i++) {
                int idx = groupIndices[i];
                int draws = groupDrawCounts[idx];
                long groupBytes = (long) draws * stride;
                outSlices[i] = buffer.slice(gpuOffset, groupBytes);
                int[] data = groupData[idx];
                if (reverseDraws[idx]) {
                    for (int d = draws - 1; d >= 0; d--) {
                        int base = d * 5;
                        long w0 = (data[base] & 0xFFFFFFFFL) | ((long) data[base + 1] << 32);
                        long w1 = (data[base + 2] & 0xFFFFFFFFL) | ((long) data[base + 3] << 32);
                        MemoryUtil.memPutLong(ptr, w0);
                        MemoryUtil.memPutLong(ptr + 8L, w1);
                        MemoryUtil.memPutInt(ptr + 16L, data[base + 4]);
                        ptr += stride;
                    }
                } else {
                    for (int d = 0; d < draws; d++) {
                        int base = d * 5;
                        long w0 = (data[base] & 0xFFFFFFFFL) | ((long) data[base + 1] << 32);
                        long w1 = (data[base + 2] & 0xFFFFFFFFL) | ((long) data[base + 3] << 32);
                        MemoryUtil.memPutLong(ptr, w0);
                        MemoryUtil.memPutLong(ptr + 8L, w1);
                        MemoryUtil.memPutInt(ptr + 16L, data[base + 4]);
                        ptr += stride;
                    }
                }
                gpuOffset += groupBytes;
            }
            pmb.vulkanturbo$flushMappedRange(baseByteOffset, totalBytes);
        } else {
            try (GpuBufferSlice.MappedView view = buffer.slice(baseByteOffset, totalBytes).map(false, true)) {
                ByteBuffer buf = view.data();
                if (vulkanturbo$NATIVE_LITTLE_ENDIAN && buf.isDirect()) {
                    long ptr = MemoryUtil.memAddress(buf);
                    long gpuOffset = baseByteOffset;
                    for (int i = 0; i < groupCount; i++) {
                        int idx = groupIndices[i];
                        int draws = groupDrawCounts[idx];
                        long groupBytes = (long) draws * stride;
                        outSlices[i] = buffer.slice(gpuOffset, groupBytes);
                        int[] data = groupData[idx];
                        if (reverseDraws[idx]) {
                            for (int d = draws - 1; d >= 0; d--) {
                                int base = d * 5;
                                long w0 = (data[base] & 0xFFFFFFFFL) | ((long) data[base + 1] << 32);
                                long w1 = (data[base + 2] & 0xFFFFFFFFL) | ((long) data[base + 3] << 32);
                                MemoryUtil.memPutLong(ptr, w0);
                                MemoryUtil.memPutLong(ptr + 8L, w1);
                                MemoryUtil.memPutInt(ptr + 16L, data[base + 4]);
                                ptr += stride;
                            }
                        } else {
                            for (int d = 0; d < draws; d++) {
                                int base = d * 5;
                                long w0 = (data[base] & 0xFFFFFFFFL) | ((long) data[base + 1] << 32);
                                long w1 = (data[base + 2] & 0xFFFFFFFFL) | ((long) data[base + 3] << 32);
                                MemoryUtil.memPutLong(ptr, w0);
                                MemoryUtil.memPutLong(ptr + 8L, w1);
                                MemoryUtil.memPutInt(ptr + 16L, data[base + 4]);
                                ptr += stride;
                            }
                        }
                        gpuOffset += groupBytes;
                    }
                } else {
                    if (buf.order() != ByteOrder.LITTLE_ENDIAN) {
                        buf.order(ByteOrder.LITTLE_ENDIAN);
                    }
                    int localPos = 0;
                    long gpuOffset = baseByteOffset;
                    for (int i = 0; i < groupCount; i++) {
                        int idx = groupIndices[i];
                        int draws = groupDrawCounts[idx];
                        long groupBytes = (long) draws * stride;
                        outSlices[i] = buffer.slice(gpuOffset, groupBytes);
                        int[] data = groupData[idx];
                        if (reverseDraws[idx]) {
                            for (int d = draws - 1; d >= 0; d--) {
                                int base = d * 5;
                                buf.putInt(localPos, data[base]);
                                buf.putInt(localPos + 4, data[base + 1]);
                                buf.putInt(localPos + 8, data[base + 2]);
                                buf.putInt(localPos + 12, data[base + 3]);
                                buf.putInt(localPos + 16, data[base + 4]);
                                localPos += stride;
                            }
                        } else {
                            for (int d = 0; d < draws; d++) {
                                int base = d * 5;
                                buf.putInt(localPos, data[base]);
                                buf.putInt(localPos + 4, data[base + 1]);
                                buf.putInt(localPos + 8, data[base + 2]);
                                buf.putInt(localPos + 12, data[base + 3]);
                                buf.putInt(localPos + 16, data[base + 4]);
                                localPos += stride;
                            }
                        }
                        gpuOffset += groupBytes;
                    }
                }
            }
            if (buffer instanceof VulkanPersistentlyMappedBuffer p) {
                p.vulkanturbo$flushMappedRange(baseByteOffset, totalBytes);
            }
        }
        this.nextBlock += totalDraws;
        this.lastData = null;
        this.vulkanturbo$lastKind = vulkanturbo$LAST_NONE;
        this.vulkanturbo$lastSlice = null;
    }
}
