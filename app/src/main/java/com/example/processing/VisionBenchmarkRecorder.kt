package com.example.processing

import android.os.Debug
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.ceil

/**
 * On-device performance and sampled memory telemetry recorder for Motorola Moto G60 (and other
 * physical devices) benchmarking (Phase 0.4E).
 *
 * Records actual measured durations and pre/post stage heap snapshots (`sampledPre*`, `sampledPost*`,
 * `sampled*DeltaBytes`) for:
 * - Client construction (`CLIENT_CONSTRUCTION`)
 * - Cold first inference (`FACE_DETECTION_COLD_FIRST_INFERENCE`, `SELFIE_SEGMENTATION_COLD_FIRST_INFERENCE`)
 * - Warm subsequent inference (`FACE_DETECTION_WARM_INFERENCE`, `SELFIE_SEGMENTATION_WARM_INFERENCE`)
 * - Sauvola signature binarization (`SAUVOLA_SIGNATURE`)
 * - Document 4-corner detection & homography warp (`DOCUMENT_CORNER_WARP`)
 * - Total end-to-end document preparation (`END_TO_END_PREPARATION`)
 *
 * Never fabricates benchmark numbers and never mislabels boundary heap snapshots as continuous
 * hardware peak memory.
 */
object VisionBenchmarkRecorder {

    enum class Stage {
        CLIENT_CONSTRUCTION,
        FACE_DETECTION_COLD_FIRST_INFERENCE,
        FACE_DETECTION_WARM_INFERENCE,
        SELFIE_SEGMENTATION_COLD_FIRST_INFERENCE,
        SELFIE_SEGMENTATION_WARM_INFERENCE,
        MODEL_INIT_COLD,
        MODEL_INIT_WARM,
        FACE_DETECTION,
        SELFIE_SEGMENTATION,
        SAUVOLA_SIGNATURE,
        DOCUMENT_CORNER_WARP,
        END_TO_END_PREPARATION
    }

    data class HeapSnapshot(
        val javaHeapBytes: Long,
        val nativeHeapBytes: Long
    )

    data class BenchmarkSample(
        val stage: Stage,
        val durationMs: Long,
        val imageWidth: Int,
        val imageHeight: Int,
        val sampledPreJavaHeapBytes: Long,
        val sampledPostJavaHeapBytes: Long,
        val sampledJavaHeapDeltaBytes: Long = sampledPostJavaHeapBytes - sampledPreJavaHeapBytes,
        val sampledPreNativeHeapBytes: Long,
        val sampledPostNativeHeapBytes: Long,
        val sampledNativeHeapDeltaBytes: Long = sampledPostNativeHeapBytes - sampledPreNativeHeapBytes,
        val timestampMs: Long = System.currentTimeMillis()
    ) {
        @Deprecated("Use sampledPostJavaHeapBytes instead.", ReplaceWith("sampledPostJavaHeapBytes"))
        val javaHeapUsedBytes: Long
            get() = sampledPostJavaHeapBytes

        @Deprecated("Use sampledPostNativeHeapBytes instead.", ReplaceWith("sampledPostNativeHeapBytes"))
        val nativeHeapAllocatedBytes: Long
            get() = sampledPostNativeHeapBytes
    }

    data class StageSummary(
        val stage: Stage,
        val sampleCount: Int,
        val medianMs: Long,
        val p95Ms: Long,
        val minMs: Long,
        val maxMs: Long,
        val maxSampledPostJavaHeapBytes: Long,
        val maxSampledJavaHeapDeltaBytes: Long,
        val maxSampledPostNativeHeapBytes: Long,
        val maxSampledNativeHeapDeltaBytes: Long
    ) {
        @Deprecated("Use maxSampledPostJavaHeapBytes instead.", ReplaceWith("maxSampledPostJavaHeapBytes"))
        val peakJavaHeapBytes: Long
            get() = maxSampledPostJavaHeapBytes

        @Deprecated("Use maxSampledPostNativeHeapBytes instead.", ReplaceWith("maxSampledPostNativeHeapBytes"))
        val peakNativeHeapBytes: Long
            get() = maxSampledPostNativeHeapBytes
    }

    private val samples = CopyOnWriteArrayList<BenchmarkSample>()

    fun captureHeapSnapshot(): HeapSnapshot {
        val runtime = Runtime.getRuntime()
        val javaUsed = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        val nativeUsed = try {
            Debug.getNativeHeapAllocatedSize().coerceAtLeast(0L)
        } catch (_: Throwable) {
            0L
        }
        return HeapSnapshot(javaHeapBytes = javaUsed, nativeHeapBytes = nativeUsed)
    }

    fun recordSample(
        stage: Stage,
        durationMs: Long,
        imageWidth: Int = 0,
        imageHeight: Int = 0,
        preSnapshot: HeapSnapshot? = null,
        postSnapshot: HeapSnapshot? = null
    ): BenchmarkSample {
        val post = postSnapshot ?: captureHeapSnapshot()
        val pre = preSnapshot ?: post
        val sample = BenchmarkSample(
            stage = stage,
            durationMs = durationMs.coerceAtLeast(0L),
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            sampledPreJavaHeapBytes = pre.javaHeapBytes,
            sampledPostJavaHeapBytes = post.javaHeapBytes,
            sampledJavaHeapDeltaBytes = post.javaHeapBytes - pre.javaHeapBytes,
            sampledPreNativeHeapBytes = pre.nativeHeapBytes,
            sampledPostNativeHeapBytes = post.nativeHeapBytes,
            sampledNativeHeapDeltaBytes = post.nativeHeapBytes - pre.nativeHeapBytes
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
        val pre = captureHeapSnapshot()
        val startNs = System.nanoTime()
        try {
            return block()
        } finally {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            val post = captureHeapSnapshot()
            recordSample(
                stage = stage,
                durationMs = elapsedMs,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                preSnapshot = pre,
                postSnapshot = post
            )
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
            maxSampledPostJavaHeapBytes = stageSamples.maxOf { it.sampledPostJavaHeapBytes },
            maxSampledJavaHeapDeltaBytes = stageSamples.maxOf { it.sampledJavaHeapDeltaBytes },
            maxSampledPostNativeHeapBytes = stageSamples.maxOf { it.sampledPostNativeHeapBytes },
            maxSampledNativeHeapDeltaBytes = stageSamples.maxOf { it.sampledNativeHeapDeltaBytes }
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
