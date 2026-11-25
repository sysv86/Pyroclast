package dev.vulkanturbo

import com.mojang.renderpearl.api.device.GpuDevice
import dev.vulkanturbo.api.shader.TurboShadersBridge
import dev.vulkanturbo.api.shader.TurboShadersExtension
import dev.vulkanturbo.config.ConfigStore
import dev.vulkanturbo.config.GpuVendor
import dev.vulkanturbo.config.VulkanTurboConfig
import dev.vulkanturbo.core.RenderStats
import dev.vulkanturbo.core.TerrainRegionPipelines
import dev.vulkanturbo.diagnostics.VulkanDiagnosticLog
import dev.vulkanturbo.gui.DiagnosticNotices
import dev.vulkanturbo.platform.PlatformInfo
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VkPhysicalDevice
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties
import org.lwjgl.vulkan.VkPhysicalDeviceProperties
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Client entrypoint (`VulkanTurbo` internal engine, distributed publicly as **Pyroclast** under
 * short distribution codename **`TEPHRA`**).
 *
 * Loads config, discovers any installed `TurboShaders` entrypoint, verifies at device
 * initialization that the active RenderPearl backend is Vulkan (disabling the mod cleanly and
 * notifying the player in chat if OpenGL is active), auto-tunes vendor-specific settings for
 * NVIDIA (`0x10DE`), AMD RDNA (`0x1002`), and Intel Arc (`0x8086`), and emits the startup banner.
 */
object VulkanTurbo : ClientModInitializer {
    const val MOD_ID: String = "vulkanturbo"
    const val INTERNAL_NAME: String = "VulkanTurbo"
    const val PUBLIC_NAME: String = "Pyroclast"
    const val CODENAME: String = "TEPHRA"
    const val TURBOSHADERS_ENTRYPOINT: String = "turboshaders"

    private const val BRAND_NAME: String = "VulkanTurbo"

    // Sampled from docs/icons/pyroclast.svg letterFrontGrad — the banner at the top of README.md:
    // #B9EBE7 -> #DCF6F4 -> #FFF8F9 -> #FBBCCB -> #F88294 -> #EC6076.
    private val BRAND_GRADIENT = intArrayOf(0xB9EBE7, 0xDCF6F4, 0xFFF8F9, 0xFBBCCB, 0xF88294, 0xEC6076)
    private const val BRAND_BODY_COLOR: Int = 0xF88294

    private fun gradientRgb(t: Float): Int {
        val last = BRAND_GRADIENT.size - 1
        val pos = t.coerceIn(0f, 1f) * last
        val idx = pos.toInt().coerceAtMost(last - 1)
        val f = pos - idx
        val a = BRAND_GRADIENT[idx]
        val b = BRAND_GRADIENT[idx + 1]
        val ar = (a shr 16) and 0xFF
        val ag = (a shr 8) and 0xFF
        val ab = a and 0xFF
        val br = (b shr 16) and 0xFF
        val bg = (b shr 8) and 0xFF
        val bb = b and 0xFF
        val r = (ar + (br - ar) * f).toInt()
        val g = (ag + (bg - ag) * f).toInt()
        val bl = (ab + (bb - ab) * f).toInt()
        return (r shl 16) or (g shl 8) or bl
    }

    val log: Logger = LoggerFactory.getLogger(MOD_ID)

    @Volatile
    var config: VulkanTurboConfig = VulkanTurboConfig()
        private set

    @JvmField @Volatile var backendDetected: Boolean = false
    @JvmField @Volatile var isVulkanBackend: Boolean = false
    @JvmField @Volatile var detectedBackendName: String = "unknown"
    @JvmField @Volatile var detectedDeviceName: String = ""
    @JvmField @Volatile var detectedVendorName: String = ""
    @JvmField @Volatile var detectedVendor: GpuVendor = GpuVendor.OTHER
    @JvmField @Volatile var detectedMaxMemoryAllocationSize: Long = 0L
    @JvmField @Volatile var isHighEndGpu: Boolean = false
    @Volatile private var pendingChatAnnouncement: Boolean = false

    // Plain fields updated alongside config so hot loops do not pay volatile barrier reads per call.
    @JvmField var modEnabled: Boolean = false
    @JvmField var verboseLoggingEnabled: Boolean = false
    @JvmField var stateDedupEnabled: Boolean = false
    @JvmField var normalCacheEnabled: Boolean = false
    @JvmField var scopedBarriersEnabled: Boolean = false
    @JvmField var fastChunkPrepEnabled: Boolean = false
    @JvmField var mappedChunkSectionsEnabled: Boolean = false
    @JvmField var bufferViewCacheEnabled: Boolean = false
    @JvmField var fastEntityRenderEnabled: Boolean = false
    @JvmField var cacheVoxelBoundsEnabled: Boolean = false
    @JvmField var asyncOcclusionCullingEnabled: Boolean = false
    @JvmField var pipelineCacheEnabled: Boolean = false
    @JvmField var optimalImageLayoutsEnabled: Boolean = false
    @Volatile private var optimalImageLayoutsLatched: Boolean = false
    @JvmField var benchmarkEnabled: Boolean = false
    @JvmField var statsCollectionEnabled: Boolean = false

    private fun applyConfig(cfg: VulkanTurboConfig) {
        config = cfg
        val on = cfg.enabled && (!backendDetected || isVulkanBackend)
        val vendor = detectedVendor
        modEnabled = on
        verboseLoggingEnabled = on && cfg.verboseLogging
        stateDedupEnabled = on && cfg.stateDedup
        normalCacheEnabled = on && cfg.normalCache
        scopedBarriersEnabled = on && cfg.scopedBarriers
        fastChunkPrepEnabled = on && cfg.fastChunkPrep
        mappedChunkSectionsEnabled = on && cfg.mappedChunkSections
        bufferViewCacheEnabled = on && cfg.bufferViewCache
        fastEntityRenderEnabled = on && cfg.fastEntityRender
        cacheVoxelBoundsEnabled = on && cfg.cacheVoxelBounds
        asyncOcclusionCullingEnabled = on && cfg.asyncOcclusionCulling
        pipelineCacheEnabled = on && cfg.resolvePipelineCache(vendor)
        if (!optimalImageLayoutsLatched) {
            optimalImageLayoutsEnabled = on && cfg.resolveOptimalImageLayouts(vendor)
        }
        benchmarkEnabled = on && cfg.benchmark
        statsCollectionEnabled = on && (cfg.benchmark || cfg.verboseLogging)
    }

    init {
        applyConfig(config)
    }

    internal fun setConfigForTesting(cfg: VulkanTurboConfig, vendor: GpuVendor = GpuVendor.OTHER) {
        detectedVendor = vendor
        optimalImageLayoutsLatched = false
        applyConfig(cfg)
    }

    /**
     * Reloads [VulkanTurboConfig] from [ConfigStore] and refreshes all runtime optimization flags.
     */
    @JvmStatic
    fun refreshRuntimeFlags() {
        applyUpdatedConfig(ConfigStore.load())
    }

    /**
     * Applies [newConfig] immediately to all runtime optimization flags without re-reading disk.
     */
    @JvmStatic
    fun applyUpdatedConfig(newConfig: VulkanTurboConfig) {
        val usedRegionMeshes = TerrainRegionPipelines.isRegionBatchingActive()
        val wasEnabled = modEnabled
        applyConfig(newConfig)
        val enabledChanged = wasEnabled != modEnabled
        val meshModeChanged = usedRegionMeshes != TerrainRegionPipelines.isRegionBatchingActive()
        if (enabledChanged && FabricLoader.getInstance().isModLoaded("vulkanshaders")) {
            runCatching {
                val shaders = Class.forName("dev.vulkanshaders.VulkanShaders")
                shaders.getMethod("onPyroclastStateChanged", Boolean::class.javaPrimitiveType)
                    .invoke(null, modEnabled)
            }.onFailure {
                VulkanDiagnosticLog.recordWarning(
                    source = "Bridge",
                    summary = "Could not synchronize Hyaloclast activation state: ${it.message}",
                    throwable = it,
                )
            }
        }
        if (enabledChanged || meshModeChanged) {
            val client: Minecraft? = Minecraft.getInstance()
            if (client?.level != null) {
                client.levelExtractor.allChanged()
            }
        }
    }

    @JvmStatic
    fun isBackendAllowed(): Boolean = !backendDetected || isVulkanBackend

    @JvmStatic
    fun classifyVulkanDevice(physicalDevice: VkPhysicalDevice, deviceName: String) {
        MemoryStack.stackPush().use { stack ->
            val properties = VkPhysicalDeviceProperties.malloc(stack)
            VK10.vkGetPhysicalDeviceProperties(physicalDevice, properties)
            val memory = VkPhysicalDeviceMemoryProperties.malloc(stack)
            VK10.vkGetPhysicalDeviceMemoryProperties(physicalDevice, memory)
            val localBytes = (0 until memory.memoryHeapCount())
                .map { memory.memoryHeaps(it) }
                .filter { (it.flags() and VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0 }
                .maxOfOrNull { it.size() } ?: 0L
            val discrete = properties.deviceType() == VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU
            isHighEndGpu = discrete && VulkanTurboConfig.isHighEndGpu(deviceName, localBytes)
        }
    }

    @JvmOverloads
    @JvmStatic
    fun onRendererInitialized(device: GpuDevice?, vendorId: Int = 0) {
        val info = try {
            device?.getDeviceInfo()
        } catch (_: Throwable) {
            null
        }
        val backend = info?.backendName()?.takeIf { it.isNotBlank() } ?: "unknown"
        val deviceName = info?.name()?.takeIf { it.isNotBlank() } ?: "GPU"
        val vendorName = info?.vendorName()?.takeIf { it.isNotBlank() } ?: ""
        val maxAlloc = try {
            info?.limits()?.maxMemoryAllocationSize() ?: 0L
        } catch (_: Throwable) {
            0L
        }
        val vulkan = backend.equals("Vulkan", ignoreCase = true)

        detectedBackendName = backend
        detectedDeviceName = deviceName
        detectedVendorName = vendorName
        detectedVendor = GpuVendor.detect(vendorId, vendorName, deviceName)
        detectedMaxMemoryAllocationSize = maxAlloc
        if (!vulkan) isHighEndGpu = false
        isVulkanBackend = vulkan
        backendDetected = true
        TerrainRegionPipelines.onBackendDetected(vulkan, device)
        optimalImageLayoutsLatched = false
        applyConfig(config)
        optimalImageLayoutsLatched = true

        if (!vulkan) {
            VulkanDiagnosticLog.recordError(
                source = "Backend",
                summary = "$backend rendering backend is NOT supported; disabling all Pyroclast hooks and optimizations.",
                showToast = false,
            )
            pendingChatAnnouncement = true
            return
        }

        if (config.enabled) {
            log.info(
                "VulkanTurbo ({} / {}) active on Vulkan device: {} (vendor={}, highEnd={}, optimalImageLayouts={}, pipelineCache={})",
                PUBLIC_NAME,
                CODENAME,
                deviceName,
                detectedVendor,
                isHighEndGpu,
                optimalImageLayoutsEnabled,
                pipelineCacheEnabled,
            )
            pendingChatAnnouncement = true
        }
    }

    @JvmStatic
    fun onClientTick(mc: Minecraft) {
        runCatching { TurboShadersBridge.extension?.drainDiagnosticLogs() }
        DiagnosticNotices.pollErrorToastInteraction(mc)
        RenderStats.onClientTick()
        if (!pendingChatAnnouncement) return
        if (mc.player == null || mc.level == null) return
        pendingChatAnnouncement = false

        val msg = buildStatusMessage(
            isVulkan = isVulkanBackend,
            backendName = detectedBackendName,
            deviceName = detectedDeviceName,
            configEnabled = config.enabled,
        )
        mc.gui.hud.getChat().addClientSystemMessage(msg)
    }

    internal fun buildGradientBrandComponent(): MutableComponent {
        val root = Component.empty()
        val last = (BRAND_NAME.length - 1).coerceAtLeast(1)
        for (i in BRAND_NAME.indices) {
            val t = i.toFloat() / last.toFloat()
            root.append(
                Component.literal(BRAND_NAME.substring(i, i + 1)).withStyle(
                    Style.EMPTY.withColor(TextColor.fromRgb(gradientRgb(t))).withBold(true),
                ),
            )
        }
        return root
    }

    internal fun buildStatusMessage(
        isVulkan: Boolean,
        backendName: String,
        deviceName: String,
        configEnabled: Boolean,
    ): MutableComponent {
        val root = buildGradientBrandComponent()
        val body = when {
            !isVulkan -> ": $backendName rendering is NOT SUPPORTED — mod disabled."
            !configEnabled -> ": Vulkan backend detected ($deviceName), but mod is disabled in config."
            else -> ": Vulkan backend active ($deviceName) — optimizations enabled."
        }
        root.append(Component.literal(body).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(BRAND_BODY_COLOR)).withBold(false)))
        return root
    }

    override fun onInitializeClient() {
        VulkanDiagnosticLog.installCrashHooks()
        applyConfig(ConfigStore.load())

        val loader = FabricLoader.getInstance()
        val version = loader
            .getModContainer(MOD_ID)
            .map { it.metadata.version.friendlyString }
            .orElse("development")

        log.info(
            "VulkanTurbo ({} / codename {}) {} on {} {} (JVM {}, session {})",
            PUBLIC_NAME,
            CODENAME,
            version,
            PlatformInfo.osName(),
            PlatformInfo.osArch(),
            PlatformInfo.jvmVersion(),
            PlatformInfo.sessionType(),
        )

        val shaderExtensions = loader.getEntrypoints(TURBOSHADERS_ENTRYPOINT, TurboShadersExtension::class.java)
        if (shaderExtensions.isNotEmpty()) {
            TurboShadersBridge.register(shaderExtensions.first())
        }

        if (!config.enabled) {
            log.info("VulkanTurbo is disabled in config; no hooks will be installed")
            return
        }
    }
}
