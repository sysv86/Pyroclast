package dev.vulkanturbo.core

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.commands.GpuQueryPool
import dev.vulkanturbo.VulkanTurbo
import dev.vulkanturbo.diagnostics.VulkanDiagnosticLog
import java.lang.management.ManagementFactory

/**
 * Lightweight diagnostic profiler active only when `benchmark = true`.
 *
 * Measures GPU frame duration via a rotating non-stalling RenderPearl timestamp [GpuQueryPool],
 * per-phase Render-thread CPU timings, Vulkan pass/barrier/upload counts, and Render-thread
 * heap allocation rate via [com.sun.management.ThreadMXBean].
 */
object FrameProfiler {
    private const val QUERY_ROTATIONS = 4

    private val allocBean: com.sun.management.ThreadMXBean? =
        ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean

    private var queryPool: GpuQueryPool? = null
    private var queryFailed = false
    private var slotIndex = 0
    private val slotSubmitted = BooleanArray(QUERY_ROTATIONS)
    private var frameTimestampStarted = false

    private var warmupLeft = 0
    private var collecting = false
    private var windowFrames = 0

    // Per-frame phase start timestamps (ns)
    private var extractStartNs = 0L
    private var chunkPrepStartNs = 0L
    private var renderStartNs = 0L
    private var acquireStartNs = 0L
    private var blitStartNs = 0L
    private var submitStartNs = 0L
    private var presentStartNs = 0L

    // Window sums (ns or counts)
    private var sumExtractNs = 0L
    private var sumChunkPrepNs = 0L
    private var sumRenderNs = 0L
    private var sumAcquireNs = 0L
    private var sumBlitNs = 0L
    private var sumSubmitNs = 0L
    private var sumPresentNs = 0L

    private var sumGpuNs = 0L
    private var gpuSamples = 0

    private var countPasses = 0L
    private var countBarriers = 0L
    private var countWriteBuf = 0L
    private var countDraws = 0L

    private var windowStartAllocBytes = -1L
    private var windowStartNs = 0L

    @JvmStatic
    fun isEnabled(): Boolean = VulkanTurbo.benchmarkEnabled

    /** Starts collecting a window aligned with [BenchmarkRecorder]. */
    fun resetWindow() {
        sumExtractNs = 0L
        sumChunkPrepNs = 0L
        sumRenderNs = 0L
        sumAcquireNs = 0L
        sumBlitNs = 0L
        sumSubmitNs = 0L
        sumPresentNs = 0L
        sumGpuNs = 0L
        gpuSamples = 0
        countPasses = 0L
        countBarriers = 0L
        countWriteBuf = 0L
        countDraws = 0L
        windowFrames = 0
        windowStartNs = System.nanoTime()
        windowStartAllocBytes = currentThreadAllocatedBytes()
        collecting = true
    }

    fun onInactive() {
        collecting = false
    }

    /** Closes the GPU timestamp query pool before `VulkanDevice.close()` destroys the command encoder. */
    @JvmStatic
    fun onDeviceClosing() {
        val pool = queryPool
        queryPool = null
        queryFailed = false
        frameTimestampStarted = false
        slotIndex = 0
        slotSubmitted.fill(false)
        collecting = false
        if (pool != null) {
            try {
                pool.close()
            } catch (_: Throwable) {
            }
        }
    }

    @JvmStatic
    fun onBenchmarkSampleFrame() {
        if (!collecting) {
            resetWindow()
        }
        windowFrames++
    }

    @JvmStatic
    fun onFrameStartTimestamp() {
        if (!isEnabled() || queryFailed) return
        try {
            val device = RenderSystem.getDevice()
            var pool = queryPool
            if (pool == null) {
                pool = device.createTimestampQueryPool(QUERY_ROTATIONS * 2)
                queryPool = pool
            }

            // Read the oldest slot (at least QUERY_ROTATIONS - 1 frames behind current, so non-blocking).
            val readSlot = (slotIndex + 1) % QUERY_ROTATIONS
            if (slotSubmitted[readSlot]) {
                val vals = pool.getValues(readSlot * 2, 2)
                val t0 = vals[0]
                val t1 = vals[1]
                if (t0.isPresent && t1.isPresent) {
                    val deltaTicks = t1.asLong - t0.asLong
                    if (deltaTicks >= 0L) {
                        val gpuNs = (deltaTicks.toDouble() * device.deviceInfo.timestampPeriod().toDouble()).toLong()
                        if (collecting) {
                            sumGpuNs += gpuNs
                            gpuSamples++
                        }
                    }
                    slotSubmitted[readSlot] = false
                }
            }

            slotIndex = (slotIndex + 1) % QUERY_ROTATIONS
            if (!slotSubmitted[slotIndex]) {
                device.createCommandEncoder().writeTimestamp(pool, slotIndex * 2)
                frameTimestampStarted = true
            } else {
                frameTimestampStarted = false
            }
        } catch (t: Throwable) {
            queryFailed = true
            VulkanDiagnosticLog.recordWarning(source = "FrameProfiler", summary = "Disabling GPU timestamp profiler: $t", throwable = t)
        }
    }

    @JvmStatic
    fun onBeforeSubmitTimestamp() {
        if (!isEnabled() || queryFailed || !frameTimestampStarted) return
        val pool = queryPool ?: return
        try {
            RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, slotIndex * 2 + 1)
            slotSubmitted[slotIndex] = true
        } catch (t: Throwable) {
            queryFailed = true
            VulkanDiagnosticLog.recordWarning(source = "FrameProfiler", summary = "Disabling GPU timestamp profiler on end: $t", throwable = t)
        } finally {
            frameTimestampStarted = false
        }
    }

    @JvmStatic fun beginExtract() { if (collecting) extractStartNs = System.nanoTime() }
    @JvmStatic fun endExtract() { if (collecting && extractStartNs != 0L) { sumExtractNs += System.nanoTime() - extractStartNs; extractStartNs = 0L } }

    @JvmStatic fun beginChunkPrep() { if (collecting) chunkPrepStartNs = System.nanoTime() }
    @JvmStatic fun endChunkPrep() { if (collecting && chunkPrepStartNs != 0L) { sumChunkPrepNs += System.nanoTime() - chunkPrepStartNs; chunkPrepStartNs = 0L } }

    @JvmStatic fun beginRender() { if (collecting) renderStartNs = System.nanoTime() }
    @JvmStatic fun endRender() { if (collecting && renderStartNs != 0L) { sumRenderNs += System.nanoTime() - renderStartNs; renderStartNs = 0L } }

    @JvmStatic fun beginAcquire() { if (collecting) acquireStartNs = System.nanoTime() }
    @JvmStatic fun endAcquire() { if (collecting && acquireStartNs != 0L) { sumAcquireNs += System.nanoTime() - acquireStartNs; acquireStartNs = 0L } }

    @JvmStatic fun beginBlit() { if (collecting) blitStartNs = System.nanoTime() }
    @JvmStatic fun endBlit() { if (collecting && blitStartNs != 0L) { sumBlitNs += System.nanoTime() - blitStartNs; blitStartNs = 0L } }

    @JvmStatic fun beginSubmit() { if (collecting) submitStartNs = System.nanoTime() }
    @JvmStatic fun endSubmit() { if (collecting && submitStartNs != 0L) { sumSubmitNs += System.nanoTime() - submitStartNs; submitStartNs = 0L } }

    @JvmStatic fun beginPresent() { if (collecting) presentStartNs = System.nanoTime() }
    @JvmStatic fun endPresent() { if (collecting && presentStartNs != 0L) { sumPresentNs += System.nanoTime() - presentStartNs; presentStartNs = 0L } }

    @JvmStatic fun countPass() { if (collecting) countPasses++ }
    @JvmStatic fun countBarrier() { if (collecting) countBarriers++ }
    @JvmStatic fun countWriteBuffer() { if (collecting) countWriteBuf++ }
    @JvmStatic fun countDraw() { if (collecting) countDraws++ }

    fun logAndReset(frameCount: Int) {
        if (!collecting || frameCount <= 0) {
            resetWindow()
            return
        }
        val f = frameCount.toDouble()
        val gpuMs = if (gpuSamples > 0) (sumGpuNs / gpuSamples.toDouble()) / 1_000_000.0 else -1.0
        val extMs = (sumExtractNs / f) / 1_000_000.0
        val chkMs = (sumChunkPrepNs / f) / 1_000_000.0
        val renMs = (sumRenderNs / f) / 1_000_000.0
        val acqMs = (sumAcquireNs / f) / 1_000_000.0
        val bltMs = (sumBlitNs / f) / 1_000_000.0
        val subMs = (sumSubmitNs / f) / 1_000_000.0
        val prsMs = (sumPresentNs / f) / 1_000_000.0

        val endAlloc = currentThreadAllocatedBytes()
        val endNs = System.nanoTime()
        val allocKbPerFrame = if (windowStartAllocBytes >= 0L && endAlloc >= windowStartAllocBytes) {
            (endAlloc - windowStartAllocBytes) / 1024.0 / f
        } else {
            -1.0
        }
        val elapsedSec = ((endNs - windowStartNs).coerceAtLeast(1L)) / 1_000_000_000.0
        val allocMbPerSec = if (windowStartAllocBytes >= 0L && endAlloc >= windowStartAllocBytes) {
            (endAlloc - windowStartAllocBytes) / (1024.0 * 1024.0) / elapsedSec
        } else {
            -1.0
        }

        VulkanTurbo.log.info(
            "Benchmark phases (ms/f): gpu={} ({}s) | extract={} | chunkPrep={} | render={} | acq={} | blit={} | submit={} | present={}",
            "%.3f".format(gpuMs),
            gpuSamples,
            "%.3f".format(extMs),
            "%.3f".format(chkMs),
            "%.3f".format(renMs),
            "%.3f".format(acqMs),
            "%.3f".format(bltMs),
            "%.3f".format(subMs),
            "%.3f".format(prsMs),
        )
        VulkanTurbo.log.info(
            "Benchmark work/RAM: passes={}/f | barriers={}/f | writeBuf={}/f | draws={}/f | alloc={} KB/f ({} MB/s)",
            "%.1f".format(countPasses / f),
            "%.1f".format(countBarriers / f),
            "%.1f".format(countWriteBuf / f),
            "%.1f".format(countDraws / f),
            "%.1f".format(allocKbPerFrame),
            "%.1f".format(allocMbPerSec),
        )
        // Stop collecting during BenchmarkRecorder's next WARMUP_FRAMES;
        // onBenchmarkSampleFrame() will resetWindow() when sampling resumes.
        onInactive()
    }

    private fun currentThreadAllocatedBytes(): Long {
        val bean = allocBean ?: return -1L
        return try {
            if (bean.isThreadAllocatedMemorySupported) {
                if (!bean.isThreadAllocatedMemoryEnabled) {
                    bean.isThreadAllocatedMemoryEnabled = true
                }
                bean.getThreadAllocatedBytes(Thread.currentThread().threadId())
            } else {
                -1L
            }
        } catch (_: Throwable) {
            -1L
        }
    }
}
