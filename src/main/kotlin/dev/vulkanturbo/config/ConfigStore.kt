package dev.vulkanturbo.config

import dev.vulkanturbo.diagnostics.VulkanDiagnosticLog
import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * File IO for [VulkanTurboConfig]. Kept apart from the data class so the model stays free of
 * loader dependencies and can be unit tested.
 */
object ConfigStore {
    private const val FILE_NAME = "vulkanturbo.json"

    private val path: Path
        get() = FabricLoader.getInstance().configDir.resolve(FILE_NAME)

    fun load(): VulkanTurboConfig {
        val file = path
        if (!Files.exists(file)) {
            val defaults = VulkanTurboConfig()
            save(defaults)
            return defaults
        }

        return try {
            VulkanTurboConfig.fromJson(Files.readString(file))
        } catch (e: IOException) {
            VulkanDiagnosticLog.recordWarning(
                source = "ConfigStore",
                summary = "Could not read $file; using defaults",
                throwable = e,
            )
            VulkanTurboConfig()
        } catch (e: IllegalArgumentException) {
            VulkanDiagnosticLog.recordWarning(
                source = "ConfigStore",
                summary = "Invalid config at $file; using defaults",
                throwable = e,
            )
            VulkanTurboConfig()
        }
    }

    fun save(config: VulkanTurboConfig) {
        try {
            val file = path
            Files.createDirectories(file.parent)
            Files.writeString(file, VulkanTurboConfig.toJson(config))
        } catch (e: IOException) {
            VulkanDiagnosticLog.recordWarning(
                source = "ConfigStore",
                summary = "Could not write config",
                throwable = e,
            )
        }
    }
}
