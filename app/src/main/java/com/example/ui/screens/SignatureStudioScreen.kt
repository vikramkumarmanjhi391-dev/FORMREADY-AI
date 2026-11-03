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
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
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
import com.example.data.model.ColorMode
import com.example.data.model.DocumentRule
import com.example.data.model.DocumentType
import com.example.data.model.FileFormat
import com.example.data.model.ProcessedFileResult
import com.example.processing.ImageProcessor
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.NavyPrimary
import com.example.ui.theme.SaffronSecondary
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

data class SignaturePreset(
    val title: String,
    val type: DocumentType,
    val widthPx: Int,
    val heightPx: Int,
    val minKb: Int,
    val maxKb: Int,
    val description: String
)

val SIGNATURE_PRESETS = listOf(
    SignaturePreset("SSC / IBPS (140x60)", DocumentType.SIGNATURE_ENGLISH, 140, 60, 10, 20, "Exact 140x60 px, 10-20 KB"),
    SignaturePreset("UPSC CSE (Square)", DocumentType.SIGNATURE_ENGLISH, 400, 400, 20, 300, "1:1 ratio, 20-300 KB"),
    SignaturePreset("BPSC English Sign", DocumentType.SIGNATURE_ENGLISH, 220, 100, 5, 15, "Strictly under 15 KB"),
    SignaturePreset("BPSC Hindi Sign (हस्ताक्षर)", DocumentType.SIGNATURE_HINDI, 220, 100, 5, 15, "Hindi signature under 15 KB"),
    SignaturePreset("Left Thumb (LTI)", DocumentType.THUMB_IMPRESSION_LEFT, 240, 240, 20, 50, "Left thumb impression 20-50 KB"),
    SignaturePreset("CTET Sign (35x15 mm)", DocumentType.SIGNATURE_ENGLISH, 350, 150, 3, 30, "3.5 x 1.5 cm format"),
    SignaturePreset("Custom Size", DocumentType.SIGNATURE_ENGLISH, 200, 100, 10, 30, "Custom width, height, and KB")
)

@Composable
fun SignatureStudioScreen(
    initialRule: DocumentRule? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var processedResult by remember { mutableStateOf<ProcessedFileResult?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    var selectedPreset by remember { mutableStateOf(SIGNATURE_PRESETS[0]) }
    var autoCropBlankMargins by remember { mutableStateOf(true) }
    var enhanceContrastWhiteBg by remember { mutableStateOf(true) }

    var customWidthPx by remember { mutableIntStateOf(140) }
    var customHeightPx by remember { mutableIntStateOf(60) }
    var targetMinKb by remember { mutableIntStateOf(10) }
    var targetMaxKb by remember { mutableIntStateOf(20) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val bmp = ImageProcessor.decodeBitmapFromUri(context, uri)
                if (bmp != null) {
                    sourceBitmap = bmp
                    processedResult = null
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("signature_studio_screen")
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Signature & Thumb Studio",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Auto-crop blank white margins, clean paper shadows, darken ink, and compress to exact KB limits for English, Hindi, and Thumb impressions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Preview Box
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (sourceBitmap != null) {
                        Image(
                            bitmap = (processedResult?.processedFilePath?.let { File(it) }?.takeIf { it.exists() }?.let {
                                android.graphics.BitmapFactory.decodeFile(it.absolutePath)
                            } ?: sourceBitmap)!!.asImageBitmap(),
                            contentDescription = "Signature Preview",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.size(54.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Draw,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Select Scanned Signature or Thumb Impression",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Black or blue ink on plain white paper",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("select_signature_btn")
                            ) {
                                Text("Choose Signature File", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Preset Selection
            Text(
                text = "Preset Format & Examination",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SIGNATURE_PRESETS) { preset ->
                    FilterChip(
                        selected = selectedPreset.title == preset.title,
                        onClick = {
                            selectedPreset = preset
                            customWidthPx = preset.widthPx
                            customHeightPx = preset.heightPx
                            targetMinKb = preset.minKb
                            targetMaxKb = preset.maxKb
                        },
                        label = { Text(preset.title, fontSize = 12.sp) },
                        shape = RoundedCornerShape(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Signature Clean-up Settings
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Smart Signature Tuning",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Crop Blank Margins", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Detects inked area and crops out empty paper borders", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = autoCropBlankMargins,
                            onCheckedChange = { autoCropBlankMargins = it }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Whiten Background & Deepen Ink", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Removes desk shadows, enhances contrast for clear portal upload", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = enhanceContrastWhiteBg,
                            onCheckedChange = { enhanceContrastWhiteBg = it }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Target Size: $targetMinKb - $targetMaxKb KB • Dimensions: $customWidthPx x $customHeightPx px",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Slider(
                        value = targetMaxKb.toFloat(),
                        onValueChange = { targetMaxKb = it.toInt() },
                        valueRange = 5f..100f,
                        steps = 19,
                        modifier = Modifier.fillMaxWidth().testTag("sign_kb_slider")
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Process Actions
            if (sourceBitmap != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isProcessing = true
                                var bmp = sourceBitmap!!
                                if (enhanceContrastWhiteBg) {
                                    bmp = ImageProcessor.applyMagicDocFilter(bmp)
                                }
                                val rule = DocumentRule(
                                    id = "sign_rule_${System.currentTimeMillis()}",
                                    documentType = selectedPreset.type,
                                    minKb = targetMinKb,
                                    maxKb = targetMaxKb,
                                    exactWidthPx = customWidthPx,
                                    exactHeightPx = customHeightPx,
                                    targetAspectRatio = customWidthPx.toFloat() / customHeightPx.toFloat(),
                                    requiresWhiteBackground = true
                                )
                                processedResult = ImageProcessor.processImageForRule(
                                    context = context,
                                    originalBitmap = bmp,
                                    rule = rule,
                                    autoCropMargins = autoCropBlankMargins
                                )
                                isProcessing = false
                                Toast.makeText(context, "Signature ready: ${String.format(Locale.US, "%.1f", processedResult?.fileSizeKb)} KB", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = !isProcessing,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).testTag("process_signature_btn")
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Processing...")
                        } else {
                            Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Process & Clean")
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

            // Results Card
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
                                text = "Signature Prepared Successfully!",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = EmeraldTertiary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Size: ${String.format(Locale.US, "%.1f", res.fileSizeKb)} KB (Allowed: $targetMinKb - $targetMaxKb KB)",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Dimensions: ${res.widthPx} x ${res.heightPx} px • Format: ${res.fileFormat.extension.uppercase()}",
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
                            modifier = Modifier.fillMaxWidth().testTag("share_signature_btn")
                        ) {
                            Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save / Export Signature")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}
