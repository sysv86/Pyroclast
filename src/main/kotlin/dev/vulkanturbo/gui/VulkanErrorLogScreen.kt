package dev.vulkanturbo.gui

import com.mojang.blaze3d.Blaze3D
import dev.vulkanturbo.VulkanTurbo
import dev.vulkanturbo.api.shader.TurboShadersBridge
import dev.vulkanturbo.diagnostics.VulkanDiagnosticLog
import dev.vulkanturbo.diagnostics.VulkanDiagnosticLog.LogLevel
import net.minecraft.client.Minecraft
import net.minecraft.client.Options
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.options.OptionsSubScreen
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import java.nio.file.Files

/**
 * Vanilla-styled `OptionsSubScreen` displaying the unified Pyroclast (and optional extension such
 * as Hyaloclast) session log with mod/level filters, color-coded rows, and clipboard/clear/folder
 * footer actions.
 */
class VulkanErrorLogScreen(
    private val returnScreen: Screen?,
    options: Options,
) : OptionsSubScreen(returnScreen ?: TitleScreen(), options, buildScreenTitle()) {

    private enum class LevelFilter(val minLevel: LogLevel?) {
        ALL(null),
        ERRORS(LogLevel.ERROR),
        WARNINGS_PLUS(LogLevel.WARN),
    }

    private var modFilterIndex: Int = 0
    private var levelFilter: LevelFilter = LevelFilter.ALL
    private var copyButton: Button? = null
    private var copiedResetTicks: Int = 0

    private fun currentModFilter(): String? {
        val ext = TurboShadersBridge.extension ?: return null
        return when (modFilterIndex) {
            1 -> VulkanTurbo.PUBLIC_NAME
            2 -> ext.displayName
            else -> null
        }
    }

    private fun modFilterLabel(): Component {
        val ext = TurboShadersBridge.extension
        val value = when {
            ext == null || modFilterIndex == 0 -> Component.translatable("vulkanturbo.screen.log.filter.all")
            modFilterIndex == 1 -> Component.literal(VulkanTurbo.PUBLIC_NAME)
            else -> Component.literal(ext.displayName)
        }
        return Component.translatable("vulkanturbo.screen.log.filter.mod", value)
    }

    private fun levelFilterLabel(): Component {
        val value = when (levelFilter) {
            LevelFilter.ALL -> Component.translatable("vulkanturbo.screen.log.filter.all")
            LevelFilter.ERRORS -> Component.translatable("vulkanturbo.screen.log.filter.errors")
            LevelFilter.WARNINGS_PLUS -> Component.translatable("vulkanturbo.screen.log.filter.warnings")
        }
        return Component.translatable("vulkanturbo.screen.log.filter.level", value)
    }

    override fun init() {
        VulkanDiagnosticLog.markAllRead()
        layout.removeChildren()
        super.init()
    }

    override fun addOptions() {
        val list = this.list ?: return
        val ext = TurboShadersBridge.extension

        val levelBtn = Button.builder(levelFilterLabel()) {
            levelFilter = when (levelFilter) {
                LevelFilter.ALL -> LevelFilter.ERRORS
                LevelFilter.ERRORS -> LevelFilter.WARNINGS_PLUS
                LevelFilter.WARNINGS_PLUS -> LevelFilter.ALL
            }
            refreshLogList()
        }.width(if (ext != null) 150 else LOG_WIDGET_WIDTH).build()

        if (ext != null) {
            val modBtn = Button.builder(modFilterLabel()) {
                modFilterIndex = (modFilterIndex + 1) % 3
                refreshLogList()
            }.width(150).build()
            list.addSmall(modBtn, levelBtn)
        } else {
            modFilterIndex = 0
            list.addBig(levelBtn)
        }

        val entries = VulkanDiagnosticLog.entriesSnapshot(currentModFilter(), levelFilter.minLevel)
        val maxTextWidth = LOG_WIDGET_WIDTH - 8

        if (entries.isEmpty()) {
            val emptyKey = if (VulkanDiagnosticLog.entryCount() == 0) {
                "vulkanturbo.screen.log.empty"
            } else {
                "vulkanturbo.screen.log.empty_filtered"
            }
            val wrapped = font.split(Component.translatable(emptyKey), maxTextWidth)
            var rowIndex = 0
            for (segment in wrapped) {
                list.addBig(LogLineWidget(segment, COLOR_DETAIL, rowIndex++))
            }
            return
        }

        val fullLog = VulkanDiagnosticLog.formatLogText(entries)
        var currentColor = COLOR_NORMAL
        var rowIndex = 0
        for (rawLine in fullLog.lineSequence()) {
            val lineColor = classifyLineColor(rawLine, currentColor)
            currentColor = lineColor

            val wrapped: List<FormattedCharSequence> = if (rawLine.isEmpty()) {
                listOf(FormattedCharSequence.EMPTY)
            } else {
                font.split(Component.literal(rawLine), maxTextWidth)
            }
            for (segment in wrapped) {
                list.addBig(LogLineWidget(segment, lineColor, rowIndex++))
            }
        }
    }

    override fun addFooter() {
        val footer = LinearLayout.horizontal().spacing(4)

        val copyBtn = Button.builder(Component.translatable("vulkanturbo.screen.log.copy")) {
            val filtered = VulkanDiagnosticLog.entriesSnapshot(currentModFilter(), levelFilter.minLevel)
            val text = VulkanDiagnosticLog.formatLogText(filtered)
            runCatching {
                minecraft.keyboardHandler.clipboard = text
            }
            copyButton?.message = Component.translatable("vulkanturbo.screen.log.copied")
            copiedResetTicks = 40
        }.width(78).build()
        copyButton = copyBtn
        footer.addChild(copyBtn)

        footer.addChild(
            Button.builder(Component.translatable("vulkanturbo.screen.log.open_folder")) {
                runCatching {
                    val dir = VulkanDiagnosticLog.logsDir()
                    Files.createDirectories(dir)
                    Blaze3D.openPath(dir)
                }
            }.width(78).build(),
        )

        footer.addChild(
            Button.builder(Component.translatable("vulkanturbo.screen.log.clear")) {
                VulkanDiagnosticLog.clear()
                refreshLogList()
            }.width(70).build(),
        )

        footer.addChild(
            Button.builder(Component.translatable("vulkanturbo.screen.log.close")) {
                onClose()
            }.width(72).build(),
        )

        layout.addToFooter(footer)
    }

    override fun onClose() {
        minecraft.gui.setScreen(returnScreen)
    }

    override fun tick() {
        super.tick()
        if (copiedResetTicks > 0) {
            copiedResetTicks--
            if (copiedResetTicks == 0) {
                copyButton?.message = Component.translatable("vulkanturbo.screen.log.copy")
            }
        }
    }

    private fun refreshLogList() {
        rebuildWidgets()
        list?.setScrollAmount(0.0)
    }

    private fun classifyLineColor(rawLine: String, previousColor: Int): Int = when {
        rawLine.contains("[ShaderCompile]") || rawLine.contains("[DeviceLost]") -> COLOR_DIAGNOSTIC
        rawLine.contains("[ERROR]") -> COLOR_ERROR
        rawLine.contains("[WARN]") -> COLOR_WARN
        rawLine.contains("[INFO]") -> COLOR_INFO
        rawLine.startsWith("    ") -> if (previousColor == COLOR_ERROR || previousColor == COLOR_DIAGNOSTIC) COLOR_ERROR_DETAIL else COLOR_DETAIL
        rawLine.startsWith("===") || rawLine.startsWith("--") -> COLOR_HEADER
        else -> COLOR_NORMAL
    }

    private class LogLineWidget(
        private val line: FormattedCharSequence,
        private val textColor: Int,
        private val rowIndex: Int,
    ) : AbstractWidget(0, 0, LOG_WIDGET_WIDTH, LOG_LINE_HEIGHT, Component.empty()) {
        init {
            this.active = false
        }

        override fun extractWidgetRenderState(
            guiGraphics: GuiGraphicsExtractor,
            mouseX: Int,
            mouseY: Int,
            partialTick: Float,
        ) {
            val bg = if ((rowIndex and 1) == 0) COLOR_ROW_EVEN else COLOR_ROW_ODD
            guiGraphics.fill(this.x, this.y, this.x + this.width, this.y + this.height, bg)
            val font = Minecraft.getInstance().font
            val textY = this.y + ((this.height - font.lineHeight) / 2).coerceAtLeast(1)
            guiGraphics.text(font, line, this.x + 4, textY, textColor)
        }

        override fun updateWidgetNarration(output: NarrationElementOutput) {
            defaultButtonNarrationText(output)
        }
    }

    companion object {
        private const val LOG_WIDGET_WIDTH: Int = 310
        private const val LOG_LINE_HEIGHT: Int = 13
        private const val COLOR_DIAGNOSTIC: Int = 0xFFFF8855.toInt()
        private const val COLOR_ERROR: Int = 0xFFFF5555.toInt()
        private const val COLOR_ERROR_DETAIL: Int = 0xFFE8A0A0.toInt()
        private const val COLOR_WARN: Int = 0xFFFFFF55.toInt()
        private const val COLOR_INFO: Int = 0xFF9EE6FF.toInt()
        private const val COLOR_HEADER: Int = 0xFFCCCCCC.toInt()
        private const val COLOR_NORMAL: Int = 0xFFE6E6E6.toInt()
        private const val COLOR_DETAIL: Int = 0xFFB8B8B8.toInt()
        private const val COLOR_ROW_EVEN: Int = 0x55101014
        private const val COLOR_ROW_ODD: Int = 0x3318181E

        private fun buildScreenTitle(): Component {
            val ext = TurboShadersBridge.extension
            return if (ext != null) {
                Component.translatable("vulkanturbo.screen.log.title_unified", ext.displayName)
            } else {
                Component.translatable("vulkanturbo.screen.log.title")
            }
        }
    }
}
