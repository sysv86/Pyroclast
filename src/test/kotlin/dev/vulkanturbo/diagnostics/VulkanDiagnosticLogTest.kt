package dev.vulkanturbo.diagnostics

import dev.vulkanturbo.VulkanTurbo
import dev.vulkanturbo.api.shader.TurboShadersBridge
import dev.vulkanturbo.api.shader.TurboShadersExtension
import dev.vulkanturbo.diagnostics.VulkanDiagnosticLog.LogLevel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VulkanDiagnosticLogTest {
    @BeforeTest
    fun setUp() {
        VulkanDiagnosticLog.clear()
        TurboShadersBridge.register(null)
    }

    @AfterTest
    fun tearDown() {
        VulkanDiagnosticLog.clear()
        TurboShadersBridge.register(null)
    }

    @Test
    fun `records entries standalone and tracks unread error state`() {
        assertEquals(0, VulkanDiagnosticLog.entryCount())
        assertFalse(VulkanDiagnosticLog.hasUnreadErrors)
        assertNull(VulkanDiagnosticLog.latestError)

        VulkanDiagnosticLog.recordInfo("Startup", "Initialized cleanly")
        assertEquals(1, VulkanDiagnosticLog.entryCount())
        assertEquals(0, VulkanDiagnosticLog.errorCount())
        assertEquals(0, VulkanDiagnosticLog.warningCount())
        assertFalse(VulkanDiagnosticLog.hasUnreadErrors)

        VulkanDiagnosticLog.recordWarning("Config", "Fallback config applied")
        assertEquals(1, VulkanDiagnosticLog.warningCount())
        assertFalse(VulkanDiagnosticLog.hasUnreadErrors)

        val err = VulkanDiagnosticLog.recordError("Pipeline", "Pipeline compile failed", details = "code=-1", showToast = false)
        assertEquals(3, VulkanDiagnosticLog.entryCount())
        assertEquals(1, VulkanDiagnosticLog.errorCount())
        assertTrue(VulkanDiagnosticLog.hasUnreadErrors)
        assertEquals(err, VulkanDiagnosticLog.latestError)

        VulkanDiagnosticLog.markAllRead()
        assertFalse(VulkanDiagnosticLog.hasUnreadErrors)

        VulkanDiagnosticLog.clear()
        assertEquals(0, VulkanDiagnosticLog.entryCount())
        assertEquals(0, VulkanDiagnosticLog.errorCount())
        assertNull(VulkanDiagnosticLog.latestError)
    }

    @Test
    fun `ring buffer evicts oldest entries beyond capacity`() {
        for (i in 1..600) {
            VulkanDiagnosticLog.recordInfo("Stress", "Entry #$i")
        }
        assertEquals(512, VulkanDiagnosticLog.entryCount())
        val snap = VulkanDiagnosticLog.entriesSnapshot()
        assertEquals("Entry #89", snap.first().summary)
        assertEquals("Entry #600", snap.last().summary)
        assertTrue(snap.last().id > snap.first().id)
    }

    @Test
    fun `filters by modId and minimum LogLevel`() {
        VulkanDiagnosticLog.recordInfo("Core", "Pyro info", modId = VulkanTurbo.PUBLIC_NAME)
        VulkanDiagnosticLog.recordWarning("Core", "Pyro warn", modId = VulkanTurbo.PUBLIC_NAME)
        VulkanDiagnosticLog.recordError("Core", "Pyro err", modId = VulkanTurbo.PUBLIC_NAME, showToast = false)
        TurboShadersBridge.recordDiagnostic(
            level = 2,
            modId = "Hyaloclast",
            source = "NativeCore",
            summary = "Shader pack error",
            showToast = false,
        )
        TurboShadersBridge.recordDiagnostic(
            level = 1,
            modId = "Hyaloclast",
            source = "NativeCore",
            summary = "Shader pack warning",
            showToast = false,
        )

        assertEquals(5, VulkanDiagnosticLog.entriesSnapshot().size)
        assertEquals(3, VulkanDiagnosticLog.entriesSnapshot(modFilter = "Pyroclast").size)
        assertEquals(2, VulkanDiagnosticLog.entriesSnapshot(modFilter = "Hyaloclast").size)
        assertEquals(2, VulkanDiagnosticLog.entriesSnapshot(minLevel = LogLevel.ERROR).size)
        assertEquals(4, VulkanDiagnosticLog.entriesSnapshot(minLevel = LogLevel.WARN).size)
        assertEquals(1, VulkanDiagnosticLog.entriesSnapshot(modFilter = "Hyaloclast", minLevel = LogLevel.ERROR).size)
        assertEquals(1, VulkanDiagnosticLog.errorCount(modFilter = "Pyroclast"))
        assertEquals(1, VulkanDiagnosticLog.errorCount(modFilter = "Hyaloclast"))
        assertEquals(2, VulkanDiagnosticLog.errorCount())
    }

    @Test
    fun `formats entries with timestamp, level, modId, source, and indented details`() {
        val entry = VulkanDiagnosticLog.recordError(
            source = "ConfigStore",
            summary = "Invalid JSON",
            details = "line 4\nunexpected token",
            modId = "Pyroclast",
            showToast = false,
        )
        val formatted = VulkanDiagnosticLog.formatLogText(listOf(entry))
        assertTrue(formatted.contains("[ERROR] [Pyroclast/ConfigStore] Invalid JSON"))
        assertTrue(formatted.contains("\n    line 4\n    unexpected token"))
    }

    @Test
    fun `collects registered extension diagnostic section in unified crash report`() {
        val mockExt = object : TurboShadersExtension {
            override val engineId: String = "vulkanshaders"
            override val displayName: String = "Hyaloclast"
            override fun isShaderPackActive(): Boolean = false
            override fun buildCrashDiagnosticSection(): String =
                "-- Hyaloclast (VulkanShaders) Diagnostics --\nActive Pack: obsidian_meridian"
        }
        TurboShadersBridge.register(mockExt)
        VulkanDiagnosticLog.recordWarning("Test", "Unified crash test entry")

        val extSection = TurboShadersBridge.collectExtensionDiagnosticSections()
        assertNotNull(extSection)
        assertTrue(extSection.contains("Active Pack: obsidian_meridian"))

        val crashSection = VulkanDiagnosticLog.buildCrashDiagnosticSection()
        assertTrue(crashSection.contains("-- Pyroclast (VulkanTurbo) Diagnostics --"))
        assertTrue(crashSection.contains("Registered Extension: Hyaloclast"))
        assertTrue(crashSection.contains("-- Hyaloclast (VulkanShaders) Diagnostics --"))
        assertTrue(crashSection.contains("Active Pack: obsidian_meridian"))
        assertTrue(crashSection.contains("Unified crash test entry"))
    }
}
