package com.example.camera

import android.Manifest
import android.content.pm.PackageManager
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.roundToInt

@Composable
fun UniversalCameraScreen(
    mode: CameraCaptureMode,
    onMediaCaptured: (UnifiedMediaInput) -> Unit,
    onMultiPageCaptured: ((List<File>) -> Unit)? = null,
    onDismiss: () -> Unit,
    instantOneClickByDefault: Boolean = !mode.isMultiPageAllowed
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        permissionRequested = true
        if (!isGranted) {
            Toast.makeText(context, "Camera permission required for in-app capture", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission && !permissionRequested) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasCameraPermission) {
        BackHandler(onBack = onDismiss)
        PermissionDeniedFallback(
            onRetryPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onDismiss = onDismiss
        )
        return
    }

    val cameraController = remember { UniversalCameraController(context) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var lensFacing by remember {
        mutableIntStateOf(
            if (mode.defaultToFrontCamera) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        )
    }
    var hasFlashUnit by remember { mutableStateOf(false) }
    var isFlashOn by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var isInitializing by remember { mutableStateOf(true) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var resumeTrigger by remember { mutableIntStateOf(0) }
    var isInstantOneClick by remember(mode) { mutableStateOf(instantOneClickByDefault && !mode.isMultiPageAllowed) }

    // Tap-to-focus visual indicator state
    var focusTapOffset by remember { mutableStateOf<Offset?>(null) }

    // In-flight review state
    var capturedFileForReview by remember { mutableStateOf<File?>(null) }
    val multiPageCaptures = remember { mutableStateListOf<File>() }
    var sessionTransferredOwnership by remember { mutableStateOf(false) }

    val dismissAndCleanup: () -> Unit = {
        if (!sessionTransferredOwnership) {
            UniversalCameraController.cleanupTemporaryFiles(multiPageCaptures)
            UniversalCameraController.cleanupTemporaryFiles(listOf(capturedFileForReview))
        }
        multiPageCaptures.clear()
        capturedFileForReview = null
        cameraController.unbind()
        onDismiss()
    }

    BackHandler {
        if (capturedFileForReview != null) {
            val toDelete = capturedFileForReview
            capturedFileForReview = null
            toDelete?.delete()
        } else {
            dismissAndCleanup()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    hasCameraPermission = granted
                    if (granted && capturedFileForReview == null) {
                        resumeTrigger++
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    isCapturing = false
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (!sessionTransferredOwnership) {
                UniversalCameraController.cleanupTemporaryFiles(multiPageCaptures)
                UniversalCameraController.cleanupTemporaryFiles(listOf(capturedFileForReview))
            }
            cameraController.unbind()
        }
    }

    LaunchedEffect(focusTapOffset) {
        if (focusTapOffset != null) {
            delay(1000)
            focusTapOffset = null
        }
    }

    LaunchedEffect(previewView, lensFacing, resumeTrigger, capturedFileForReview) {
        if (capturedFileForReview != null) return@LaunchedEffect
        val pv = previewView ?: return@LaunchedEffect
        isInitializing = true
        cameraError = null
        try {
            cameraController.getCameraProvider()
            val bound = cameraController.bindCamera(
                lifecycleOwner = lifecycleOwner,
                previewView = pv,
                lensFacing = lensFacing,
                flashMode = if (isFlashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF,
                onCameraBound = { flashAvailable, actualLens ->
                    hasFlashUnit = flashAvailable
                    if (!flashAvailable) {
                        isFlashOn = false
                    }
                    if (actualLens != lensFacing) {
                        lensFacing = actualLens
                    }
                },
                onError = { err ->
                    cameraError = err.localizedMessage ?: "Failed to initialize camera"
                    isInitializing = false
                }
            )
            if (bound) {
                isInitializing = false
            }
        } catch (e: Exception) {
            cameraError = e.localizedMessage ?: "Camera not available on this device"
            isInitializing = false
        }
    }

    // If reviewing captured photo, show Camera Review overlay
    if (capturedFileForReview != null) {
        CameraCaptureReviewView(
            file = capturedFileForReview!!,
            mode = mode,
            pageIndex = multiPageCaptures.size + 1,
            isMultiPageAllowed = mode.isMultiPageAllowed,
            onRetake = {
                val toDelete = capturedFileForReview
                capturedFileForReview = null
                toDelete?.delete()
            },
            onUsePhoto = {
                val file = capturedFileForReview!!
                sessionTransferredOwnership = true
                capturedFileForReview = null
                if (mode.isMultiPageAllowed) {
                    multiPageCaptures.add(file)
                    onMultiPageCaptured?.invoke(multiPageCaptures.toList())
                } else {
                    val mediaInput = UnifiedMediaInput.fromCameraFile(file, mode)
                    onMediaCaptured(mediaInput)
                }
            },
            onAddAnotherPage = {
                val file = capturedFileForReview!!
                multiPageCaptures.add(file)
                capturedFileForReview = null
            }
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("universal_camera_view")
    ) {
        // CameraX Preview Surface with Tap-to-Focus
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { pv ->
                    pv.scaleType = PreviewView.ScaleType.FILL_CENTER
                    pv.setOnTouchListener { view, motionEvent ->
                        if (motionEvent.action == MotionEvent.ACTION_UP) {
                            focusTapOffset = Offset(motionEvent.x, motionEvent.y)
                            cameraController.tapToFocus(pv, motionEvent.x, motionEvent.y)
                            view.performClick()
                        }
                        true
                    }
                    previewView = pv
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Tap-to-Focus visual ring
        focusTapOffset?.let { offset ->
            val ringSizePx = with(LocalDensity.current) { 56.dp.toPx() }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (offset.x - ringSizePx / 2f).roundToInt(),
                            (offset.y - ringSizePx / 2f).roundToInt()
                        )
                    }
                    .size(56.dp)
                    .border(2.dp, Color(0xFFFFD54F), CircleShape)
            )
        }

        // Mode-Specific Visual Guidance Overlay (with darkened outer mask)
        CameraModeGuidanceOverlay(mode = mode)

        // Top Control Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = dismissAndCleanup,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .testTag("camera_close_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Camera",
                    tint = Color.White
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.65f)
                ) {
                    Text(
                        text = mode.title,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                if (!mode.isMultiPageAllowed) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        onClick = { isInstantOneClick = !isInstantOneClick },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isInstantOneClick) Color(0xFF059669) else Color.Black.copy(alpha = 0.7f),
                        modifier = Modifier.testTag("camera_one_click_mode_toggle")
                    ) {
                        Text(
                            text = if (isInstantOneClick) "⚡ 1-Click Auto-Process: ON" else "Review Before Process",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                } else if (multiPageCaptures.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1976D2).copy(alpha = 0.85f)
                    ) {
                        Text(
                            text = "${multiPageCaptures.size} page(s) ready",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Only display Flash toggle when the active camera hardware supports a flash unit
                if (hasFlashUnit) {
                    IconButton(
                        onClick = {
                            val nextFlashState = !isFlashOn
                            val updated = cameraController.setFlashMode(
                                if (nextFlashState) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                            )
                            if (updated) {
                                isFlashOn = nextFlashState
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .testTag("camera_flash_toggle")
                    ) {
                        Icon(
                            imageVector = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Toggle Flash",
                            tint = if (isFlashOn) Color(0xFFFFD54F) else Color.White
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))
                }

                // Lens switch (Front / Back)
                IconButton(
                    onClick = {
                        val targetLens = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                        if (cameraController.hasCameraForLens(targetLens)) {
                            lensFacing = targetLens
                        } else {
                            Toast.makeText(context, "Other camera lens not available on this device", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .testTag("camera_switch_lens_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = "Switch Camera",
                        tint = Color.White
                    )
                }
            }
        }

        // Bottom Capture Controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 36.dp)
                .align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Instruction Banner
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.Black.copy(alpha = 0.72f),
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Text(
                    text = "${mode.instruction} (Tap to focus)",
                    color = Color.White.copy(alpha = 0.95f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Shutter Button
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .border(4.dp, if (isInstantOneClick) Color(0xFF34D399) else Color.White, CircleShape)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(if (isCapturing || cameraError != null) Color.Gray else Color.White)
                        .testTag("camera_shutter_btn"),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = {
                            if (!isCapturing && !isInitializing && cameraError == null) {
                                isCapturing = true
                                cameraController.takePicture(
                                    previewView = previewView,
                                    onSuccess = { file, _ ->
                                        isCapturing = false
                                        if (isInstantOneClick && !mode.isMultiPageAllowed) {
                                            // Instant 1-Click Auto-Processing: transfer directly without manual review step
                                            sessionTransferredOwnership = true
                                            val mediaInput = UnifiedMediaInput.fromCameraFile(file, mode)
                                            onMediaCaptured(mediaInput)
                                        } else {
                                            capturedFileForReview = file
                                        }
                                    },
                                    onError = { exc ->
                                        isCapturing = false
                                        Toast.makeText(context, "Capture error: ${exc.message}", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        },
                        enabled = !isCapturing && !isInitializing && cameraError == null,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (isCapturing) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                            )
                        }
                    }
                }

                if (mode.isMultiPageAllowed && multiPageCaptures.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(20.dp))
                    Button(
                        onClick = {
                            sessionTransferredOwnership = true
                            onMultiPageCaptured?.invoke(multiPageCaptures.toList())
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("camera_finish_multipage_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Done (${multiPageCaptures.size})")
                    }
                }
            }
        }

        // Camera Loading or Error
        if (isInitializing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Initializing Camera...",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (cameraError != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Camera Unavailable",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = cameraError ?: "Please select from Gallery instead.",
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { resumeTrigger++ }) {
                            Text("Retry")
                        }
                        Button(onClick = dismissAndCleanup) {
                            Text("Back to Gallery")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraModeGuidanceOverlay(mode: CameraCaptureMode) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when (mode) {
            CameraCaptureMode.PASSPORT_PHOTO -> {
                // Passport Face Oval + Shoulder Line Guide
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(width = 230.dp, height = 300.dp)
                            .border(2.5.dp, Color(0xFF38BDF8), RoundedCornerShape(150.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        // Eye-level line
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.72f)
                                .offset(y = (-24).dp)
                                .height(1.dp)
                                .background(Color(0xFFFFD54F).copy(alpha = 0.85f))
                        )
                        // Shoulder level guide line near bottom of passport frame
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.88f)
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 38.dp)
                                .height(2.dp)
                                .background(Color(0xFF34D399).copy(alpha = 0.85f))
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "FACE IN OVAL • SHOULDERS ON GREEN LINE",
                            color = Color(0xFF34D399),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            CameraCaptureMode.SIGNATURE -> {
                // Darken outside area + crisp Horizontal Signature Cutout Box in center
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0xB3090D16)),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Text(
                            text = "SIGNATURE CUTOUT WINDOW (AUTO-CROPS TO THIS BOX)",
                            color = Color(0xFFF472B6),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(24.dp)
                                .fillMaxSize()
                                .background(Color(0xB3090D16))
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .border(2.5.dp, Color(0xFFEC4899), RoundedCornerShape(10.dp))
                        )
                        Box(
                            modifier = Modifier
                                .width(24.dp)
                                .fillMaxSize()
                                .background(Color(0xB3090D16))
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0xB3090D16))
                    )
                }
            }
            CameraCaptureMode.THUMB_IMPRESSION -> {
                // Thumb Box Frame
                Box(
                    modifier = Modifier
                        .size(width = 160.dp, height = 200.dp)
                        .border(2.5.dp, Color(0xFF4CAF50), RoundedCornerShape(16.dp))
                )
            }
            CameraCaptureMode.DOCUMENT_PAGE, CameraCaptureMode.MULTI_PAGE_DOCUMENT -> {
                // Document A4 / ID Card Framing Guide
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(width = 290.dp, height = 395.dp)
                            .border(2.5.dp, Color(0xFF00E5FF), RoundedCornerShape(12.dp))
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "FIT ID CARD OR A4 PAGE INSIDE FRAME",
                            color = Color(0xFF00E5FF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraCaptureReviewView(
    file: File,
    mode: CameraCaptureMode,
    pageIndex: Int,
    isMultiPageAllowed: Boolean,
    onRetake: () -> Unit,
    onUsePhoto: () -> Unit,
    onAddAnotherPage: () -> Unit
) {
    val bitmap = remember(file) {
        UnifiedMediaInput.decodeBitmapWithExif(file, maxDimension = 2048)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("camera_review_screen")
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Captured Photo Review",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Unable to preview captured image. Please retake.",
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
        }

        // Top info header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.65f)
            ) {
                Text(
                    text = if (isMultiPageAllowed) "Page $pageIndex Captured" else "Review Capture — ${mode.title}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        // Bottom action buttons
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            color = Color.Black.copy(alpha = 0.85f),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Confirm capture quality before processing",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.75f)
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onRetake,
                        modifier = Modifier.weight(1f).testTag("camera_retake_btn"),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Retake")
                    }

                    if (isMultiPageAllowed && bitmap != null) {
                        OutlinedButton(
                            onClick = onAddAnotherPage,
                            modifier = Modifier.weight(1f).testTag("camera_add_page_btn"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF64B5F6)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF64B5F6)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("+ Page")
                        }
                    }

                    Button(
                        onClick = onUsePhoto,
                        enabled = bitmap != null,
                        modifier = Modifier.weight(1f).testTag("camera_use_photo_btn"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isMultiPageAllowed) "Finish" else "Use Photo")
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionDeniedFallback(
    onRetryPermission: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Camera Permission Required",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "FORMREADY AI requires camera permission to capture live application photos, signatures, and certificates locally on device. You can also select existing photos from your Gallery.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onRetryPermission,
                modifier = Modifier.fillMaxWidth().testTag("grant_camera_permission_btn"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Allow Camera Access")
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().testTag("fallback_to_gallery_btn"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Select from Gallery Instead")
            }
        }
    }
}
