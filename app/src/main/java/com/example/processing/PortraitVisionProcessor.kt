package com.example.processing

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import com.example.data.model.DocumentRule
import com.example.data.model.PortraitFramingState
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
        val diagnosticMessage: String,
        val detectedFaces: List<DetectedFaceInfo> = emptyList(),
        val detectionBitmapWidthPx: Int = 0,
        val detectionBitmapHeightPx: Int = 0,
        val detectionRotationDegrees: Float = 0f,
        val selectedFaceIndex: Int? = null,
        val manualCropConfirmedByUser: Boolean = false,
        val framingConfidencePassed: Boolean = detectedFaceCount == 1,
        val faceFramingVerified: Boolean = !requiresManualCropConfirmation || manualCropConfirmedByUser,
        val portraitFramingState: PortraitFramingState = when {
            manualCropConfirmedByUser -> PortraitFramingState.MANUALLY_CONFIRMED
            requiresManualCropConfirmation && detectedFaceCount == 0 -> PortraitFramingState.NO_FACE_DETECTED
            requiresManualCropConfirmation && detectedFaceCount > 1 -> PortraitFramingState.MULTIPLE_FACES_DETECTED
            requiresManualCropConfirmation -> PortraitFramingState.LOW_CONFIDENCE_FRAMING
            detectedFaceCount == 1 -> PortraitFramingState.VERIFIED_SINGLE_FACE
            else -> PortraitFramingState.NOT_APPLICABLE
        }
    ) {
        val preparedBitmap: Bitmap
            get() = croppedPortraitBitmap
    }

    /**
     * Evaluates whether a single detected face has reliable portrait framing geometry
     * (adequate size, not clipped at the image edge, and within levelable head tilt bounds).
     */
    fun evaluateFaceFramingConfidence(face: DetectedFaceInfo): Boolean {
        val box = face.boundingBoxNorm
        val w = box.width()
        val h = box.height()
        val validSize = w in 0.08f..0.92f && h in 0.10f..0.90f
        val notClippedAtEdge = box.left >= 0.01f && box.top >= 0.01f && box.right <= 0.99f && box.bottom <= 0.99f
        val validRoll = abs(face.eulerZDegrees) <= 18f
        return validSize && notClippedAtEdge && validRoll
    }

    fun sanitizeCropRectNorm(rect: RectF): RectF {
        val left = rect.left.coerceIn(0f, 0.90f)
        val top = rect.top.coerceIn(0f, 0.90f)
        val right = rect.right.coerceIn(left + 0.10f, 1f)
        val bottom = rect.bottom.coerceIn(top + 0.10f, 1f)
        return RectF(left, top, right, bottom)
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
     * matching [targetAspectRatio] (width / height, e.g., `3.5f / 4.5f` or `200f / 230f`)
     * within the exact coordinate space of the detection bitmap ([imageWidth] x [imageHeight]).
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

        val clampedLeftNorm = face.boundingBoxNorm.left.coerceIn(0f, 1f)
        val clampedTopNorm = face.boundingBoxNorm.top.coerceIn(0f, 1f)
        val clampedRightNorm = face.boundingBoxNorm.right.coerceIn(clampedLeftNorm, 1f)
        val clampedBottomNorm = face.boundingBoxNorm.bottom.coerceIn(clampedTopNorm, 1f)

        val faceBoxPx = RectF(
            clampedLeftNorm * imgW,
            clampedTopNorm * imgH,
            clampedRightNorm * imgW,
            clampedBottomNorm * imgH
        )
        val estimatedHeadHeightPx = (faceBoxPx.height() * 1.18f).coerceAtLeast(imgH * 0.15f)
        val minHeightForFaceHeightPx = faceBoxPx.height() * 1.25f
        val minHeightForFaceWidthPx = (faceBoxPx.width() * 1.12f) / desiredRatio
        val minRequiredCropHeightPx = max(minHeightForFaceHeightPx, minHeightForFaceWidthPx)
        val desiredCropHeightPx = max(
            estimatedHeadHeightPx / framingProfile.targetHeadHeightRatio,
            minRequiredCropHeightPx
        ).coerceIn(faceBoxPx.height().coerceAtLeast(1f), imgH)
        val desiredCropWidthPx = (desiredCropHeightPx * desiredRatio).coerceAtMost(imgW)
        val finalCropHeightPx = (desiredCropWidthPx / desiredRatio).coerceAtMost(imgH)
        val finalCropWidthPx = (finalCropHeightPx * desiredRatio).coerceAtMost(imgW)

        val faceCenterX = faceBoxPx.centerX()
        val estimatedCrownY = (faceBoxPx.top - faceBoxPx.height() * 0.14f).coerceAtLeast(0f)

        var cropTop = estimatedCrownY - finalCropHeightPx * framingProfile.topHeadroomRatio
        if (finalCropHeightPx >= faceBoxPx.height()) {
            val minTopToContainFace = (faceBoxPx.bottom - finalCropHeightPx).coerceAtLeast(0f)
            val maxTopToContainFace = faceBoxPx.top.coerceAtMost((imgH - finalCropHeightPx).coerceAtLeast(0f))
            if (minTopToContainFace <= maxTopToContainFace) {
                cropTop = cropTop.coerceIn(minTopToContainFace, maxTopToContainFace)
            }
        }
        cropTop = cropTop.coerceIn(0f, (imgH - finalCropHeightPx).coerceAtLeast(0f))
        val cropBottom = (cropTop + finalCropHeightPx).coerceAtMost(imgH)

        var cropLeft = faceCenterX - finalCropWidthPx * 0.5f
        if (finalCropWidthPx >= faceBoxPx.width()) {
            val minLeftToContainFace = (faceBoxPx.right - finalCropWidthPx).coerceAtLeast(0f)
            val maxLeftToContainFace = faceBoxPx.left.coerceAtMost((imgW - finalCropWidthPx).coerceAtLeast(0f))
            if (minLeftToContainFace <= maxLeftToContainFace) {
                cropLeft = cropLeft.coerceIn(minLeftToContainFace, maxLeftToContainFace)
            }
        }
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
     * Computes a suggested normalized portrait crop rectangle (`[0..1]`) for a user-selected
     * face at [selectedFaceIndex] in [detectedFacesNorm], using the exact face-detection
     * bitmap dimensions ([detectionBitmapWidthPx] x [detectionBitmapHeightPx]).
     *
     * Never silently falls back to processed output dimensions: returns `null` if
     * [detectionBitmapWidthPx] <= 0, [detectionBitmapHeightPx] <= 0, [selectedFaceIndex] is
     * out of bounds, or the face bounding box is invalid.
     */
    fun computeSuggestedCropForSelectedFace(
        detectedFacesNorm: List<RectF>,
        selectedFaceIndex: Int,
        detectionBitmapWidthPx: Int,
        detectionBitmapHeightPx: Int,
        targetAspectRatio: Float,
        framingProfile: PortraitFramingProfile = PortraitFramingProfile.DEFAULT_BALANCED
    ): RectF? {
        if (detectionBitmapWidthPx <= 0 || detectionBitmapHeightPx <= 0) {
            return null
        }
        if (selectedFaceIndex !in detectedFacesNorm.indices) {
            return null
        }
        val rawFaceRect = detectedFacesNorm[selectedFaceIndex]
        val left = rawFaceRect.left.coerceIn(0f, 1f)
        val top = rawFaceRect.top.coerceIn(0f, 1f)
        val right = rawFaceRect.right.coerceIn(0f, 1f)
        val bottom = rawFaceRect.bottom.coerceIn(0f, 1f)
        if (right <= left || bottom <= top) {
            return null
        }
        val faceInfo = DetectedFaceInfo(
            boundingBoxNorm = RectF(left, top, right, bottom)
        )
        return computePortraitCropRectFromFace(
            face = faceInfo,
            imageWidth = detectionBitmapWidthPx,
            imageHeight = detectionBitmapHeightPx,
            targetAspectRatio = targetAspectRatio,
            framingProfile = framingProfile
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
        try {
            sourceBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        } catch (_: NullPointerException) {
            pixels.fill(Color.WHITE)
        }

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
     *
     * Handles the 4 portrait face/framing cases explicitly:
     * A. Zero faces detected -> requires manual framing confirmation.
     * B. Multiple faces detected -> never silently selects a face; exposes [PortraitPreparationAnalysis.detectedFaces]
     *    and requires explicit user face selection or manual framing confirmation.
     * C. One face detected with insufficient framing or segmentation confidence -> requires manual review/confirmation.
     * D. One face detected with reliable framing and segmentation -> proceeds automatically.
     */
    suspend fun analyzeAndPreparePortrait(
        sourceBitmap: Bitmap,
        rule: DocumentRule,
        applyWhiteBackground: Boolean,
        manualCropRectNorm: RectF? = null,
        selectedFaceIndex: Int? = null,
        manualCropConfirmedByUser: Boolean = false,
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
        var appliedRotationDegrees = 0f
        var faces = faceEngine.detectFaces(workingBitmap)

        if (faces.isEmpty() && min(sourceBitmap.width, sourceBitmap.height) >= 120) {
            for (angle in listOf(90f, 270f)) {
                val rotated = rotateBitmap(sourceBitmap, angle)
                val rotatedFaces = faceEngine.detectFaces(rotated)
                if (rotatedFaces.isNotEmpty()) {
                    if (workingBitmap !== sourceBitmap && !workingBitmap.isRecycled) {
                        workingBitmap.recycle()
                    }
                    workingBitmap = rotated
                    faces = rotatedFaces
                    appliedRotationDegrees = angle
                    break
                } else if (rotated !== sourceBitmap && !rotated.isRecycled) {
                    rotated.recycle()
                }
            }
        }

        // Exact dimensions of the bitmap on which faceEngine.detectFaces() produced `faces`
        val detectionWidthPx = workingBitmap.width
        val detectionHeightPx = workingBitmap.height

        val validSelectedFaceIdx = selectedFaceIndex?.takeIf { it in faces.indices }
        // Never silently choose a face when multiple faces are detected unless the user explicitly selected one.
        val chosenFace: DetectedFaceInfo? = when {
            validSelectedFaceIdx != null -> faces[validSelectedFaceIdx]
            faces.size == 1 -> faces[0]
            else -> null
        }

        val singleFaceFramingOk = when {
            validSelectedFaceIdx != null && chosenFace != null -> evaluateFaceFramingConfidence(chosenFace)
            faces.size == 1 && chosenFace != null -> evaluateFaceFramingConfidence(chosenFace)
            else -> false
        }

        if (chosenFace != null && faces.size == 1 && abs(chosenFace.eulerZDegrees) in 2.5f..18f && manualCropRectNorm == null) {
            val leveled = rotateBitmap(workingBitmap, -chosenFace.eulerZDegrees)
            if (workingBitmap !== sourceBitmap && workingBitmap !== leveled && !workingBitmap.isRecycled) {
                workingBitmap.recycle()
            }
            workingBitmap = leveled
        }

        val sanitizedManualCrop = manualCropRectNorm?.let { sanitizeCropRectNorm(it) }
        val cropRectNorm = when {
            sanitizedManualCrop != null -> sanitizedManualCrop
            chosenFace != null -> computePortraitCropRectFromFace(
                face = chosenFace,
                imageWidth = detectionWidthPx,
                imageHeight = detectionHeightPx,
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
        val rawMask = segmentationEngine.segmentPortrait(cropped)

        if (rawMask == null) {
            val segConfidenceOk = !shouldRunSegmentation
            val requiresManual = !manualCropConfirmedByUser && (faces.size != 1 || !singleFaceFramingOk || !segConfidenceOk)
            val framingState = when {
                manualCropConfirmedByUser -> PortraitFramingState.MANUALLY_CONFIRMED
                faces.isEmpty() -> PortraitFramingState.NO_FACE_DETECTED
                faces.size > 1 -> PortraitFramingState.MULTIPLE_FACES_DETECTED
                !singleFaceFramingOk || !segConfidenceOk -> PortraitFramingState.LOW_CONFIDENCE_FRAMING
                else -> PortraitFramingState.VERIFIED_SINGLE_FACE
            }
            val diag = when {
                manualCropConfirmedByUser && validSelectedFaceIdx != null ->
                    "Manual Framing Confirmed: Selected Person #${validSelectedFaceIdx + 1} of ${faces.size} and re-ran portrait preparation."
                manualCropConfirmedByUser ->
                    "Manual Framing Confirmed: User-verified portrait crop applied."
                faces.isEmpty() ->
                    "No Face Detected: Automatic portrait framing could not find a face. Manual crop/framing confirmation is required."
                faces.size > 1 ->
                    "Multiple Faces Detected (${faces.size}): Please select the intended person or manually frame and confirm the crop."
                !singleFaceFramingOk ->
                    "Low Framing Confidence: Detected face is near the boundary or out of standard proportions. Please review and confirm framing."
                shouldRunSegmentation ->
                    "Low Segmentation Confidence: On-device segmentation mask unavailable — manual review required and background compliance remains unverified."
                else ->
                    "Single Face Verified: Face detected and framed to target aspect ratio."
            }
            return PortraitPreparationAnalysis(
                croppedPortraitBitmap = cropped,
                computedCropRectNorm = cropRectNorm,
                detectedFaceCount = faces.size,
                primaryFace = chosenFace,
                requiresManualCropConfirmation = requiresManual,
                segmentationExecuted = false,
                backgroundReplacementApplied = false,
                maskConfidencePassed = false,
                subjectMaskConfidence = 0f,
                edgeUncertaintyRatio = 1f,
                foregroundAreaRatio = 0f,
                backgroundMask = null,
                framingProfile = framingProfile,
                diagnosticMessage = diag,
                detectedFaces = faces,
                detectionBitmapWidthPx = detectionWidthPx,
                detectionBitmapHeightPx = detectionHeightPx,
                detectionRotationDegrees = appliedRotationDegrees,
                selectedFaceIndex = validSelectedFaceIdx,
                manualCropConfirmedByUser = manualCropConfirmedByUser,
                framingConfidencePassed = singleFaceFramingOk,
                faceFramingVerified = !requiresManual || manualCropConfirmedByUser,
                portraitFramingState = framingState
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

        val requiresManual = !manualCropConfirmedByUser &&
            (faces.size != 1 || !singleFaceFramingOk || !segOutcome.maskConfidencePassed)

        val framingState = when {
            manualCropConfirmedByUser -> PortraitFramingState.MANUALLY_CONFIRMED
            faces.isEmpty() -> PortraitFramingState.NO_FACE_DETECTED
            faces.size > 1 -> PortraitFramingState.MULTIPLE_FACES_DETECTED
            !singleFaceFramingOk || !segOutcome.maskConfidencePassed -> PortraitFramingState.LOW_CONFIDENCE_FRAMING
            else -> PortraitFramingState.VERIFIED_SINGLE_FACE
        }

        val diag = when {
            manualCropConfirmedByUser && validSelectedFaceIdx != null ->
                "Manual Framing Confirmed: Selected Person #${validSelectedFaceIdx + 1} of ${faces.size} and re-ran portrait pipeline."
            manualCropConfirmedByUser ->
                "Manual Framing Confirmed: User-verified portrait crop applied."
            faces.isEmpty() ->
                "No Face Detected: Portrait segmented, but no face was found. Please manually frame and confirm the portrait crop."
            faces.size > 1 ->
                "Multiple Faces Detected (${faces.size}): Please select the intended person or manually frame and confirm the crop."
            !singleFaceFramingOk ->
                "Low Framing Confidence: Detected face is near the boundary or tilted — please review and confirm portrait framing."
            !segOutcome.maskConfidencePassed ->
                "Low Segmentation Confidence (confidence ${(segOutcome.subjectMaskConfidence * 100).toInt()}%, edge uncertainty ${(segOutcome.edgeUncertaintyRatio * 100).toInt()}%) — manual review and confirmation required before export."
            else ->
                "Single Face Verified: Face framed & background segmented (confidence ${(segOutcome.subjectMaskConfidence * 100).toInt()}%, edge uncertainty ${(segOutcome.edgeUncertaintyRatio * 100).toInt()}%)."
        }

        return PortraitPreparationAnalysis(
            croppedPortraitBitmap = segOutcome.outputBitmap,
            computedCropRectNorm = cropRectNorm,
            detectedFaceCount = faces.size,
            primaryFace = chosenFace,
            requiresManualCropConfirmation = requiresManual,
            segmentationExecuted = true,
            backgroundReplacementApplied = applyWhiteBackground,
            maskConfidencePassed = segOutcome.maskConfidencePassed,
            subjectMaskConfidence = segOutcome.subjectMaskConfidence,
            edgeUncertaintyRatio = segOutcome.edgeUncertaintyRatio,
            foregroundAreaRatio = segOutcome.foregroundAreaRatio,
            backgroundMask = segOutcome.backgroundMask,
            framingProfile = framingProfile,
            diagnosticMessage = diag,
            detectedFaces = faces,
            detectionBitmapWidthPx = detectionWidthPx,
            detectionBitmapHeightPx = detectionHeightPx,
            detectionRotationDegrees = appliedRotationDegrees,
            selectedFaceIndex = validSelectedFaceIdx,
            manualCropConfirmedByUser = manualCropConfirmedByUser,
            framingConfidencePassed = singleFaceFramingOk,
            faceFramingVerified = !requiresManual || manualCropConfirmedByUser,
            portraitFramingState = framingState
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
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        return try {
            if (rotated.width > 0 && rotated.height > 0) {
                rotated.getPixel(0, 0)
            }
            rotated
        } catch (_: NullPointerException) {
            val w = rotated.width.coerceAtLeast(1)
            val h = rotated.height.coerceAtLeast(1)
            if (rotated !== src && !rotated.isRecycled) rotated.recycle()
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
            }
        }
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
