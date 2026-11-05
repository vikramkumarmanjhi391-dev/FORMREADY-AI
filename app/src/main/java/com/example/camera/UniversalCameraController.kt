package com.example.camera

import android.content.Context
import android.view.MotionEvent
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class UniversalCameraController(
    private val context: Context
) {
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var activeCamera: Camera? = null

    /**
     * Asynchronously resolves the CameraX [ProcessCameraProvider] in a cancellation-safe manner.
     * - Resumes the coroutine at most once.
     * - Delivers asynchronous initialization errors via [resumeWithException].
     * - Never throws uncaught exceptions inside the main-thread executor listener.
     */
    suspend fun getCameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { continuation ->
        val existingProvider = cameraProvider
        if (existingProvider != null) {
            if (continuation.isActive) {
                continuation.resume(existingProvider)
            }
            return@suspendCancellableCoroutine
        }

        val future = try {
            ProcessCameraProvider.getInstance(context)
        } catch (t: Throwable) {
            if (continuation.isActive) {
                continuation.resumeWithException(t)
            }
            return@suspendCancellableCoroutine
        }

        val executor = try {
            ContextCompat.getMainExecutor(context)
        } catch (t: Throwable) {
            java.util.concurrent.Executor { runnable -> runnable.run() }
        }

        future.addListener({
            if (!continuation.isActive) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                if (continuation.isActive) {
                    continuation.resume(provider)
                }
            } catch (t: Throwable) {
                if (continuation.isActive) {
                    val cause = if (t is java.util.concurrent.ExecutionException && t.cause != null) t.cause!! else t
                    continuation.resumeWithException(cause)
                }
            }
        }, executor)

        continuation.invokeOnCancellation {
            // Note: ProcessCameraProvider.getInstance returns a singleton future that should not be force-cancelled globally
        }
    }

    /**
     * Checks whether the device has a camera available for the requested [lensFacing].
     */
    fun hasCameraForLens(lensFacing: Int): Boolean {
        val provider = cameraProvider ?: return false
        return try {
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            provider.hasCamera(selector)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks whether the currently bound camera supports flash unit.
     */
    fun hasFlashUnit(): Boolean {
        return try {
            activeCamera?.cameraInfo?.hasFlashUnit() == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Binds Preview and ImageCapture use cases to the provided [lifecycleOwner].
     * Returns true if binding succeeded, or invokes [onError] and returns false.
     */
    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        lensFacing: Int,
        flashMode: Int = ImageCapture.FLASH_MODE_OFF,
        onCameraBound: (hasFlash: Boolean, actualLensFacing: Int) -> Unit = { _, _ -> },
        onError: (Throwable) -> Unit = {}
    ): Boolean {
        val provider = cameraProvider
        if (provider == null) {
            onError(IllegalStateException("Camera provider is not initialized"))
            return false
        }

        return try {
            provider.unbindAll()
            activeCamera = null

            // Resolve available lens facing (fallback to alternative lens if requested lens is missing)
            val resolvedLensFacing = when {
                hasCameraForLens(lensFacing) -> lensFacing
                lensFacing == CameraSelector.LENS_FACING_FRONT && hasCameraForLens(CameraSelector.LENS_FACING_BACK) ->
                    CameraSelector.LENS_FACING_BACK
                lensFacing == CameraSelector.LENS_FACING_BACK && hasCameraForLens(CameraSelector.LENS_FACING_FRONT) ->
                    CameraSelector.LENS_FACING_FRONT
                else -> lensFacing
            }

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val builder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)

            previewView.display?.rotation?.let { rotation ->
                builder.setTargetRotation(rotation)
            }

            val capture = builder.build()
            imageCapture = capture

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(resolvedLensFacing)
                .build()

            val camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                capture
            )
            activeCamera = camera

            val flashSupported = camera.cameraInfo.hasFlashUnit()
            capture.flashMode = if (flashSupported) flashMode else ImageCapture.FLASH_MODE_OFF

            onCameraBound(flashSupported, resolvedLensFacing)
            true
        } catch (t: Throwable) {
            t.printStackTrace()
            onError(t)
            false
        }
    }

    fun setFlashMode(flashMode: Int): Boolean {
        val capture = imageCapture ?: return false
        val flashSupported = hasFlashUnit()
        if (!flashSupported) {
            capture.flashMode = ImageCapture.FLASH_MODE_OFF
            return false
        }
        return try {
            capture.flashMode = flashMode
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Initiates tap-to-focus and exposure metering at the specified (x, y) coordinates on [previewView].
     */
    fun tapToFocus(
        previewView: PreviewView,
        x: Float,
        y: Float,
        onFocusResult: (Boolean) -> Unit = {}
    ) {
        val camera = activeCamera ?: run {
            onFocusResult(false)
            return
        }
        try {
            val factory = previewView.meteringPointFactory
            val point = factory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            )
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()

            val future = camera.cameraControl.startFocusAndMetering(action)
            future.addListener({
                try {
                    val result = future.get()
                    onFocusResult(result?.isFocusSuccessful == true)
                } catch (e: Exception) {
                    onFocusResult(false)
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            onFocusResult(false)
        }
    }

    fun takePicture(
        previewView: PreviewView? = null,
        onSuccess: (File, Int) -> Unit,
        onError: (ImageCaptureException) -> Unit
    ) {
        val capture = imageCapture ?: run {
            onError(ImageCaptureException(ImageCapture.ERROR_INVALID_CAMERA, "Camera is not ready for capture", null))
            return
        }

        previewView?.display?.rotation?.let { rotation ->
            try {
                capture.targetRotation = rotation
            } catch (_: Exception) {
            }
        }

        val cameraCapturesDir = File(context.cacheDir, "camera_captures").apply {
            if (!exists()) mkdirs()
        }
        val photoFile = File(
            cameraCapturesDir,
            "capture_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg"
        )
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        try {
            capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        if (photoFile.exists() && photoFile.length() > 0L) {
                            onSuccess(photoFile, 0)
                        } else {
                            photoFile.delete()
                            onError(
                                ImageCaptureException(
                                    ImageCapture.ERROR_FILE_IO,
                                    "Captured image file is empty or unreadable",
                                    null
                                )
                            )
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (photoFile.exists()) {
                            photoFile.delete()
                        }
                        onError(exception)
                    }
                }
            )
        } catch (e: Exception) {
            if (photoFile.exists()) {
                photoFile.delete()
            }
            onError(
                ImageCaptureException(
                    ImageCapture.ERROR_UNKNOWN,
                    e.localizedMessage ?: "Failed to capture image",
                    e
                )
            )
        }
    }

    fun unbind() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            activeCamera = null
            imageCapture = null
        }
    }

    companion object {
        /**
         * Safely deletes a list of temporary camera files without affecting non-temporary files.
         */
        fun cleanupTemporaryFiles(files: Iterable<File?>) {
            files.forEach { file ->
                try {
                    if (file != null && file.exists() && file.absolutePath.contains("camera_captures")) {
                        file.delete()
                    }
                } catch (_: Exception) {
                }
            }
        }
    }
}
