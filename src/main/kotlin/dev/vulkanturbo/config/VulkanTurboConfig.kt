package dev.vulkanturbo.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/**
 * Hardware GPU vendor classification used for vendor-specific Vulkan optimizations.
 */
enum class GpuVendor(val vendorId: Int) {
    NVIDIA(0x10DE),
    AMD(0x1002),
    INTEL(0x8086),
    OTHER(0);

    companion object {
        @JvmStatic
        fun detect(vendorId: Int, vendorName: String?, deviceName: String?): GpuVendor {
            when (vendorId) {
                0x10DE -> return NVIDIA
                0x1002, 0x1022 -> return AMD
                0x8086 -> return INTEL
            }
            val combined = "${vendorName.orEmpty()} ${deviceName.orEmpty()}".lowercase()
            return when {
                "nvidia" in combined || "geforce" in combined || "rtx" in combined || "gtx" in combined || "quadro" in combined -> NVIDIA
                "amd" in combined || "radeon" in combined || "advanced micro devices" in combined || "rdna" in combined || "rx " in combined -> AMD
                "intel" in combined || "arc" in combined || "iris" in combined || "uhd" in combined -> INTEL
                else -> OTHER
            }
        }
    }
}

/**
 * VulkanTurbo settings. Deliberately small: a key only exists here if the code that reads it exists
 * as well. New keys must be added together with their implementation.
 */
data class VulkanTurboConfig(
    /** Master switch. When false the mod installs no hooks and does nothing. */
    val enabled: Boolean = true,
    /** Print the environment and backend probe at startup. */
    val verboseLogging: Boolean = false,
    /** Skip pipeline binds and scissor changes that are already in effect. */
    val stateDedup: Boolean = true,
    /** Cache packed vertex normals between vertices that share the same normal. */
    val normalCache: Boolean = true,
    /** Narrow global Vulkan ALL_COMMANDS barriers and swapchain blit sync. */
    val scopedBarriers: Boolean = true,
    /** Eliminate per-frame allocations and HashMap lookups during indirect chunk draw preparation. */
    val fastChunkPrep: Boolean = true,
    /** Use mapped ring buffers instead of staging copies for ChunkSectionInfo instanced data. */
    val mappedChunkSections: Boolean = true,
    /** Cache VkBufferView handles for TEXEL_BUFFER uniform bindings instead of recreating every draw. */
    val bufferViewCache: Boolean = true,
    /** Eliminate per-entity/particle allocations and speed up model/shadow geometry submission. */
    val fastEntityRender: Boolean = true,
    /** Cache immutable VoxelShape bounding boxes across collision and shadow queries. */
    val cacheVoxelBounds: Boolean = true,
    /** Run section occlusion graph BFS and visibility propagation asynchronously off the render thread. */
    val asyncOcclusionCulling: Boolean = true,
    /**
     * Automatically enable `optimalImageLayouts` and `pipelineCache` on AMD RDNA (`0x1002`) and
     * Intel Arc (`0x8086`) where `VK_IMAGE_LAYOUT_GENERAL` disables DCC compression, while keeping
     * them off by default on NVIDIA (`0x10DE`) where driver-internal caches already compress `GENERAL`
     * and serialize parallel `vkCreateGraphicsPipelines` calls when an explicit cache is supplied.
     */
    val autoVendorOptimizations: Boolean = true,
    /**
     * Persist VkPipelineCache blobs to disk across launches.
     * Off by default on NVIDIA; auto-enabled on AMD/Intel when [autoVendorOptimizations] is true
     * unless explicitly set to `false` in `vulkanturbo.json`.
     */
    val pipelineCache: Boolean = false,
    /**
     * Track and transition Vulkan images to optimal usage layouts instead of VK_IMAGE_LAYOUT_GENERAL.
     * Off by default on NVIDIA; auto-enabled on AMD/Intel when [autoVendorOptimizations] is true
     * unless explicitly set to `false` in `vulkanturbo.json`.
     */
    val optimalImageLayouts: Boolean = false,
    /** Log frame-time windows once a world is loaded. Development aid, off by default. */
    val benchmark: Boolean = false,
    @Transient val explicitPipelineCache: Boolean? = null,
    @Transient val explicitOptimalImageLayouts: Boolean? = null,
) {
    /**
     * Resolves whether `pipelineCache` should be active for the detected [vendor].
     */
    fun resolvePipelineCache(vendor: GpuVendor): Boolean {
        if (explicitPipelineCache != null) return explicitPipelineCache
        if (pipelineCache) return true
        return autoVendorOptimizations && (vendor == GpuVendor.AMD || vendor == GpuVendor.INTEL)
    }

    /**
     * Resolves whether `optimalImageLayouts` should be active for the detected [vendor].
     */
    fun resolveOptimalImageLayouts(vendor: GpuVendor): Boolean {
        if (explicitOptimalImageLayouts != null) return explicitOptimalImageLayouts
        if (optimalImageLayouts) return true
        return autoVendorOptimizations && (vendor == GpuVendor.AMD || vendor == GpuVendor.INTEL)
    }

    companion object {
        private const val GIB: Long = 1024L * 1024L * 1024L
        private val GSON = GsonBuilder().setPrettyPrinting().create()

        /**
         * Uses the device-local heap, since the maximum allocation size is a driver limit.
         */
        @JvmStatic
        fun isHighEndGpu(deviceName: String, localMemoryBytes: Long): Boolean {
            if (localMemoryBytes >= 12 * GIB) return true
            if (localMemoryBytes < 8 * GIB) return false
            val lower = deviceName.lowercase()
            return "rtx 4070" in lower ||
                "rtx 4080" in lower ||
                "rtx 4090" in lower ||
                "rtx 5070" in lower ||
                "rtx 5080" in lower ||
                "rtx 5090" in lower ||
                "rtx 3080" in lower ||
                "rtx 3090" in lower ||
                "rx 7800" in lower ||
                "rx 7900" in lower ||
                "rx 9070" in lower
        }

        fun fromJson(json: String): VulkanTurboConfig =
            try {
                val obj: JsonObject? = JsonParser.parseString(json)?.takeIf { it.isJsonObject }?.asJsonObject
                val parsed = GSON.fromJson(json, VulkanTurboConfig::class.java) ?: VulkanTurboConfig()
                val expPipe = obj?.get("pipelineCache")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                    ?.asBoolean
                val expLayout = obj?.get("optimalImageLayouts")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                    ?.asBoolean
                parsed.copy(
                    explicitPipelineCache = expPipe,
                    explicitOptimalImageLayouts = expLayout,
                )
            } catch (e: JsonParseException) {
                throw IllegalArgumentException("Malformed vulkanturbo.json", e)
            } catch (e: IllegalStateException) {
                throw IllegalArgumentException("Malformed vulkanturbo.json", e)
            } catch (e: UnsupportedOperationException) {
                throw IllegalArgumentException("Malformed vulkanturbo.json", e)
            }

        fun toJson(config: VulkanTurboConfig): String {
            val tree = GSON.toJsonTree(config).asJsonObject
            if (config.explicitPipelineCache == null) {
                tree.remove("pipelineCache")
            }
            if (config.explicitOptimalImageLayouts == null) {
                tree.remove("optimalImageLayouts")
            }
            return GSON.toJson(tree)
        }
    }
}
