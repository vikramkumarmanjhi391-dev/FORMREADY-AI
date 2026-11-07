package com.example.processing

import android.os.Debug
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.ceil

/**
 * On-device performance and memory telemetry recorder for Motorola Moto G60 (and other physical
 * devices) benchmarking.
 *
 * Records actual measured durations and heap deltas for:
 * - Cold and warm model initialization
 * - Face detection latency
 * - Single-image selfie segmentation latency
 * - Sauvola signature binarization latency
 * - Document 4-corner detection & homography warp latency
 * - Total end-to-end document preparation time
 * - Peak Java and native heap memory usage
 *
 * Does NOT fabricate benchmark numbers; median and p95 statistics are computed strictly from
 * recorded execution samples.
 */
object VisionBenchmarkRecorder {

    enum class Stage {
        MODEL_INIT_COLD,
        MODEL_INIT_WARM,
        FACE_DETECTION,
        SELFIE_SEGMENTATION,
        SAUVOLA_SIGNATURE,
        DOCUMENT_CORNER_WARP,
        END_TO_END_PREPARATION
    }

    data class BenchmarkSample(
        val stage: Stage,
        val durationMs: Long,
        val imageWidth: Int,
        val imageHeight: Int,
        val javaHeapUsedBytes: Long,
        val nativeHeapAllocatedBytes: Long,
        val timestampMs: Long = System.currentTimeMillis()
    )

    data class StageSummary(
        val stage: Stage,
        val sampleCount: Int,
        val medianMs: Long,
        val p95Ms: Long,
        val minMs: Long,
        val maxMs: Long,
        val peakJavaHeapBytes: Long,
        val peakNativeHeapBytes: Long
    )

    private val samples = CopyOnWriteArrayList<BenchmarkSample>()

    fun recordSample(
        stage: Stage,
        durationMs: Long,
        imageWidth: Int = 0,
        imageHeight: Int = 0
    ): BenchmarkSample {
        val runtime = Runtime.getRuntime()
        val javaUsed = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        val nativeUsed = try {
            Debug.getNativeHeapAllocatedSize().coerceAtLeast(0L)
        } catch (_: Throwable) {
            0L
        }
        val sample = BenchmarkSample(
            stage = stage,
            durationMs = durationMs.coerceAtLeast(0L),
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            javaHeapUsedBytes = javaUsed,
            nativeHeapAllocatedBytes = nativeUsed
        )
        samples.add(sample)
        return sample
    }

    inline fun <T> measureStage(
        stage: Stage,
        imageWidth: Int = 0,
        imageHeight: Int = 0,
        block: () -> T
    ): T {
        val startNs = System.nanoTime()
        try {
            return block()
        } finally {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            recordSample(stage, elapsedMs, imageWidth, imageHeight)
        }
    }

    fun summarizeStage(stage: Stage): StageSummary? {
        val stageSamples = samples.filter { it.stage == stage }
        if (stageSamples.isEmpty()) return null
        val sortedDurations = stageSamples.map { it.durationMs }.sorted()
        val count = sortedDurations.size
        val median = if (count % 2 == 1) {
            sortedDurations[count / 2]
        } else {
            (sortedDurations[(count / 2) - 1] + sortedDurations[count / 2]) / 2L
        }
        val p95Index = (ceil(count * 0.95).toInt() - 1).coerceIn(0, count - 1)
        val p95 = sortedDurations[p95Index]

        return StageSummary(
            stage = stage,
            sampleCount = count,
            medianMs = median,
            p95Ms = p95,
            minMs = sortedDurations.first(),
            maxMs = sortedDurations.last(),
            peakJavaHeapBytes = stageSamples.maxOf { it.javaHeapUsedBytes },
            peakNativeHeapBytes = stageSamples.maxOf { it.nativeHeapAllocatedBytes }
        )
    }

    fun getAllSummaries(): List<StageSummary> {
        return Stage.values().mapNotNull { summarizeStage(it) }
    }

    fun getRecordedSamples(): List<BenchmarkSample> = samples.toList()

    fun clear() {
        samples.clear()
    }
}
