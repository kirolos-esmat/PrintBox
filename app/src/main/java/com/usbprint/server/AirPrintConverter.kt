package com.usbprint.server

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.BufferUnderflowException

internal data class ConversionResult(
    val zjsFile: File,
    val stats: PbmStats,
    val documentType: String
)

object AirPrintConverter {

    /**
     * Converts an incoming PDF, JPEG/PNG/WEBP image, PWG Raster, or URF document into an HP LaserJet Pro P1102 ZjStream (.zjs) file.
     * Returns the resulting [ConversionResult] or null if conversion failed.
     *
     * Synchronized because the underlying foo2zjs C encoder uses non-reentrant process-wide globals.
     */
    @Synchronized
    internal fun convertDocumentToZjs(
        context: Context,
        docBytes: ByteArray,
        paperCode: Int = 9 // 9 = A4, 1 = Letter
    ): ConversionResult? {
        val cacheDir = context.cacheDir
        val timestamp = System.currentTimeMillis()
        val pbmFile = File(cacheDir, "airprint_${timestamp}.pbm")
        val zjsFile = File(cacheDir, "airprint_${timestamp}.zjs")

        val isPdf = docBytes.size >= 4 &&
                docBytes[0] == '%'.code.toByte() &&
                docBytes[1] == 'P'.code.toByte() &&
                docBytes[2] == 'D'.code.toByte() &&
                docBytes[3] == 'F'.code.toByte()

        val (docType, stats) = when {
            isPdf -> "PDF" to convertPdfToPbm(context, docBytes, pbmFile)
            RasterToPbm.supports(docBytes) -> {
                val label = if (docBytes.size >= 4 && docBytes[0] == 'U'.code.toByte()) "URF" else "PWG"
                label to convertRasterToPbm(docBytes, pbmFile)
            }
            isSupportedImage(docBytes) -> "Image" to convertImageToPbm(docBytes, paperCode, pbmFile)
            else -> {
                AppState.log("Unsupported print document header", isError = true)
                "Unknown" to null
            }
        }

        if (stats == null || !pbmFile.exists() || pbmFile.length() == 0L) {
            AppState.log("Rasterization to PBM failed", isError = true)
            pbmFile.delete()
            return null
        }

        AppState.log("Rasterization complete (${pbmFile.length()} bytes PBM). Encoding ZJS...")
        val encoded = ZjsNative.convertPbmToZjs(
            pbmPath = pbmFile.absolutePath,
            zjsPath = zjsFile.absolutePath,
            paperCode = paperCode,
            copies = 1
        )

        pbmFile.delete()

        return if (encoded && zjsFile.exists() && zjsFile.length() > 0) {
            AppState.log("AirPrint job ready: ${zjsFile.length()} bytes ZJS encoded (${stats.pageCount}p, ${RefillTracker.formatPercent(stats.coveragePercent)}% ink).")
            ConversionResult(zjsFile, stats, docType)
        } else {
            AppState.log("ZJS encoding failed", isError = true)
            zjsFile.delete()
            null
        }
    }

    internal fun isSupportedImage(bytes: ByteArray): Boolean {
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) return true
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) return true
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) return true
        return false
    }

    private const val MAX_PRINT_WIDTH = 5104
    private const val MAX_PRINT_HEIGHT = 7020
    private const val MAX_PDF_PAGES = 100
    private const val MAX_PBM_BYTES = 128L * 1024 * 1024

    private fun convertPdfToPbm(context: Context, pdfBytes: ByteArray, outputFile: File): PbmStats? {
        val tempPdf = File(context.cacheDir, "temp_${System.currentTimeMillis()}.pdf")
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var reusableBitmap: Bitmap? = null
        var renderedPages = 0
        var totalDarkPixels = 0L
        var totalPixels = 0L
        var totalPbmBytes = 0L
        return try {
            tempPdf.writeBytes(pdfBytes)
            pfd = ParcelFileDescriptor.open(tempPdf, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val pageCount = renderer.pageCount
            if (pageCount <= 0 || pageCount > MAX_PDF_PAGES) {
                AppState.log("Rejected PDF with $pageCount pages (limit $MAX_PDF_PAGES)", isError = true)
                return null
            }
            AppState.log("Rendering PDF with $pageCount page(s)...")

            BufferedOutputStream(FileOutputStream(outputFile), 65536).use { out ->
                // Render up to 600 DPI (scale factor: 600 / 72 = 8.3333f), clamped to printable page bounds
                val baseScale = 600f / 72f

                for (pageIndex in 0 until pageCount) {
                    val page = renderer.openPage(pageIndex)
                    try {
                        val landscape = page.width > page.height
                        val srcWidth = if (landscape) page.height else page.width
                        val srcHeight = if (landscape) page.width else page.height
                        if (srcWidth <= 0 || srcHeight <= 0) continue

                        val scale = minOf(
                            baseScale,
                            MAX_PRINT_WIDTH.toFloat() / srcWidth,
                            MAX_PRINT_HEIGHT.toFloat() / srcHeight
                        )
                        // Align width to multiple of 8 for clean PBM byte rows
                        var targetWidth = ((srcWidth * scale).toInt() + 7) and 7.inv()
                        targetWidth = targetWidth.coerceIn(8, MAX_PRINT_WIDTH)
                        val targetHeight = (srcHeight * scale).toInt().coerceIn(1, MAX_PRINT_HEIGHT)
                        val estimatedPbmBytes = 24L + (targetWidth / 8L) * targetHeight
                        if (totalPbmBytes + estimatedPbmBytes > MAX_PBM_BYTES) {
                            throw IOException("PDF raster exceeded ${MAX_PBM_BYTES / (1024 * 1024)} MB PBM limit")
                        }
                        totalPbmBytes += estimatedPbmBytes

                        val bitmap = if (reusableBitmap != null &&
                            reusableBitmap!!.width == targetWidth &&
                            reusableBitmap!!.height == targetHeight
                        ) {
                            reusableBitmap!!.eraseColor(Color.WHITE)
                            reusableBitmap!!
                        } else {
                            reusableBitmap?.recycle()
                            Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also {
                                reusableBitmap = it
                            }
                        }

                        val canvas = Canvas(bitmap)
                        canvas.drawColor(Color.WHITE)

                        val matrix = if (landscape || scale != baseScale) {
                            Matrix().apply {
                                if (landscape) {
                                    postRotate(90f)
                                    postTranslate(page.height.toFloat(), 0f)
                                }
                                postScale(targetWidth.toFloat() / srcWidth, targetHeight.toFloat() / srcHeight)
                            }
                        } else null

                        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val pageStats = writeBitmapToPbm(bitmap, out)
                        renderedPages++
                        totalDarkPixels += pageStats.darkPixels
                        totalPixels += pageStats.totalPixels
                        AppState.log(
                            "Rendered page ${pageIndex + 1}/$pageCount ($targetWidth x $targetHeight${if (landscape) ", rotated" else ""})"
                        )
                    } finally {
                        page.close()
                    }
                }
                out.flush()
            }
            if (renderedPages > 0) PbmStats(renderedPages, totalDarkPixels, totalPixels) else null
        } catch (e: Throwable) {
            outputFile.delete()
            AppState.log("PDF render error: ${e.message}", isError = true)
            null
        } finally {
            reusableBitmap?.recycle()
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            tempPdf.delete()
        }
    }

    private fun convertImageToPbm(imageBytes: ByteArray, paperCode: Int, outputFile: File): PbmStats? {
        var decoded: Bitmap? = null
        var pageBitmap: Bitmap? = null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val pageWidth = if (paperCode == 1) 5104 else 4960
            val pageHeight = if (paperCode == 1) 6600 else 7016

            var sampleSize = 1
            val maxSrc = maxOf(bounds.outWidth, bounds.outHeight)
            val maxDst = maxOf(pageWidth, pageHeight)
            while (maxSrc / (sampleSize * 2) >= maxDst) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOpts) ?: return null

            val exifRotation = readExifRotationDegrees(imageBytes)
            val orientedWidth = if (exifRotation == 90 || exifRotation == 270) decoded.height else decoded.width
            val orientedHeight = if (exifRotation == 90 || exifRotation == 270) decoded.width else decoded.height
            val autoRotateLandscape = orientedWidth > orientedHeight
            val totalRotation = (exifRotation + if (autoRotateLandscape) 90 else 0) % 360

            val margin = 140
            val availW = (pageWidth - margin * 2).toFloat()
            val availH = (pageHeight - margin * 2).toFloat()
            val finalSrcW = if (totalRotation == 90 || totalRotation == 270) decoded.height else decoded.width
            val finalSrcH = if (totalRotation == 90 || totalRotation == 270) decoded.width else decoded.height
            val scale = minOf(availW / finalSrcW, availH / finalSrcH)

            pageBitmap = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(pageBitmap)
            canvas.drawColor(Color.WHITE)

            val matrix = Matrix().apply {
                postTranslate(-decoded.width / 2f, -decoded.height / 2f)
                if (totalRotation != 0) postRotate(totalRotation.toFloat())
                postScale(scale, scale)
                postTranslate(pageWidth / 2f, pageHeight / 2f)
            }
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(decoded, matrix, paint)

            BufferedOutputStream(FileOutputStream(outputFile), 65536).use { out ->
                val stats = writeBitmapToPbm(pageBitmap, out)
                out.flush()
                AppState.log("Rendered image ($finalSrcW x $finalSrcH -> $pageWidth x $pageHeight)")
                stats
            }
        } catch (e: Throwable) {
            AppState.log("Image render error: ${e.message}", isError = true)
            null
        } finally {
            pageBitmap?.recycle()
            decoded?.recycle()
        }
    }

    private fun readExifRotationDegrees(imageBytes: ByteArray): Int {
        return try {
            val exif = ExifInterface(ByteArrayInputStream(imageBytes))
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun writeBitmapToPbm(bitmap: Bitmap, out: BufferedOutputStream): PbmStats {
        val width = bitmap.width
        val height = bitmap.height
        val header = "P4\n$width $height\n"
        out.write(header.toByteArray(Charsets.US_ASCII))

        val bpl = (width + 7) / 8
        val rowBytes = ByteArray(bpl)
        val pixels = IntArray(width)
        var currErr = IntArray(width + 2)
        var nextErr = IntArray(width + 2)
        var darkPixels = 0L

        for (y in 0 until height) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
            nextErr.fill(0)
            darkPixels += ditherArgbRowToPbm(pixels, width, currErr, nextErr, rowBytes)
            out.write(rowBytes)

            val swap = currErr
            currErr = nextErr
            nextErr = swap
        }
        return PbmStats(1, darkPixels, width.toLong() * height)
    }

    /**
     * Converts one ARGB_8888 scanline into packed 1-bit PBM bytes using contrast-guarded
     * Floyd-Steinberg dithering: near-black text stays solid, off-white margins stay clean,
     * and intermediate grays diffuse smoothly instead of dropping out at a hard cutoff.
     */
    internal fun ditherArgbRowToPbm(
        pixels: IntArray,
        width: Int,
        currErr: IntArray,
        nextErr: IntArray,
        rowBytes: ByteArray
    ): Long {
        return HalftoneDither.ditherRowToPbm(width, currErr, nextErr, rowBytes) { x ->
            val pixel = pixels[x]
            HalftoneDither.rgbLuminance(
                (pixel shr 16) and 0xFF,
                (pixel shr 8) and 0xFF,
                pixel and 0xFF
            )
        }
    }

    private fun convertRasterToPbm(document: ByteArray, output: File): PbmStats? {
        return try {
            output.outputStream().buffered().use { RasterToPbm.convert(document, it) }
        } catch (e: BufferUnderflowException) {
            AppState.log("Incomplete raster document", isError = true)
            null
        } catch (e: IllegalArgumentException) {
            AppState.log("Invalid raster document: ${e.message}", isError = true)
            null
        } catch (e: IOException) {
            AppState.log("Could not write raster document: ${e.message}", isError = true)
            null
        }
    }
}
