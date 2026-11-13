package com.usbprint.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefillTrackerTest {

    @Test
    fun estimatesRemainingPercentageAcrossRefillCycle() {
        assertEquals(0, RefillTracker.pagesSinceRefill(3149, 3149))
        assertEquals(100, RefillTracker.estimateRemainingPercent(3149, 3149))
        assertEquals(50, RefillTracker.estimateRemainingPercent(3949, 3149))
        assertEquals(0, RefillTracker.estimateRemainingPercent(5000, 3149))
    }

    @Test
    fun effectiveSupplyPercentUsesChipUnlessRefillEnabled() {
        val chip = SupplyStatus(available = true, percentRemaining = 0, pageCount = 3149)
        assertEquals(0, AppState.effectiveSupplyPercent(chip, RefillState(enabled = false, baselinePages = -1)))
        assertEquals(100, AppState.effectiveSupplyPercent(chip, RefillState(enabled = true, baselinePages = 3149)))
        assertEquals(75, AppState.effectiveSupplyPercent(chip.copy(pageCount = 3549), RefillState(enabled = true, baselinePages = 3149)))
    }

    @Test
    fun weightedCoverageScalesByDarkPixelDensity() {
        val sparse = PbmStats(pageCount = 1, darkPixels = 500, totalPixels = 100_000) // 0.5% ink -> 0.20 floor
        val isoStandard = PbmStats(pageCount = 1, darkPixels = 5_000, totalPixels = 100_000) // 5.0% -> 1.00 std
        val darkImage = PbmStats(pageCount = 1, darkPixels = 50_000, totalPixels = 100_000) // 50.0% -> 10.0 std

        assertEquals(0.20, sparse.standardPageEquivalents(), 0.001)
        assertEquals(1.00, isoStandard.standardPageEquivalents(), 0.001)
        assertEquals(10.00, darkImage.standardPageEquivalents(), 0.001)
        assertTrue(darkImage.standardPageEquivalents() > isoStandard.standardPageEquivalents())
    }

    @Test
    fun exactTrackingCountsSeededPdfsAndHandlesFrozenOrAdvancingPjl() {
        val initialSeed = RefillState(
            enabled = true,
            baselinePages = 3149,
            pagesPrinted = 3,
            standardPagesUsed = 4.8
        )
        assertEquals(99.7, RefillTracker.estimateRemainingPercentExact(3149, initialSeed), 0.01)
        assertEquals(3, RefillTracker.effectivePagesPrinted(3149, initialSeed))

        // If printer PJL increments alongside our 3 recorded pages (e.g. +2), must NOT double-count
        assertEquals(3, RefillTracker.effectivePagesPrinted(3151, initialSeed))
        assertEquals(4.8, RefillTracker.effectiveStandardPagesUsed(3151, initialSeed), 0.001)

        // If 5 unrecorded pages happen outside the app (PJL +5 > 3 recorded), only the delta +2 adds
        assertEquals(5, RefillTracker.effectivePagesPrinted(3154, initialSeed))
        assertEquals(6.8, RefillTracker.effectiveStandardPagesUsed(3154, initialSeed), 0.001)
    }

    @Test
    fun formatsTransferredBytesAsKbOrMb() {
        assertEquals("0 KB", AppMemoryStore.formatBytes(0L))
        assertEquals("512 KB", AppMemoryStore.formatBytes(512L * 1024L))
        assertEquals("1.5 MB", AppMemoryStore.formatBytes(1536L * 1024L))
    }
}
