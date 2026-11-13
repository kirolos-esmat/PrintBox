package com.usbprint.server

internal data class PbmStats(
    val pageCount: Int,
    val darkPixels: Long,
    val totalPixels: Long
) {
    val coveragePercent: Double
        get() = if (totalPixels > 0L) (darkPixels * 100.0) / totalPixels else 5.0

    /**
     * Converts pixel coverage into CE285A ISO-equivalent standard pages (5% coverage = 1.0 page).
     * Applies a 0.20 minimum per physical sheet for drum/transfer wear on near-blank pages.
     */
    fun standardPageEquivalents(): Double {
        if (pageCount <= 0) return 0.0
        return maxOf(pageCount * 0.20, pageCount * (coveragePercent / 5.0))
    }
}

/**
 * Shared contrast-guarded Floyd-Steinberg halftoning for PDF bitmaps, images, and URF/PWG rasters.
 */
internal object HalftoneDither {

    fun rgbLuminance(red: Int, green: Int, blue: Int): Int =
        (red * 299 + green * 587 + blue * 114) / 1000

    /**
     * Dithers a single scanline into packed 1-bit MSB-first PBM bytes and returns the dark pixel count.
     * Pixels at or below luminance 96 stay solid black, pixels at or above 248 stay spotless white,
     * and intermediate grays diffuse error across [currErr] and [nextErr].
     */
    inline fun ditherRowToPbm(
        width: Int,
        currErr: IntArray,
        nextErr: IntArray,
        rowBytes: ByteArray,
        scale: Int = 1,
        luminanceAt: (Int) -> Int
    ): Long {
        rowBytes.fill(0)
        var darkPixels = 0L
        for (x in 0 until width) {
            val rawLum = luminanceAt(x)
            val isDark = when {
                rawLum <= 96 -> true
                rawLum >= 248 -> false
                else -> {
                    val base = ((rawLum - 96) * 255) / 152
                    val adjusted = (base + currErr[x + 1]).coerceIn(0, 255)
                    val dark = adjusted < 128
                    val err = adjusted - if (dark) 0 else 255
                    val e7 = (err * 7) / 16
                    val e3 = (err * 3) / 16
                    val e5 = (err * 5) / 16
                    currErr[x + 2] += e7
                    nextErr[x] += e3
                    nextErr[x + 1] += e5
                    nextErr[x + 2] += err - e7 - e3 - e5
                    dark
                }
            }
            if (isDark) {
                darkPixels += scale
                setScaledPixel(rowBytes, x, scale)
            }
        }
        return darkPixels
    }

    fun setScaledPixel(rowBytes: ByteArray, x: Int, scale: Int) {
        if (scale == 1) {
            val index = x ushr 3
            rowBytes[index] = (rowBytes[index].toInt() or (0x80 ushr (x and 7))).toByte()
        } else {
            val basePixel = x * scale
            for (offset in 0 until scale) {
                val pixel = basePixel + offset
                val index = pixel ushr 3
                rowBytes[index] = (rowBytes[index].toInt() or (0x80 ushr (pixel and 7))).toByte()
            }
        }
    }
}
