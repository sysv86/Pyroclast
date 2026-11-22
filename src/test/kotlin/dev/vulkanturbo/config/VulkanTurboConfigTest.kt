package dev.vulkanturbo.config

import dev.vulkanturbo.VulkanTurbo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VulkanTurboConfigTest {
    @Test
    fun `high-end profile requires sufficient device-local memory`() {
        val gib = 1024L * 1024L * 1024L
        assertFalse(VulkanTurboConfig.isHighEndGpu("GeForce RTX 3050 Laptop GPU", 4 * gib))
        assertFalse(VulkanTurboConfig.isHighEndGpu("GeForce RTX 4050 Laptop GPU", 6 * gib))
        assertTrue(VulkanTurboConfig.isHighEndGpu("GeForce RTX 4070 Laptop GPU", 8 * gib))
        assertTrue(VulkanTurboConfig.isHighEndGpu("GeForce RTX 5090", 32 * gib))
    }

    @Test
    fun `defaults are enabled and quiet with pipelineCache and optimalImageLayouts disabled on NVIDIA`() {
        val config = VulkanTurboConfig()
        assertTrue(config.enabled)
        assertTrue(config.asyncOcclusionCulling)
        assertTrue(config.autoVendorOptimizations)
        assertFalse(config.verboseLogging)
        assertFalse(config.pipelineCache)
        assertFalse(config.optimalImageLayouts)
        assertFalse(config.resolvePipelineCache(GpuVendor.NVIDIA))
        assertFalse(config.resolveOptimalImageLayouts(GpuVendor.NVIDIA))
    }

    @Test
    fun `vendor detection identifies NVIDIA, AMD RDNA, and Intel Arc and auto-enables optimalImageLayouts on AMD and Intel`() {
        assertEquals(GpuVendor.NVIDIA, GpuVendor.detect(0x10DE, "NVIDIA Corporation", "NVIDIA GeForce RTX 4080"))
        assertEquals(GpuVendor.AMD, GpuVendor.detect(0x1002, "AMD", "AMD Radeon RX 7900 XTX (RADV NAVI31)"))
        assertEquals(GpuVendor.INTEL, GpuVendor.detect(0x8086, "Intel", "Intel(R) Arc(TM) A770 Graphics"))

        val config = VulkanTurboConfig()
        assertTrue(config.resolveOptimalImageLayouts(GpuVendor.AMD))
        assertTrue(config.resolvePipelineCache(GpuVendor.AMD))
        assertTrue(config.resolveOptimalImageLayouts(GpuVendor.INTEL))
        assertTrue(config.resolvePipelineCache(GpuVendor.INTEL))

        val explicitDisabled = VulkanTurboConfig.fromJson("""{"optimalImageLayouts": false, "pipelineCache": false}""")
        assertFalse(explicitDisabled.resolveOptimalImageLayouts(GpuVendor.AMD))
        assertFalse(explicitDisabled.resolvePipelineCache(GpuVendor.INTEL))
    }

    @Test
    fun `json round trip preserves values`() {
        val original = VulkanTurboConfig(
            enabled = false,
            asyncOcclusionCulling = false,
            verboseLogging = true,
            pipelineCache = true,
            explicitPipelineCache = true,
        )
        val restored = VulkanTurboConfig.fromJson(VulkanTurboConfig.toJson(original))
        assertEquals(original.enabled, restored.enabled)
        assertEquals(original.asyncOcclusionCulling, restored.asyncOcclusionCulling)
        assertEquals(original.verboseLogging, restored.verboseLogging)
        assertEquals(original.pipelineCache, restored.pipelineCache)
    }

    @Test
    fun `empty json falls back to defaults`() {
        val parsed = VulkanTurboConfig.fromJson("{}")
        assertEquals(VulkanTurboConfig(), parsed)
    }

    @Test
    fun `malformed json is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            VulkanTurboConfig.fromJson("{ not json")
        }
    }

    @Test
    fun `applyUpdatedConfig updates runtime optimization flags including asyncOcclusionCulling`() {
        val original = VulkanTurbo.config
        try {
            VulkanTurbo.setConfigForTesting(
                VulkanTurboConfig(enabled = true, asyncOcclusionCulling = false, scopedBarriers = false),
                GpuVendor.NVIDIA,
            )
            assertTrue(VulkanTurbo.modEnabled)
            assertFalse(VulkanTurbo.asyncOcclusionCullingEnabled)
            assertFalse(VulkanTurbo.scopedBarriersEnabled)

            VulkanTurbo.applyUpdatedConfig(
                VulkanTurboConfig(enabled = true, asyncOcclusionCulling = true, scopedBarriers = true),
            )
            assertTrue(VulkanTurbo.asyncOcclusionCullingEnabled)
            assertTrue(VulkanTurbo.scopedBarriersEnabled)
        } finally {
            VulkanTurbo.setConfigForTesting(original)
        }
    }
}
