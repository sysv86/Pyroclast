package dev.vulkanturbo.diagnostics

import dev.vulkanturbo.VulkanTurbo
import dev.vulkanturbo.api.shader.TurboShadersBridge
import dev.vulkanturbo.core.TerrainRegionPipelines
import dev.vulkanturbo.gui.DiagnosticNotices
import dev.vulkanturbo.platform.PlatformInfo
import net.fabricmc.loader.api.FabricLoader
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure-Kotlin unified diagnostic log ring buffer, persistent session log
 * (`<gameDir>/pyroclast/logs/latest.log`), and crash-log dumper
 * (`<gameDir>/pyroclast/crash-logs/crash-YYYY-MM-DD_HH-mm-ss.log`) for Pyroclast (VulkanTurbo)
 * and any registered [dev.vulkanturbo.api.shader.TurboShadersExtension] (such as Hyaloclast).
 */
object VulkanDiagnosticLog {
    private const val MAX_ENTRIES: Int = 512
    private const val CRASH_SECTION_TAIL_ENTRIES: Int = 50

    private val entryTimestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val fullTimestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val crashFileTimestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    enum class LogLevel {
        INFO,
        WARN,
        ERROR,
    }

    data class LogEntry(
        val id: Long,
        val timestamp: String,
        val level: LogLevel,
        val modId: String,
        val source: String,
        val summary: String,
        val details: String? = null,
    )

    private val lock = Any()
    private val entries = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private var nextEntryId: Long = 1L
    private var sessionLogInitialized: Boolean = false
    private val hooksInstalled = AtomicBoolean(false)
    private val cleanShutdown = AtomicBoolean(false)
    private val crashDumpWritten = AtomicBoolean(false)

    @Volatile
    var hasUnreadErrors: Boolean = false
        private set

    @Volatile
    var latestError: LogEntry? = null
        private set

    @Volatile
    var lastCrashLogPath: Path? = null
        private set

    @JvmStatic
    fun resolveGameDir(): Path =
        runCatching { FabricLoader.getInstance().gameDir }
            .recoverCatching {
                val fmlPaths = Class.forName("net.neoforged.fml.loading.FMLPaths")
                val gameDirEnum = fmlPaths.getField("GAMEDIR").get(null)
                gameDirEnum.javaClass.getMethod("get").invoke(gameDirEnum) as Path
            }
            .getOrElse { Path.of(System.getProperty("user.dir") ?: ".") }

    @JvmStatic
    fun logsDir(): Path =
        resolveGameDir().resolve("pyroclast").resolve("logs")

    @JvmStatic
    fun crashLogsDir(): Path =
        resolveGameDir().resolve("pyroclast").resolve("crash-logs")

    @JvmStatic
    fun latestLogFile(): Path =
        logsDir().resolve("latest.log")

    @JvmStatic
    fun markAllRead() {
        hasUnreadErrors = false
    }

    @JvmStatic
    fun markCleanShutdown() {
        cleanShutdown.set(true)
    }

    @JvmStatic
    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
        hasUnreadErrors = false
        latestError = null
    }

    @JvmStatic
    @JvmOverloads
    fun entriesSnapshot(modFilter: String? = null, minLevel: LogLevel? = null): List<LogEntry> =
        synchronized(lock) {
            if (modFilter == null && minLevel == null) {
                entries.toList()
            } else {
                entries.filter { entry ->
                    val modOk = modFilter == null || entry.modId.equals(modFilter, ignoreCase = true)
                    val levelOk = minLevel == null || entry.level.ordinal >= minLevel.ordinal
                    modOk && levelOk
                }
            }
        }

    @JvmStatic
    @JvmOverloads
    fun errorCount(modFilter: String? = null): Int = synchronized(lock) {
        entries.count { entry ->
            entry.level == LogLevel.ERROR &&
                (modFilter == null || entry.modId.equals(modFilter, ignoreCase = true))
        }
    }

    @JvmStatic
    @JvmOverloads
    fun warningCount(modFilter: String? = null): Int = synchronized(lock) {
        entries.count { entry ->
            entry.level == LogLevel.WARN &&
                (modFilter == null || entry.modId.equals(modFilter, ignoreCase = true))
        }
    }

    @JvmStatic
    fun entryCount(): Int = synchronized(lock) {
        entries.size
    }

    @JvmStatic
    @JvmOverloads
    fun record(
        level: LogLevel,
        source: String,
        summary: String,
        details: String? = null,
        throwable: Throwable? = null,
        modId: String = VulkanTurbo.PUBLIC_NAME,
        showToast: Boolean = (level == LogLevel.ERROR),
    ): LogEntry {
        val cleanMod = modId.trim().ifEmpty { VulkanTurbo.PUBLIC_NAME }
        val cleanSource = source.trim().ifEmpty { cleanMod }
        val cleanSummary = summary.trim().ifEmpty { throwable?.javaClass?.simpleName ?: "Unknown diagnostic event" }
        val combinedDetails = combineDetails(details, throwable)
        val timestamp = entryTimestampFormatter.format(LocalDateTime.now())

        val entry: LogEntry
        synchronized(lock) {
            entry = LogEntry(
                id = nextEntryId++,
                timestamp = timestamp,
                level = level,
                modId = cleanMod,
                source = cleanSource,
                summary = cleanSummary,
                details = combinedDetails,
            )
            if (entries.size >= MAX_ENTRIES) {
                entries.removeFirst()
            }
            entries.addLast(entry)
            appendToLatestLogLocked(entry)
        }

        if (level == LogLevel.ERROR) {
            hasUnreadErrors = true
            latestError = entry
        }

        val suffix = detailsSuffix(details)
        when (level) {
            LogLevel.ERROR -> if (throwable != null) {
                VulkanTurbo.log.error("[{}/{}] {}{}", cleanMod, cleanSource, cleanSummary, suffix, throwable)
            } else {
                VulkanTurbo.log.error("[{}/{}] {}{}", cleanMod, cleanSource, cleanSummary, suffix)
            }
            LogLevel.WARN -> if (throwable != null) {
                VulkanTurbo.log.warn("[{}/{}] {}{}", cleanMod, cleanSource, cleanSummary, suffix, throwable)
            } else {
                VulkanTurbo.log.warn("[{}/{}] {}{}", cleanMod, cleanSource, cleanSummary, suffix)
            }
            LogLevel.INFO -> if (throwable != null) {
                VulkanTurbo.log.info("[{}/{}] {}{}", cleanMod, cleanSource, cleanSummary, suffix, throwable)
            } else {
                VulkanTurbo.log.info("[{}/{}] {}{}", cleanMod, cleanSource, cleanSummary, suffix)
            }
        }

        if (showToast && level == LogLevel.ERROR) {
            runCatching { DiagnosticNotices.showClickableErrorToast(cleanSummary) }
        }
        return entry
    }

    @JvmStatic
    @JvmOverloads
    fun recordError(
        source: String,
        summary: String,
        details: String? = null,
        throwable: Throwable? = null,
        modId: String = VulkanTurbo.PUBLIC_NAME,
        showToast: Boolean = true,
    ): LogEntry = record(
        level = LogLevel.ERROR,
        source = source,
        summary = summary,
        details = details,
        throwable = throwable,
        modId = modId,
        showToast = showToast,
    )

    @JvmStatic
    @JvmOverloads
    fun recordWarning(
        source: String,
        summary: String,
        details: String? = null,
        throwable: Throwable? = null,
        modId: String = VulkanTurbo.PUBLIC_NAME,
    ): LogEntry = record(
        level = LogLevel.WARN,
        source = source,
        summary = summary,
        details = details,
        throwable = throwable,
        modId = modId,
        showToast = false,
    )

    @JvmStatic
    @JvmOverloads
    fun recordInfo(
        source: String,
        summary: String,
        details: String? = null,
        modId: String = VulkanTurbo.PUBLIC_NAME,
    ): LogEntry = record(
        level = LogLevel.INFO,
        source = source,
        summary = summary,
        details = details,
        throwable = null,
        modId = modId,
        showToast = false,
    )

    @JvmStatic
    @JvmOverloads
    fun formatLogText(entries: List<LogEntry> = entriesSnapshot()): String {
        if (entries.isEmpty()) {
            return "No diagnostic entries recorded in this session."
        }
        val sb = StringBuilder(entries.size * 128)
        for (i in entries.indices) {
            if (i > 0) sb.append('\n')
            appendFormattedEntry(sb, entries[i])
        }
        return sb.toString()
    }

    @JvmStatic
    fun buildCrashDiagnosticSection(): String {
        val sb = StringBuilder(1536)
        sb.append("-- Pyroclast (VulkanTurbo) Diagnostics --\n")

        val modVersion = runCatching {
            FabricLoader.getInstance()
                .getModContainer(VulkanTurbo.MOD_ID)
                .map { it.metadata.version.friendlyString }
                .orElse("development")
        }.getOrElse { "unknown" }

        val ext = runCatching { TurboShadersBridge.extension }.getOrNull()
        val regionMode = runCatching { TerrainRegionPipelines.resolveDrawMode().name }.getOrElse { "UNKNOWN" }

        sb.append("Mod Version: ").append(modVersion)
            .append(" (").append(VulkanTurbo.PUBLIC_NAME)
            .append(" / ").append(VulkanTurbo.CODENAME).append(")\n")
        sb.append("Platform: ").append(PlatformInfo.osName())
            .append(' ').append(PlatformInfo.osArch())
            .append(" | JVM ").append(PlatformInfo.jvmVersion())
            .append(" | Session ").append(PlatformInfo.sessionType()).append('\n')
        sb.append("Backend: ").append(VulkanTurbo.detectedBackendName)
            .append(" (vulkan=").append(VulkanTurbo.isVulkanBackend)
            .append(", detected=").append(VulkanTurbo.backendDetected).append(")\n")
        sb.append("GPU Device: ").append(VulkanTurbo.detectedDeviceName.ifBlank { "unknown" })
            .append(" | Vendor: ").append(VulkanTurbo.detectedVendor)
            .append(" | High-End: ").append(VulkanTurbo.isHighEndGpu).append('\n')
        sb.append("Active Flags: modEnabled=").append(VulkanTurbo.modEnabled)
            .append(", fastChunkPrep=").append(VulkanTurbo.fastChunkPrepEnabled)
            .append(", asyncOcclusion=").append(VulkanTurbo.asyncOcclusionCullingEnabled)
            .append(", optimalLayouts=").append(VulkanTurbo.optimalImageLayoutsEnabled)
            .append(", pipelineCache=").append(VulkanTurbo.pipelineCacheEnabled)
            .append(", regionDrawMode=").append(regionMode).append('\n')
        sb.append("Registered Extension: ")
            .append(if (ext != null) "${ext.displayName} (id=${ext.engineId}, active=${TurboShadersBridge.active})" else "none")
            .append('\n')

        val extSection = runCatching { TurboShadersBridge.collectExtensionDiagnosticSections() }.getOrNull()
        if (!extSection.isNullOrBlank()) {
            sb.append('\n').append(extSection.trimEnd()).append('\n')
        }

        val recent = entriesSnapshot().takeLast(CRASH_SECTION_TAIL_ENTRIES)
        sb.append("\nRecent Unified Log Entries (last ").append(recent.size).append("):\n")
        if (recent.isEmpty()) {
            sb.append("  <none>\n")
        } else {
            for (entry in recent) {
                appendFormattedEntry(sb, entry, indent = "  ")
                sb.append('\n')
            }
        }
        return sb.toString().trimEnd()
    }

    @JvmStatic
    @JvmOverloads
    fun dumpCrashLog(
        reason: String,
        crashReportText: String? = null,
        throwable: Throwable? = null,
    ): Path? {
        if (!crashDumpWritten.compareAndSet(false, true)) {
            return lastCrashLogPath
        }
        return try {
            val dir = crashLogsDir()
            Files.createDirectories(dir)
            val now = LocalDateTime.now()
            val fileName = "crash-${crashFileTimestampFormatter.format(now)}.log"
            val target = dir.resolve(fileName)
            val tmp = dir.resolve("$fileName.tmp")

            val ext = runCatching { TurboShadersBridge.extension }.getOrNull()
            val headerTitle = if (ext != null) {
                "Pyroclast + ${ext.displayName} Unified Crash / Diagnostic Log"
            } else {
                "Pyroclast Crash / Diagnostic Log"
            }

            val sb = StringBuilder(4096)
            sb.append("================================================================================\n")
            sb.append(headerTitle).append('\n')
            sb.append("Timestamp: ").append(fullTimestampFormatter.format(now)).append('\n')
            sb.append("Reason: ").append(reason).append('\n')
            sb.append("================================================================================\n\n")
            sb.append(buildCrashDiagnosticSection()).append("\n\n")

            if (throwable != null) {
                sb.append("-- Throwable Stacktrace --\n")
                sb.append(formatThrowable(throwable)).append("\n\n")
            }

            sb.append("-- Full Unified Session Log --\n")
            sb.append(formatLogText()).append('\n')

            if (!crashReportText.isNullOrBlank()) {
                sb.append("\n-- Minecraft CrashReport Details --\n")
                sb.append(crashReportText).append('\n')
            }

            Files.writeString(
                tmp,
                sb.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            try {
                Files.move(
                    tmp,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: Throwable) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            lastCrashLogPath = target
            target
        } catch (t: Throwable) {
            crashDumpWritten.set(false)
            runCatching { VulkanTurbo.log.error("Failed to write Pyroclast crash log", t) }
            null
        }
    }

    @JvmStatic
    fun installCrashHooks() {
        if (!hooksInstalled.compareAndSet(false, true)) return

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                recordError(
                    source = "UncaughtException",
                    summary = "Uncaught exception in thread '${thread.name}': ${throwable.message ?: throwable.javaClass.simpleName}",
                    throwable = throwable,
                    showToast = false,
                )
                dumpCrashLog("Uncaught exception in thread ${thread.name}", throwable = throwable)
            }
            previousHandler?.uncaughtException(thread, throwable)
        }

        runCatching {
            Runtime.getRuntime().addShutdownHook(
                Thread(
                    {
                        val hasErrors = errorCount() > 0
                        val unclean = !cleanShutdown.get()
                        if (hasErrors || unclean) {
                            val reason = when {
                                unclean && hasErrors -> "Abnormal JVM shutdown with recorded diagnostic errors"
                                unclean -> "Abnormal JVM shutdown (clean shutdown not marked)"
                                else -> "Session ended with recorded diagnostic errors"
                            }
                            dumpCrashLog(reason)
                        }
                    },
                    "Pyroclast-CrashLog-ShutdownHook",
                ),
            )
        }
    }

    private fun appendToLatestLogLocked(entry: LogEntry) {
        runCatching {
            val dir = logsDir()
            Files.createDirectories(dir)
            val file = latestLogFile()
            val sb = StringBuilder(256)
            if (!sessionLogInitialized) {
                sessionLogInitialized = true
                sb.append("=== Pyroclast Unified Session Log (started ")
                    .append(fullTimestampFormatter.format(LocalDateTime.now()))
                    .append(") ===\n")
                appendFormattedEntry(sb, entry)
                sb.append('\n')
                Files.writeString(
                    file,
                    sb.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
            } else {
                appendFormattedEntry(sb, entry)
                sb.append('\n')
                Files.writeString(
                    file,
                    sb.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE,
                )
            }
        }
    }

    private fun appendFormattedEntry(sb: StringBuilder, entry: LogEntry, indent: String = "") {
        sb.append(indent)
            .append('[').append(entry.timestamp).append("] ")
            .append('[').append(entry.level.name).append("] ")
            .append('[').append(entry.modId).append('/')
            .append(entry.source).append("] ")
            .append(entry.summary)
        val details = entry.details
        if (!details.isNullOrBlank()) {
            for (line in details.lineSequence()) {
                sb.append('\n').append(indent).append("    ").append(line)
            }
        }
    }

    private fun combineDetails(details: String?, throwable: Throwable?): String? {
        val cleanDetails = details?.trim()?.takeIf { it.isNotEmpty() }
        val trace = throwable?.let { formatThrowable(it).trim() }?.takeIf { it.isNotEmpty() }
        return when {
            cleanDetails != null && trace != null -> "$cleanDetails\n$trace"
            cleanDetails != null -> cleanDetails
            else -> trace
        }
    }

    private fun formatThrowable(throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun detailsSuffix(details: String?): String =
        if (details.isNullOrBlank()) "" else " — $details"
}
