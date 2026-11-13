package com.usbprint.server

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Generates a 1-page A4 PDF self-test sheet for checking toner darkness, drum uniformity, and dithering. */
object TestPageGenerator {

    fun buildA4TestPdf(): ByteArray {
        val doc = PdfDocument()
        val pageWidth = 595 // A4 points (72 DPI)
        val pageHeight = 842
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = doc.startPage(pageInfo)
        val canvas = page.canvas

        canvas.drawColor(Color.WHITE)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 20f
            isFakeBoldText = true
        }
        val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 13f
            isFakeBoldText = true
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 11f
        }
        val fillPaint = Paint()
        val strokePaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }

        val left = 42f
        val right = pageWidth - 42f
        var y = 56f

        canvas.drawText("USB Print Server — HP LaserJet P1102 Test Page", left, y, titlePaint)
        y += 18f
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        canvas.drawText("Printed at: $now  |  Endpoint: ${AppState.networkAddress.value}", left, y, bodyPaint)
        y += 12f
        fillPaint.color = Color.BLACK
        canvas.drawRect(left, y, right, y + 3f, fillPaint)
        y += 24f

        val supply = AppState.supplyStatus.value
        val refill = AppState.refillState.value
        val chipText = if (supply.available) {
            "${supply.name}: ${supply.percentRemaining}% (${supply.chipState}${if (supply.lowReached) "/Low" else ""}), Lifetime Pages: ${supply.pageCount}"
        } else {
            "Supply chip unavailable"
        }
        canvas.drawText("Printer Info: ${AppState.printerInfo.value}", left, y, bodyPaint)
        y += 16f
        canvas.drawText(chipText, left, y, bodyPaint)
        if (refill.enabled && supply.pageCount >= 0) {
            y += 16f
            val est = RefillTracker.estimateRemainingPercent(supply.pageCount, refill.baselinePages)
            val used = RefillTracker.pagesSinceRefill(supply.pageCount, refill.baselinePages)
            canvas.drawText("Local Refill Estimate: $est% ($used pages since baseline ${refill.baselinePages})", left, y, bodyPaint)
        }
        y += 28f

        // Full-width solid black drum-check bar (reveals repeating OPC drum voids immediately)
        canvas.drawText("1. Solid Black Drum & Toner Uniformity Bar (Full Width)", left, y, headingPaint)
        y += 10f
        fillPaint.color = Color.BLACK
        canvas.drawRect(left, y, right, y + 48f, fillPaint)
        y += 74f

        // Grayscale Floyd-Steinberg dithering check ramp
        canvas.drawText("2. Grayscale Dithering Check (90%, 70%, 50%, 30%, 15%)", left, y, headingPaint)
        y += 10f
        val shades = intArrayOf(26, 76, 128, 179, 217)
        val labels = arrayOf("90%", "70%", "50%", "30%", "15%")
        val boxW = (right - left) / shades.size
        for (i in shades.indices) {
            val bx = left + i * boxW
            val v = shades[i]
            fillPaint.color = Color.rgb(v, v, v)
            canvas.drawRect(bx, y, bx + boxW - 6f, y + 38f, fillPaint)
            canvas.drawRect(bx, y, bx + boxW - 6f, y + 38f, strokePaint)
            canvas.drawText(labels[i], bx + 8f, y + 52f, bodyPaint)
        }
        y += 76f

        // Text alignment & thin-stroke legibility check across columns
        canvas.drawText("3. Text Crispness & Column Alignment Check", left, y, headingPaint)
        y += 16f
        val sampleSizes = floatArrayOf(14f, 11f, 9f, 7.5f)
        for (sz in sampleSizes) {
            bodyPaint.textSize = sz
            canvas.drawText(
                "${sz}pt — The quick brown fox jumps over 0123456789 (CE285A • 600 DPI ZJS)",
                left,
                y,
                bodyPaint
            )
            y += sz + 7f
        }

        // Bottom vertical alignment rules across page
        y += 14f
        val ruleBottom = pageHeight - 54f
        for (col in 0..8) {
            val rx = left + col * ((right - left) / 8f)
            canvas.drawLine(rx, y, rx, ruleBottom, strokePaint)
        }
        canvas.drawRect(left, y, right, ruleBottom, strokePaint)

        doc.finishPage(page)
        return ByteArrayOutputStream().use { out ->
            doc.writeTo(out)
            doc.close()
            out.toByteArray()
        }
    }
}
