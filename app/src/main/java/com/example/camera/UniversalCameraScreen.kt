package com.example.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun UniversalCameraScreen(
    mode: CameraCaptureMode,
    onMediaCaptured: (UnifiedMediaInput) -> Unit,
    onMultiPageCaptured: ((List<File>) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

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
    var isFlashOn by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var isInitializing by remember { mutableStateOf(true) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    // In-flight review state
    var capturedFileForReview by remember { mutableStateOf<File?>(null) }
    val multiPageCaptures = remember { mutableStateListOf<File>() }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            cameraController.unbind()
        }
    }

    LaunchedEffect(previewView, lensFacing) {
        val pv = previewView ?: return@LaunchedEffect
        isInitializing = true
        try {
            cameraController.getCameraProvider()
            cameraController.bindCamera(
                lifecycleOwner = lifecycleOwner,
                previewView = pv,
                lensFacing = lensFacing,
                flashMode = if (isFlashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF,
                onError = { err ->
                    cameraError = err.localizedMessage ?: "Failed to initialize camera"
                    isInitializing = false
                }
            )
            isInitializing = false
        } catch (e: Exception) {
            cameraError = e.localizedMessage ?: "Camera not available"
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
        // CameraX Preview Surface
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { pv ->
                    pv.scaleType = PreviewView.ScaleType.FILL_CENTER
                    previewView = pv
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Mode-Specific Visual Guidance Overlay
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
                onClick = onDismiss,
                modifier = Modifier
                    .size(42.dp)
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

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                Text(
                    text = mode.title,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            Row {
                // Flash toggle
                IconButton(
                    onClick = {
                        isFlashOn = !isFlashOn
                        cameraController.setFlashMode(
                            if (isFlashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                        )
                    },
                    modifier = Modifier
                        .size(42.dp)
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

                // Lens switch (Front / Back)
                IconButton(
                    onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    },
                    modifier = Modifier
                        .size(42.dp)
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
                color = Color.Black.copy(alpha = 0.65f),
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Text(
                    text = mode.instruction,
                    color = Color.White.copy(alpha = 0.95f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Shutter Button
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(if (isCapturing) Color.Gray else Color.White)
                    .testTag("camera_shutter_btn"),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = {
                        if (!isCapturing && !isInitializing) {
                            isCapturing = true
                            cameraController.takePicture(
                                onSuccess = { file, rotation ->
                                    isCapturing = false
                                    capturedFileForReview = file
                                },
                                onError = { exc ->
                                    isCapturing = false
                                    Toast.makeText(context, "Capture error: ${exc.message}", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    },
                    enabled = !isCapturing && !isInitializing,
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
                    Button(onClick = onDismiss) {
                        Text("Back to Gallery Selection")
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
                // Face Oval Guide with Eye-level indicator
                Box(
                    modifier = Modifier
                        .size(width = 230.dp, height = 300.dp)
                        .border(2.dp, Color(0xCC2196F3), RoundedCornerShape(150.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    // Eye-level line
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(1.dp)
                            .background(Color(0xFFFFC107).copy(alpha = 0.8f))
                    )
                }
            }
            CameraCaptureMode.SIGNATURE -> {
                // Signature Rectangular Frame
                Box(
                    modifier = Modifier
                        .size(width = 300.dp, height = 120.dp)
                        .border(2.dp, Color(0xCCE91E63), RoundedCornerShape(12.dp))
                )
            }
            CameraCaptureMode.THUMB_IMPRESSION -> {
                // Thumb Box Frame
                Box(
                    modifier = Modifier
                        .size(width = 160.dp, height = 200.dp)
                        .border(2.dp, Color(0xFF4CAF50), RoundedCornerShape(16.dp))
                )
            }
            CameraCaptureMode.DOCUMENT_PAGE, CameraCaptureMode.MULTI_PAGE_DOCUMENT -> {
                // Document A4 Sheet Framing Guide
                Box(
                    modifier = Modifier
                        .size(width = 280.dp, height = 390.dp)
                        .border(2.dp, Color(0xCC00BCD4), RoundedCornerShape(10.dp))
                )
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
        BitmapFactory.decodeFile(file.absolutePath)
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
                    text = if (isMultiPageAllowed) "Page $pageIndex Captured" else "Review Capture",
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

                    if (isMultiPageAllowed) {
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
                        modifier = Modifier.weight(1f).testTag("camera_use_photo_btn"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Use Photo")
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
