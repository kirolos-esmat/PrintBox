package com.usbprint.server

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

class PrinterManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        const val ACTION_USB_PERMISSION = "com.usbprint.server.USB_PERMISSION"
        const val HP_VENDOR_ID = 0x03F0 // 1008
        const val FIRMWARE_ASSET_NAME = "sihpP1102.dl"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val printMutex = Mutex()

    private var activeDevice: UsbDevice? = null
    private var activeConnection: UsbDeviceConnection? = null
    private var activeInterface: UsbInterface? = null
    private var bulkOutEndpoint: UsbEndpoint? = null
    private var bulkInEndpoint: UsbEndpoint? = null

    private var deviceIdString: String = ""
    private var isFirmwareLoaded: Boolean = false
    @Volatile private var isScanning = false
    private val smartInstallAttempted = mutableSetOf<Int>()
    private var lastScannedSignature: String? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        AppState.log("USB permission granted for ${device.deviceName} (VID: 0x${device.vendorId.toString(16)})")
                        scanAndConnect()
                    } else {
                        AppState.updatePrinterState(PrinterState.PERMISSION_REQUIRED, "USB Permission Denied")
                        AppState.log("USB permission DENIED for ${device?.deviceName}", isError = true)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        AppState.log("USB Device attached: ${device.deviceName} (0x${device.vendorId.toString(16)}:0x${device.productId.toString(16)})")
                        scanAndConnect()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null && device.deviceId == activeDevice?.deviceId) {
                        AppState.log("Printer detached: ${device.deviceName}")
                        closeConnection()
                        AppState.updatePrinterState(PrinterState.DISCONNECTED, "Printer detached")
                    }
                }
            }
        }
    }

    private var started = false
    private var rescanJob: Job? = null

    fun rescan() {
        AppState.log("Manual USB rescan requested")
        lastScannedSignature = null
        val current = activeDevice
        val deviceList = usbManager.deviceList
        if (current != null && deviceList.values.any { it.deviceId == current.deviceId }) {
            resetToReady()
            return
        }
        scanAndConnect()
    }

    fun resetToReady() {
        val current = activeDevice
        if (current != null && activeConnection != null) {
            val state = AppState.printerState.value
            if (state == PrinterState.CONNECTING || state == PrinterState.UPLOADING_FIRMWARE ||
                state == PrinterState.PRINTING || (current.vendorId == HP_VENDOR_ID && !isFirmwareLoaded)) return
            val model = extractModelName(deviceIdString)
            AppState.updatePrinterState(PrinterState.READY, "$model (Ready for print jobs)")
            AppState.log("Printer state reset to READY.")
            scope.launch(Dispatchers.IO) { refreshSupplies(logChange = true) }
        } else {
            scanAndConnect()
        }
    }

    fun start() {
        if (started) {
            scanAndConnect()
            return
        }
        started = true
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context, usbReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        scanAndConnect()

        // Poll while no printer is attached, so hot-plug works even if the attach broadcast is missed.
        rescanJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(5000)
                val state = AppState.printerState.value
                if (activeDevice == null &&
                    (state == PrinterState.DISCONNECTED || state == PrinterState.ERROR ||
                        state == PrinterState.SWITCHING_SMART_INSTALL)
                ) {
                    scanAndConnect()
                }
            }
        }
    }

    fun stop() {
        rescanJob?.cancel()
        rescanJob = null
        if (started) {
            try {
                context.unregisterReceiver(usbReceiver)
            } catch (ignored: Exception) {}
            started = false
        }
        closeConnection()
    }

    @Synchronized
    fun scanAndConnect() {
        if (isScanning) return
        isScanning = true
        scope.launch(Dispatchers.IO) {
            try {
                findAndOpenDevice()
            } finally {
                isScanning = false
            }
        }
    }

    private suspend fun findAndOpenDevice() {
        val deviceList = usbManager.deviceList
        smartInstallAttempted.retainAll(deviceList.values.map { it.deviceId }.toSet())

        // Already connected and still plugged in: nothing to do.
        val current = activeDevice
        if (current != null && deviceList.values.any { it.deviceId == current.deviceId }) {
            return
        }

        val signature = deviceList.values.sortedBy { it.deviceId }
            .joinToString(",") { "${it.deviceId}:${it.vendorId}:${it.productId}:${it.interfaceCount}" }
        val shouldLogScan = signature != lastScannedSignature
        lastScannedSignature = signature

        if (shouldLogScan) {
            AppState.log("Scanning USB devices (${deviceList.size} found)...")
        }

        for ((_, device) in deviceList) {
            if (shouldLogScan) {
                AppState.log("Found USB: ${device.deviceName} (VID=0x${device.vendorId.toString(16)}, PID=0x${device.productId.toString(16)}, Class=${device.deviceClass})")
            }

            val isHp = device.vendorId == HP_VENDOR_ID
            var printerInterfaceIndex = -1
            var massStorageInterfaceIndex = -1

            for (idx in 0 until device.interfaceCount) {
                val iface = device.getInterface(idx)
                if (shouldLogScan) {
                    AppState.log("  Interface #$idx: class=${iface.interfaceClass}, subclass=${iface.interfaceSubclass}, endpoints=${iface.endpointCount}")
                }
                if (iface.interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                    printerInterfaceIndex = idx
                } else if (iface.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
                    massStorageInterfaceIndex = idx
                }
            }

            val hasPrinterInterface = printerInterfaceIndex != -1
            val hasMassStorageInterface = massStorageInterfaceIndex != -1

            if (isHp || hasPrinterInterface) {
                lastScannedSignature = null
                AppState.log("Candidate printer found: ${device.deviceName} (VID=0x${device.vendorId.toString(16)}, PID=0x${device.productId.toString(16)})")

                if (!usbManager.hasPermission(device)) {
                    AppState.updatePrinterState(PrinterState.PERMISSION_REQUIRED, "Requesting USB permission...")
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                    val pi = PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                        flags
                    )
                    usbManager.requestPermission(device, pi)
                    return
                }

                when {
                    hasPrinterInterface -> printMutex.withLock { initializeUsbPrinter(device) }
                    isHp && hasMassStorageInterface -> handleSmartInstall(device)
                    else -> printMutex.withLock { initializeUsbPrinter(device) }
                }
                return
            }
        }

        if (activeDevice == null && AppState.printerState.value != PrinterState.DISCONNECTED) {
            AppState.updatePrinterState(PrinterState.DISCONNECTED, "No USB printer found. Please connect printer via USB.")
        }
    }

    private suspend fun handleSmartInstall(device: UsbDevice) {
        if (!smartInstallAttempted.add(device.deviceId)) {
            val message = "HP printer is still in Smart Install mode. Power-cycle the printer and reconnect USB."
            if (AppState.printerInfo.value != message) AppState.updatePrinterState(PrinterState.ERROR, message)
            return
        }
        AppState.updatePrinterState(PrinterState.SWITCHING_SMART_INSTALL, "Switching HP Smart Install mode...")
        switchSmartInstallMode(device)
    }

    private suspend fun switchSmartInstallMode(device: UsbDevice) {
        AppState.log("Attempting SCSI mode switch for HP Smart Install CD-ROM...")
        val connection = usbManager.openDevice(device) ?: run {
            AppState.log("Failed to open device for Smart Install switch", isError = true)
            return
        }

        try {
            // Find Mass Storage interface and Bulk OUT endpoint
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
                    if (!connection.claimInterface(iface, true)) {
                        AppState.log("Failed to claim mass storage interface $i", isError = true)
                        continue
                    }

                    var outEp: UsbEndpoint? = null
                    var inEp: UsbEndpoint? = null
                    for (e in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(e)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                            ep.direction == UsbConstants.USB_DIR_OUT) {
                            outEp = ep
                        } else if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                            ep.direction == UsbConstants.USB_DIR_IN) {
                            inEp = ep
                        }
                    }

                    if (outEp != null && inEp != null) {
                        // 31-byte Command Block Wrapper (CBW) with SCSI vendor command 0xD0
                        val cbw = ByteArray(31)
                        cbw[0] = 0x55.toByte() // 'U'
                        cbw[1] = 0x53.toByte() // 'S'
                        cbw[2] = 0x42.toByte() // 'B'
                        cbw[3] = 0x43.toByte() // 'C'
                        cbw[4] = 0x78.toByte() // Tag
                        cbw[5] = 0x56.toByte()
                        cbw[6] = 0x34.toByte()
                        cbw[7] = 0x12.toByte()
                        cbw[14] = 0x06.toByte() // Command length: 6
                        cbw[15] = 0xD0.toByte() // SCSI command 0xD0 (vendor mode switch)

                        val sent = connection.bulkTransfer(outEp, cbw, cbw.size, 3000)
                        AppState.log("Sent SCSI switch command: $sent bytes transferred")
                        if (sent == cbw.size) {
                            val response = ByteArray(13)
                            val received = connection.bulkTransfer(inEp, response, response.size, 3000)
                            AppState.log("Smart Install response: $received bytes")
                        } else {
                            AppState.log("Incomplete Smart Install command", isError = true)
                        }
                        delay(2000)
                        connection.releaseInterface(iface)
                        break
                    } else {
                        AppState.log("Smart Install interface missing bulk endpoints", isError = true)
                        connection.releaseInterface(iface)
                    }
                }
            }
        } catch (e: Exception) {
            AppState.log("Error during Smart Install switch: ${e.message}", isError = true)
        } finally {
            connection.close()
            delay(2000)
        }
    }

    private suspend fun initializeUsbPrinter(device: UsbDevice) {
        closeConnection()
        AppState.updatePrinterState(PrinterState.CONNECTING, "Opening USB printer...")

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            AppState.updatePrinterState(PrinterState.ERROR, "Failed to open USB connection")
            AppState.log("Cannot open UsbDeviceConnection", isError = true)
            return
        }

        var printerInterface: UsbInterface? = null
        var outEp: UsbEndpoint? = null
        var inEp: UsbEndpoint? = null

        // Search for printer interface (Class 7)
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_PRINTER ||
                (device.vendorId == HP_VENDOR_ID && iface.interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE)) {
                printerInterface = iface
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_OUT) {
                            outEp = ep
                        } else if (ep.direction == UsbConstants.USB_DIR_IN) {
                            inEp = ep
                        }
                    }
                }
                if (outEp != null) break
            }
        }

        if (printerInterface == null || outEp == null) {
            AppState.updatePrinterState(PrinterState.ERROR, "No Bulk OUT printer interface found")
            AppState.log("Bulk OUT endpoint missing on device", isError = true)
            connection.close()
            return
        }

        if (!connection.claimInterface(printerInterface, true)) {
            AppState.updatePrinterState(PrinterState.ERROR, "Failed to claim printer interface")
            AppState.log("Failed to claim USB interface ${printerInterface.id}", isError = true)
            connection.close()
            return
        }

        activeDevice = device
        activeConnection = connection
        activeInterface = printerInterface
        bulkOutEndpoint = outEp
        bulkInEndpoint = inEp

        AppState.log("Printer interface claimed. OUT EP: 0x${outEp.address.toString(16)}, IN EP: 0x${inEp?.address?.toString(16) ?: "none"}")

        // Query IEEE-1284 Device ID
        val devId = readDeviceId(connection, printerInterface.id)
        deviceIdString = devId
        AppState.log("Device ID: ${devId.ifEmpty { "None returned" }}")

        // Check if firmware is loaded
        isFirmwareLoaded = devId.contains("FWVER:", ignoreCase = true) ||
                devId.contains("FW:", ignoreCase = true)

        if (!isFirmwareLoaded && device.vendorId == HP_VENDOR_ID) {
            AppState.updatePrinterState(PrinterState.UPLOADING_FIRMWARE, "Uploading firmware...")
            uploadFirmware()
        } else {
            val model = extractModelName(devId)
            AppState.updatePrinterState(PrinterState.READY, "$model (Ready for print jobs)")
            queryPjlSuppliesLocked(connection, outEp, inEp, logChange = true)
        }
    }

    private fun readDeviceId(connection: UsbDeviceConnection, ifaceId: Int): String {
        return try {
            val buf = ByteArray(1024)
            // PRINTER_REQ_GET_DEVICE_ID = 0x00, RequestType = 0xA1 (Class | Interface | Device-to-host)
            val len = connection.controlTransfer(0xA1, 0x00, 0, ifaceId, buf, buf.size, 3000)
            if (len > 2) {
                val stringLen = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
                val actualLen = minOf(stringLen - 2, len - 2)
                if (actualLen > 0) {
                    String(buf, 2, actualLen, StandardCharsets.US_ASCII)
                } else ""
            } else ""
        } catch (e: Exception) {
            AppState.log("Failed to read IEEE-1284 ID: ${e.message}")
            ""
        }
    }

    private fun extractModelName(deviceId: String): String {
        val pairs = deviceId.split(";")
        for (pair in pairs) {
            val trimmed = pair.trim()
            if (trimmed.startsWith("MDL:", ignoreCase = true)) {
                return trimmed.substring(4)
            }
        }
        return "HP LaserJet Pro P1102"
    }

    fun reloadFirmware() {
        scope.launch(Dispatchers.IO) {
            if (activeConnection == null) {
                AppState.log("Reload firmware ignored: no printer connected", isError = true)
                return@launch
            }
            printMutex.withLock { uploadFirmware() }
        }
    }

    private suspend fun uploadFirmware(): Boolean {
        val conn = activeConnection ?: run {
            AppState.log("Cannot upload firmware: no USB connection", isError = true)
            return false
        }
        val ep = bulkOutEndpoint ?: run {
            AppState.log("Cannot upload firmware: no bulk OUT endpoint", isError = true)
            return false
        }

        AppState.updatePrinterState(PrinterState.UPLOADING_FIRMWARE, "Loading $FIRMWARE_ASSET_NAME...")
        AppState.log("Starting firmware upload from assets/$FIRMWARE_ASSET_NAME...")

        return try {
            context.assets.open(FIRMWARE_ASSET_NAME).use { input ->
                val buffer = ByteArray(8192)
                var totalBytes = 0L
                var bytesRead: Int
                val fileSize = input.available().toLong()
                var lastLoggedStep = -1

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    var offset = 0
                    while (offset < bytesRead) {
                        val transferred = conn.bulkTransfer(ep, buffer, offset, bytesRead - offset, 5000)
                        if (transferred <= 0) {
                            AppState.log("Firmware upload failed at $totalBytes bytes", isError = true)
                            AppState.updatePrinterState(PrinterState.ERROR, "Firmware upload transfer error")
                            return false
                        }
                        offset += transferred
                        totalBytes += transferred
                    }
                    val progress = if (fileSize > 0) (totalBytes * 100 / fileSize).toInt() else 0
                    val step = progress / 25
                    if (step != lastLoggedStep) {
                        lastLoggedStep = step
                        AppState.log("Firmware uploading: $progress% ($totalBytes bytes)")
                    }
                }

                AppState.log("Firmware upload complete: $totalBytes bytes sent. Waiting for printer warm-up...")
                AppState.updatePrinterState(PrinterState.UPLOADING_FIRMWARE, "Firmware sent. Printer initializing...")
                delay(3000)

                // Refresh IEEE-1284 ID
                activeInterface?.let { iface ->
                    val refreshedId = readDeviceId(conn, iface.id)
                    deviceIdString = refreshedId
                    AppState.log("Post-firmware Device ID: $refreshedId")
                }

                isFirmwareLoaded = true
                val model = extractModelName(deviceIdString)
                AppState.updatePrinterState(PrinterState.READY, "$model (Firmware Loaded, Ready)")
                queryPjlSuppliesLocked(conn, ep, bulkInEndpoint, logChange = true)
                true
            }
        } catch (e: Exception) {
            AppState.log("Exception during firmware upload: ${e.message}", isError = true)
            AppState.updatePrinterState(PrinterState.ERROR, "Firmware error: ${e.message}")
            false
        }
    }

    suspend fun printStream(
        input: InputStream,
        jobMetrics: JobTonerMetrics? = null,
        onProgress: (bytes: Long) -> Unit
    ): Boolean {
        printMutex.withLock {
            val conn = activeConnection
            val ep = bulkOutEndpoint
            val inEp = bulkInEndpoint

            if (conn == null || ep == null || AppState.printerState.value != PrinterState.READY) {
                AppState.log("Print failed: Printer is not ready", isError = true)
                return false
            }

            val prePageCount = AppState.supplyStatus.value.pageCount
            AppState.updatePrinterState(PrinterState.PRINTING, "Receiving and sending print job...")
            drainBulkIn(conn, inEp, logReplies = false)
            val buffer = ByteArray(16384)
            var totalBytes = 0L
            try {
                while (true) {
                    val readBytes = input.read(buffer)
                    if (readBytes == -1) break

                    var offset = 0
                    while (offset < readBytes) {
                        val toSend = readBytes - offset
                        val sent = conn.bulkTransfer(ep, buffer, offset, toSend, 30000)
                        if (sent <= 0) {
                            AppState.log("USB write error ($sent) after $totalBytes bytes", isError = true)
                            val attached = activeDevice?.let { device ->
                                usbManager.deviceList.values.any { it.deviceId == device.deviceId }
                            } == true
                            AppState.updatePrinterState(
                                if (attached) PrinterState.ERROR else PrinterState.DISCONNECTED,
                                if (attached) "USB write error" else "Printer disconnected during printing"
                            )
                            return false
                        }
                        offset += sent
                        totalBytes += sent
                        onProgress(totalBytes)
                    }
                }

                if (totalBytes > 0) {
                    drainBulkIn(conn, inEp, logReplies = true)
                    AppState.recordJobCompleted(totalBytes)
                    AppState.log("Print job completed: $totalBytes bytes sent to printer.")
                    val postSupply = queryPjlSuppliesLocked(conn, ep, inEp, logChange = true)
                    val resolvedMetrics = jobMetrics ?: run {
                        val pjlDelta = if (prePageCount >= 0 && postSupply != null && postSupply.pageCount > prePageCount) {
                            postSupply.pageCount - prePageCount
                        } else 1
                        JobTonerMetrics(
                            pages = pjlDelta,
                            coveragePercent = 5.0,
                            standardPages = pjlDelta.toDouble(),
                            sourceLabel = "Raw TCP"
                        )
                    }
                    RefillTracker.recordPrintJob(context, resolvedMetrics, postSupply?.pageCount)
                }
                val model = extractModelName(deviceIdString)
                AppState.updatePrinterState(PrinterState.READY, "$model (Ready for print jobs)")
                return totalBytes > 0
            } catch (e: IOException) {
                AppState.log("Error streaming print job: ${e.message}", isError = true)
                if (activeConnection === conn && activeDevice != null) {
                    val model = extractModelName(deviceIdString)
                    AppState.updatePrinterState(PrinterState.READY, "$model (Ready for print jobs)")
                }
                return false
            }
        }
    }

    /** Drain buffered PJL status replies from Bulk IN (0x81) to prevent FIFO backpressure. */
    private fun drainBulkIn(conn: UsbDeviceConnection, inEp: UsbEndpoint?, logReplies: Boolean) {
        if (inEp == null) return
        val rxBuf = ByteArray(maxOf(512, inEp.maxPacketSize))
        val collected = StringBuilder()
        for (attempt in 0 until 8) {
            val read = conn.bulkTransfer(inEp, rxBuf, rxBuf.size, 80)
            if (read <= 0) break
            if (logReplies) {
                collected.append(String(rxBuf, 0, read, StandardCharsets.US_ASCII))
            }
        }
        if (logReplies && collected.isNotEmpty()) {
            val clean = collected.toString()
                .replace("\r", "")
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" | ")
            if (clean.isNotEmpty()) {
                AppState.log("Printer status reply: $clean")
            }
        }
    }

    suspend fun refreshSupplies(logChange: Boolean = true): SupplyStatus? = printMutex.withLock {
        val conn = activeConnection ?: return@withLock null
        queryPjlSuppliesLocked(conn, bulkOutEndpoint, bulkInEndpoint, logChange)
    }

    private fun queryPjlSuppliesLocked(
        conn: UsbDeviceConnection,
        outEp: UsbEndpoint?,
        inEp: UsbEndpoint?,
        logChange: Boolean
    ): SupplyStatus? {
        if (outEp == null || inEp == null) {
            AppState.log("Supply query skipped: missing bulk endpoints")
            return null
        }
        return try {
            drainBulkIn(conn, inEp, logReplies = false)
            val pjl = ("\u001B%-12345X@PJL\r\n" +
                "@PJL INFO SUPPLIES\r\n" +
                "@PJL INFO PAGECOUNT\r\n" +
                "@PJL INFO STATUS\r\n" +
                "\u001B%-12345X").toByteArray(StandardCharsets.US_ASCII)
            val sent = conn.bulkTransfer(outEp, pjl, pjl.size, 2000)
            if (sent <= 0) {
                AppState.log("Supply query send failed (sent=$sent)", isError = true)
                return null
            }

            val rx = ByteArray(4096)
            val reply = StringBuilder()
            var ffCount = 0
            for (i in 0 until 8) {
                val n = conn.bulkTransfer(inEp, rx, rx.size, if (i == 0) 1500 else 150)
                if (n <= 0) break
                val chunk = String(rx, 0, n, StandardCharsets.US_ASCII)
                reply.append(chunk)
                ffCount += chunk.count { it == '\u000C' }
                if (ffCount >= 3) break
            }

            if (reply.isEmpty()) {
                AppState.log("Supply query returned empty PJL reply")
                return null
            }
            val parsed = PjlSupplyParser.parse(reply.toString())
            if (parsed == null) {
                val preview = reply.toString().replace("\r", "").replace("\n", " | ").take(120)
                AppState.log("Supply query unparsed reply: $preview")
                return null
            }
            val previous = AppState.supplyStatus.value
            AppState.updateSupplyStatus(parsed)
            RefillTracker.bindBaselineIfNeeded(context, parsed.pageCount)
            if (logChange && parsed != previous) {
                val pct = if (parsed.percentRemaining >= 0) "${parsed.percentRemaining}%" else "Unknown"
                val pages = if (parsed.pageCount >= 0) " | Pages: ${parsed.pageCount}" else ""
                AppState.log("Supplies: ${parsed.name} $pct (${parsed.chipState}${if (parsed.lowReached) "/Low" else ""})$pages")
            }
            parsed
        } catch (e: Exception) {
            AppState.log("Supply query error: ${e.message}")
            null
        }
    }

    @Synchronized
    private fun closeConnection() {
        try {
            activeInterface?.let { iface ->
                activeConnection?.releaseInterface(iface)
            }
            activeConnection?.close()
        } catch (e: Exception) {
            AppState.log("Error closing USB connection: ${e.message}")
        } finally {
            activeDevice = null
            activeConnection = null
            activeInterface = null
            bulkOutEndpoint = null
            bulkInEndpoint = null
            isFirmwareLoaded = false
            lastScannedSignature = null
            AppState.updateSupplyStatus(SupplyStatus())
        }
    }
}
