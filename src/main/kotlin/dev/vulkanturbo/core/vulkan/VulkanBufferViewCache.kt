package dev.vulkanturbo.core

import com.mojang.renderpearl.backend.vulkan.Destroyable
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK12
import org.lwjgl.vulkan.VkAllocationCallbacks
import org.lwjgl.vulkan.VkBufferViewCreateInfo
import org.lwjgl.vulkan.VkDevice
import java.nio.LongBuffer

/**
 * Pure JVM LRU cache table for texel `VkBufferView` handles grouped by `(deviceAddr, vkBuffer)`.
 *
 * Uses a single-entry hot-hit pointer (`hotEntry`) in front of the bucket list so consecutive
 * draws binding the same texel buffer slice resolve in O(1) without hash probing or LRU pointer
 * shuffling.
 *
 * Kept decoupled from native Vulkan structs so eviction, buffer invalidation, and device teardown
 * can be verified directly in unit tests.
 */
internal class BufferViewRegistry(
    private val maxEntries: Int = MAX_CACHED_VIEWS,
) {
    class ViewEntry(
        @JvmField val deviceAddr: Long,
        @JvmField val vkBuffer: Long,
        @JvmField val offset: Long,
        @JvmField val range: Long,
        @JvmField val format: Int,
        @JvmField val vkView: Long,
    ) {
        @JvmField var prev: ViewEntry? = null
        @JvmField var next: ViewEntry? = null
    }

    private class BufferBucket {
        val views: ArrayList<ViewEntry> = ArrayList(4)
    }

    private val byDeviceAndBuffer = Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<BufferBucket>>()
    private var head: ViewEntry? = null // MRU
    private var tail: ViewEntry? = null // LRU
    private var hotEntry: ViewEntry? = null
    var size: Int = 0
        private set

    fun find(deviceAddr: Long, vkBuffer: Long, offset: Long, range: Long, format: Int): Long {
        val hot = hotEntry
        if (hot != null &&
            hot.vkBuffer == vkBuffer &&
            hot.offset == offset &&
            hot.range == range &&
            hot.format == format &&
            hot.deviceAddr == deviceAddr
        ) {
            return hot.vkView
        }

        val byBuffer = byDeviceAndBuffer.get(deviceAddr) ?: return 0L
        val bucket = byBuffer.get(vkBuffer) ?: return 0L
        val list = bucket.views
        for (i in 0 until list.size) {
            val entry = list[i]
            if (entry.offset == offset && entry.range == range && entry.format == format) {
                moveToFront(entry)
                hotEntry = entry
                return entry.vkView
            }
        }
        return 0L
    }

    /**
     * Inserts a newly created view. If the registry is at capacity, unlinks and returns the LRU
     * evicted handle; returns `0L` if nothing was evicted.
     */
    fun insert(deviceAddr: Long, vkBuffer: Long, offset: Long, range: Long, format: Int, vkView: Long): Long {
        val entry = ViewEntry(deviceAddr, vkBuffer, offset, range, format, vkView)
        var byBuffer = byDeviceAndBuffer.get(deviceAddr)
        if (byBuffer == null) {
            byBuffer = Long2ObjectOpenHashMap()
            byDeviceAndBuffer.put(deviceAddr, byBuffer)
        }
        var bucket = byBuffer.get(vkBuffer)
        if (bucket == null) {
            bucket = BufferBucket()
            byBuffer.put(vkBuffer, bucket)
        }
        bucket.views.add(entry)
        linkFront(entry)
        hotEntry = entry
        size++

        if (size > maxEntries) {
            val victim = tail
            if (victim != null && victim !== entry) {
                if (hotEntry === victim) {
                    hotEntry = entry
                }
                unlink(victim)
                size--
                val victimByBuffer = byDeviceAndBuffer.get(victim.deviceAddr)
                val victimBucket = victimByBuffer?.get(victim.vkBuffer)
                if (victimBucket != null) {
                    victimBucket.views.remove(victim)
                    if (victimBucket.views.isEmpty()) {
                        victimByBuffer.remove(victim.vkBuffer)
                        if (victimByBuffer.isEmpty()) {
                            byDeviceAndBuffer.remove(victim.deviceAddr)
                        }
                    }
                }
                return victim.vkView
            }
        }
        return 0L
    }

    /** Removes all entries bound to [vkBuffer] on [deviceAddr] and feeds their handles to [onRemove]. */
    fun removeBuffer(deviceAddr: Long, vkBuffer: Long, onRemove: (Long) -> Unit) {
        val hot = hotEntry
        if (hot != null && hot.deviceAddr == deviceAddr && hot.vkBuffer == vkBuffer) {
            hotEntry = null
        }
        val byBuffer = byDeviceAndBuffer.get(deviceAddr) ?: return
        val bucket = byBuffer.remove(vkBuffer) ?: return
        val list = bucket.views
        for (i in 0 until list.size) {
            val entry = list[i]
            unlink(entry)
            size--
            onRemove(entry.vkView)
        }
        if (byBuffer.isEmpty()) {
            byDeviceAndBuffer.remove(deviceAddr)
        }
    }

    /** Removes all entries belonging to [deviceAddr] and feeds their handles to [onRemove]. */
    fun removeDevice(deviceAddr: Long, onRemove: (Long) -> Unit) {
        val hot = hotEntry
        if (hot != null && hot.deviceAddr == deviceAddr) {
            hotEntry = null
        }
        val byBuffer = byDeviceAndBuffer.remove(deviceAddr) ?: return
        val iter = byBuffer.values.iterator()
        while (iter.hasNext()) {
            val list = iter.next().views
            for (i in 0 until list.size) {
                val entry = list[i]
                unlink(entry)
                size--
                onRemove(entry.vkView)
            }
        }
    }

    private fun linkFront(entry: ViewEntry) {
        entry.prev = null
        entry.next = head
        head?.prev = entry
        head = entry
        if (tail == null) {
            tail = entry
        }
    }

    private fun unlink(entry: ViewEntry) {
        val p = entry.prev
        val n = entry.next
        if (p != null) {
            p.next = n
        } else {
            head = n
        }
        if (n != null) {
            n.prev = p
        } else {
            tail = p
        }
        entry.prev = null
        entry.next = null
    }

    private fun moveToFront(entry: ViewEntry) {
        if (entry === head) return
        unlink(entry)
        linkFront(entry)
    }

    companion object {
        const val MAX_CACHED_VIEWS = 4096
    }
}

/**
 * Caches `VkBufferView` objects created for `UniformType.TEXEL_BUFFER` descriptors across draws.
 *
 * Mojang's `VulkanRenderPass.pushDescriptors()` normally calls `vkCreateBufferView` and schedules
 * `vkDestroyBufferView` via `encoder.queueForDestroy` on every texel-buffer descriptor write.
 * Caching by `(VkDevice, vkBuffer, offset, range, format)` eliminates repeated driver handle
 * creation/destruction while deferring view destruction via `encoder.queueForDestroy` on buffer
 * close (`onBufferClosed`) and LRU eviction so `vkDestroyBufferView` never races an active or
 * in-flight command buffer (`VUID-vkDestroyBufferView-bufferView-00936`).
 */
object VulkanBufferViewCache {
    private val lock = Any()
    private val registry = BufferViewRegistry()

    @JvmStatic
    fun acquireView(
        vkDevice: VkDevice,
        createInfo: VkBufferViewCreateInfo,
        allocator: VkAllocationCallbacks?,
        pView: LongBuffer,
        encoder: VulkanCommandEncoder,
    ): Int {
        val deviceAddr = vkDevice.address()
        val vkBuffer = createInfo.buffer()
        val offset = createInfo.offset()
        val range = createInfo.range()
        val format = createInfo.format()
        val pos = pView.position()

        synchronized(lock) {
            val cached = registry.find(deviceAddr, vkBuffer, offset, range, format)
            if (cached != 0L) {
                pView.put(pos, cached)
                return VK10.VK_SUCCESS
            }
        }

        val res = VK12.vkCreateBufferView(vkDevice, createInfo, allocator, pView)
        if (res != VK10.VK_SUCCESS) {
            return res
        }

        val created = pView.get(pos)
        val evicted: Long
        synchronized(lock) {
            // Re-check under lock in case of concurrent creation; safe no-op collision if unique.
            val existing = registry.find(deviceAddr, vkBuffer, offset, range, format)
            if (existing != 0L) {
                pView.put(pos, existing)
                encoder.queueForDestroy(Destroyable { VK12.vkDestroyBufferView(vkDevice, created, null) })
                return VK10.VK_SUCCESS
            }
            evicted = registry.insert(deviceAddr, vkBuffer, offset, range, format, created)
        }

        if (evicted != 0L) {
            // Defer destruction until the active command submission finishes on the GPU.
            encoder.queueForDestroy(Destroyable { VK12.vkDestroyBufferView(vkDevice, evicted, null) })
        }
        return VK10.VK_SUCCESS
    }

    /**
     * Unlinks all cached `VkBufferView` handles for [vkBuffer] when `VulkanGpuBuffer.Direct.close()`
     * is called and schedules their destruction onto [encoder]'s `DestructionQueue` BEFORE the
     * underlying `VkBuffer` itself is queued for destruction.
     */
    @JvmStatic
    fun onBufferClosed(vkDevice: VkDevice, vkBuffer: Long, encoder: VulkanCommandEncoder?) {
        val deviceAddr = vkDevice.address()
        val removedViews = ArrayList<Long>(4)
        synchronized(lock) {
            registry.removeBuffer(deviceAddr, vkBuffer) { view ->
                removedViews.add(view)
            }
        }
        if (removedViews.isEmpty()) return
        if (encoder != null) {
            encoder.queueForDestroy(
                Destroyable {
                    for (i in 0 until removedViews.size) {
                        VK12.vkDestroyBufferView(vkDevice, removedViews[i], null)
                    }
                },
            )
        } else {
            for (i in 0 until removedViews.size) {
                VK12.vkDestroyBufferView(vkDevice, removedViews[i], null)
            }
        }
    }

    @JvmStatic
    fun onBufferDestroyed(vkDevice: VkDevice, vkBuffer: Long, encoder: VulkanCommandEncoder? = null) {
        onBufferClosed(vkDevice, vkBuffer, encoder)
    }

    @JvmStatic
    fun onDeviceClosing(vkDevice: VkDevice) {
        val deviceAddr = vkDevice.address()
        synchronized(lock) {
            registry.removeDevice(deviceAddr) { view ->
                VK12.vkDestroyBufferView(vkDevice, view, null)
            }
        }
    }
}
