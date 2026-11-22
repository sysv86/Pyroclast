package dev.vulkanturbo.benchagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Clean-room, runtime-only frame-time sampler shared across Vanilla, VulkanTurbo, and Sodium runs.
 *
 * Loaded onto the bootstrap classloader by {@link BenchAgent}; touches no mod classes and holds
 * no static references to Minecraft, LWJGL, or mod types.
 */
public final class BenchSampler {
    private static final int WARMUP_FRAMES = Integer.getInteger("vt.bench.warmup", 3600);
    private static final int SAMPLE_FRAMES = Integer.getInteger("vt.bench.samples", 5400);
    private static final boolean AUTO_EXIT = Boolean.parseBoolean(System.getProperty("vt.bench.autoExit", "false"));
    private static final boolean VKTRACE = Boolean.parseBoolean(System.getProperty("vt.bench.vktrace", "false"));
    private static final String OUT_PATH = System.getProperty("vt.bench.out", "");
    private static final String LABEL = System.getProperty("vt.bench.label", "run");
    private static final long MAX_VALID_DELTA_NS = 1_000_000_000L;

    private static final int OP_WAIT_FENCES = 0;
    private static final int OP_ACQUIRE = 1;
    private static final int OP_PRESENT = 2;
    private static final int OP_COUNT = 3;

    private static final ThreadLocal<long[]> TIMERS = ThreadLocal.withInitial(() -> new long[OP_COUNT]);

    private static final long[] SAMPLES = new long[SAMPLE_FRAMES];
    private static int index = 0;
    private static int warmup = 0;
    private static long lastNanos = 0L;
    private static boolean active = false;
    private static boolean finished = false;
    private static boolean sampling = false;
    private static long renderFrameCount = 0L;
    private static long renderLevelCount = 0L;

    // Vulkan telemetry accumulated strictly while sampling == true (steady-state window).
    private static long vkDrawIndexedCalls;
    private static long vkDrawIndexedIndices;
    private static long vkDrawIndexedIndirectCalls;
    private static long vkDrawIndexedIndirectSubDraws;
    private static long vkMultiDrawIndexedCalls;
    private static long vkMultiDrawIndexedSubDraws;
    private static long vkDrawCalls;
    private static long vkDrawVertices;
    private static long vkRenderPasses;
    private static long vkBarriers;
    private static long vkBindPipelines;
    private static long vkBindDescriptorSets;
    private static long vkBindVertexBuffers;
    private static long vkBindIndexBuffers;
    private static long vkPushConstants;
    private static long vkQueueSubmits;
    private static long vkWaitFencesCalls;
    private static long vkWaitFencesNs;
    private static long vkAcquireCalls;
    private static long vkAcquireNs;
    private static long vkPresentCalls;
    private static long vkPresentNs;

    private BenchSampler() {
    }

    /** Called at the start of {@code GameRenderer.renderLevel()}. */
    public static void onRenderLevel() {
        if (finished) {
            return;
        }
        renderLevelCount++;
        long now = System.nanoTime();
        if (!active) {
            active = true;
            lastNanos = now;
            return;
        }

        long delta = now - lastNanos;
        lastNanos = now;
        if (delta <= 0L || delta > MAX_VALID_DELTA_NS) {
            return;
        }

        if (warmup < WARMUP_FRAMES) {
            warmup++;
            if (warmup == WARMUP_FRAMES) {
                sampling = true;
            }
            return;
        }

        sampling = true;
        SAMPLES[index++] = delta;
        if (index == SAMPLE_FRAMES) {
            sampling = false;
            finishRun();
        }
    }

    /**
     * Called at the start of {@code Minecraft.renderFrame(boolean)} strictly as a watchdog so a
     * run cannot hang silently if a renderer ever stops invoking {@code GameRenderer.renderLevel()}.
     */
    public static void onRenderFrame() {
        if (finished) {
            return;
        }
        renderFrameCount++;
        if (AUTO_EXIT && renderLevelCount == 0L && renderFrameCount > 15_000L) {
            finished = true;
            System.err.println("[BenchAgent] Error: GameRenderer.renderLevel() was not invoked during 15000 frames.");
            System.exit(2);
        }
    }

    public static void onDrawIndexed(int indexCount, int instanceCount) {
        if (sampling) {
            vkDrawIndexedCalls++;
            vkDrawIndexedIndices += (long) indexCount * Math.max(1, instanceCount);
        }
    }

    public static void onDrawIndexedIndirect(int drawCount) {
        if (sampling) {
            vkDrawIndexedIndirectCalls++;
            vkDrawIndexedIndirectSubDraws += Math.max(0, drawCount);
        }
    }

    public static void onMultiDrawIndexed(int drawCount) {
        if (sampling) {
            vkMultiDrawIndexedCalls++;
            vkMultiDrawIndexedSubDraws += Math.max(0, drawCount);
        }
    }

    public static void onDraw(int vertexCount, int instanceCount) {
        if (sampling) {
            vkDrawCalls++;
            vkDrawVertices += (long) vertexCount * Math.max(1, instanceCount);
        }
    }

    public static void onBeginRendering() {
        if (sampling) {
            vkRenderPasses++;
        }
    }

    public static void onBarrier() {
        if (sampling) {
            vkBarriers++;
        }
    }

    public static void onBindPipeline() {
        if (sampling) {
            vkBindPipelines++;
        }
    }

    public static void onBindDescriptorSets() {
        if (sampling) {
            vkBindDescriptorSets++;
        }
    }

    public static void onBindVertexBuffers() {
        if (sampling) {
            vkBindVertexBuffers++;
        }
    }

    public static void onBindIndexBuffer() {
        if (sampling) {
            vkBindIndexBuffers++;
        }
    }

    public static void onPushConstants() {
        if (sampling) {
            vkPushConstants++;
        }
    }

    public static void onQueueSubmit() {
        if (sampling) {
            vkQueueSubmits++;
        }
    }

    public static void enterOp(int opId) {
        if (sampling) {
            TIMERS.get()[opId] = System.nanoTime();
        }
    }

    public static void exitOp(int opId) {
        if (!sampling) {
            return;
        }
        long start = TIMERS.get()[opId];
        if (start == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - start;
        TIMERS.get()[opId] = 0L;
        if (opId == OP_WAIT_FENCES) {
            vkWaitFencesCalls++;
            vkWaitFencesNs += elapsed;
        } else if (opId == OP_ACQUIRE) {
            vkAcquireCalls++;
            vkAcquireNs += elapsed;
        } else if (opId == OP_PRESENT) {
            vkPresentCalls++;
            vkPresentNs += elapsed;
        }
    }

    private static void finishRun() {
        long[] sorted = Arrays.copyOf(SAMPLES, index);
        Arrays.sort(sorted);

        long sumNs = 0L;
        for (long ns : sorted) {
            sumNs += ns;
        }
        double meanNs = (double) sumNs / sorted.length;
        double meanMs = meanNs / 1_000_000.0;
        double avgFps = 1_000_000_000.0 / meanNs;

        double p50Ms = sorted[percentileIndex(sorted.length, 0.50)] / 1_000_000.0;
        double p95Ms = sorted[percentileIndex(sorted.length, 0.95)] / 1_000_000.0;
        double p99Ms = sorted[percentileIndex(sorted.length, 0.99)] / 1_000_000.0;
        double p999Ms = sorted[percentileIndex(sorted.length, 0.999)] / 1_000_000.0;
        double minMs = sorted[0] / 1_000_000.0;
        double maxMs = sorted[sorted.length - 1] / 1_000_000.0;

        int worstOnePercentCount = Math.max(1, sorted.length / 100);
        long worstSumNs = 0L;
        for (int i = sorted.length - worstOnePercentCount; i < sorted.length; i++) {
            worstSumNs += sorted[i];
        }
        double worstOnePercentMeanNs = (double) worstSumNs / worstOnePercentCount;
        double onePercentLowFps = 1_000_000_000.0 / worstOnePercentMeanNs;

        double n = sorted.length;
        System.out.printf(
            Locale.ROOT,
            "[BenchAgent:%s] %d frames (warmup %d) | avg %.3f ms (%.1f fps) | p50 %.3f ms | p95 %.3f ms | p99 %.3f ms | 1%%low %.1f fps | max %.3f ms%n",
            LABEL,
            sorted.length,
            warmup,
            meanMs,
            avgFps,
            p50Ms,
            p95Ms,
            p99Ms,
            onePercentLowFps,
            maxMs
        );
        if (VKTRACE) {
            System.out.printf(
                Locale.ROOT,
                "[BenchVk:%s] passes %.2f/f | barriers %.2f/f | drawIdx %.1f/f (%.0f idx/f) | indirect %.1f/f (sub %.1f/f) | multi %.1f/f (sub %.1f/f) | draw %.1f/f (%.0f vtx/f) | pipe %.1f/f | desc %.1f/f | vb %.1f/f | ib %.1f/f | push %.1f/f | submit %.2f/f | fenceWait %.3fms/f | acq %.3fms/f | present %.3fms/f%n",
                LABEL,
                vkRenderPasses / n,
                vkBarriers / n,
                vkDrawIndexedCalls / n,
                vkDrawIndexedIndices / n,
                vkDrawIndexedIndirectCalls / n,
                vkDrawIndexedIndirectSubDraws / n,
                vkMultiDrawIndexedCalls / n,
                vkMultiDrawIndexedSubDraws / n,
                vkDrawCalls / n,
                vkDrawVertices / n,
                vkBindPipelines / n,
                vkBindDescriptorSets / n,
                vkBindVertexBuffers / n,
                vkBindIndexBuffers / n,
                vkPushConstants / n,
                vkQueueSubmits / n,
                (vkWaitFencesNs / 1_000_000.0) / n,
                (vkAcquireNs / 1_000_000.0) / n,
                (vkPresentNs / 1_000_000.0) / n
            );
        }
        System.out.flush();

        if (!OUT_PATH.isEmpty()) {
            String vkJson = VKTRACE
                ? String.format(
                    Locale.ROOT,
                    ",\n"
                        + "  \"vk\": {\n"
                        + "    \"passesPerFrame\": %.4f,\n"
                        + "    \"barriersPerFrame\": %.4f,\n"
                        + "    \"drawIndexedPerFrame\": %.4f,\n"
                        + "    \"indicesPerFrame\": %.1f,\n"
                        + "    \"drawIndexedIndirectPerFrame\": %.4f,\n"
                        + "    \"indirectSubDrawsPerFrame\": %.4f,\n"
                        + "    \"multiDrawIndexedPerFrame\": %.4f,\n"
                        + "    \"multiDrawSubDrawsPerFrame\": %.4f,\n"
                        + "    \"drawPerFrame\": %.4f,\n"
                        + "    \"verticesPerFrame\": %.1f,\n"
                        + "    \"bindPipelinePerFrame\": %.4f,\n"
                        + "    \"bindDescriptorSetsPerFrame\": %.4f,\n"
                        + "    \"bindVertexBuffersPerFrame\": %.4f,\n"
                        + "    \"bindIndexBufferPerFrame\": %.4f,\n"
                        + "    \"pushConstantsPerFrame\": %.4f,\n"
                        + "    \"queueSubmitsPerFrame\": %.4f,\n"
                        + "    \"waitFencesPerFrame\": %.4f,\n"
                        + "    \"waitFencesMsPerFrame\": %.6f,\n"
                        + "    \"acquireCallsPerFrame\": %.4f,\n"
                        + "    \"acquireMsPerFrame\": %.6f,\n"
                        + "    \"presentCallsPerFrame\": %.4f,\n"
                        + "    \"presentMsPerFrame\": %.6f\n"
                        + "  }",
                    vkRenderPasses / n,
                    vkBarriers / n,
                    vkDrawIndexedCalls / n,
                    vkDrawIndexedIndices / n,
                    vkDrawIndexedIndirectCalls / n,
                    vkDrawIndexedIndirectSubDraws / n,
                    vkMultiDrawIndexedCalls / n,
                    vkMultiDrawIndexedSubDraws / n,
                    vkDrawCalls / n,
                    vkDrawVertices / n,
                    vkBindPipelines / n,
                    vkBindDescriptorSets / n,
                    vkBindVertexBuffers / n,
                    vkBindIndexBuffers / n,
                    vkPushConstants / n,
                    vkQueueSubmits / n,
                    vkWaitFencesCalls / n,
                    (vkWaitFencesNs / 1_000_000.0) / n,
                    vkAcquireCalls / n,
                    (vkAcquireNs / 1_000_000.0) / n,
                    vkPresentCalls / n,
                    (vkPresentNs / 1_000_000.0) / n
                )
                : "";
            String json = String.format(
                Locale.ROOT,
                "{\n"
                    + "  \"label\": \"%s\",\n"
                    + "  \"warmupFrames\": %d,\n"
                    + "  \"sampleFrames\": %d,\n"
                    + "  \"avgMs\": %.6f,\n"
                    + "  \"avgFps\": %.3f,\n"
                    + "  \"p50Ms\": %.6f,\n"
                    + "  \"medianFps\": %.3f,\n"
                    + "  \"p95Ms\": %.6f,\n"
                    + "  \"p99Ms\": %.6f,\n"
                    + "  \"p999Ms\": %.6f,\n"
                    + "  \"minMs\": %.6f,\n"
                    + "  \"maxMs\": %.6f,\n"
                    + "  \"onePercentLowFps\": %.3f,\n"
                    + "  \"onePercentLowMs\": %.6f,\n"
                    + "  \"renderLevelCalls\": %d,\n"
                    + "  \"renderFrameCalls\": %d%s\n"
                    + "}\n",
                escapeJson(LABEL),
                warmup,
                sorted.length,
                meanMs,
                avgFps,
                p50Ms,
                1000.0 / p50Ms,
                p95Ms,
                p99Ms,
                p999Ms,
                minMs,
                maxMs,
                onePercentLowFps,
                worstOnePercentMeanNs / 1_000_000.0,
                renderLevelCount,
                renderFrameCount,
                vkJson
            );
            try {
                Path target = Path.of(OUT_PATH);
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.writeString(target, json, StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("[BenchAgent] Failed to write output JSON: " + e);
            }
        }

        if (AUTO_EXIT) {
            finished = true;
            System.exit(0);
        } else {
            index = 0;
            warmup = 0;
        }
    }

    private static int percentileIndex(int len, double p) {
        int idx = (int) (len * p);
        return Math.min(len - 1, Math.max(0, idx));
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
