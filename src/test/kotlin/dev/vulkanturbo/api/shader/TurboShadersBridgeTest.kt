package dev.vulkanturbo.api.shader

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.commands.RenderPass
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.Float.intBitsToFloat

class TurboShadersBridgeTest {
    @AfterEach
    fun tearDown() {
        TurboShadersBridge.register(null)
    }

    @Test
    fun `bridge defaults to inactive with zero flags`() {
        TurboShadersBridge.register(null)
        assertFalse(TurboShadersBridge.active)
        assertFalse(TurboShadersBridge.extendedBlockVerticesActive)
        assertFalse(TurboShadersBridge.entityRayTracingActive)
        assertFalse(TurboShadersBridge.skipWorldRasterizationActive)
        assertFalse(TurboShadersBridge.shouldSuppressEntityBlobShadows())
    }

    @Test
    fun `registering active TurboShadersExtension updates fast-path flags and refreshes state`() {
        var packEnabled = true
        var extendedVertices = true
        var entityRt = true
        var skipWorld = true
        val ext = object : TurboShadersExtension {
            override fun isShaderPackActive(): Boolean = packEnabled
            override fun usesExtendedBlockVertexFormat(): Boolean = extendedVertices
            override fun isEntityRayTracingActive(): Boolean = entityRt
            override fun shouldSkipWorldRasterization(): Boolean = skipWorld
        }

        TurboShadersBridge.register(ext)
        assertTrue(TurboShadersBridge.active)
        assertTrue(TurboShadersBridge.extendedBlockVerticesActive)
        assertTrue(TurboShadersBridge.entityRayTracingActive)
        assertTrue(TurboShadersBridge.skipWorldRasterizationActive)
        assertTrue(TurboShadersBridge.shouldSuppressEntityBlobShadows())

        entityRt = false
        skipWorld = false
        TurboShadersBridge.refreshState()
        assertFalse(TurboShadersBridge.entityRayTracingActive)
        assertFalse(TurboShadersBridge.skipWorldRasterizationActive)

        packEnabled = false
        TurboShadersBridge.refreshState()
        assertFalse(TurboShadersBridge.active)
        assertFalse(TurboShadersBridge.extendedBlockVerticesActive)
        assertFalse(TurboShadersBridge.entityRayTracingActive)
        assertFalse(TurboShadersBridge.skipWorldRasterizationActive)
        assertFalse(TurboShadersBridge.shouldSuppressEntityBlobShadows())
    }

    @Test
    fun `onEntityVerticesEmitted forwards streamed vertices without duplicate onEntityGeometrySubmitted call`() {
        var receivedPtr = 0L
        var receivedCount = 0
        var receivedStride = 0
        var geometrySubmitCalls = 0
        var submittedIndices = 0

        val ext = object : TurboShadersExtension {
            override fun isShaderPackActive(): Boolean = true
            override fun isEntityRayTracingActive(): Boolean = true
            override fun onEntityVerticesEmitted(basePtr: Long, vertexCount: Int, vertexStride: Int) {
                receivedPtr = basePtr
                receivedCount = vertexCount
                receivedStride = vertexStride
            }

            override fun onEntityGeometrySubmitted(
                entityId: Int,
                vertexBufferAddress: Long,
                vertexOffsetBytes: Long,
                vertexCount: Int,
                vertexStride: Int,
                indexBufferAddress: Long,
                indexCount: Int,
                transformMatrix: FloatArray,
                isBlockEntity: Boolean,
            ) {
                geometrySubmitCalls++
                submittedIndices = indexCount
            }
        }

        TurboShadersBridge.register(ext)
        TurboShadersBridge.onEntityVerticesEmitted(0x1000L, 24, 36)
        assertEquals(0x1000L, receivedPtr)
        assertEquals(24, receivedCount)
        assertEquals(36, receivedStride)
        assertEquals(0, geometrySubmitCalls)

        TurboShadersBridge.onEntityGeometrySubmitted(
            entityId = 7,
            vertexBufferAddress = 0x2000L,
            vertexOffsetBytes = 64L,
            vertexCount = 24,
            vertexStride = 36,
            indexBufferAddress = 0L,
            indexCount = 36,
            transformMatrix = FloatArray(16),
            isBlockEntity = false,
        )
        assertEquals(1, geometrySubmitCalls)
        assertEquals(36, submittedIndices)
    }

    @Test
    fun `swapchain HDR and FrameGen acquire-present hooks forward cleanly`() {
        var configuredSwapchain = 0L
        var presentedImage = -1

        val ext = object : TurboShadersExtension {
            override fun isShaderPackActive(): Boolean = true
            override fun onConfigureSwapchain(
                surfaceHandle: Long,
                requestedWidth: Int,
                requestedHeight: Int,
                defaultFormat: Int,
                defaultColorSpace: Int,
                defaultUsage: Int,
                defaultPresentMode: Int,
            ): SwapchainConfigOverride = SwapchainConfigOverride(64, 1000104008, 8, defaultPresentMode)

            override fun onSwapchainCreated(
                swapchainHandle: Long,
                images: LongArray,
                imageFormat: Int,
                colorSpace: Int,
                width: Int,
                height: Int,
            ) {
                configuredSwapchain = swapchainHandle
            }

            override fun interceptAcquireNextImage(
                swapchainHandle: Long,
                timeoutNs: Long,
                semaphore: Long,
                fence: Long,
            ): Int = 2

            override fun interceptQueuePresent(
                queueHandle: Long,
                swapchainHandle: Long,
                imageIndex: Int,
                waitSemaphore: Long,
            ): Boolean {
                presentedImage = imageIndex
                return true
            }

            override fun getCustomDescriptorSetLayouts(vkDevice: Long): LongArray = longArrayOf(101L, 102L)
        }

        TurboShadersBridge.register(ext)
        val override = TurboShadersBridge.onConfigureSwapchain(1L, 1920, 1080, 44, 0, 2, 1)
        assertNotNull(override)
        assertEquals(64, override!!.imageFormat)
        TurboShadersBridge.onSwapchainCreated(999L, longArrayOf(1L, 2L, 3L), 64, 1000104008, 1920, 1080)
        assertEquals(999L, configuredSwapchain)
        assertEquals(2, TurboShadersBridge.interceptAcquireNextImage(999L, 5000000000L, 10L, 0L))
        assertTrue(TurboShadersBridge.interceptQueuePresent(55L, 999L, 2, 20L))
        assertEquals(2, presentedImage)
        assertArrayEquals(longArrayOf(101L, 102L), TurboShadersBridge.getCustomDescriptorSetLayouts(77L))
    }

    @Test
    fun `computePackedMidUv packs average U and V in little-endian 64-bit word`() {
        val packed = TurboShadersBridge.computePackedMidUv(
            0.25f, 0.50f,
            0.75f, 0.50f,
            0.75f, 1.00f,
            0.25f, 1.00f,
        )
        val midU = intBitsToFloat(packed.toInt())
        val midV = intBitsToFloat((packed ushr 32).toInt())
        assertEquals(0.50f, midU, 1e-6f)
        assertEquals(0.75f, midV, 1e-6f)
    }

    @Test
    fun `computePackedTangent computes normalized SNORM8x4 tangent and handedness`() {
        val packed = TurboShadersBridge.computePackedTangent(
            0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            1.0f, 0.0f, 0.0f, 1.0f, 0.0f,
            1.0f, 1.0f, 0.0f, 1.0f, 1.0f,
        )
        val tx = (packed and 0xFF).toByte().toInt()
        val ty = ((packed ushr 8) and 0xFF).toByte().toInt()
        val tz = ((packed ushr 16) and 0xFF).toByte().toInt()
        val tw = ((packed ushr 24) and 0xFF).toByte().toInt()

        assertEquals(127, tx)
        assertEquals(0, ty)
        assertEquals(0, tz)
        assertEquals(127, tw)
    }

    @Test
    fun `computePackedFaceNormal packs SNORM8x3 normal and 7-bit material mask`() {
        val packed = TurboShadersBridge.computePackedFaceNormal(
            0.0f, 1.0f, 0.0f,
            0.0f, 1.0f, 1.0f,
            1.0f, 1.0f, 1.0f,
            0x05,
        )
        val nx = (packed and 0xFF).toByte().toInt()
        val ny = ((packed ushr 8) and 0xFF).toByte().toInt()
        val nz = ((packed ushr 16) and 0xFF).toByte().toInt()
        val mask = (packed ushr 24) and 0x7F

        assertEquals(0, nx)
        assertEquals(127, ny)
        assertEquals(0, nz)
        assertEquals(5, mask)
    }

    @Test
    fun `detailed slice upload callback forwards legacy fallback when not overridden`() {
        var legacyCalled = false
        val ext = object : TurboShadersExtension {
            override fun isShaderPackActive(): Boolean = true
            override fun onSectionSliceUploaded(
                allocationKey: Any,
                layerOrdinal: Int,
                gpuBuffer: GpuBuffer,
                byteOffset: Long,
                byteSize: Long,
            ) {
                legacyCalled = (layerOrdinal == 1 && byteOffset == 280L && byteSize == 1600L)
            }
        }
        TurboShadersBridge.register(ext)
        assertTrue(TurboShadersBridge.active)
        assertFalse(legacyCalled)
    }

    @Test
    fun `inactive bridge returns safe defaults for slice and surface blit hooks`() {
        TurboShadersBridge.register(null)
        TurboShadersBridge.onSectionSliceFreed(Any(), 0)
        assertFalse(TurboShadersBridge.active)
    }

    @Test
    fun `custom set pipeline layout registry tracks firstSet and removes it on destroy`() {
        val handle = 0xABCDEF01L
        assertFalse(TurboShadersBridge.isPipelineLayoutRegistered(handle))
        assertEquals(0, TurboShadersBridge.getFirstSetForPipelineLayout(handle))

        TurboShadersBridge.registerPipelineLayoutWithCustomSets(handle, 2)
        assertTrue(TurboShadersBridge.isPipelineLayoutRegistered(handle))
        assertEquals(2, TurboShadersBridge.getFirstSetForPipelineLayout(handle))

        // Re-registration overwrites the offset (the same handle can be recreated after destroy).
        TurboShadersBridge.registerPipelineLayoutWithCustomSets(handle, 3)
        assertEquals(3, TurboShadersBridge.getFirstSetForPipelineLayout(handle))

        TurboShadersBridge.unregisterPipelineLayoutWithCustomSets(handle)
        assertFalse(TurboShadersBridge.isPipelineLayoutRegistered(handle))
        assertEquals(0, TurboShadersBridge.getFirstSetForPipelineLayout(handle))
    }

    @Test
    fun `registering a null pipeline layout handle is a no-op`() {
        TurboShadersBridge.registerPipelineLayoutWithCustomSets(0L, 5)
        assertFalse(TurboShadersBridge.isPipelineLayoutRegistered(0L))
        assertEquals(0, TurboShadersBridge.getFirstSetForPipelineLayout(0L))
    }

    @Test
    fun `onBindCustomDescriptorSets forwards the exact registered firstSet`() {
        val handle = 0x1234L
        var seenFirstSet = -1
        var seenPassKind = -1
        val ext = object : TurboShadersExtension {
            override fun isShaderPackActive(): Boolean = true
            override fun onBindCustomDescriptorSetsWithFirstSet(
                vkCommandBuffer: Long,
                vkPipelineLayout: Long,
                pipelineBindPoint: Int,
                passKind: Int,
                firstSet: Int,
            ) {
                seenFirstSet = firstSet
                seenPassKind = passKind
            }
        }
        TurboShadersBridge.register(ext)
        TurboShadersBridge.registerPipelineLayoutWithCustomSets(handle, 4)
        try {
            TurboShadersBridge.onBindCustomDescriptorSets(10L, handle, 0, TurboShadersBridge.PASS_KIND_GENERAL)
            assertEquals(4, seenFirstSet)
            assertEquals(TurboShadersBridge.PASS_KIND_GENERAL, seenPassKind)
        } finally {
            TurboShadersBridge.unregisterPipelineLayoutWithCustomSets(handle)
        }
    }

    @Test
    fun `legacy onBindCustomDescriptorSets override receives the registered firstSet in its last slot`() {
        val handle = 0x5678L
        var legacyCalls = 0
        var receivedLastSlot = -1
        val ext = object : TurboShadersExtension {
            override fun isShaderPackActive(): Boolean = true
            override fun onBindCustomDescriptorSets(
                vkCommandBuffer: Long,
                vkPipelineLayout: Long,
                pipelineBindPoint: Int,
                passKind: Int,
            ) {
                legacyCalls++
                receivedLastSlot = passKind
            }
        }
        TurboShadersBridge.register(ext)
        TurboShadersBridge.registerPipelineLayoutWithCustomSets(handle, 1)
        try {
            TurboShadersBridge.onBindCustomDescriptorSets(10L, handle, 0, TurboShadersBridge.PASS_KIND_GENERAL)
            assertEquals(1, legacyCalls)
            assertEquals(1, receivedLastSlot)
        } finally {
            TurboShadersBridge.unregisterPipelineLayoutWithCustomSets(handle)
        }
    }

    private fun fakeRenderPass(): RenderPass = java.lang.reflect.Proxy.newProxyInstance(
        RenderPass::class.java.classLoader,
        arrayOf(RenderPass::class.java),
    ) { _, method, _ ->
        if (method.name == "toString") "fakeWorldRenderPass" else null
    } as RenderPass

    @Test
    fun `onBeginNonTerrainWorldPass forwards the live world RenderPass to the active extension`() {
        val renderPass = fakeRenderPass()
        var seen: RenderPass? = null
        val ext = object : TurboShadersExtension {
            override fun isShaderPackActive(): Boolean = true
            override fun onBeginNonTerrainWorldPass(renderPass: RenderPass) {
                seen = renderPass
            }
        }
        TurboShadersBridge.register(ext)
        TurboShadersBridge.onBeginNonTerrainWorldPass(renderPass)
        assertTrue(seen === renderPass)
    }

    @Test
    fun `onBeginNonTerrainWorldPass is a no-op when the bridge is inactive`() {
        var calls = 0
        val ext = object : TurboShadersExtension {
            override fun isShaderPackActive(): Boolean = false
            override fun onBeginNonTerrainWorldPass(renderPass: RenderPass) {
                calls++
            }
        }
        TurboShadersBridge.register(ext)
        TurboShadersBridge.onBeginNonTerrainWorldPass(fakeRenderPass())
        assertFalse(TurboShadersBridge.active)
        assertEquals(0, calls)
    }

    @Test
    fun `cached custom descriptor sets update and clear on pack deactivation or unregister`() {
        var packEnabled = true
        val ext = object : TurboShadersExtension {
            override fun isShaderPackActive(): Boolean = packEnabled
        }
        TurboShadersBridge.register(ext)
        assertEquals(0L, TurboShadersBridge.cachedBindlessDescriptorSet)
        assertEquals(0L, TurboShadersBridge.cachedPackDescriptorSet)

        TurboShadersBridge.updateCachedCustomDescriptorSets(0x1111L, 0x2222L)
        assertEquals(0x1111L, TurboShadersBridge.cachedBindlessDescriptorSet)
        assertEquals(0x2222L, TurboShadersBridge.cachedPackDescriptorSet)

        packEnabled = false
        TurboShadersBridge.refreshState()
        assertEquals(0L, TurboShadersBridge.cachedBindlessDescriptorSet)
        assertEquals(0L, TurboShadersBridge.cachedPackDescriptorSet)

        packEnabled = true
        TurboShadersBridge.refreshState()
        TurboShadersBridge.updateCachedCustomDescriptorSets(0x3333L, 0x4444L)
        TurboShadersBridge.register(null)
        assertEquals(0L, TurboShadersBridge.cachedBindlessDescriptorSet)
        assertEquals(0L, TurboShadersBridge.cachedPackDescriptorSet)
    }
}
