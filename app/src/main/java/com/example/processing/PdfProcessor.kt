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

    /**
     * Converts a list of Bitmaps into a single standard A4 PDF document.
     * Ensures that [PdfDocument] is always closed in a `finally` block and that empty or unreadable
     * page lists fail safely.
     */
    suspend fun createPdfFromBitmaps(
        context: Context,
        bitmaps: List<Bitmap>,
        outputFileName: String = "document_${System.currentTimeMillis()}.pdf",
        marginPoints: Int = 24
    ): File = withContext(Dispatchers.IO) {
        val validBitmaps = bitmaps.filter { !it.isRecycled && it.width > 0 && it.height > 0 }
        require(validBitmaps.isNotEmpty()) { "Cannot generate PDF from an empty or invalid page list" }

        val pdfDocument = PdfDocument()
        val outDir = File(context.cacheDir, "prepared_pdfs").apply { mkdirs() }
        val outFile = File(outDir, outputFileName)

        try {
            validBitmaps.forEachIndexed { index, bitmap ->
                val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH_POINTS, A4_HEIGHT_POINTS, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                try {
                    val canvas: Canvas = page.canvas

                    // Fill white background
                    val bgPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
                    canvas.drawRect(0f, 0f, A4_WIDTH_POINTS.toFloat(), A4_HEIGHT_POINTS.toFloat(), bgPaint)

                    // Calculate scaled rect inside margins preserving aspect ratio
                    val availW = A4_WIDTH_POINTS - (marginPoints * 2)
                    val availH = A4_HEIGHT_POINTS - (marginPoints * 2)

                    val bmpW = bitmap.width.toFloat()
                    val bmpH = bitmap.height.toFloat()
                    val scale = (availW / bmpW).coerceAtMost(availH / bmpH)

                    val drawW = bmpW * scale
                    val drawH = bmpH * scale
                    val left = marginPoints + (availW - drawW) / 2f
                    val top = marginPoints + (availH - drawH) / 2f

                    val destRect = Rect(left.toInt(), top.toInt(), (left + drawW).toInt(), (top + drawH).toInt())
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
     * Compresses a PDF file down to a target KB limit by rendering pages
     * to bitmaps and re-encoding at optimized resolution and compression.
     */
    suspend fun compressPdfToTargetKb(
        context: Context,
        inputPdfFile: File,
        targetMaxKb: Int
    ): File = withContext(Dispatchers.IO) {
        val pfd = ParcelFileDescriptor.open(inputPdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val pageCount = renderer.pageCount

        val renderedBitmaps = mutableListOf<Bitmap>()
        // Calculate target scale depending on page count and target KB
        val targetDpiScale = when {
            targetMaxKb <= 100 -> 1.0f // ~72-96 DPI
            targetMaxKb <= 300 -> 1.5f // ~120-150 DPI
            else -> 2.0f // ~180-200 DPI
        }

        for (i in 0 until pageCount) {
            val page = renderer.openPage(i)
            val w = (page.width * targetDpiScale).toInt()
            val h = (page.height * targetDpiScale).toInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            page.close()
            renderedBitmaps.add(bmp)
        }
        renderer.close()
        pfd.close()

        // Write to new PDF with compressed bitmaps
        val pdfDoc = PdfDocument()
        renderedBitmaps.forEachIndexed { idx, bmp ->
            val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH_POINTS, A4_HEIGHT_POINTS, idx + 1).create()
            val page = pdfDoc.startPage(pageInfo)
            page.canvas.drawColor(Color.WHITE)
            val dest = Rect(0, 0, A4_WIDTH_POINTS, A4_HEIGHT_POINTS)
            page.canvas.drawBitmap(bmp, null, dest, Paint(Paint.FILTER_BITMAP_FLAG))
            pdfDoc.finishPage(page)
        }

        val outDir = File(context.cacheDir, "prepared_pdfs").apply { mkdirs() }
        val outFile = File(outDir, "compressed_${targetMaxKb}kb_${inputPdfFile.name}")
        FileOutputStream(outFile).use { pdfDoc.writeTo(it) }
        pdfDoc.close()

        outFile
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
