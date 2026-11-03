package com.example.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.ColorMode
import com.example.data.model.DocumentRule
import com.example.data.model.DocumentType
import com.example.data.model.FileFormat
import com.example.data.model.ProcessedFileResult
import com.example.processing.ImageProcessor
import com.example.processing.QualityInspector
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.NavyPrimary
import com.example.ui.theme.SaffronSecondary
import com.example.ui.theme.VerifiedGreen
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PhotoPreset(
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val minKb: Int,
    val maxKb: Int,
    val description: String,
    val requiresStrip: Boolean = false
)

val PHOTO_PRESETS = listOf(
    PhotoPreset("Standard 35x45 mm", 350, 450, 20, 50, "Universal Passport photo"),
    PhotoPreset("UPSC CSE (With Name/Date)", 500, 500, 20, 300, "1:1 ratio, DOP mandatory", requiresStrip = true),
    PhotoPreset("SSC CGL/CHSL", 200, 230, 20, 50, "Exact 200x230 px, light background"),
    PhotoPreset("BPSC (Bihar)", 250, 250, 10, 25, "Strictly under 25 KB"),
    PhotoPreset("Railway RRB", 320, 400, 30, 70, "CEN format, 320x400 px"),
    PhotoPreset("IBPS / SBI Bank", 200, 230, 20, 50, "200x230 px, 20-50 KB"),
    PhotoPreset("NTA Postcard (4x6)", 1200, 1800, 10, 200, "Postcard size for NEET/JEE"),
    PhotoPreset("Custom Dimensions", 400, 500, 10, 100, "Manually configure px & KB")
)

@Composable
fun PhotoStudioScreen(
    initialRule: DocumentRule? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var processedResult by remember { mutableStateOf<ProcessedFileResult?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    var selectedPreset by remember { mutableStateOf(PHOTO_PRESETS[0]) }
    var targetMaxKb by remember { mutableIntStateOf(50) }
    var targetMinKb by remember { mutableIntStateOf(20) }

    var candidateName by remember { mutableStateOf("") }
    var dateOfPhoto by remember {
        mutableStateOf(SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date()))
    }
    var addNameDateStrip by remember { mutableStateOf(false) }
    var showFaceGuideOverlay by remember { mutableStateOf(true) }
    var colorMode by remember { mutableStateOf(ColorMode.COLOUR) }

    var qualityReport by remember { mutableStateOf<QualityInspector.QualityReport?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val bmp = ImageProcessor.decodeBitmapFromUri(context, uri)
                if (bmp != null) {
                    sourceBitmap = bmp
                    qualityReport = QualityInspector.inspectImageQuality(bmp)
                    processedResult = null // reset until reprocessed
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("photo_studio_screen")
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "AI Passport Photo Studio",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Crop, align, add Name & Date slate, and compress strictly to any portal's exact KB and pixel limits.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Main Photo Display Card with Face Alignment Guide
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F4F8)),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (sourceBitmap != null) {
                        Image(
                            bitmap = (processedResult?.processedFilePath?.let { File(it) }?.takeIf { it.exists() }?.let {
                                android.graphics.BitmapFactory.decodeFile(it.absolutePath)
                            } ?: sourceBitmap)!!.asImageBitmap(),
                            contentDescription = "Photograph preview",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )

                        // Face Guide Oval Overlay
                        if (showFaceGuideOverlay && processedResult == null) {
                            Box(
                                modifier = Modifier
                                    .size(width = 160.dp, height = 210.dp)
                                    .border(2.dp, Color(0xCC0D47A1), RoundedCornerShape(100.dp))
                            ) {
                                // Eye line guide
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .align(Alignment.Center)
                                        .background(Color(0x99FF6F00))
                                )
                            }
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.PhotoCamera,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Select or Capture Candidate Photo",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Supports front camera selfies or passport scans",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("select_photo_btn")
                            ) {
                                Text("Choose Photo from Gallery / Files")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Photo Quality Analysis Card
            qualityReport?.let { report ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (report.isSharp) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (report.isSharp) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (report.isSharp) VerifiedGreen else SaffronSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "AI Photo Quality Assessment: Sharpness ${report.sharpnessScore}% • ${report.exposureStatus}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (report.issues.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            report.issues.forEach { issue ->
                                Text(text = "• $issue", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Presets Selection
            Text(
                text = "Select Portal Preset or Standard Size",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PHOTO_PRESETS) { preset ->
                    FilterChip(
                        selected = selectedPreset.name == preset.name,
                        onClick = {
                            selectedPreset = preset
                            targetMaxKb = preset.maxKb
                            targetMinKb = preset.minKb
                            if (preset.requiresStrip) {
                                addNameDateStrip = true
                            }
                        },
                        label = { Text(preset.name, fontSize = 12.sp) },
                        shape = RoundedCornerShape(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Fine tuning: Dimensions & Target KB
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Target File Size & Quality Settings",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Allowed Portal Range: $targetMinKb KB to $targetMaxKb KB",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )

                    Slider(
                        value = targetMaxKb.toFloat(),
                        onValueChange = { targetMaxKb = it.toInt() },
                        valueRange = 15f..300f,
                        steps = 28,
                        modifier = Modifier.fillMaxWidth().testTag("photo_kb_slider")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Add Candidate Name & Date Strip", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Switch(
                            checked = addNameDateStrip,
                            onCheckedChange = { addNameDateStrip = it }
                        )
                    }

                    if (addNameDateStrip) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = candidateName,
                            onValueChange = { candidateName = it },
                            label = { Text("Candidate Full Name") },
                            placeholder = { Text("e.g. RAHUL KUMAR") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = dateOfPhoto,
                            onValueChange = { dateOfPhoto = it },
                            label = { Text("Date of Photo (DD-MM-YYYY)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Show Face Alignment Oval", fontSize = 13.sp)
                        Switch(
                            checked = showFaceGuideOverlay,
                            onCheckedChange = { showFaceGuideOverlay = it }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (sourceBitmap != null) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isProcessing = true
                                val rule = DocumentRule(
                                    id = "photo_custom_${System.currentTimeMillis()}",
                                    documentType = DocumentType.PASSPORT_PHOTO,
                                    minKb = targetMinKb,
                                    maxKb = targetMaxKb,
                                    exactWidthPx = selectedPreset.widthPx,
                                    exactHeightPx = selectedPreset.heightPx,
                                    targetAspectRatio = selectedPreset.widthPx.toFloat() / selectedPreset.heightPx.toFloat(),
                                    requiresNameAndDateStrip = addNameDateStrip,
                                    colorMode = colorMode
                                )
                                processedResult = ImageProcessor.processImageForRule(
                                    context = context,
                                    originalBitmap = sourceBitmap!!,
                                    rule = rule,
                                    candidateName = candidateName,
                                    dateOfPhoto = dateOfPhoto,
                                    customTargetKb = targetMaxKb
                                )
                                isProcessing = false
                                Toast.makeText(context, "Photo ready: ${String.format(Locale.US, "%.1f", processedResult?.fileSizeKb)} KB", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = !isProcessing,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).testTag("generate_photo_btn")
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Compressing...")
                        } else {
                            Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Process Photo")
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Replace")
                    }
                }
            }

            // Processed Result Summary Card
            processedResult?.let { res ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldTertiary)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldTertiary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Photo Prepared Successfully!",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = EmeraldTertiary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Output Size: ${String.format(Locale.US, "%.1f", res.fileSizeKb)} KB (Target: $targetMinKb - $targetMaxKb KB)",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Resolution: ${res.widthPx} x ${res.heightPx} px • Format: ${res.fileFormat.extension.uppercase()}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                sharePreparedFiles(context, listOf(File(res.processedFilePath)))
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldTertiary),
                            modifier = Modifier.fillMaxWidth().testTag("share_photo_btn")
                        ) {
                            Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save / Export Photo")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}
