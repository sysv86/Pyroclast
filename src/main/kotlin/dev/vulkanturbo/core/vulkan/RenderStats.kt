package dev.vulkanturbo.core

import dev.vulkanturbo.VulkanTurbo

/**
 * Duck interface mixed onto `VulkanRenderPass` (`VulkanRenderPassMixin`) so external shader engines
 * or raw `VkCommandBuffer` state mutations can invalidate cached pipeline/VBO/IBO/scissor state.
 */
interface VulkanRenderPassStateCache {
    fun vtInvalidateStateCache()
}

/**
 * Counters for redundant graphics state changes.
 *
 * All state mutations happen exclusively on the Render thread inside `VulkanRenderPass`, so
 * counters use plain `Long` fields gated by `VulkanTurbo.statsCollectionEnabled` instead of
 * unconditional `AtomicLong` CAS operations on every draw/state bind.
 */
object RenderStats {
    const val VERBOSE_LOG_INTERVAL_FRAMES: Int = 5400
    const val VERBOSE_LOG_INTERVAL_TICKS: Int = 300

    private var binds: Long = 0L
    private var bindSkips: Long = 0L
    private var scissors: Long = 0L
    private var scissorSkips: Long = 0L
    private var verboseFrames: Int = 0
    private var verboseTicks: Int = 0

    /** Plain-field gate updated at config load so per-call checks avoid volatile barriers. */
    @JvmStatic
    fun dedupEnabled(): Boolean = VulkanTurbo.stateDedupEnabled

    @JvmStatic
    fun pipelineBind(redundant: Boolean) {
        if (!VulkanTurbo.statsCollectionEnabled) return
        binds++
        if (redundant) {
            bindSkips++
        }
    }

    @JvmStatic
    fun scissorCall(redundant: Boolean) {
        if (!VulkanTurbo.statsCollectionEnabled) return
        scissors++
        if (redundant) {
            scissorSkips++
        }
    }

    @JvmStatic
    fun onFrame() {
        if (!VulkanTurbo.verboseLoggingEnabled || VulkanTurbo.benchmarkEnabled) {
            verboseFrames = 0
            return
        }
        if (++verboseFrames >= VERBOSE_LOG_INTERVAL_FRAMES) {
            verboseFrames = 0
            verboseTicks = 0
            logAndReset("verbose frame window")
        }
    }

    @JvmStatic
    fun onClientTick() {
        if (!VulkanTurbo.verboseLoggingEnabled || VulkanTurbo.benchmarkEnabled) {
            verboseTicks = 0
            return
        }
        if (++verboseTicks >= VERBOSE_LOG_INTERVAL_TICKS) {
            verboseTicks = 0
            verboseFrames = 0
            logAndReset("verbose tick window")
        }
    }

    @JvmStatic
    fun logAndReset(reason: String) {
        val b = binds
        val bs = bindSkips
        val s = scissors
        val ss = scissorSkips
        binds = 0L
        bindSkips = 0L
        scissors = 0L
        scissorSkips = 0L
        verboseFrames = 0
        verboseTicks = 0

        if (b == 0L && s == 0L) {
            return
        }

        VulkanTurbo.log.info(
            "State stats ({}): pipeline binds {}/{} skipped, scissor {}/{} skipped",
            reason,
            bs,
            b,
            ss,
            s,
        )
    }
}
