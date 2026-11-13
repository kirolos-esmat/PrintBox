package com.usbprint.server

import android.content.Context
import android.hardware.usb.UsbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class DiagnosticHttpServer(
    private val port: Int = 9101,
    private val context: Context,
    private val printerManager: PrinterManager,
    private val scope: CoroutineScope
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val clientSlots = kotlinx.coroutines.sync.Semaphore(16)

    fun start() {
        if (serverJob?.isActive == true) return
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                AppState.log("Diagnostic HTTP server listening on port $port")

                while (isActive && !ss.isClosed) {
                    try {
                        val client = ss.accept()
                        if (!clientSlots.tryAcquire()) {
                            try { client.close() } catch (_: Exception) {}
                            continue
                        }
                        launch(Dispatchers.IO) {
                            try {
                                handleClient(client)
                            } finally {
                                clientSlots.release()
                            }
                        }
                    } catch (ignored: Exception) {}
                }
            } catch (e: Exception) {
                AppState.log("Diagnostic server error on port $port: ${e.message}", isError = true)
            }
        }
    }

    private fun readBoundedLine(input: java.io.InputStream, maxBytes: Int = 4096): String? {
        val out = java.io.ByteArrayOutputStream(256)
        while (out.size() < maxBytes) {
            val b = input.read()
            if (b == -1) {
                return if (out.size() == 0) null else out.toString(Charsets.UTF_8.name())
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 5000
            val requestLine = readBoundedLine(s.getInputStream()) ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: "/"

            when {
                path.startsWith("/rescan") || path.startsWith("/reload-fw") || path.startsWith("/reset") -> {
                    sendResponse(s, 403, "text/plain", "Use the TV controls for printer actions.\n")
                }
                else -> {
                    val body = buildStatusReport()
                    sendResponse(s, 200, "text/plain; charset=utf-8", body)
                }
            }
        }
    }

    private fun buildStatusReport(): String {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val sb = StringBuilder()
        sb.appendLine("=== USB PRINT SERVER DIAGNOSTICS ===")
        sb.appendLine("Network Endpoint : ${AppState.networkAddress.value}")
        sb.appendLine("Printer State    : ${AppState.printerState.value}")
        sb.appendLine("Printer Info     : ${AppState.printerInfo.value}")
        val stats = AppState.stats.value
        sb.appendLine("Jobs Completed   : ${stats.jobsCompleted} (${stats.totalBytesTransferred} bytes)")
        sb.appendLine("Last Job         : ${stats.lastJobTime} (${stats.lastJobBytes} bytes)")
        val supply = AppState.supplyStatus.value
        val refill = AppState.refillState.value
        if (supply.available || refill.enabled) {
            val pctLabel = if (refill.enabled) {
                val exact = RefillTracker.estimateRemainingPercentExact(supply.pageCount, refill)
                val pagesPrinted = RefillTracker.effectivePagesPrinted(supply.pageCount, refill)
                val stdUsed = String.format(java.util.Locale.US, "%.1f", RefillTracker.effectiveStandardPagesUsed(supply.pageCount, refill))
                "~${RefillTracker.formatPercent(exact)}% (Refill: $pagesPrinted pages / $stdUsed std eq.)"
            } else {
                if (supply.percentRemaining >= 0) "${supply.percentRemaining}%" else "Unknown"
            }
            val chipPct = if (supply.percentRemaining >= 0) "${supply.percentRemaining}%" else "Unknown"
            val pages = if (supply.pageCount >= 0) " | Printer Pages: ${supply.pageCount}" else ""
            sb.appendLine("Supply Level     : ${supply.name} = $pctLabel (Chip: $chipPct ${supply.chipState}${if (supply.lowReached) ", Low" else ""}$pages)")
        } else {
            sb.appendLine("Supply Level     : Unavailable")
        }
        sb.appendLine()
        sb.appendLine("=== ATTACHED USB DEVICES (${usbManager.deviceList.size}) ===")
        for ((name, dev) in usbManager.deviceList) {
            val hasPerm = try { usbManager.hasPermission(dev) } catch (_: Exception) { false }
            sb.appendLine("- $name: VID=0x${dev.vendorId.toString(16)} PID=0x${dev.productId.toString(16)} Class=${dev.deviceClass} Perm=$hasPerm")
            for (i in 0 until dev.interfaceCount) {
                val iface = dev.getInterface(i)
                sb.appendLine("    Interface #$i (id=${iface.id}): class=${iface.interfaceClass} subclass=${iface.interfaceSubclass} proto=${iface.interfaceProtocol} endpoints=${iface.endpointCount}")
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    sb.appendLine("      EP #$e: addr=0x${ep.address.toString(16)} type=${ep.type} dir=${ep.direction} maxPkt=${ep.maxPacketSize}")
                }
            }
        }
        sb.appendLine()
        sb.appendLine("=== RECENT LOGS ===")
        for (line in AppState.logs.replayCache) {
            sb.appendLine(line)
        }
        return sb.toString()
    }

    private fun sendResponse(socket: Socket, code: Int, contentType: String, body: String) {
        val reason = when (code) {
            200 -> "OK"
            403 -> "Forbidden"
            404 -> "Not Found"
            else -> "Status"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), false)
        writer.print("HTTP/1.1 $code $reason\r\n")
        writer.print("Content-Type: $contentType\r\n")
        writer.print("Content-Length: ${bytes.size}\r\n")
        writer.print("X-Content-Type-Options: nosniff\r\n")
        writer.print("Cache-Control: no-store\r\n")
        writer.print("Connection: close\r\n\r\n")
        writer.flush()
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverJob?.cancel()
        serverSocket = null
        serverJob = null
    }
}
