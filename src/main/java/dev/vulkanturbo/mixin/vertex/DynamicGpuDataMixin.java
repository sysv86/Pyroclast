package dev.vulkanturbo.mixin.vertex;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.core.ChunkRenderPrepOptimizer;
import dev.vulkanturbo.core.FastDynamicGpuData;
import dev.vulkanturbo.core.RawChunkCommandWriter;
import dev.vulkanturbo.core.RawChunkSectionWriter;
import dev.vulkanturbo.core.RawTransformWriter;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.DynamicGpuDataStorage;
import net.minecraft.client.renderer.DynamicGpuDataStorageMapped;
import net.minecraft.client.renderer.DynamicGpuDataStorageNonMapped;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(DynamicGpuData.class)
public class DynamicGpuDataMixin implements FastDynamicGpuData {
    @Shadow
    @Final
    private static Vector4fc WHITE;

    @Shadow
    @Final
    private static Vector3fc NO_OFFSET;

    @Shadow
    @Final
    private static Matrix4fc IDENTITY_TEXTURE_TRANSFORM;

    @Shadow
    @Final
    private DynamicGpuDataStorageMapped<DynamicGpuData.Transform> transforms;

    @Shadow
    @Final
    private DynamicGpuDataStorageMapped<DynamicGpuData.TerrainTransform> terrain;

    @Shadow
    private DynamicGpuDataStorage<DynamicGpuData.ChunkSectionInfo> chunkSections;

    @Shadow
    private DynamicGpuDataStorageMapped<DynamicGpuData.IndexedDraw> chunkSectionsCommandBuffer;

    @Inject(
        method = "writeTransform(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void vulkanturbo$writeTransform1(Matrix4f modelView, CallbackInfoReturnable<GpuBufferSlice> cir) {
        if (VulkanTurbo.modEnabled) {
            cir.setReturnValue(
                ((RawTransformWriter) this.transforms).vulkanturbo$writeRawTransform(
                    modelView, WHITE, NO_OFFSET, IDENTITY_TEXTURE_TRANSFORM
                )
            );
        }
    }

    @Inject(
        method = "writeTransform(Lorg/joml/Matrix4f;Lorg/joml/Vector4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void vulkanturbo$writeTransform2(
        Matrix4f modelView,
        Vector4f colorModulator,
        CallbackInfoReturnable<GpuBufferSlice> cir
    ) {
        if (VulkanTurbo.modEnabled) {
            cir.setReturnValue(
                ((RawTransformWriter) this.transforms).vulkanturbo$writeRawTransform(
                    modelView, colorModulator, NO_OFFSET, IDENTITY_TEXTURE_TRANSFORM
                )
            );
        }
    }

    @Inject(
        method = "writeTransform(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void vulkanturbo$writeTransform3(
        Matrix4f modelView,
        Matrix4f textureMatrix,
        CallbackInfoReturnable<GpuBufferSlice> cir
    ) {
        if (VulkanTurbo.modEnabled) {
            cir.setReturnValue(
                ((RawTransformWriter) this.transforms).vulkanturbo$writeRawTransform(
                    modelView, WHITE, NO_OFFSET, textureMatrix
                )
            );
        }
    }

    @Inject(
        method = "writeTransform(Lorg/joml/Matrix4f;Lorg/joml/Vector4f;Lorg/joml/Vector3f;Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void vulkanturbo$writeTransform4(
        Matrix4f modelView,
        Vector4f colorModulator,
        Vector3f modelOffset,
        Matrix4f textureMatrix,
        CallbackInfoReturnable<GpuBufferSlice> cir
    ) {
        if (VulkanTurbo.modEnabled) {
            cir.setReturnValue(
                ((RawTransformWriter) this.transforms).vulkanturbo$writeRawTransform(
                    modelView, colorModulator, modelOffset, textureMatrix
                )
            );
        }
    }

    @Inject(
        method = "writeTerrainTransform",
        at = @At("HEAD"),
        cancellable = true
    )
    private void vulkanturbo$writeTerrainTransform(
        Matrix4fc modelView,
        int textureAtlasWidth,
        int textureAtlasHeight,
        CallbackInfoReturnable<GpuBufferSlice> cir
    ) {
        if (VulkanTurbo.modEnabled) {
            cir.setReturnValue(
                ((RawTransformWriter) this.terrain).vulkanturbo$writeRawTerrainTransform(
                    modelView, textureAtlasWidth, textureAtlasHeight
                )
            );
        }
    }

    @Unique
    private void vulkanturbo$ensureChunkSectionsStorage() {
        boolean wantMapped = ChunkRenderPrepOptimizer.isMappedChunkSectionsEnabled();
        if (this.chunkSections != null) {
            boolean usageMismatch = (this.chunkSections.usage() & 32) == 0;
            boolean mappedMismatch = wantMapped != (this.chunkSections instanceof DynamicGpuDataStorageMapped);
            if (usageMismatch || mappedMismatch) {
                this.chunkSections.close();
                this.chunkSections = null;
            }
        }
        if (this.chunkSections == null) {
            if (wantMapped) {
                this.chunkSections = new DynamicGpuDataStorageMapped<>(
                    "Chunk Sections Instanced",
                    DynamicGpuData.CHUNK_SECTION_UBO_SIZE,
                    32,
                    2
                );
            } else {
                this.chunkSections = new DynamicGpuDataStorageNonMapped<>(
                    "Chunk Sections Instanced",
                    DynamicGpuData.CHUNK_SECTION_UBO_SIZE,
                    32,
                    2
                );
            }
        }
    }

    @Inject(method = "writeChunkSectionsInstanced", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$maybeUseMappedChunkSections(
        List<DynamicGpuData.ChunkSectionInfo> sections,
        CallbackInfoReturnable<GpuBufferSlice> cir
    ) {
        if (ChunkRenderPrepOptimizer.isMappedChunkSectionsEnabled()) {
            this.vulkanturbo$ensureChunkSectionsStorage();
            cir.setReturnValue(this.chunkSections.writeDataBatched(sections));
        }
    }

    @Override
    public GpuBufferSlice vulkanturbo$writeRawChunkSections(int[] coords, float[] visibilities, int count) {
        this.vulkanturbo$ensureChunkSectionsStorage();
        return ((RawChunkSectionWriter) this.chunkSections).vulkanturbo$writeRawSections(coords, visibilities, count);
    }

    @Override
    public void vulkanturbo$writeRawChunkCommands(
        int[][] groupData,
        int[] groupDrawCounts,
        int[] groupIndices,
        int groupCount,
        boolean[] reverseDraws,
        GpuBufferSlice[] outSlices
    ) {
        if (this.chunkSectionsCommandBuffer == null) {
            this.chunkSectionsCommandBuffer = new DynamicGpuDataStorageMapped<>(
                "Chunk Sections Command Buffer",
                20,
                512,
                2
            );
        }
        ((RawChunkCommandWriter) this.chunkSectionsCommandBuffer).vulkanturbo$writeRawCommandGroups(
            groupData,
            groupDrawCounts,
            groupIndices,
            groupCount,
            reverseDraws,
            outSlices
        );
    }
}
