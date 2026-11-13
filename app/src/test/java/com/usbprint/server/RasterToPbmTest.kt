package com.usbprint.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class RasterToPbmTest {

    @Test
    fun supportsDetectsUrfAndPwgHeadersOnly() {
        assertTrue(RasterToPbm.supports(byteArrayOf('U'.code.toByte(), 'N'.code.toByte(), 'I'.code.toByte(), 'R'.code.toByte())))
        assertTrue(RasterToPbm.supports(byteArrayOf('R'.code.toByte(), 'a'.code.toByte(), 'S'.code.toByte(), '2'.code.toByte())))
        assertFalse(RasterToPbm.supports("%PDF-1.4".toByteArray()))
        assertFalse(RasterToPbm.supports(byteArrayOf('U'.code.toByte(), 'N'.code.toByte(), 'I'.code.toByte())))
    }

    @Test
    fun convertsUrf300DpiUpscaledAndThresholdsPixels() {
        val urf = buildUrf(
            pageCount = 0,
            bpp = 8,
            colorSpace = 0,
            width = 4,
            height = 2,
            dpi = 300,
            body = byteArrayOf(
                // Row 0 (repeats = 0 + 1 = 1):
                // control = 0 (repeat 1x) pixel=0x00 (black), control = 0x80 (fill remaining white)
                0x00, 0x00, 0x00, 0x80.toByte(),
                // Row 1 (repeats = 0 + 1 = 1):
                // control = 0x80 (all white)
                0x00, 0x80.toByte()
            )
        )

        val out = ByteArrayOutputStream()
        RasterToPbm.convert(urf, out)
        val pbm = out.toByteArray()

        // Output header: "P4\n8 4\n" (7 bytes) + 4 rows * 1 byte = 11 bytes
        val expectedHeader = "P4\n8 4\n".toByteArray(Charsets.US_ASCII)
        val expectedPixels = byteArrayOf(
            0xC0.toByte(), // pixel 0 scaled 2x -> bits 7,6 set
            0xC0.toByte(), // row duplicated 2x vertically
            0x00,
            0x00
        )
        assertArrayEquals(expectedHeader + expectedPixels, pbm)
    }

    @Test
    fun convertsPwgBlack1At600Dpi() {
        val pwg = ByteArrayOutputStream().apply {
            write("RaS2".toByteArray(Charsets.US_ASCII))
            val hdr = ByteArray(1796)
            ByteBuffer.wrap(hdr).apply {
                putInt(276, 600) // HWResolution X
                putInt(280, 600) // HWResolution Y
                putInt(372, 8)   // width
                putInt(376, 2)   // height
                putInt(388, 1)   // bpp
                putInt(392, 1)   // bytesPerLine
                putInt(396, 0)   // colorOrder = chunky
                putInt(400, 3)   // colorSpace = Black
            }
            write(hdr)
            // Row 0..1 (repeats = 1 -> 2 rows): repeat 1x byte 0xA5
            write(byteArrayOf(0x01, 0x00, 0xA5.toByte()))
        }.toByteArray()

        val out = ByteArrayOutputStream()
        RasterToPbm.convert(pwg, out)

        val expected = "P4\n8 2\n".toByteArray(Charsets.US_ASCII) + byteArrayOf(0xA5.toByte(), 0xA5.toByte())
        assertArrayEquals(expected, out.toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedUrfDimensions() {
        val urf = buildUrf(
            pageCount = 1,
            bpp = 8,
            colorSpace = 0,
            width = 6000,
            height = 7000,
            dpi = 600,
            body = byteArrayOf()
        )
        RasterToPbm.convert(urf, ByteArrayOutputStream())
    }

    @Test
    fun convertsMultiPageUrfDocument() {
        val pageBody = byteArrayOf(0x00, 0x80.toByte()) // 1 white row
        val pageHeader = ByteArray(32).apply {
            this[0] = 8
            this[1] = 4 // sGray
            ByteBuffer.wrap(this).apply {
                putInt(12, 8)
                putInt(16, 1)
                putInt(20, 600)
            }
        }
        val doc = ByteArrayOutputStream().apply {
            write("UNIR".toByteArray(Charsets.US_ASCII))
            write(byteArrayOf(0x41, 0x53, 0x54, 0x00))
            write(intBytes(2))
            write(pageHeader)
            write(pageBody)
            write(pageHeader)
            write(pageBody)
        }.toByteArray()

        val out = ByteArrayOutputStream()
        RasterToPbm.convert(doc, out)
        val expected = ("P4\n8 1\n".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00)) +
            ("P4\n8 1\n".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00))
        assertEquals(expected.size, out.size())
        assertArrayEquals(expected, out.toByteArray())
    }

    private fun buildUrf(
        pageCount: Int,
        bpp: Int,
        colorSpace: Int,
        width: Int,
        height: Int,
        dpi: Int,
        body: ByteArray
    ): ByteArray = ByteArrayOutputStream().apply {
        write("UNIR".toByteArray(Charsets.US_ASCII))
        write(byteArrayOf(0x41, 0x53, 0x54, 0x00))
        write(intBytes(pageCount))
        val hdr = ByteArray(32)
        hdr[0] = bpp.toByte()
        hdr[1] = colorSpace.toByte()
        ByteBuffer.wrap(hdr).apply {
            putInt(12, width)
            putInt(16, height)
            putInt(20, dpi)
        }
        write(hdr)
        write(body)
    }.toByteArray()

    @Test
    fun dithersUrfLightGrayAcrossRepeatedRowsAndPwgBlack8() {
        // 64x8 URF Gray_8 image at 600 DPI with repeated 0xC8 (lum = 200, brighter than old 160 cutoff)
        val urf = buildUrf(
            pageCount = 1,
            bpp = 8,
            colorSpace = 0,
            width = 64,
            height = 8,
            dpi = 600,
            body = byteArrayOf(
                0x07, // repeats = 7 + 1 = 8 rows
                63,   // PackBits repeat next byte 64 times
                0xC8.toByte()
            )
        )
        val urfOut = ByteArrayOutputStream()
        RasterToPbm.convert(urf, urfOut)
        val urfBytes = urfOut.toByteArray()
        val urfHeader = "P4\n64 8\n".toByteArray(Charsets.US_ASCII)
        val urfPixels = urfBytes.copyOfRange(urfHeader.size, urfBytes.size)
        val urfSetBits = urfPixels.sumOf { Integer.bitCount(it.toInt() and 0xFF) }
        assertTrue("Expected URF light gray to dither across repeated rows, got $urfSetBits bits", urfSetBits in 40..180)

        // 64x8 PWG Black_8 image at 600 DPI with blackness = 55 (lum = 200, below old >95 cutoff)
        val pwg = ByteArrayOutputStream().apply {
            write("RaS2".toByteArray(Charsets.US_ASCII))
            val hdr = ByteArray(1796)
            ByteBuffer.wrap(hdr).apply {
                putInt(276, 600)
                putInt(280, 600)
                putInt(372, 64)
                putInt(376, 8)
                putInt(388, 8)
                putInt(392, 64)
                putInt(396, 0)
                putInt(400, 3) // Black
            }
            write(hdr)
            write(byteArrayOf(0x07, 63, 55))
        }.toByteArray()
        val pwgOut = ByteArrayOutputStream()
        RasterToPbm.convert(pwg, pwgOut)
        val pwgBytes = pwgOut.toByteArray()
        val pwgPixels = pwgBytes.copyOfRange(urfHeader.size, pwgBytes.size)
        val pwgSetBits = pwgPixels.sumOf { Integer.bitCount(it.toInt() and 0xFF) }
        assertEquals("BLACK_8=55 should match GRAY_8=200 halftone output", urfSetBits, pwgSetBits)

        // Also check returned PbmStats matches actual emitted dark bits
        val statsOut = ByteArrayOutputStream()
        val stats = RasterToPbm.convert(urf, statsOut)
        assertEquals(1, stats.pageCount)
        assertEquals(64L * 8L, stats.totalPixels)
        assertEquals(urfSetBits.toLong(), stats.darkPixels)
    }

    @Test
    fun detectsDirectImageHeadersForJpegPngAndWebp() {
        assertTrue(AirPrintConverter.isSupportedImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertTrue(AirPrintConverter.isSupportedImage(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertTrue(AirPrintConverter.isSupportedImage("RIFF1234WEBP".toByteArray()))
        assertFalse(AirPrintConverter.isSupportedImage("%PDF-1.7".toByteArray()))
    }

    private fun intBytes(v: Int): ByteArray = ByteBuffer.allocate(4).putInt(v).array()
}
