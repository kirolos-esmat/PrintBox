package com.usbprint.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPrintConverterDitherTest {

    private fun countSetBits(bytes: ByteArray, width: Int): Int {
        var count = 0
        for (x in 0 until width) {
            val b = bytes[x / 8].toInt() and 0xFF
            if ((b and (1 shl (7 - (x % 8)))) != 0) {
                count++
            }
        }
        return count
    }

    @Test
    fun nearBlackStaysSolidAndOffWhiteStaysClean() {
        val width = 16
        val pixels = IntArray(width) { x ->
            if (x < 8) 0xFF505050.toInt() else 0xFFF9F9F9.toInt()
        }
        val currErr = IntArray(width + 2)
        val nextErr = IntArray(width + 2)
        val rowBytes = ByteArray(2)

        AirPrintConverter.ditherArgbRowToPbm(pixels, width, currErr, nextErr, rowBytes)

        assertEquals(0xFF, rowBytes[0].toInt() and 0xFF)
        assertEquals(0x00, rowBytes[1].toInt() and 0xFF)
    }

    @Test
    fun lightGrayBrighterThanOldCutoffProducesHalftoneDotsAcrossRows() {
        val width = 64
        val height = 8
        val lightGray = 0xFFC8C8C8.toInt() // lum = 200 (previously dropped completely at lum < 160)
        val pixels = IntArray(width) { lightGray }
        var currErr = IntArray(width + 2)
        var nextErr = IntArray(width + 2)
        val rowBytes = ByteArray(width / 8)

        var darkPixels = 0
        repeat(height) {
            nextErr.fill(0)
            AirPrintConverter.ditherArgbRowToPbm(pixels, width, currErr, nextErr, rowBytes)
            darkPixels += countSetBits(rowBytes, width)
            val swap = currErr
            currErr = nextErr
            nextErr = swap
        }

        assertTrue("Expected light gray to produce halftone dots, got $darkPixels", darkPixels in 40..160)
    }
}
