package dev.vulkanturbo.mixin.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import dev.vulkanturbo.VulkanTurbo;
import dev.vulkanturbo.api.shader.TurboShadersBridge;
import dev.vulkanturbo.core.VulkanBufferViewCache;
import dev.vulkanturbo.core.VulkanPersistentlyMappedBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;

/**
 * Promotes vertex and index `VulkanGpuBuffer.Direct` allocations with `SHADER_DEVICE_ADDRESS_BIT`,
 * `STORAGE_BUFFER_BIT`, and `ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR` when supported
 * so `TurboShaders` can build zero-copy hardware BLAS over `UberGpuBuffer` and `SequentialIndexBuffer`
 * slices; defers cached `VkBufferView` destruction via `encoder.queueForDestroy` on `close()`;
 * queries `VK_MEMORY_PROPERTY_HOST_COHERENT_BIT` via `vmaGetAllocationMemoryProperties`; and flushes
 * non-coherent mapped ranges via `vmaFlushAllocation`.
 */
@Mixin(VulkanGpuBuffer.Direct.class)
public class VulkanGpuBufferDestroyMixin implements VulkanPersistentlyMappedBuffer {
    @Shadow
    @Final
    protected VulkanDevice device;

    @Shadow
    @Final
    private long vmaAllocation;

    @Shadow
    private boolean closed;

    @Unique
    private volatile long vulkanturbo$cachedMappedPtr = 0L;

    @Unique
    private volatile boolean vulkanturbo$coherencyChecked = false;

    @Unique
    private volatile boolean vulkanturbo$hostCoherent = false;

    @ModifyArg(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/util/vma/Vma;vmaCreateBuffer(JLorg/lwjgl/vulkan/VkBufferCreateInfo;Lorg/lwjgl/util/vma/VmaAllocationCreateInfo;Ljava/nio/LongBuffer;Lorg/lwjgl/PointerBuffer;Lorg/lwjgl/util/vma/VmaAllocationInfo;)I"
        ),
        index = 1
    )
    private static VkBufferCreateInfo vulkanturbo$promoteVertexAndIndexBufferUsage(VkBufferCreateInfo bufferCreateInfo) {
        if (!VulkanTurbo.isBackendAllowed() || TurboShadersBridge.INSTANCE.getExtension() == null || !TurboShadersBridge.bdaSupported) {
            return bufferCreateInfo;
        }
        int usage = bufferCreateInfo.usage();
        int geomMask = VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
        if ((usage & geomMask) != 0) {
            int extra = VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
            if (TurboShadersBridge.accelStructSupported) {
                extra |= KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
            }
            bufferCreateInfo.usage(usage | extra);
        }
        return bufferCreateInfo;
    }

    @Unique
    private void vulkanturbo$ensureCoherencyChecked() {
        if (this.vulkanturbo$coherencyChecked) {
            return;
        }
        synchronized (this) {
            if (this.vulkanturbo$coherencyChecked) {
                return;
            }
            boolean coherent = false;
            if (this.device != null && this.vmaAllocation != 0L) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    IntBuffer flagsPtr = stack.callocInt(1);
                    Vma.vmaGetAllocationMemoryProperties(this.device.vma(), this.vmaAllocation, flagsPtr);
                    coherent = (flagsPtr.get(0) & VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
                } catch (Throwable ignored) {
                    coherent = false;
                }
            }
            this.vulkanturbo$hostCoherent = coherent;
            this.vulkanturbo$coherencyChecked = true;
        }
    }

    @Override
    public boolean vulkanturbo$isPersistentlyMapped() {
        return this.vulkanturbo$cachedMappedPtr != 0L;
    }

    @Override
    public boolean vulkanturbo$isHostCoherent() {
        vulkanturbo$ensureCoherencyChecked();
        return this.vulkanturbo$hostCoherent;
    }

    @Override
    public void vulkanturbo$flushMappedRange(long offset, long size) {
        if (size <= 0L || this.closed || this.device == null || this.vmaAllocation == 0L) {
            return;
        }
        vulkanturbo$ensureCoherencyChecked();
        if (!this.vulkanturbo$hostCoherent) {
            Vma.vmaFlushAllocation(this.device.vma(), this.vmaAllocation, offset, size);
        }
    }

    @Override
    public long vulkanturbo$getOrMapAddress() {
        if (!VulkanTurbo.modEnabled || this.closed) {
            return 0L;
        }
        long ptr = this.vulkanturbo$cachedMappedPtr;
        if (ptr != 0L) {
            return ptr;
        }
        synchronized (this) {
            if (this.closed) {
                return 0L;
            }
            ptr = this.vulkanturbo$cachedMappedPtr;
            if (ptr != 0L) {
                return ptr;
            }
            vulkanturbo$ensureCoherencyChecked();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer pData = stack.callocPointer(1);
                int rc = Vma.vmaMapMemory(this.device.vma(), this.vmaAllocation, pData);
                if (rc == VK10.VK_SUCCESS) {
                    ptr = pData.get(0);
                    this.vulkanturbo$cachedMappedPtr = ptr;
                }
            }
            return ptr;
        }
    }

    @Redirect(
        method = "map",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/util/vma/Vma;vmaMapMemory(JJLorg/lwjgl/PointerBuffer;)I"
        )
    )
    private int vulkanturbo$mapPersisted(long allocator, long allocation, PointerBuffer pData) {
        long ptr = this.vulkanturbo$cachedMappedPtr;
        if (ptr != 0L) {
            pData.put(0, ptr);
            return VK10.VK_SUCCESS;
        }
        if (!VulkanTurbo.modEnabled) {
            return Vma.vmaMapMemory(allocator, allocation, pData);
        }
        synchronized (this) {
            ptr = this.vulkanturbo$cachedMappedPtr;
            if (ptr != 0L) {
                pData.put(0, ptr);
                return VK10.VK_SUCCESS;
            }
            vulkanturbo$ensureCoherencyChecked();
            int rc = Vma.vmaMapMemory(allocator, allocation, pData);
            if (rc == VK10.VK_SUCCESS) {
                this.vulkanturbo$cachedMappedPtr = pData.get(0);
            }
            return rc;
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void vulkanturbo$onBufferClose(CallbackInfo ci) {
        if (!this.closed && this.device != null) {
            long vkBuffer = ((VulkanGpuBuffer) (Object) this).vkBuffer();
            VulkanBufferViewCache.onBufferClosed(
                this.device.vkDevice(),
                vkBuffer,
                this.device.createCommandEncoder()
            );
        }
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void vulkanturbo$onBufferDestroy(CallbackInfo ci) {
        long ptr;
        synchronized (this) {
            ptr = this.vulkanturbo$cachedMappedPtr;
            this.vulkanturbo$cachedMappedPtr = 0L;
        }
        if (ptr != 0L) {
            Vma.vmaUnmapMemory(this.device.vma(), this.vmaAllocation);
        }
        long vkBuffer = ((VulkanGpuBuffer) (Object) this).vkBuffer();
        VulkanBufferViewCache.onBufferDestroyed(this.device.vkDevice(), vkBuffer, null);
    }
}
