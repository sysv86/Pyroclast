package dev.vulkanturbo.core

/**
 * Duck interface mixed into `VulkanGpuBuffer.Direct` to expose whether VMA memory is currently
 * held persistently mapped across frames, whether its backing memory type has
 * `VK_MEMORY_PROPERTY_HOST_COHERENT_BIT`, and to provide direct native address access + explicit
 * `vmaFlushAllocation` support for non-coherent host memory.
 */
interface VulkanPersistentlyMappedBuffer {
    fun `vulkanturbo$isPersistentlyMapped`(): Boolean
    fun `vulkanturbo$isHostCoherent`(): Boolean
    fun `vulkanturbo$getOrMapAddress`(): Long
    fun `vulkanturbo$flushMappedRange`(offset: Long, size: Long)
}
