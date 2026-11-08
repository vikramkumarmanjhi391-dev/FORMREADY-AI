package com.example.processing

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import com.example.data.model.DocumentRule
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-Device Portrait Intelligence Engine (Phase 2 / Phase 0.4E).
 *
 * Responsibilities:
 * 1. Face & landmark detection via bundled Google ML Kit Face Detection with cached client reuse
 *    guarded by [faceDetectorMutex].
 * 2. Foreground/background confidence mask extraction via bundled Google ML Kit Selfie Segmentation
 *    (`SINGLE_IMAGE_MODE`) with cached client reuse guarded by an independent [segmenterMutex].
 * 3. Single Lifecycle Owner (`MainActivity` only): [releaseEngines] safely closes cached native
 *    ML Kit clients under their respective mutex locks when the activity stops or receives memory trim,
 *    guaranteeing `.close()` never races with an in-flight `.process()` call.
 * 4. Separate timing of `CLIENT_CONSTRUCTION`, `*_COLD_FIRST_INFERENCE`, and `*_WARM_INFERENCE`
 *    via [VisionBenchmarkRecorder].
 */
object PortraitVisionProcessor {

    const val FOREGROUND_CORE_THRESHOLD = 0.80f
    const val BACKGROUND_CONFIDENT_THRESHOLD = 0.20f
    const val MAX_ACCEPTABLE_EDGE_UNCERTAINTY_RATIO = 0.18f
    const val MIN_VALID_FOREGROUND_AREA_RATIO = 0.12f
    const val MAX_VALID_FOREGROUND_AREA_RATIO = 0.82f
    const val MIN_CORE_FOREGROUND_MEAN_CONFIDENCE = 0.80f

    data class DetectedFaceInfo(
        val boundingBoxNorm: RectF,
        val leftEyeNorm: PointF? = null,
        val rightEyeNorm: PointF? = null,
        val eulerZDegrees: Float = 0f
    ) {
        constructor(
            boundingBox: RectF,
            headEulerAngleZ: Float = 0f,
            headEulerAngleY: Float = 0f
        ) : this(
            boundingBoxNorm = boundingBox,
            leftEyeNorm = null,
            rightEyeNorm = null,
            eulerZDegrees = headEulerAngleZ
        )
    }

    data class SegmentationConfidenceMask(
        val width: Int,
        val height: Int,
        val confidences: FloatArray // 0.0f (background) .. 1.0f (foreground)
    )

    fun RawSegmentationMask(
        width: Int,
        height: Int,
        confidences: FloatArray
    ): SegmentationConfidenceMask = SegmentationConfidenceMask(width, height, confidences)

    fun interface FaceDetectionEngine {
        suspend fun detectFaces(bitmap: Bitmap): List<DetectedFaceInfo>
    }

    fun interface SelfieSegmentationEngine {
        suspend fun segmentPortrait(bitmap: Bitmap): SegmentationConfidenceMask?
    }

    /**
     * Closeable client abstraction so both real ML Kit native clients and deterministic
     * unit tests share the exact same dual-mutex caching, cold/warm telemetry, and release lifecycle.
     */
    interface ManagedFaceDetectorClient : AutoCloseable {
        suspend fun processBitmap(bitmap: Bitmap): List<DetectedFaceInfo>
        override fun close()
    }

    interface ManagedSelfieSegmenterClient : AutoCloseable {
        suspend fun processBitmap(bitmap: Bitmap): SegmentationConfidenceMask?
        override fun close()
    }

    private val faceDetectorMutex = Mutex()
    private var cachedFaceDetector: ManagedFaceDetectorClient? = null
    private var faceDetectorFirstInferenceDone = false

    private val segmenterMutex = Mutex()
    private var cachedSelfieSegmenter: ManagedSelfieSegmenterClient? = null
    private var segmenterFirstInferenceDone = false

    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val defaultFaceDetectorClientFactory: () -> ManagedFaceDetectorClient = {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build()
        val detector = FaceDetection.getClient(options)
        object : ManagedFaceDetectorClient {
            override suspend fun processBitmap(bitmap: Bitmap): List<DetectedFaceInfo> {
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val faces = detector.process(inputImage).awaitTask()
                val w = bitmap.width.toFloat().coerceAtLeast(1f)
                val h = bitmap.height.toFloat().coerceAtLeast(1f)
                return faces.map { face ->
                    val box = face.boundingBox
                    val normBox = RectF(
                        (box.left / w).coerceIn(0f, 1f),
                        (box.top / h).coerceIn(0f, 1f),
                        (box.right / w).coerceIn(0f, 1f),
                        (box.bottom / h).coerceIn(0f, 1f)
                    )
                    val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position?.let {
                        PointF((it.x / w).coerceIn(0f, 1f), (it.y / h).coerceIn(0f, 1f))
                    }
                    val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position?.let {
                        PointF((it.x / w).coerceIn(0f, 1f), (it.y / h).coerceIn(0f, 1f))
                    }
                    DetectedFaceInfo(
                        boundingBoxNorm = normBox,
                        leftEyeNorm = leftEye,
                        rightEyeNorm = rightEye,
                        eulerZDegrees = face.headEulerAngleZ
                    )
                }
            }

            override fun close() {
                try {
                    detector.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    val defaultSelfieSegmenterClientFactory: () -> ManagedSelfieSegmenterClient = {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
        val segmenter = Segmentation.getClient(options)
        object : ManagedSelfieSegmenterClient {
            override suspend fun processBitmap(bitmap: Bitmap): SegmentationConfidenceMask? {
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val mask = segmenter.process(inputImage).awaitTask()
                val maskW = mask.width
                val maskH = mask.height
                if (maskW <= 0 || maskH <= 0) return null
                val buffer = mask.buffer
                buffer.rewind()
                val rawFloats = FloatArray(maskW * maskH)
                for (i in rawFloats.indices) {
                    rawFloats[i] = buffer.float.coerceIn(0f, 1f)
                }
                return if (maskW == bitmap.width && maskH == bitmap.height) {
                    SegmentationConfidenceMask(maskW, maskH, rawFloats)
                } else {
                    upsampleConfidenceMaskBilinear(rawFloats, maskW, maskH, bitmap.width, bitmap.height)
                }
            }

            override fun close() {
                try {
                    segmenter.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    @Volatile
    var faceDetectorClientFactory: () -> ManagedFaceDetectorClient = defaultFaceDetectorClientFactory

    @Volatile
    var selfieSegmenterClientFactory: () -> ManagedSelfieSegmenterClient = defaultSelfieSegmenterClientFactory

    fun isFaceDetectorCached(): Boolean = cachedFaceDetector != null
    fun isSelfieSegmenterCached(): Boolean = cachedSelfieSegmenter != null
    fun isFaceDetectorWarm(): Boolean = cachedFaceDetector != null && faceDetectorFirstInferenceDone
    fun isSelfieSegmenterWarm(): Boolean = cachedSelfieSegmenter != null && segmenterFirstInferenceDone

    /**
     * Releases cached ML Kit FaceDetector and SelfieSegmenter clients safely under their
     * respective mutex locks so `.close()` can never race with an in-flight `.process()` call.
     *
     * Owned exclusively by `MainActivity` (`onStop` when `!isChangingConfigurations` and
     * `onTrimMemory(TRIM_MEMORY_UI_HIDDEN)`).
     */
    suspend fun releaseEngines() {
        faceDetectorMutex.withLock {
            try {
                cachedFaceDetector?.close()
            } catch (_: Throwable) {
            } finally {
                cachedFaceDetector = null
                faceDetectorFirstInferenceDone = false
            }
        }
        segmenterMutex.withLock {
            try {
                cachedSelfieSegmenter?.close()
            } catch (_: Throwable) {
            } finally {
                cachedSelfieSegmenter = null
                segmenterFirstInferenceDone = false
            }
        }
    }

    /**
     * Non-blocking entry point for `MainActivity.onStop()` and `MainActivity.onTrimMemory()`.
     * Immediately closes any idle client via `tryLock()`, and dispatches a coroutine to await
     * completion of any in-flight inference before closing.
     */
    fun releaseEnginesFromLifecycleOwner() {
        var needAsyncFaceRelease = false
        if (faceDetectorMutex.tryLock()) {
            try {
                cachedFaceDetector?.close()
                cachedFaceDetector = null
                faceDetectorFirstInferenceDone = false
            } catch (_: Throwable) {
            } finally {
                faceDetectorMutex.unlock()
            }
        } else {
            needAsyncFaceRelease = true
        }

        var needAsyncSegRelease = false
        if (segmenterMutex.tryLock()) {
            try {
                cachedSelfieSegmenter?.close()
                cachedSelfieSegmenter = null
                segmenterFirstInferenceDone = false
            } catch (_: Throwable) {
            } finally {
                segmenterMutex.unlock()
            }
        } else {
            needAsyncSegRelease = true
        }

        if (needAsyncFaceRelease || needAsyncSegRelease) {
            cleanupScope.launch {
                releaseEngines()
            }
        }
    }

    /**
     * Adjustable portrait framing profile. Never claims hardcoded ratios are universal official
     * government rules unless explicitly provided by the portal's [DocumentRule].
     */
    data class PortraitFramingProfile(
        val targetHeadHeightRatio: Float = 0.58f,
        val topHeadroomRatio: Float = 0.12f,
        val isFromOfficialPortalRule: Boolean = false
    ) {
        companion object {
            val DEFAULT_BALANCED = PortraitFramingProfile(
                targetHeadHeightRatio = 0.58f,
                topHeadroomRatio = 0.12f,
                isFromOfficialPortalRule = false
            )

            fun fromRule(rule: DocumentRule): PortraitFramingProfile {
                val minHead = rule.minHeadHeightRatio
                val maxHead = rule.maxHeadHeightRatio
                val topHeadroom = rule.topHeadroomRatio
                return if (minHead != null && maxHead != null && minHead > 0f && maxHead >= minHead) {
                    PortraitFramingProfile(
                        targetHeadHeightRatio = ((minHead + maxHead) * 0.5f).coerceIn(0.40f, 0.80f),
                        topHeadroomRatio = (topHeadroom ?: 0.12f).coerceIn(0.05f, 0.25f),
                        isFromOfficialPortalRule = true
                    )
                } else {
                    DEFAULT_BALANCED
                }
            }
        }
    }

    data class PortraitPreparationAnalysis(
        val croppedPortraitBitmap: Bitmap,
        val computedCropRectNorm: RectF,
        val detectedFaceCount: Int,
        val primaryFace: DetectedFaceInfo?,
        val requiresManualCropConfirmation: Boolean,
        val segmentationExecuted: Boolean,
        val backgroundReplacementApplied: Boolean,
        val maskConfidencePassed: Boolean,
        val subjectMaskConfidence: Float,
        val edgeUncertaintyRatio: Float,
        val foregroundAreaRatio: Float,
        val backgroundMask: BooleanArray?,
        val framingProfile: PortraitFramingProfile,
        val diagnosticMessage: String
    ) {
        val preparedBitmap: Bitmap
            get() = croppedPortraitBitmap
    }

    /**
     * Default on-device Google ML Kit Face Detection implementation with `faceDetectorMutex`-guarded
     * client caching and separate `CLIENT_CONSTRUCTION`, `FACE_DETECTION_COLD_FIRST_INFERENCE`, and
     * `FACE_DETECTION_WARM_INFERENCE` telemetry.
     */
    val mlKitFaceDetectionEngine: FaceDetectionEngine = FaceDetectionEngine { bitmap ->
        faceDetectorMutex.withLock {
            try {
                val client = cachedFaceDetector ?: VisionBenchmarkRecorder.measureStage(
                    stage = VisionBenchmarkRecorder.Stage.CLIENT_CONSTRUCTION,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height
                ) {
                    faceDetectorClientFactory()
                }.also {
                    cachedFaceDetector = it
                    faceDetectorFirstInferenceDone = false
                }

                val inferenceStage = if (!faceDetectorFirstInferenceDone) {
                    VisionBenchmarkRecorder.Stage.FACE_DETECTION_COLD_FIRST_INFERENCE
                } else {
                    VisionBenchmarkRecorder.Stage.FACE_DETECTION_WARM_INFERENCE
                }

                val preSnap = VisionBenchmarkRecorder.captureHeapSnapshot()
                val startNs = System.nanoTime()
                val detected = client.processBitmap(bitmap)
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                val postSnap = VisionBenchmarkRecorder.captureHeapSnapshot()

                faceDetectorFirstInferenceDone = true
                VisionBenchmarkRecorder.recordSample(
                    stage = inferenceStage,
                    durationMs = elapsedMs,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    preSnapshot = preSnap,
                    postSnapshot = postSnap
                )
                detected
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    /**
     * Default on-device Google ML Kit Selfie Segmentation implementation (`SINGLE_IMAGE_MODE`)
     * with independent `segmenterMutex`-guarded client caching and separate `CLIENT_CONSTRUCTION`,
     * `SELFIE_SEGMENTATION_COLD_FIRST_INFERENCE`, and `SELFIE_SEGMENTATION_WARM_INFERENCE` telemetry.
     */
    val mlKitSelfieSegmentationEngine: SelfieSegmentationEngine = SelfieSegmentationEngine { bitmap ->
        segmenterMutex.withLock {
            try {
                val client = cachedSelfieSegmenter ?: VisionBenchmarkRecorder.measureStage(
                    stage = VisionBenchmarkRecorder.Stage.CLIENT_CONSTRUCTION,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height
                ) {
                    selfieSegmenterClientFactory()
                }.also {
                    cachedSelfieSegmenter = it
                    segmenterFirstInferenceDone = false
                }

                val inferenceStage = if (!segmenterFirstInferenceDone) {
                    VisionBenchmarkRecorder.Stage.SELFIE_SEGMENTATION_COLD_FIRST_INFERENCE
                } else {
                    VisionBenchmarkRecorder.Stage.SELFIE_SEGMENTATION_WARM_INFERENCE
                }

                val preSnap = VisionBenchmarkRecorder.captureHeapSnapshot()
                val startNs = System.nanoTime()
                val mask = client.processBitmap(bitmap)
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                val postSnap = VisionBenchmarkRecorder.captureHeapSnapshot()

                segmenterFirstInferenceDone = true
                VisionBenchmarkRecorder.recordSample(
                    stage = inferenceStage,
                    durationMs = elapsedMs,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    preSnapshot = preSnap,
                    postSnapshot = postSnap
                )
                mask
            } catch (_: Throwable) {
                null
            }
        }
    }

    @Volatile
    var activeFaceDetectionEngine: FaceDetectionEngine = mlKitFaceDetectionEngine

    @Volatile
    var activeSelfieSegmentationEngine: SelfieSegmentationEngine = mlKitSelfieSegmentationEngine

    /**
     * Computes a normalized portrait crop rectangle (`[0..1]`) around the detected face
     * matching [targetAspectRatio] (width / height, e.g., `3.5f / 4.5f` or `200f / 230f`).
     */
    fun computePortraitCropRectFromFace(
        face: DetectedFaceInfo,
        imageWidth: Int,
        imageHeight: Int,
        targetAspectRatio: Float,
        framingProfile: PortraitFramingProfile = PortraitFramingProfile.DEFAULT_BALANCED
    ): RectF {
        val imgW = imageWidth.toFloat().coerceAtLeast(1f)
        val imgH = imageHeight.toFloat().coerceAtLeast(1f)
        val desiredRatio = if (targetAspectRatio > 0f) targetAspectRatio else (3.5f / 4.5f)

        val faceBoxPx = RectF(
            face.boundingBoxNorm.left * imgW,
            face.boundingBoxNorm.top * imgH,
            face.boundingBoxNorm.right * imgW,
            face.boundingBoxNorm.bottom * imgH
        )
        val estimatedHeadHeightPx = (faceBoxPx.height() * 1.18f).coerceAtLeast(imgH * 0.15f)
        val desiredCropHeightPx = (estimatedHeadHeightPx / framingProfile.targetHeadHeightRatio)
            .coerceIn(faceBoxPx.height() * 1.35f, imgH)
        val desiredCropWidthPx = (desiredCropHeightPx * desiredRatio).coerceAtMost(imgW)
        val finalCropHeightPx = (desiredCropWidthPx / desiredRatio).coerceAtMost(imgH)
        val finalCropWidthPx = finalCropHeightPx * desiredRatio

        val faceCenterX = faceBoxPx.centerX()
        val estimatedCrownY = (faceBoxPx.top - faceBoxPx.height() * 0.14f).coerceAtLeast(0f)

        var cropTop = estimatedCrownY - finalCropHeightPx * framingProfile.topHeadroomRatio
        cropTop = cropTop.coerceIn(0f, (imgH - finalCropHeightPx).coerceAtLeast(0f))
        val cropBottom = (cropTop + finalCropHeightPx).coerceAtMost(imgH)

        var cropLeft = faceCenterX - finalCropWidthPx * 0.5f
        cropLeft = cropLeft.coerceIn(0f, (imgW - finalCropWidthPx).coerceAtLeast(0f))
        val cropRight = (cropLeft + finalCropWidthPx).coerceAtMost(imgW)

        return RectF(
            (cropLeft / imgW).coerceIn(0f, 1f),
            (cropTop / imgH).coerceIn(0f, 1f),
            (cropRight / imgW).coerceIn(0f, 1f),
            (cropBottom / imgH).coerceIn(0f, 1f)
        )
    }

    /**
     * Evaluates a [SegmentationConfidenceMask] and composites the subject onto pure `#FFFFFF` white
     * when `applyWhiteBackground == true`.
     *
     * Never modifies core facial/subject pixels (`confidence >= 0.80f`).
     */
    fun applySegmentationAndEvaluateConfidence(
        sourceBitmap: Bitmap,
        mask: SegmentationConfidenceMask,
        applyWhiteBackground: Boolean
    ): PortraitSegmentationOutcome {
        val width = sourceBitmap.width
        val height = sourceBitmap.height
        val totalPixels = (width * height).coerceAtLeast(1)

        val alignedMask = if (mask.width == width && mask.height == height) {
            mask.confidences
        } else {
            upsampleConfidenceMaskBilinear(mask.confidences, mask.width, mask.height, width, height).confidences
        }

        val pixels = IntArray(totalPixels)
        sourceBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val backgroundMask = BooleanArray(totalPixels)
        var coreSubjectCount = 0
        var coreSubjectConfidenceSum = 0.0
        var uncertainEdgeCount = 0
        var totalForegroundLikeCount = 0
        var confidentBgCount = 0

        for (i in 0 until totalPixels) {
            val conf = alignedMask[i].coerceIn(0f, 1f)
            when {
                conf >= FOREGROUND_CORE_THRESHOLD -> {
                    coreSubjectCount++
                    totalForegroundLikeCount++
                    coreSubjectConfidenceSum += conf
                    backgroundMask[i] = false
                }
                conf <= BACKGROUND_CONFIDENT_THRESHOLD -> {
                    confidentBgCount++
                    backgroundMask[i] = true
                    if (applyWhiteBackground) {
                        pixels[i] = Color.WHITE
                    }
                }
                else -> {
                    uncertainEdgeCount++
                    if (conf >= 0.50f) totalForegroundLikeCount++
                    backgroundMask[i] = false
                    if (applyWhiteBackground) {
                        val alpha = ((conf - BACKGROUND_CONFIDENT_THRESHOLD) /
                            (FOREGROUND_CORE_THRESHOLD - BACKGROUND_CONFIDENT_THRESHOLD)).coerceIn(0f, 1f)
                        val c = pixels[i]
                        val r = (Color.red(c) * alpha + 255f * (1f - alpha)).roundToInt().coerceIn(0, 255)
                        val g = (Color.green(c) * alpha + 255f * (1f - alpha)).roundToInt().coerceIn(0, 255)
                        val b = (Color.blue(c) * alpha + 255f * (1f - alpha)).roundToInt().coerceIn(0, 255)
                        pixels[i] = Color.rgb(r, g, b)
                    }
                }
            }
        }

        val foregroundAreaRatio = totalForegroundLikeCount.toFloat() / totalPixels.toFloat()
        val meanCoreConfidence = if (coreSubjectCount > 0) {
            (coreSubjectConfidenceSum / coreSubjectCount).toFloat()
        } else {
            0f
        }
        val edgeUncertaintyRatio = if (totalForegroundLikeCount > 0) {
            uncertainEdgeCount.toFloat() / totalForegroundLikeCount.toFloat()
        } else {
            1f
        }

        val maskConfidencePassed = confidentBgCount > 0 &&
            foregroundAreaRatio in MIN_VALID_FOREGROUND_AREA_RATIO..MAX_VALID_FOREGROUND_AREA_RATIO &&
            meanCoreConfidence >= MIN_CORE_FOREGROUND_MEAN_CONFIDENCE &&
            edgeUncertaintyRatio <= MAX_ACCEPTABLE_EDGE_UNCERTAINTY_RATIO

        val outputBitmap = if (applyWhiteBackground) {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } else {
            sourceBitmap
        }

        return PortraitSegmentationOutcome(
            outputBitmap = outputBitmap,
            backgroundMask = if (confidentBgCount > 0) backgroundMask else null,
            subjectMaskConfidence = meanCoreConfidence,
            edgeUncertaintyRatio = edgeUncertaintyRatio,
            foregroundAreaRatio = foregroundAreaRatio,
            maskConfidencePassed = maskConfidencePassed
        )
    }

    data class PortraitSegmentationOutcome(
        val outputBitmap: Bitmap,
        val backgroundMask: BooleanArray?,
        val subjectMaskConfidence: Float,
        val edgeUncertaintyRatio: Float,
        val foregroundAreaRatio: Float,
        val maskConfidencePassed: Boolean
    )

    /**
     * Full on-device portrait analysis and segmentation pipeline.
     */
    suspend fun analyzeAndPreparePortrait(
        sourceBitmap: Bitmap,
        rule: DocumentRule,
        applyWhiteBackground: Boolean,
        manualCropRectNorm: RectF? = null,
        faceEngine: FaceDetectionEngine = activeFaceDetectionEngine,
        segmentationEngine: SelfieSegmentationEngine = activeSelfieSegmentationEngine
    ): PortraitPreparationAnalysis {
        val targetRatio = when {
            rule.exactWidthPx != null && rule.exactHeightPx != null && rule.exactHeightPx > 0 ->
                rule.exactWidthPx.toFloat() / rule.exactHeightPx.toFloat()
            rule.targetAspectRatio != null && rule.targetAspectRatio > 0f ->
                rule.targetAspectRatio
            rule.physicalWidthMm != null && rule.physicalHeightMm != null && rule.physicalHeightMm > 0f ->
                rule.physicalWidthMm / rule.physicalHeightMm
            else -> 3.5f / 4.5f
        }
        val framingProfile = PortraitFramingProfile.fromRule(rule)

        var workingBitmap = sourceBitmap
        var faces = faceEngine.detectFaces(workingBitmap)

        if (faces.isEmpty() && min(sourceBitmap.width, sourceBitmap.height) >= 120) {
            for (angle in listOf(90f, 270f)) {
                val rotated = rotateBitmap(sourceBitmap, angle)
                val rotatedFaces = faceEngine.detectFaces(rotated)
                if (rotatedFaces.size == 1) {
                    if (workingBitmap !== sourceBitmap && !workingBitmap.isRecycled) {
                        workingBitmap.recycle()
                    }
                    workingBitmap = rotated
                    faces = rotatedFaces
                    break
                } else if (rotated !== sourceBitmap && !rotated.isRecycled) {
                    rotated.recycle()
                }
            }
        }

        val primaryFace = faces.maxByOrNull { it.boundingBoxNorm.width() * it.boundingBoxNorm.height() }
        val requiresManualConfirmation = faces.size != 1

        if (primaryFace != null && faces.size == 1 && abs(primaryFace.eulerZDegrees) in 2.5f..18f && manualCropRectNorm == null) {
            val leveled = rotateBitmap(workingBitmap, -primaryFace.eulerZDegrees)
            if (workingBitmap !== sourceBitmap && workingBitmap !== leveled && !workingBitmap.isRecycled) {
                workingBitmap.recycle()
            }
            workingBitmap = leveled
        }

        val cropRectNorm = when {
            manualCropRectNorm != null -> manualCropRectNorm
            primaryFace != null && faces.size == 1 -> computePortraitCropRectFromFace(
                face = primaryFace,
                imageWidth = workingBitmap.width,
                imageHeight = workingBitmap.height,
                targetAspectRatio = targetRatio,
                framingProfile = framingProfile
            )
            else -> computeCenteredAspectCropRect(workingBitmap.width, workingBitmap.height, targetRatio)
        }

        val cropped = ImageProcessor.cropBitmap(workingBitmap, cropRectNorm)
        if (workingBitmap !== sourceBitmap && workingBitmap !== cropped && !workingBitmap.isRecycled) {
            workingBitmap.recycle()
        }

        val shouldRunSegmentation = applyWhiteBackground || rule.requiresWhiteBackground
        val rawMask = if (shouldRunSegmentation) {
            segmentationEngine.segmentPortrait(cropped)
        } else {
            null
        }

        if (rawMask == null) {
            val diag = when {
                faces.isEmpty() -> "No face detected automatically — used centered aspect crop. Adjust crop manually if needed."
                faces.size > 1 -> "Multiple faces (${faces.size}) detected — please confirm crop region."
                shouldRunSegmentation -> "Face framed; on-device segmentation mask unavailable — background compliance remains unverified."
                else -> "Face detected and framed to target aspect ratio."
            }
            return PortraitPreparationAnalysis(
                croppedPortraitBitmap = cropped,
                computedCropRectNorm = cropRectNorm,
                detectedFaceCount = faces.size,
                primaryFace = primaryFace,
                requiresManualCropConfirmation = requiresManualConfirmation,
                segmentationExecuted = false,
                backgroundReplacementApplied = false,
                maskConfidencePassed = false,
                subjectMaskConfidence = 0f,
                edgeUncertaintyRatio = 1f,
                foregroundAreaRatio = 0f,
                backgroundMask = null,
                framingProfile = framingProfile,
                diagnosticMessage = diag
            )
        }

        val segOutcome = applySegmentationAndEvaluateConfidence(
            sourceBitmap = cropped,
            mask = rawMask,
            applyWhiteBackground = applyWhiteBackground
        )
        if (segOutcome.outputBitmap !== cropped && segOutcome.outputBitmap !== sourceBitmap && !cropped.isRecycled) {
            cropped.recycle()
        }

        val diag = when {
            requiresManualConfirmation ->
                "Portrait segmented, but ${if (faces.isEmpty()) "no single face" else "multiple faces"} detected — please review crop."
            !segOutcome.maskConfidencePassed ->
                "Background segmented with boundary uncertainty (confidence ${(segOutcome.subjectMaskConfidence * 100).toInt()}%, edge uncertainty ${(segOutcome.edgeUncertaintyRatio * 100).toInt()}%) — please review preview before saving."
            else ->
                "Face framed & background segmented (confidence ${(segOutcome.subjectMaskConfidence * 100).toInt()}%, edge uncertainty ${(segOutcome.edgeUncertaintyRatio * 100).toInt()}%)."
        }

        return PortraitPreparationAnalysis(
            croppedPortraitBitmap = segOutcome.outputBitmap,
            computedCropRectNorm = cropRectNorm,
            detectedFaceCount = faces.size,
            primaryFace = primaryFace,
            requiresManualCropConfirmation = requiresManualConfirmation || !segOutcome.maskConfidencePassed,
            segmentationExecuted = true,
            backgroundReplacementApplied = applyWhiteBackground,
            maskConfidencePassed = segOutcome.maskConfidencePassed,
            subjectMaskConfidence = segOutcome.subjectMaskConfidence,
            edgeUncertaintyRatio = segOutcome.edgeUncertaintyRatio,
            foregroundAreaRatio = segOutcome.foregroundAreaRatio,
            backgroundMask = segOutcome.backgroundMask,
            framingProfile = framingProfile,
            diagnosticMessage = diag
        )
    }

    fun upsampleConfidenceMaskBilinear(
        src: FloatArray,
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int
    ): SegmentationConfidenceMask {
        val out = FloatArray(dstW * dstH)
        val scaleX = (srcW - 1).coerceAtLeast(1).toFloat() / (dstW - 1).coerceAtLeast(1).toFloat()
        val scaleY = (srcH - 1).coerceAtLeast(1).toFloat() / (dstH - 1).coerceAtLeast(1).toFloat()

        for (y in 0 until dstH) {
            val fy = y * scaleY
            val y0 = fy.toInt().coerceIn(0, srcH - 1)
            val y1 = (y0 + 1).coerceAtMost(srcH - 1)
            val wy = fy - y0
            for (x in 0 until dstW) {
                val fx = x * scaleX
                val x0 = fx.toInt().coerceIn(0, srcW - 1)
                val x1 = (x0 + 1).coerceAtMost(srcW - 1)
                val wx = fx - x0

                val v00 = src[y0 * srcW + x0]
                val v10 = src[y0 * srcW + x1]
                val v01 = src[y1 * srcW + x0]
                val v11 = src[y1 * srcW + x1]

                val top = v00 * (1f - wx) + v10 * wx
                val bottom = v01 * (1f - wx) + v11 * wx
                out[y * dstW + x] = (top * (1f - wy) + bottom * wy).coerceIn(0f, 1f)
            }
        }
        return SegmentationConfidenceMask(dstW, dstH, out)
    }

    private fun computeCenteredAspectCropRect(width: Int, height: Int, targetRatio: Float): RectF {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val currentRatio = w / h
        return if (currentRatio > targetRatio) {
            val newW = h * targetRatio
            val left = ((w - newW) * 0.5f) / w
            RectF(left.coerceIn(0f, 1f), 0f, (1f - left).coerceIn(0f, 1f), 1f)
        } else {
            val newH = w / targetRatio
            val top = ((h - newH) * 0.5f) / h
            RectF(0f, top.coerceIn(0f, 1f), 1f, (1f - top).coerceIn(0f, 1f))
        }
    }

    private fun rotateBitmap(src: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { res ->
            if (cont.isActive) cont.resume(res)
        }
        addOnFailureListener { exc ->
            if (cont.isActive) cont.resumeWithException(exc)
        }
        addOnCanceledListener {
            if (cont.isActive) cont.cancel()
        }
    }
}
