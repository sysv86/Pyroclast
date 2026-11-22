package dev.vulkanturbo.mixin.vertex;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.api.shader.TurboShadersBridge;
import dev.vulkanturbo.core.ChunkPipelineOptimizer;
import dev.vulkanturbo.core.DirectEntityBufferBuilder;
import dev.vulkanturbo.core.EntityRenderOptimizer;
import dev.vulkanturbo.core.NormalCache;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces Minecraft's per-vertex normal packing with a thread-safe per-instance cached version
 * and batches chunk `DefaultVertexFormat.BLOCK` (plus extended `TurboShaders` PBR block formats),
 * `ENTITY`, `PARTICLE`, `POSITION_TEX_[LIGHTMAP_]COLOR`, and `POSITION_COLOR` quad writes into
 * single off-heap reservations after verifying exact `VertexFormat` element offsets and types.
 */
@Mixin(BufferBuilder.class)
public abstract class BufferBuilderMixin implements VertexConsumer, DirectEntityBufferBuilder {
    @Shadow
    @Final
    private static boolean IS_LITTLE_ENDIAN;

    @Shadow
    @Final
    private ByteBufferBuilder buffer;

    @Shadow
    private long vertexPointer;

    @Shadow
    private int vertices;

    @Shadow
    @Final
    private VertexFormat format;

    @Shadow
    @Final
    private PrimitiveTopology primitiveTopology;

    @Shadow
    @Final
    private boolean blockFormat;

    @Shadow
    @Final
    private boolean entityFormat;

    @Shadow
    @Final
    private int vertexSize;

    @Shadow
    private int elementsToFill;

    @Shadow
    protected abstract void ensureBuilding();

    @Shadow
    protected abstract void endLastVertex();

    @Unique
    private float vulkanturbo$lastNx = Float.NaN;

    @Unique
    private float vulkanturbo$lastNy = Float.NaN;

    @Unique
    private float vulkanturbo$lastNz = Float.NaN;

    @Unique
    private int vulkanturbo$packedNormal;

    @Unique
    private boolean vulkanturbo$verifiedBlockHeader;

    @Unique
    private boolean vulkanturbo$verifiedExtendedBlock;

    @Unique
    private boolean vulkanturbo$verifiedEntity36;

    @Unique
    private boolean vulkanturbo$verifiedParticle28;

    @Unique
    private boolean vulkanturbo$verifiedPosTexColor24;

    @Unique
    private boolean vulkanturbo$verifiedPosTexLightColor28;

    @Unique
    private boolean vulkanturbo$verifiedPosColor16;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void vulkanturbo$verifyVertexFormatLayout(
        ByteBufferBuilder buffer,
        PrimitiveTopology primitiveTopology,
        VertexFormat format,
        CallbackInfo ci
    ) {
        this.vulkanturbo$verifiedBlockHeader = ChunkPipelineOptimizer.isVerifiedBlockHeaderLayout(format);
        this.vulkanturbo$verifiedExtendedBlock = ChunkPipelineOptimizer.isVerifiedExtendedBlockLayout(format);
        this.vulkanturbo$verifiedEntity36 = ChunkPipelineOptimizer.isVerifiedEntity36Layout(format);
        this.vulkanturbo$verifiedParticle28 = ChunkPipelineOptimizer.isVerifiedParticle28Layout(format);
        this.vulkanturbo$verifiedPosTexColor24 = ChunkPipelineOptimizer.isVerifiedPosTexColor24Layout(format);
        this.vulkanturbo$verifiedPosTexLightColor28 = ChunkPipelineOptimizer.isVerifiedPosTexLightColor28Layout(format);
        this.vulkanturbo$verifiedPosColor16 = ChunkPipelineOptimizer.isVerifiedPosColor16Layout(format);
    }

    @Redirect(
        method = {
            "setNormal(FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
            "addVertex(FFFIFIIFFF)V"
        },
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;putNormals(JFFF)V"
        )
    )
    private void vulkanturbo$writeInstanceCachedNormals(long address, float x, float y, float z) {
        if (!NormalCache.isEnabled()) {
            NormalCache.write(address, x, y, z);
            return;
        }
        if (x != this.vulkanturbo$lastNx || y != this.vulkanturbo$lastNy || z != this.vulkanturbo$lastNz) {
            this.vulkanturbo$lastNx = x;
            this.vulkanturbo$lastNy = y;
            this.vulkanturbo$lastNz = z;
            this.vulkanturbo$packedNormal = NormalCache.packNormal(x, y, z);
        }
        NormalCache.writePacked(address, this.vulkanturbo$packedNormal);
    }

    @Inject(method = "putNormals", at = @At("HEAD"), cancellable = true)
    private static void vulkanturbo$putNormals(long address, float x, float y, float z, CallbackInfo ci) {
        if (!NormalCache.isEnabled()) {
            return;
        }

        NormalCache.write(address, x, y, z);
        ci.cancel();
    }

    @Override
    public int vulkanturbo$getVertexSize() {
        return this.vertexSize;
    }

    @Override
    @SuppressWarnings("AddedMixinMembersNamePattern")
    public void putBlockBakedQuad(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
        boolean extended = this.vulkanturbo$verifiedExtendedBlock && TurboShadersBridge.active ||
            VulkanTurbo.fastChunkPrepEnabled && TurboShadersBridge.extendedBlockVerticesActive && this.vertexSize > 28;
        boolean standard = VulkanTurbo.fastChunkPrepEnabled && this.blockFormat && this.vertexSize == 28;
        if ((extended || standard) && IS_LITTLE_ENDIAN
            && this.vulkanturbo$verifiedBlockHeader
            && this.primitiveTopology != PrimitiveTopology.LINES
            && this.vertices + 4 <= 16777215) {
            int stride = this.vertexSize;
            this.ensureBuilding();
            this.endLastVertex();
            long basePtr = this.buffer.reserve(stride * 4);
            this.vertexPointer = basePtr + 3L * stride;
            this.vertices += 4;
            this.elementsToFill = 0;
            ChunkPipelineOptimizer.writeBlockBakedQuadExtended(basePtr, stride, x, y, z, quad, instance);
            if (extended || TurboShadersBridge.extendedBlockVerticesActive) {
                TurboShadersBridge.populateExtendedBlockQuad(basePtr, stride, x, y, z, quad, instance);
            }
            return;
        }
        VertexConsumer.super.putBlockBakedQuad(x, y, z, quad, instance);
    }

    @Override
    @SuppressWarnings("AddedMixinMembersNamePattern")
    public void putBakedQuad(PoseStack.Pose pose, BakedQuad quad, QuadInstance instance) {
        if (VulkanTurbo.fastEntityRenderEnabled
            && IS_LITTLE_ENDIAN
            && this.primitiveTopology != PrimitiveTopology.LINES
            && this.vertices + 4 <= 16777215) {
            if (this.entityFormat && this.vertexSize == 36 && this.vulkanturbo$verifiedEntity36) {
                this.ensureBuilding();
                this.endLastVertex();
                long basePtr = this.buffer.reserve(144);
                this.vertexPointer = basePtr + 108L;
                this.vertices += 4;
                this.elementsToFill = 0;
                EntityRenderOptimizer.writeBakedQuadEntity(basePtr, pose, quad, instance);
                return;
            }
            if (this.blockFormat && this.vertexSize == 28 && this.vulkanturbo$verifiedBlockHeader) {
                this.ensureBuilding();
                this.endLastVertex();
                long basePtr = this.buffer.reserve(112);
                this.vertexPointer = basePtr + 84L;
                this.vertices += 4;
                this.elementsToFill = 0;
                EntityRenderOptimizer.writeBakedQuadBlock(basePtr, pose, quad, instance);
                return;
            }
        }
        VertexConsumer.super.putBakedQuad(pose, quad, instance);
    }

    @Override
    public long vulkanturbo$reserveBlockVertices(int vertexCount) {
        boolean standardBlock = VulkanTurbo.fastChunkPrepEnabled && this.blockFormat &&
            this.vertexSize == 28 && this.vulkanturbo$verifiedBlockHeader;
        boolean extendedShaderBlock = this.vulkanturbo$verifiedExtendedBlock && TurboShadersBridge.active ||
            VulkanTurbo.fastChunkPrepEnabled && TurboShadersBridge.extendedBlockVerticesActive &&
                this.vertexSize >= 28 && this.vulkanturbo$verifiedBlockHeader;
        if ((!standardBlock && !extendedShaderBlock)
            || !IS_LITTLE_ENDIAN
            || this.primitiveTopology == PrimitiveTopology.LINES
            || vertexCount <= 0
            || this.vertices + vertexCount > 16777215) {
            return -1L;
        }
        int stride = this.vertexSize;
        this.ensureBuilding();
        this.endLastVertex();
        long basePtr = this.buffer.reserve(vertexCount * stride);
        this.vertexPointer = basePtr + (long) (vertexCount - 1) * stride;
        this.vertices += vertexCount;
        this.elementsToFill = 0;
        return basePtr;
    }

    @Override
    public long vulkanturbo$reserveEntityVertices(int vertexCount) {
        if (!this.entityFormat
            || this.vertexSize != 36
            || !this.vulkanturbo$verifiedEntity36
            || !IS_LITTLE_ENDIAN
            || this.primitiveTopology == PrimitiveTopology.LINES
            || vertexCount <= 0
            || this.vertices + vertexCount > 16777215) {
            return -1L;
        }
        this.ensureBuilding();
        this.endLastVertex();
        long basePtr = this.buffer.reserve(vertexCount * 36);
        this.vertexPointer = basePtr + (long) (vertexCount - 1) * 36L;
        this.vertices += vertexCount;
        this.elementsToFill = 0;
        return basePtr;
    }

    @Override
    public long vulkanturbo$reserveParticleQuad() {
        if (this.format != DefaultVertexFormat.PARTICLE
            || this.vertexSize != 28
            || !this.vulkanturbo$verifiedParticle28
            || !IS_LITTLE_ENDIAN
            || this.primitiveTopology == PrimitiveTopology.LINES
            || this.vertices + 4 > 16777215) {
            return -1L;
        }
        this.ensureBuilding();
        this.endLastVertex();
        long basePtr = this.buffer.reserve(112);
        this.vertexPointer = basePtr + 84L;
        this.vertices += 4;
        this.elementsToFill = 0;
        return basePtr;
    }

    @Override
    public long vulkanturbo$reserveGlyphQuad() {
        boolean isGuiText = this.format == DefaultVertexFormat.POSITION_TEX_COLOR
            && this.vertexSize == 24
            && this.vulkanturbo$verifiedPosTexColor24;
        boolean isWorldText = this.format == DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR
            && this.vertexSize == 28
            && this.vulkanturbo$verifiedPosTexLightColor28;
        if ((!isGuiText && !isWorldText)
            || !IS_LITTLE_ENDIAN
            || this.primitiveTopology == PrimitiveTopology.LINES
            || this.vertices + 4 > 16777215) {
            return -1L;
        }
        this.ensureBuilding();
        this.endLastVertex();
        int quadBytes = this.vertexSize * 4;
        long basePtr = this.buffer.reserve(quadBytes);
        this.vertexPointer = basePtr + 3L * this.vertexSize;
        this.vertices += 4;
        this.elementsToFill = 0;
        return basePtr;
    }

    @Override
    public boolean vulkanturbo$glyphHasLightmap() {
        return this.format == DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR
            && this.vulkanturbo$verifiedPosTexLightColor28;
    }

    @Override
    public long vulkanturbo$reserveGuiColoredQuad() {
        if (this.format != DefaultVertexFormat.POSITION_COLOR
            || this.vertexSize != 16
            || !this.vulkanturbo$verifiedPosColor16
            || !IS_LITTLE_ENDIAN
            || this.primitiveTopology == PrimitiveTopology.LINES
            || this.vertices + 4 > 16777215) {
            return -1L;
        }
        this.ensureBuilding();
        this.endLastVertex();
        long basePtr = this.buffer.reserve(64);
        this.vertexPointer = basePtr + 48L;
        this.vertices += 4;
        this.elementsToFill = 0;
        return basePtr;
    }
}
