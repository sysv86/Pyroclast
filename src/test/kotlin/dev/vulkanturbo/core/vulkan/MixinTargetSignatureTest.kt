package dev.vulkanturbo.core

import com.mojang.renderpearl.backend.vulkan.VulkanBackend
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder
import com.mojang.renderpearl.backend.vulkan.VulkanDevice
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView
import com.mojang.renderpearl.backend.vulkan.VulkanInstance
import com.mojang.renderpearl.backend.vulkan.VulkanQueue
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.components.OptionsList
import net.minecraft.client.gui.screens.options.OptionsSubScreen
import net.minecraft.client.gui.screens.options.VideoSettingsScreen
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.SectionOcclusionGraph
import net.minecraft.client.renderer.ShaderManager
import net.minecraft.client.renderer.block.BlockModelLighter
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins and verifies all critical RenderPearl (`com.mojang.renderpearl.backend.vulkan.*`) and
 * Minecraft 26.3+ client renderer target classes and method signatures used by `VulkanTurbo`
 * and `VulkanShaders` mixins.
 */
class MixinTargetSignatureTest {
    @Test
    fun `renderpearl vulkan backend classes and mixin target methods exist with expected signatures`() {
        assertNotNull(VulkanInstance::class.java)
        assertNotNull(VulkanBackend::class.java)
        assertNotNull(VulkanDevice::class.java.getDeclaredMethod("close"))
        assertNotNull(VulkanGpuSurface::class.java.getDeclaredMethod("acquireNextTexture"))
        assertNotNull(VulkanGpuSurface::class.java.getDeclaredMethod("present"))
        assertTrue(VulkanGpuSurface::class.java.declaredMethods.any { it.name == "configure" })
        assertTrue(VulkanGpuSurface::class.java.declaredMethods.any { it.name == "blitFromTexture" })

        assertNotNull(VulkanCommandEncoder::class.java.getDeclaredMethod("submit"))
        assertNotNull(VulkanCommandEncoder::class.java.getDeclaredMethod("submitRenderPass"))
        assertTrue(VulkanCommandEncoder::class.java.declaredMethods.any { it.name == "createRenderPass" })

        assertTrue(VulkanRenderPass::class.java.declaredMethods.any { it.name == "setPipeline" })
        assertTrue(VulkanRenderPass::class.java.declaredMethods.any { it.name == "pushDescriptors" })
        assertTrue(VulkanRenderPass::class.java.declaredMethods.any { it.name == "multiDrawIndexed" })

        assertTrue(VulkanRenderPipeline::class.java.declaredMethods.any { it.name == "compile" })
        assertNotNull(VulkanGpuTexture::class.java.getDeclaredMethod("vkImage"))
        assertNotNull(VulkanGpuTextureView::class.java.getDeclaredMethod("vkImageView"))
        assertNotNull(VulkanGpuBuffer.Direct::class.java.getDeclaredMethod("destroy"))
        assertNotNull(VulkanGpuBuffer.Direct::class.java.getDeclaredMethod("close"))
        assertNotNull(VulkanQueue::class.java.getDeclaredMethod("waitIdle"))
        assertNotNull(VulkanQueue.Submission::class.java.getDeclaredMethod("close"))
    }

    @Test
    fun `minecraft 26_3 renderer classes and mixin target methods exist`() {
        assertNotNull(GameRenderer::class.java.getDeclaredMethod("render"))
        assertNotNull(GameRenderer::class.java.getDeclaredMethod("renderLevel"))
        assertTrue(LevelRenderer::class.java.declaredMethods.any { it.name == "prepareChunkRendersIndirect" })
        assertTrue(LevelRenderer::class.java.declaredMethods.any { it.name == "executeSolid" })
        assertTrue(LevelRenderer::class.java.declaredMethods.any { it.name == "addSkyPass" })
        assertNotNull(RenderPipelines::class.java)
        assertTrue(ShaderManager.Configs::class.java.declaredMethods.any { it.name == "getShader" })

        assertNotNull(SectionOcclusionGraph::class.java.getDeclaredMethod("update", net.minecraft.client.renderer.state.level.CameraRenderState::class.java, Int::class.javaPrimitiveType, net.minecraft.client.renderer.state.level.ChunkLoadingRenderState::class.java))
        assertTrue(SectionOcclusionGraph::class.java.declaredMethods.any { it.name == "runUpdates" })
        assertTrue(SectionOcclusionGraph::class.java.declaredMethods.any { it.name == "scheduleFullUpdate" })
        assertTrue(SectionOcclusionGraph::class.java.declaredMethods.any { it.name == "runPartialUpdate" })
        assertNotNull(SectionOcclusionReflection)

        assertNotNull(BlockModelLighter::class.java.getDeclaredField("scratchPos"))
        assertTrue(BlockModelLighter::class.java.declaredMethods.any { it.name == "prepareQuadAmbientOcclusion" })

        assertTrue(VideoSettingsScreen::class.java.declaredMethods.any { it.name == "addOptions" })
        assertNotNull(OptionsSubScreen::class.java)
        assertTrue(OptionsList::class.java.declaredMethods.any { it.name == "addBig" })
        assertTrue(OptionInstance::class.java.declaredMethods.any { it.name == "createBoolean" })
    }
}
