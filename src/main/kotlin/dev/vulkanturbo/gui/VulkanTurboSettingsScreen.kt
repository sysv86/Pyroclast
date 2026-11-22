package dev.vulkanturbo.gui

import dev.vulkanturbo.VulkanTurbo
import dev.vulkanturbo.config.ConfigStore
import dev.vulkanturbo.config.VulkanTurboConfig
import dev.vulkanturbo.diagnostics.VulkanDiagnosticLog
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.OptionInstance
import net.minecraft.client.Options
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.options.OptionsSubScreen
import net.minecraft.network.chat.Component

/**
 * In-game configuration screen for all [VulkanTurboConfig] optimization toggles, accessible from
 * Minecraft's Video Settings screen. Changes are persisted immediately via [ConfigStore.save] and
 * applied to runtime flags via [VulkanTurbo.refreshRuntimeFlags].
 */
class VulkanTurboSettingsScreen(
    lastScreen: Screen,
    options: Options,
) : OptionsSubScreen(lastScreen, options, Component.translatable("vulkanturbo.options.title")) {

    private var workingConfig: VulkanTurboConfig = VulkanTurbo.config

    override fun init() {
        layout.removeChildren()
        super.init()
    }

    override fun addOptions() {
        val list = this.list ?: return

        list.addHeader(Component.translatable("vulkanturbo.options.section.general"))
        list.addBig(
            boolOption("enabled", workingConfig.enabled) { cfg, v ->
                cfg.copy(enabled = v)
            },
        )

        list.addHeader(Component.translatable("vulkanturbo.options.section.chunk"))
        list.addSmall(
            boolOption("asyncOcclusionCulling", workingConfig.asyncOcclusionCulling) { cfg, v ->
                cfg.copy(asyncOcclusionCulling = v)
            },
            boolOption("fastChunkPrep", workingConfig.fastChunkPrep) { cfg, v ->
                cfg.copy(fastChunkPrep = v)
            },
            boolOption("mappedChunkSections", workingConfig.mappedChunkSections) { cfg, v ->
                cfg.copy(mappedChunkSections = v)
            },
            boolOption("cacheVoxelBounds", workingConfig.cacheVoxelBounds) { cfg, v ->
                cfg.copy(cacheVoxelBounds = v)
            },
        )

        list.addHeader(Component.translatable("vulkanturbo.options.section.rendering"))
        list.addSmall(
            boolOption("stateDedup", workingConfig.stateDedup) { cfg, v ->
                cfg.copy(stateDedup = v)
            },
            boolOption("normalCache", workingConfig.normalCache) { cfg, v ->
                cfg.copy(normalCache = v)
            },
            boolOption("fastEntityRender", workingConfig.fastEntityRender) { cfg, v ->
                cfg.copy(fastEntityRender = v)
            },
        )

        list.addHeader(Component.translatable("vulkanturbo.options.section.vulkan"))
        val vendor = VulkanTurbo.detectedVendor
        list.addSmall(
            boolOption("scopedBarriers", workingConfig.scopedBarriers) { cfg, v ->
                cfg.copy(scopedBarriers = v)
            },
            boolOption("bufferViewCache", workingConfig.bufferViewCache) { cfg, v ->
                cfg.copy(bufferViewCache = v)
            },
            boolOption("autoVendorOptimizations", workingConfig.autoVendorOptimizations) { cfg, v ->
                cfg.copy(autoVendorOptimizations = v)
            },
            boolOption("pipelineCache", workingConfig.resolvePipelineCache(vendor)) { cfg, v ->
                cfg.copy(pipelineCache = v, explicitPipelineCache = v)
            },
            boolOption("optimalImageLayouts", workingConfig.resolveOptimalImageLayouts(vendor)) { cfg, v ->
                cfg.copy(optimalImageLayouts = v, explicitOptimalImageLayouts = v)
            },
        )

        list.addHeader(Component.translatable("vulkanturbo.options.section.diagnostics"))
        list.addSmall(
            boolOption("verboseLogging", workingConfig.verboseLogging) { cfg, v ->
                cfg.copy(verboseLogging = v)
            },
            boolOption("benchmark", workingConfig.benchmark) { cfg, v ->
                cfg.copy(benchmark = v)
            },
        )

        val errCount = VulkanDiagnosticLog.errorCount()
        val logBtnLabel = if (errCount > 0) {
            Component.translatable("vulkanturbo.options.viewLog.errors", errCount)
                .withStyle(ChatFormatting.RED)
        } else {
            Component.translatable("vulkanturbo.options.viewLog")
        }
        list.addBig(
            Button.builder(logBtnLabel) {
                minecraft.gui.setScreen(VulkanErrorLogScreen(this, options))
            }.width(Button.BIG_WIDTH).build(),
        )
    }

    override fun removed() {
        super.removed()
        persistAndRefresh()
    }

    private fun boolOption(
        key: String,
        initial: Boolean,
        updater: (VulkanTurboConfig, Boolean) -> VulkanTurboConfig,
    ): OptionInstance<Boolean> =
        OptionInstance.createBoolean(
            "vulkanturbo.options.$key",
            OptionInstance.cachedConstantTooltip(Component.translatable("vulkanturbo.options.$key.tooltip")),
            initial,
        ) { value ->
            workingConfig = updater(workingConfig, value)
            persistAndRefresh()
        }

    private fun persistAndRefresh() {
        ConfigStore.save(workingConfig)
        VulkanTurbo.refreshRuntimeFlags()
    }

    companion object {
        @JvmStatic
        fun createSettingsButton(parent: Screen, options: Options): Button =
            Button.builder(Component.translatable("vulkanturbo.options.button")) {
                Minecraft.getInstance().gui.setScreen(VulkanTurboSettingsScreen(parent, options))
            }.width(Button.BIG_WIDTH).build()
    }
}
