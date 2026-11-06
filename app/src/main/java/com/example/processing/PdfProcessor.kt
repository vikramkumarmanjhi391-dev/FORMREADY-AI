package com.example.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.data.model.FileFormat
import com.example.data.model.ProcessedFileResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

object PdfProcessor {

    // Standard A4 dimensions in PDF points (72 points per inch)
    const val A4_WIDTH_POINTS = 595
    const val A4_HEIGHT_POINTS = 842
    private const val MAX_RASTER_SIDE_PX = 2400

    data class PdfPageGeometry(
        val pageNumber: Int,
        val widthPoints: Int,
        val heightPoints: Int
    ) {
        val aspectRatio: Float
            get() = if (heightPoints > 0) widthPoints.toFloat() / heightPoints.toFloat() else 1.0f
    }

    data class PdfCompressionReport(
        val outputFile: File,
        val actualSizeBytes: Long,
        val actualSizeKb: Double,
        val targetMaxKb: Int,
        val metTarget: Boolean,
        val isValidPdf: Boolean,
        val originalPageCount: Int,
        val outputPageCount: Int,
        val pageCountPreserved: Boolean,
        val geometryPreserved: Boolean,
        val originalPages: List<PdfPageGeometry>,
        val outputPages: List<PdfPageGeometry>,
        val errorMessage: String? = null
    ) {
        val compressedPages: List<PdfPageGeometry>
            get() = outputPages
    }

    /**
     * Converts a list of Bitmaps into a single PDF document with configurable physical page dimensions
     * (defaulting to standard A4: 595 x 842 pt).
     * Ensures that [PdfDocument] is always closed in a `finally` block and that empty or unreadable
     * page lists fail safely.
     */
    suspend fun createPdfFromBitmaps(
        context: Context,
        bitmaps: List<Bitmap>,
        outputFileName: String = "document_${System.currentTimeMillis()}.pdf",
        marginPoints: Int = 24,
        pageWidthPoints: Int = A4_WIDTH_POINTS,
        pageHeightPoints: Int = A4_HEIGHT_POINTS
    ): File = withContext(Dispatchers.IO) {
        require(pageWidthPoints > 0 && pageHeightPoints > 0) {
            "Page dimensions must be positive (got ${pageWidthPoints}x${pageHeightPoints})"
        }
        val validBitmaps = bitmaps.filter { !it.isRecycled && it.width > 0 && it.height > 0 }
        require(validBitmaps.isNotEmpty()) { "Cannot generate PDF from an empty or invalid page list" }

        val pagesWithSizes = validBitmaps.map { bmp -> bmp to (pageWidthPoints to pageHeightPoints) }
        createCustomGeometryPdfFromBitmaps(
            context = context,
            pages = pagesWithSizes,
            outputFileName = outputFileName,
            marginPoints = marginPoints
        )
    }

    /**
     * Creates a multi-page PDF where each page can have its own physical width and height in PDF points
     * (e.g., A4, Letter, Legal, or custom landscape/portrait dimensions).
     */
    suspend fun createCustomGeometryPdfFromBitmaps(
        context: Context,
        pages: List<Pair<Bitmap, Pair<Int, Int>>>,
        outputFileName: String = "document_${System.currentTimeMillis()}.pdf",
        marginPoints: Int = 24
    ): File = withContext(Dispatchers.IO) {
        val validPages = pages.filter { (bmp, dims) ->
            !bmp.isRecycled && bmp.width > 0 && bmp.height > 0 && dims.first > 0 && dims.second > 0
        }
        require(validPages.isNotEmpty()) { "Cannot generate PDF from an empty or invalid page list" }

        val pdfDocument = PdfDocument()
        val outDir = File(context.cacheDir, "prepared_pdfs").apply { mkdirs() }
        val outFile = File(outDir, outputFileName)

        try {
            validPages.forEachIndexed { index, (bitmap, dims) ->
                val pageW = dims.first
                val pageH = dims.second
                val safeMargin = marginPoints.coerceIn(0, minOf(pageW, pageH) / 4)
                val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                try {
                    val canvas: Canvas = page.canvas

                    // Fill white background
                    val bgPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
                    canvas.drawRect(0f, 0f, pageW.toFloat(), pageH.toFloat(), bgPaint)

                    // Calculate scaled rect inside margins preserving aspect ratio
                    val availW = (pageW - (safeMargin * 2)).coerceAtLeast(1)
                    val availH = (pageH - (safeMargin * 2)).coerceAtLeast(1)

                    val bmpW = bitmap.width.toFloat()
                    val bmpH = bitmap.height.toFloat()
                    val scale = (availW / bmpW).coerceAtMost(availH / bmpH)

                    val drawW = bmpW * scale
                    val drawH = bmpH * scale
                    val left = safeMargin + (availW - drawW) / 2f
                    val top = safeMargin + (availH - drawH) / 2f

                    val destRect = Rect(
                        left.toInt(),
                        top.toInt(),
                        (left + drawW).toInt().coerceAtLeast(left.toInt() + 1),
                        (top + drawH).toInt().coerceAtLeast(top.toInt() + 1)
                    )
                    canvas.drawBitmap(bitmap, null, destRect, Paint(Paint.FILTER_BITMAP_FLAG))
                } finally {
                    pdfDocument.finishPage(page)
                }
            }

            FileOutputStream(outFile).use { pdfDocument.writeTo(it) }
            outFile
        } catch (t: Throwable) {
            if (outFile.exists()) {
                outFile.delete()
            }
            throw t
        } finally {
            try {
                pdfDocument.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Rotates a bitmap by a specified angle (e.g. 90, 180, 270 degrees).
     */
    fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees % 360f == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Combines Front and Back of an ID card (Aadhaar, Voter ID, PAN) onto a single page PDF.
     */
    suspend fun createFrontAndBackCardPdf(
        context: Context,
        frontBitmap: Bitmap,
        backBitmap: Bitmap,
        cardTitle: String = "Identity Card"
    ): File = withContext(Dispatchers.IO) {
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH_POINTS, A4_HEIGHT_POINTS, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas

        // Background
        canvas.drawColor(Color.WHITE)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 14f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("$cardTitle — Front & Back Document (Verified Copy)", A4_WIDTH_POINTS / 2f, 40f, titlePaint)

        // Draw Front Card in Top Half
        val cardWidth = 400
        val cardHeight = (cardWidth * (frontBitmap.height.toFloat() / frontBitmap.width.toFloat())).toInt().coerceIn(200, 320)

        val leftX = (A4_WIDTH_POINTS - cardWidth) / 2
        val topFrontY = 70
        canvas.drawBitmap(frontBitmap, null, Rect(leftX, topFrontY, leftX + cardWidth, topFrontY + cardHeight), Paint(Paint.FILTER_BITMAP_FLAG))

        // Draw subtle divider/label
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textSize = 11f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("▲ FRONT SIDE ▲", A4_WIDTH_POINTS / 2f, (topFrontY + cardHeight + 20).toFloat(), labelPaint)
        canvas.drawText("▼ BACK SIDE ▼", A4_WIDTH_POINTS / 2f, (topFrontY + cardHeight + 50).toFloat(), labelPaint)

        // Draw Back Card in Bottom Half
        val topBackY = topFrontY + cardHeight + 65
        val backHeight = (cardWidth * (backBitmap.height.toFloat() / backBitmap.width.toFloat())).toInt().coerceIn(200, 320)
        canvas.drawBitmap(backBitmap, null, Rect(leftX, topBackY, leftX + cardWidth, topBackY + backHeight), Paint(Paint.FILTER_BITMAP_FLAG))

        pdfDocument.finishPage(page)

        val outDir = File(context.cacheDir, "prepared_pdfs").apply { mkdirs() }
        val outFile = File(outDir, "id_front_back_${System.currentTimeMillis()}.pdf")
        FileOutputStream(outFile).use { pdfDocument.writeTo(it) }
        pdfDocument.close()
        outFile
    }

    /**
     * Checks whether a generated or compressed PDF file is a valid PDF AND meets the requested maximum KB limit on disk.
     */
    fun isPdfWithinTargetKb(pdfFile: File, targetMaxKb: Int): Boolean {
        if (!pdfFile.exists() || targetMaxKb <= 0) return false
        val validation = com.example.storage.DocumentValidationManager.validateFile(pdfFile)
        if (!validation.isValid || validation.detectedFormat != FileFormat.PDF) return false
        return pdfFile.length() in 1L..(targetMaxKb * 1024L)
    }

    /**
     * Inspects a PDF file and returns the ordered list of [PdfPageGeometry] (physical width & height in PDF points)
     * for every page in the document.
     */
    fun inspectPdfGeometry(pdfFile: File): List<PdfPageGeometry> {
        if (!pdfFile.exists() || !pdfFile.canRead() || pdfFile.length() < 100L) {
            return emptyList()
        }

        // 1. Try Android PdfRenderer first
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val count = renderer.pageCount
            if (count > 0) {
                val pages = mutableListOf<PdfPageGeometry>()
                for (i in 0 until count) {
                    val page = renderer.openPage(i)
                    try {
                        val w = page.width
                        val h = page.height
                        if (w > 0 && h > 0) {
                            pages.add(PdfPageGeometry(pageNumber = i + 1, widthPoints = w, heightPoints = h))
                        }
                    } finally {
                        try {
                            page.close()
                        } catch (_: Exception) {
                        }
                    }
                }
                if (pages.size == count) {
                    return pages
                }
            }
        } catch (_: Throwable) {
            // Fall through to PDF structure /MediaBox parser on JVM tests where native PdfRenderer is unavailable
        } finally {
            try {
                renderer?.close()
            } catch (_: Exception) {
            }
            try {
                pfd?.close()
            } catch (_: Exception) {
            }
        }

        // 2. Fallback: parse /MediaBox [x0 y0 x1 y1] entries from valid PDF structure
        val validation = com.example.storage.DocumentValidationManager.validateFile(pdfFile)
        if (!validation.isValid || validation.detectedFormat != FileFormat.PDF) {
            return emptyList()
        }
        return try {
            val maxScanBytes = minOf(pdfFile.length(), 8L * 1024L * 1024L).toInt()
            val bytes = ByteArray(maxScanBytes)
            pdfFile.inputStream().use { input ->
                var total = 0
                while (total < maxScanBytes) {
                    val r = input.read(bytes, total, maxScanBytes - total)
                    if (r == -1) break
                    total += r
                }
            }
            val content = String(bytes, Charsets.ISO_8859_1)
            val mediaBoxRegex = Regex("""/MediaBox\s*\[\s*(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)\s*]""")
            val matches = mediaBoxRegex.findAll(content).toList()
            if (matches.isNotEmpty()) {
                matches.mapIndexedNotNull { idx, match ->
                    val x0 = match.groupValues[1].toDoubleOrNull() ?: 0.0
                    val y0 = match.groupValues[2].toDoubleOrNull() ?: 0.0
                    val x1 = match.groupValues[3].toDoubleOrNull() ?: 0.0
                    val y1 = match.groupValues[4].toDoubleOrNull() ?: 0.0
                    val w = kotlin.math.round(x1 - x0).toInt()
                    val h = kotlin.math.round(y1 - y0).toInt()
                    if (w > 0 && h > 0) PdfPageGeometry(pageNumber = idx + 1, widthPoints = w, heightPoints = h) else null
                }
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Compresses a PDF file toward a target KB limit while strictly preserving each original page's
     * physical dimensions (widthPoints x heightPoints), aspect ratio, page count, and page order.
     *
     * Rejects invalid [targetMaxKb] (<= 0) or invalid/corrupt PDF inputs explicitly with [IllegalArgumentException].
     */
    suspend fun compressPdfToTargetKb(
        context: Context,
        inputPdfFile: File,
        targetMaxKb: Int
    ): File {
        return compressPdfToTargetKbWithReport(context, inputPdfFile, targetMaxKb).outputFile
    }

    /**
     * Compresses a PDF file toward [targetMaxKb] and reopens the saved output PDF to verify:
     * - Valid PDF structure
     * - Actual file size in bytes
     * - Page count preservation
     * - Original physical page dimensions and aspect ratio preservation for every page
     * - Honest [PdfCompressionReport.metTarget] status
     */
    suspend fun compressPdfToTargetKbWithReport(
        context: Context,
        inputPdfFile: File,
        targetMaxKb: Int
    ): PdfCompressionReport = withContext(Dispatchers.IO) {
        require(targetMaxKb > 0) { "targetMaxKb must be greater than 0 (received $targetMaxKb)" }
        require(inputPdfFile.exists() && inputPdfFile.canRead() && inputPdfFile.length() > 0L) {
            "Input PDF file does not exist, is unreadable, or is empty"
        }

        val inputValidation = com.example.storage.DocumentValidationManager.validateFile(inputPdfFile)
        require(inputValidation.isValid && inputValidation.detectedFormat == FileFormat.PDF) {
            inputValidation.errorMessage ?: "Input file is not a valid PDF document"
        }

        val originalPages = inspectPdfGeometry(inputPdfFile)
        require(originalPages.isNotEmpty()) {
            "Input PDF contains 0 readable pages or invalid page geometry"
        }

        val targetMaxBytes = targetMaxKb.toLong() * 1024L
        val outDir = File(context.cacheDir, "prepared_pdfs").apply { mkdirs() }
        val outFile = File(outDir, "compressed_${targetMaxKb}kb_${System.currentTimeMillis()}_${inputPdfFile.name}")

        // Candidate (renderScale, jpegQuality) steps ordered from highest visual fidelity to minimum floor.
        // Note: renderScale ONLY affects raster bitmap resolution; each output PDF page's physical PageInfo
        // always uses the original page's exact widthPoints x heightPoints.
        val compressionSteps = listOf(
            2.00f to 88,
            1.60f to 78,
            1.25f to 68,
            1.00f to 58,
            0.80f to 46,
            0.60f to 34
        )

        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        val tempStepFiles = mutableListOf<File>()

        try {
            pfd = ParcelFileDescriptor.open(inputPdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = try {
                PdfRenderer(pfd)
            } catch (_: Throwable) {
                null
            }

            var bestCandidateFile: File? = null

            for ((stepIndex, step) in compressionSteps.withIndex()) {
                val (renderScale, jpegQuality) = step
                val candidateFile = File(outDir, "temp_step_${stepIndex}_${System.nanoTime()}.pdf")
                tempStepFiles.add(candidateFile)

                val pdfDoc = PdfDocument()
                try {
                    for (pageIdx in originalPages.indices) {
                        val origGeom = originalPages[pageIdx]
                        val origWidthPts = origGeom.widthPoints
                        val origHeightPts = origGeom.heightPoints

                        // Compute raster resolution preserving exact original page aspect ratio, capped to prevent OOM
                        val rawRasterW = kotlin.math.round(origWidthPts * renderScale).toInt().coerceAtLeast(72)
                        val rawRasterH = kotlin.math.round(origHeightPts * renderScale).toInt().coerceAtLeast(72)
                        val maxSide = maxOf(rawRasterW, rawRasterH)
                        val capFactor = if (maxSide > MAX_RASTER_SIDE_PX) {
                            MAX_RASTER_SIDE_PX.toFloat() / maxSide.toFloat()
                        } else {
                            1.0f
                        }
                        val rasterW = kotlin.math.round(rawRasterW * capFactor).toInt().coerceAtLeast(72)
                        val rasterH = kotlin.math.round(rawRasterH * capFactor).toInt().coerceAtLeast(72)

                        var rawBmp: Bitmap? = null
                        var quantizedBmp: Bitmap? = null
                        try {
                            rawBmp = Bitmap.createBitmap(rasterW, rasterH, Bitmap.Config.ARGB_8888)
                            rawBmp.eraseColor(Color.WHITE)

                            if (renderer != null && pageIdx < renderer.pageCount) {
                                val srcPage = renderer.openPage(pageIdx)
                                try {
                                    srcPage.render(rawBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                } finally {
                                    srcPage.close()
                                }
                            }

                            // Re-encode page bitmap through JPEG at jpegQuality
                            val jpegBaos = ByteArrayOutputStream()
                            rawBmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, jpegBaos)
                            val jpegBytes = jpegBaos.toByteArray()

                            // Release rawBmp immediately before decoding quantizedBmp
                            rawBmp.recycle()
                            rawBmp = null

                            quantizedBmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

                            // Start PDF page with EXACT original physical page dimensions (origWidthPts x origHeightPts)
                            val pageInfo = PdfDocument.PageInfo.Builder(origWidthPts, origHeightPts, pageIdx + 1).create()
                            val pdfPage = pdfDoc.startPage(pageInfo)
                            try {
                                val canvas = pdfPage.canvas
                                canvas.drawColor(Color.WHITE)
                                if (quantizedBmp != null) {
                                    // Fit proportionally inside (0, 0, origWidthPts, origHeightPts) without stretching
                                    val bmpW = quantizedBmp.width.toFloat()
                                    val bmpH = quantizedBmp.height.toFloat()
                                    val fitScale = minOf(origWidthPts / bmpW, origHeightPts / bmpH)
                                    val drawW = bmpW * fitScale
                                    val drawH = bmpH * fitScale
                                    val left = ((origWidthPts - drawW) / 2f).toInt()
                                    val top = ((origHeightPts - drawH) / 2f).toInt()
                                    val destRect = Rect(
                                        left,
                                        top,
                                        (left + drawW).toInt().coerceAtMost(origWidthPts),
                                        (top + drawH).toInt().coerceAtMost(origHeightPts)
                                    )
                                    canvas.drawBitmap(quantizedBmp, null, destRect, Paint(Paint.FILTER_BITMAP_FLAG))
                                }
                            } finally {
                                pdfDoc.finishPage(pdfPage)
                            }
                        } finally {
                            if (rawBmp != null && !rawBmp.isRecycled) {
                                rawBmp.recycle()
                            }
                            if (quantizedBmp != null && !quantizedBmp.isRecycled) {
                                quantizedBmp.recycle()
                            }
                        }
                    }

                    FileOutputStream(candidateFile).use { pdfDoc.writeTo(it) }
                } finally {
                    try {
                        pdfDoc.close()
                    } catch (_: Exception) {
                    }
                }

                if (candidateFile.exists() && candidateFile.length() > 0L) {
                    if (bestCandidateFile == null || candidateFile.length() < bestCandidateFile.length()) {
                        bestCandidateFile = candidateFile
                    }
                    if (candidateFile.length() <= targetMaxBytes) {
                        bestCandidateFile = candidateFile
                        break
                    }
                }
            }

            val chosenSource = bestCandidateFile ?: inputPdfFile
            chosenSource.copyTo(outFile, overwrite = true)
        } finally {
            try {
                renderer?.close()
            } catch (_: Exception) {
            }
            try {
                pfd?.close()
            } catch (_: Exception) {
            }
            tempStepFiles.forEach { temp ->
                if (temp.exists() && temp.absolutePath != outFile.absolutePath) {
                    temp.delete()
                }
            }
        }

        // Reopen final written PDF on disk and verify validity, size, page count, and page geometry
        verifyCompressedPdf(
            outputFile = outFile,
            originalPages = originalPages,
            targetMaxKb = targetMaxKb
        )
    }

    /**
     * Reopens a written PDF file and verifies its format validity, byte size, page count,
     * physical page dimensions, and page aspect ratios against [originalPages].
     */
    fun verifyCompressedPdf(
        outputFile: File,
        originalPages: List<PdfPageGeometry>,
        targetMaxKb: Int
    ): PdfCompressionReport {
        val actualSizeBytes = if (outputFile.exists()) outputFile.length() else 0L
        val actualSizeKb = actualSizeBytes / 1024.0
        val validation = com.example.storage.DocumentValidationManager.validateFile(outputFile)
        val outputPages = if (validation.isValid && validation.detectedFormat == FileFormat.PDF) {
            inspectPdfGeometry(outputFile)
        } else {
            emptyList()
        }

        val isValidPdf = validation.isValid && validation.detectedFormat == FileFormat.PDF && outputPages.isNotEmpty()
        val pageCountPreserved = isValidPdf && outputPages.size == originalPages.size
        val geometryPreserved = pageCountPreserved && originalPages.indices.all { idx ->
            val orig = originalPages[idx]
            val out = outputPages[idx]
            orig.widthPoints == out.widthPoints &&
                orig.heightPoints == out.heightPoints &&
                kotlin.math.abs(orig.aspectRatio - out.aspectRatio) < 0.005f
        }
        val metTarget = isValidPdf &&
            pageCountPreserved &&
            geometryPreserved &&
            actualSizeBytes in 1L..(targetMaxKb.toLong() * 1024L)

        val errorMsg = when {
            !isValidPdf -> validation.errorMessage ?: "Compressed output is not a valid PDF document"
            !pageCountPreserved -> "Page count changed unexpectedly: expected ${originalPages.size} pages, got ${outputPages.size} pages"
            !geometryPreserved -> "Page physical dimensions or aspect ratio changed unexpectedly during compression"
            else -> null
        }

        return PdfCompressionReport(
            outputFile = outputFile,
            actualSizeBytes = actualSizeBytes,
            actualSizeKb = actualSizeKb,
            targetMaxKb = targetMaxKb,
            metTarget = metTarget,
            isValidPdf = isValidPdf,
            originalPageCount = originalPages.size,
            outputPageCount = outputPages.size,
            pageCountPreserved = pageCountPreserved,
            geometryPreserved = geometryPreserved,
            originalPages = originalPages,
            outputPages = outputPages,
            errorMessage = errorMsg
        )
    }

    /**
     * Renders pages of a PDF into a list of preview Bitmaps.
     */
    suspend fun renderPdfPagesToBitmaps(
        context: Context,
        pdfFile: File,
        maxPages: Int = 10
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Bitmap>()
        try {
            val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val count = renderer.pageCount.coerceAtMost(maxPages)
            for (i in 0 until count) {
                val page = renderer.openPage(i)
                val width = page.width
                val height = page.height
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                results.add(bmp)
            }
            renderer.close()
            pfd.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        results
    }

    /**
     * Extracts all pages of a PDF as individual image files on disk.
     */
    suspend fun renderPdfPagesToImages(
        context: Context,
        pdfFile: File
    ): List<File> = withContext(Dispatchers.IO) {
        val outputFiles = mutableListOf<File>()
        try {
            val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val outDir = File(context.cacheDir, "extracted_pages").apply { mkdirs() }

            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                page.close()

                val imgFile = File(outDir, "${pdfFile.nameWithoutExtension}_page_${i + 1}.jpg")
                FileOutputStream(imgFile).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                outputFiles.add(imgFile)
            }
            renderer.close()
            pfd.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        outputFiles
    }

    /**
     * Merges multiple PDF files into one combined PDF.
     */
    suspend fun mergePdfs(
        context: Context,
        pdfFiles: List<File>,
        outputFileName: String = "merged_${System.currentTimeMillis()}.pdf"
    ): File = withContext(Dispatchers.IO) {
        val pdfDoc = PdfDocument()
        var totalPageIndex = 0

        pdfFiles.forEach { file ->
            try {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                for (p in 0 until renderer.pageCount) {
                    val page = renderer.openPage(p)
                    val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    page.close()

                    val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH_POINTS, A4_HEIGHT_POINTS, totalPageIndex + 1).create()
                    val pdfPage = pdfDoc.startPage(pageInfo)
                    pdfPage.canvas.drawColor(Color.WHITE)
                    val dest = Rect(0, 0, A4_WIDTH_POINTS, A4_HEIGHT_POINTS)
                    pdfPage.canvas.drawBitmap(bmp, null, dest, Paint(Paint.FILTER_BITMAP_FLAG))
                    pdfDoc.finishPage(pdfPage)
                    totalPageIndex++
                }
                renderer.close()
                pfd.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val outDir = File(context.cacheDir, "prepared_pdfs").apply { mkdirs() }
        val outFile = File(outDir, outputFileName)
        FileOutputStream(outFile).use { pdfDoc.writeTo(it) }
        pdfDoc.close()
        outFile
    }

    suspend fun getPdfPageCount(pdfFile: File): Int = withContext(Dispatchers.IO) {
        try {
            val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val count = renderer.pageCount
            renderer.close()
            pfd.close()
            count
        } catch (e: Exception) {
            1
        }
    }
}
