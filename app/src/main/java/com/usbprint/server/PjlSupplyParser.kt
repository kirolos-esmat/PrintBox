package com.usbprint.server

/** Parses HP PJL `@PJL INFO SUPPLIES` and `@PJL INFO PAGECOUNT` responses. */
object PjlSupplyParser {

    fun parse(raw: String): SupplyStatus? {
        if (raw.isBlank()) return null

        var cartridgeModel = "CE285A"
        var percentRemaining = -2
        var chipState = "Unknown"
        var lowReached = false
        var pageCount = -1
        var sawSupplyField = false
        var expectingPageCount = false

        for (rawLine in raw.replace("\r", "").lines()) {
            val line = rawLine.replace("\u000C", "").trim()
            if (line.isEmpty()) continue

            if (line.startsWith("@PJL INFO PAGECOUNT", ignoreCase = true)) {
                expectingPageCount = true
                continue
            }
            if (line.startsWith("@PJL", ignoreCase = true)) {
                expectingPageCount = false
                continue
            }
            if (expectingPageCount) {
                line.toIntOrNull()?.let {
                    if (it >= 0) pageCount = it
                }
                expectingPageCount = false
                continue
            }

            val eqIdx = line.indexOf('=')
            if (eqIdx <= 0) continue
            val key = line.substring(0, eqIdx).trim()
            val value = line.substring(eqIdx + 1).trim()

            when (key) {
                "ReorderPN" -> if (value.isNotEmpty() && value != "0") {
                    cartridgeModel = value
                    sawSupplyField = true
                }
                "PercentRemaining" -> value.toIntOrNull()?.let {
                    percentRemaining = it.coerceIn(0, 100)
                    sawSupplyField = true
                }
                "Type" -> if (value.isNotEmpty()) {
                    chipState = value
                    sawSupplyField = true
                }
                "LowReached" -> {
                    lowReached = value == "1"
                    sawSupplyField = true
                }
            }
        }

        if (!sawSupplyField && pageCount < 0) return null

        return SupplyStatus(
            available = sawSupplyField,
            cartridgeModel = cartridgeModel,
            name = "Black Toner ($cartridgeModel)",
            percentRemaining = percentRemaining,
            chipState = chipState,
            lowReached = lowReached,
            pageCount = pageCount
        )
    }
}
