package com.usbprint.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PjlSupplyParserTest {

    @Test
    fun parsesP1102SuppliesAndPageCount() {
        val sample = """
            @PJL INFO SUPPLIES
            Type = Used
            ReorderPN = CE285A
            PercentRemaining = 0
            LowReached = 1
            SerialNumber = 568397314
            @PJL INFO PAGECOUNT
            3149
            
        """.trimIndent()

        val parsed = PjlSupplyParser.parse(sample)
        assertNotNull(parsed)
        assertTrue(parsed!!.available)
        assertEquals("CE285A", parsed.cartridgeModel)
        assertEquals("Black Toner (CE285A)", parsed.name)
        assertEquals(0, parsed.percentRemaining)
        assertEquals("Used", parsed.chipState)
        assertTrue(parsed.lowReached)
        assertEquals(3149, parsed.pageCount)
    }

    @Test
    fun parsesHealthyGenuineCartridgeAndIgnoresBlank() {
        assertNull(PjlSupplyParser.parse(""))

        val healthy = """
            @PJL INFO SUPPLIES
            Type = Genuine HP
            ReorderPN = CE285A
            PercentRemaining = 78
            LowReached = 0
            @PJL INFO PAGECOUNT
            120
        """.trimIndent()

        val parsed = PjlSupplyParser.parse(healthy)
        assertNotNull(parsed)
        assertEquals(78, parsed!!.percentRemaining)
        assertEquals("Genuine HP", parsed.chipState)
        assertFalse(parsed.lowReached)
        assertEquals(120, parsed.pageCount)
    }
}
