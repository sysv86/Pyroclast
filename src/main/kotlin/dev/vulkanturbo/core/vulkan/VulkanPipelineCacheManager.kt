package dev.vulkanturbo.core

import dev.vulkanturbo.VulkanTurbo
import dev.vulkanturbo.diagnostics.VulkanDiagnosticLog
import net.fabricmc.loader.api.FabricLoader
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK12
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkPhysicalDeviceProperties
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Pure JVM header check for Vulkan 1.x `VkPipelineCache` binary blobs (standard table 12 header).
 *
 * Keeps vendor, device, header-version, and pipelineCacheUUID verification testable without native
 * Vulkan initialization.
 */
internal object PipelineCacheHeader {
    private const val MIN_HEADER_BYTES = 32
    private const val EXPECTED_HEADER_VERSION_ONE = 1

    fun isCompatible(
        blob: ByteArray,
        vendorId: Int,
        deviceId: Int,
        expectedUuid: ByteArray,
    ): Boolean {
        if (blob.size < MIN_HEADER_BYTES || expectedUuid.size < 16) {
            return false
        }
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.nativeOrder())
        val headerSize = buf.getInt(0)
        val headerVersion = buf.getInt(4)
        val blobVendor = buf.getInt(8)
        val blobDevice = buf.getInt(12)
        if (headerSize < MIN_HEADER_BYTES || headerSize > blob.size) {
            return false
        }
        if (headerVersion != EXPECTED_HEADER_VERSION_ONE) {
            return false
        }
        if (blobVendor != vendorId || blobDevice != deviceId) {
            return false
        }
        for (i in 0 until 16) {
            if (blob[16 + i] != expectedUuid[i]) {
                return false
            }
        }
        return true
    }
}

/**
 * Persistent disk-backed `VkPipelineCache` keyed by GPU vendor, device, driver version, and UUID.
 *
 * Mojang's `VulkanRenderPipeline.compile` normally passes `0L` (`VK_NULL_HANDLE`) to every
 * `vkCreateGraphicsPipelines` call, forcing full driver shader recompilation on every launch and
 * resource reload.
 */
object VulkanPipelineCacheManager {
    private class CacheEntry(
        val vkDevice: VkDevice,
        val handle: Long,
        val diskPath: Path?,
    ) {
        @Volatile
        var destroyed: Boolean = false
        var pendingFlush: ScheduledFuture<*>? = null
    }

    private val caches = ConcurrentHashMap<Long, CacheEntry>()
    private val flusher = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "VulkanTurbo-PipelineCacheWriter").apply { isDaemon = true }
    }

    @JvmStatic
    fun isEnabled(): Boolean = VulkanTurbo.pipelineCacheEnabled

    @JvmStatic
    fun getOrCreateCache(vkDevice: VkDevice): Long {
        if (!VulkanTurbo.pipelineCacheEnabled) {
            return VK10.VK_NULL_HANDLE
        }
        val key = vkDevice.address()
        caches[key]?.let { return it.handle }

        synchronized(this) {
            caches[key]?.let { return it.handle }
            val created = createCache(vkDevice)
            caches[key] = created
            return created.handle
        }
    }

    @JvmStatic
    fun notifyCompiled(vkDevice: VkDevice) {
        if (!VulkanTurbo.pipelineCacheEnabled) return
        val entry = caches[vkDevice.address()] ?: return
        if (entry.handle == VK10.VK_NULL_HANDLE || entry.diskPath == null) return
        synchronized(entry) {
            if (entry.destroyed) return
            entry.pendingFlush?.cancel(false)
            entry.pendingFlush = flusher.schedule({
                synchronized(entry) {
                    if (!entry.destroyed) {
                        persistToDisk(entry.vkDevice, entry)
                    }
                }
            }, 2L, TimeUnit.SECONDS)
        }
    }

    @JvmStatic
    fun saveAndDestroy(vkDevice: VkDevice) {
        val entry = synchronized(this) { caches.remove(vkDevice.address()) } ?: return
        if (entry.handle == VK10.VK_NULL_HANDLE) {
            return
        }
        synchronized(entry) {
            entry.pendingFlush?.cancel(false)
            entry.pendingFlush = null
            entry.destroyed = true
            try {
                persistToDisk(vkDevice, entry)
            } finally {
                VK12.vkDestroyPipelineCache(vkDevice, entry.handle, null)
            }
        }
    }

    private fun createCache(vkDevice: VkDevice): CacheEntry {
        MemoryStack.stackPush().use { stack ->
            val props = VkPhysicalDeviceProperties.calloc(stack)
            VK12.vkGetPhysicalDeviceProperties(vkDevice.physicalDevice, props)
            val vendorId = props.vendorID()
            val deviceId = props.deviceID()
            val driverVersion = props.driverVersion()
            val uuidBuf = props.pipelineCacheUUID()
            val uuid = ByteArray(16) { idx -> uuidBuf.get(idx) }

            val diskPath = resolveCachePath(vendorId, deviceId, driverVersion, uuid)
            var initialBytes: ByteArray? = null
            if (diskPath != null && Files.isRegularFile(diskPath)) {
                try {
                    val loaded = Files.readAllBytes(diskPath)
                    if (PipelineCacheHeader.isCompatible(loaded, vendorId, deviceId, uuid)) {
                        initialBytes = loaded
                    }
                } catch (t: Exception) {
                    VulkanTurbo.log.debug("Ignoring unreadable pipeline cache {}: {}", diskPath, t.message)
                }
            }

            var nativeInitial: ByteBuffer? = null
            try {
                val createInfo = VkPipelineCacheCreateInfo.calloc(stack).`sType$Default`()
                if (initialBytes != null && initialBytes.isNotEmpty()) {
                    nativeInitial = MemoryUtil.memAlloc(initialBytes.size)
                    nativeInitial.put(initialBytes).flip()
                    createInfo.pInitialData(nativeInitial)
                }
                val pCache = stack.callocLong(1)
                val res = VK12.vkCreatePipelineCache(vkDevice, createInfo, null, pCache)
                if (res != VK10.VK_SUCCESS) {
                    VulkanDiagnosticLog.recordWarning(source = "PipelineCache", summary = "vkCreatePipelineCache failed with error $res; falling back to uncached")
                    return CacheEntry(vkDevice, VK10.VK_NULL_HANDLE, null)
                }
                val handle = pCache.get(0)
                if (initialBytes != null) {
                    VulkanTurbo.log.info("Loaded Vulkan pipeline cache ({} bytes) from {}", initialBytes.size, diskPath?.fileName)
                }
                return CacheEntry(vkDevice, handle, diskPath)
            } finally {
                if (nativeInitial != null) {
                    MemoryUtil.memFree(nativeInitial)
                }
            }
        }
    }

    private fun persistToDisk(vkDevice: VkDevice, entry: CacheEntry) {
        val path = entry.diskPath ?: return
        MemoryStack.stackPush().use { stack ->
            val pSize = stack.callocPointer(1)
            val queryRes = VK12.vkGetPipelineCacheData(vkDevice, entry.handle, pSize, null)
            if (queryRes != VK10.VK_SUCCESS) {
                return
            }
            val size = pSize.get(0).toInt()
            if (size <= 0) {
                return
            }
            val buf = MemoryUtil.memAlloc(size)
            try {
                val readRes = VK12.vkGetPipelineCacheData(vkDevice, entry.handle, pSize, buf)
                if (readRes != VK10.VK_SUCCESS && readRes != VK10.VK_INCOMPLETE) {
                    return
                }
                val actualSize = pSize.get(0).toInt().coerceIn(0, size)
                val bytes = ByteArray(actualSize)
                buf.limit(actualSize)
                buf.position(0)
                buf.get(bytes)

                Files.createDirectories(path.parent)
                val tmp = path.resolveSibling("${path.fileName}.tmp")
                Files.write(tmp, bytes)
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (t: Exception) {
                // Non-atomic move fallback if filesystem rejects ATOMIC_MOVE across mounts
                try {
                    val tmp = path.resolveSibling("${path.fileName}.tmp")
                    if (Files.exists(tmp)) {
                        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
                    }
                } catch (_: Exception) {
                    VulkanTurbo.log.debug("Failed to write pipeline cache {}: {}", path, t.message)
                }
            } finally {
                MemoryUtil.memFree(buf)
            }
        }
    }

    private fun resolveCachePath(
        vendorId: Int,
        deviceId: Int,
        driverVersion: Int,
        uuid: ByteArray,
    ): Path? {
        return try {
            val uuidHex = buildString(16) {
                for (i in 0 until 8) {
                    append("%02x".format(uuid[i].toInt() and 0xFF))
                }
            }
            val name = "pipeline-%04x-%04x-%08x-%s.bin".format(vendorId, deviceId, driverVersion, uuidHex)
            FabricLoader.getInstance().gameDir.resolve("cache").resolve("vulkanturbo").resolve(name)
        } catch (_: Throwable) {
            null
        }
    }
}
