package com.usbprint.server

import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.Arrays

internal object RasterToPbm {
    const val PWG_MIME = "image/pwg-raster"
    const val URF_MIME = "image/urf"
    private const val PWG_MAGIC = 0x52615332
    private const val URF_MAGIC = 0x554e4952
    private const val MAX_PBM_BYTES = 128L * 1024 * 1024

    fun supports(document: ByteArray): Boolean =
        document.size >= 4 && ByteBuffer.wrap(document).int in setOf(PWG_MAGIC, URF_MAGIC)

    private enum class PixelFormat(val bits: Int, val white: Byte) {
        BLACK_1(1, 0), GRAY_1(1, -1), BLACK_8(8, 0), GRAY_8(8, -1), RGB_24(24, -1)
    }

    private data class Page(val width: Int, val height: Int, val dpi: Int, val format: PixelFormat) {
        val bytesPerLine = ((width.toLong() * format.bits + 7) / 8)
        val scale = if (dpi == 300) 2 else 1
    }

    fun convert(document: ByteArray, output: OutputStream): PbmStats {
        val input = ByteBuffer.wrap(document)
        val urf = input.int == URF_MAGIC
        val pageCount = if (urf) {
            require(input.int == 0x41535400) { "Invalid URF signature" }
            input.int.also { require(it >= 0) { "Invalid URF page count" } }
        } else null
        var pages = 0
        var decodedBytes = 0L
        var totalDarkPixels = 0L
        var totalPixels = 0L
        while (input.hasRemaining()) {
            require(pageCount == null || pageCount == 0 || pages < pageCount) { "Extra URF page data" }
            val page = if (urf) readUrfPage(input) else readPwgPage(input)
            require(page.dpi == 300 || page.dpi == 600) { "Unsupported raster resolution" }
            require(page.width in 1..(5104 / page.scale) && page.height in 1..(7020 / page.scale)) {
                "Raster page exceeds supported dimensions"
            }
            val outputWidth = page.width * page.scale
            val outputHeight = page.height * page.scale
            val rowBytes = (outputWidth + 7) / 8
            decodedBytes += rowBytes.toLong() * outputHeight
            require(decodedBytes <= MAX_PBM_BYTES) { "Decoded raster exceeds the job limit" }
            output.write("P4\n${outputWidth} ${outputHeight}\n".toByteArray(Charsets.US_ASCII))
            totalDarkPixels += writePage(input, output, page, rowBytes)
            totalPixels += outputWidth.toLong() * outputHeight
            pages++
        }
        require(pages > 0 && (pageCount == null || pageCount == 0 || pages == pageCount)) { "Incomplete raster document" }
        return PbmStats(pages, totalDarkPixels, totalPixels)
    }

    private fun readPwgPage(input: ByteBuffer): Page {
        val start = input.position()
        require(input.remaining() >= 1796) { "Incomplete PWG page header" }
        val dpi = input.getInt(start + 276)
        require(dpi == input.getInt(start + 280) && input.getInt(start + 396) == 0) {
            "Unsupported PWG resolution or pixel order"
        }
        val colorSpace = input.getInt(start + 400)
        val bits = input.getInt(start + 388)
        val format = when (colorSpace to bits) {
            3 to 1 -> PixelFormat.BLACK_1
            18 to 1 -> PixelFormat.GRAY_1
            3 to 8 -> PixelFormat.BLACK_8
            18 to 8 -> PixelFormat.GRAY_8
            19 to 24 -> PixelFormat.RGB_24
            else -> throw IllegalArgumentException("Unsupported PWG pixel format")
        }
        val page = Page(input.getInt(start + 372), input.getInt(start + 376), dpi, format)
        require(input.getInt(start + 392).toLong() == page.bytesPerLine) { "Invalid PWG line length" }
        input.position(start + 1796)
        return page
    }

    private fun readUrfPage(input: ByteBuffer): Page {
        val start = input.position()
        require(input.remaining() >= 32) { "Incomplete URF page header" }
        val format = when (input.get(start + 1).toInt() to input.get(start).toInt()) {
            0 to 8, 4 to 8 -> PixelFormat.GRAY_8
            1 to 24, 5 to 24 -> PixelFormat.RGB_24
            else -> throw IllegalArgumentException("Unsupported URF pixel format")
        }
        val page = Page(input.getInt(start + 12), input.getInt(start + 16), input.getInt(start + 20), format)
        input.position(start + 32)
        return page
    }

    private fun writePage(input: ByteBuffer, output: OutputStream, page: Page, rowBytes: Int): Long {
        val rasterRow = ByteArray(page.bytesPerLine.toInt())
        val pbmRow = ByteArray(rowBytes)
        val isMonochrome = page.format == PixelFormat.BLACK_1 || page.format == PixelFormat.GRAY_1
        var currErr = if (isMonochrome) IntArray(0) else IntArray(page.width + 2)
        var nextErr = if (isMonochrome) IntArray(0) else IntArray(page.width + 2)
        var pageDarkPixels = 0L
        var remaining = page.height
        while (remaining > 0) {
            val repeats = input.unsignedByte() + 1
            require(repeats <= remaining) { "Raster row repeat crosses the page boundary" }
            readRow(input, rasterRow, page.format)
            if (isMonochrome) {
                val rowDark = copyMonochromeRow(rasterRow, pbmRow, page)
                repeat(repeats * page.scale) { output.write(pbmRow) }
                pageDarkPixels += rowDark * repeats * page.scale
            } else {
                // Dither each repeated scanline so uniform shaded regions diffuse in 2D instead of striping vertically.
                repeat(repeats) {
                    nextErr.fill(0)
                    val rowDark = HalftoneDither.ditherRowToPbm(page.width, currErr, nextErr, pbmRow, page.scale) { x ->
                        luminanceAt(rasterRow, x, page.format)
                    }
                    repeat(page.scale) { output.write(pbmRow) }
                    pageDarkPixels += rowDark * page.scale
                    val swap = currErr
                    currErr = nextErr
                    nextErr = swap
                }
            }
            remaining -= repeats
        }
        return pageDarkPixels
    }

    private fun copyMonochromeRow(rasterRow: ByteArray, pbmRow: ByteArray, page: Page): Long {
        pbmRow.fill(0)
        val invertOnBit = page.format == PixelFormat.BLACK_1
        var darkPixels = 0L
        for (x in 0 until page.width) {
            val isDark = (rasterRow[x / 8].toInt() and (0x80 ushr (x % 8)) != 0) == invertOnBit
            if (isDark) {
                darkPixels += page.scale
                HalftoneDither.setScaledPixel(pbmRow, x, page.scale)
            }
        }
        return darkPixels
    }

    private fun readRow(input: ByteBuffer, row: ByteArray, format: PixelFormat) {
        val unit = maxOf(1, format.bits / 8)
        var offset = 0
        while (offset < row.size) {
            val control = input.unsignedByte()
            if (control == 128) {
                Arrays.fill(row, offset, row.size, format.white)
                return
            }
            val repeated = control < 128
            val length = (if (repeated) control + 1 else 257 - control) * unit
            require(length <= row.size - offset) { "Raster run crosses the line boundary" }
            if (repeated) {
                input.get(row, offset, unit)
                for (index in unit until length) row[offset + index] = row[offset + index % unit]
            } else {
                input.get(row, offset, length)
            }
            offset += length
        }
    }

    private fun luminanceAt(row: ByteArray, x: Int, format: PixelFormat): Int = when (format) {
        PixelFormat.BLACK_8 -> 255 - (row[x].toInt() and 255)
        PixelFormat.GRAY_8 -> row[x].toInt() and 255
        PixelFormat.RGB_24 -> {
            val offset = x * 3
            HalftoneDither.rgbLuminance(
                row[offset].toInt() and 255,
                row[offset + 1].toInt() and 255,
                row[offset + 2].toInt() and 255
            )
        }
        PixelFormat.BLACK_1, PixelFormat.GRAY_1 -> 255
    }

    private fun ByteBuffer.unsignedByte(): Int = get().toInt() and 255
}
