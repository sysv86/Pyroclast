package dev.vulkanturbo.core

import dev.vulkanturbo.VulkanTurbo
import net.minecraft.client.Minecraft

/**
 * Frame-time sampler used to prove or disprove a change.
 *
 * Only rendered in-world frames count: `runTick(false)` frames and any frame with a screen open are
 * discarded, because loading screens otherwise dominate the sample and produce impossible numbers.
 */
object BenchmarkRecorder {
    private const val WARMUP_FRAMES = 3600
    private const val SAMPLE_FRAMES = 5400

    private val samples = LongArray(SAMPLE_FRAMES)
    private var index = 0
    private var warmup = 0
    private var lastNanos = 0L
    private var active = false

    @JvmStatic
    fun onFrame() {
        if (!VulkanTurbo.benchmarkEnabled) {
            RenderStats.onFrame()
            return
        }

        val minecraft = Minecraft.getInstance()
        val now = System.nanoTime()

        if (minecraft.level == null || minecraft.player == null || minecraft.gui.screen() != null) {
            active = false
            lastNanos = 0L
            FrameProfiler.onInactive()
            return
        }

        if (!active) {
            active = true
            lastNanos = now
            return
        }

        val delta = now - lastNanos
        lastNanos = now

        if (delta <= 0L || delta > 1_000_000_000L) {
            return
        }

        if (warmup < WARMUP_FRAMES) {
            warmup++
            return
        }

        FrameProfiler.onBenchmarkSampleFrame()
        samples[index++] = delta
        if (index == SAMPLE_FRAMES) {
            report()
            FrameProfiler.logAndReset(index)
            index = 0
            warmup = 0
            RenderStats.logAndReset("frame window")
        }
    }

    private fun report() {
        val sorted = samples.copyOf(index)
        sorted.sort()

        val meanNanos = sorted.average()
        val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
        val p99 = sorted[(sorted.size * 0.99).toInt().coerceAtMost(sorted.size - 1)]

        VulkanTurbo.log.info(
            "Benchmark: {} frames | avg {} ms ({} fps) | p95 {} ms | p99 {} ms",
            index,
            "%.2f".format(meanNanos / 1_000_000.0),
            "%.1f".format(1_000_000_000.0 / meanNanos),
            "%.2f".format(p95 / 1_000_000.0),
            "%.2f".format(p99 / 1_000_000.0),
        )

        VulkanTurbo.log.info(
            "Benchmark scene: entities {} | renderDistance {} | sections {}",
            Minecraft.getInstance().level?.entitiesForRendering()?.count(),
            Minecraft.getInstance().options.renderDistance().get(),
            Minecraft.getInstance().levelRenderer.visibleSections().size,
        )
    }
}
