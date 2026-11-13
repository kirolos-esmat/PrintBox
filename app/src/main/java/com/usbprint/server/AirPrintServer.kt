package com.usbprint.server

import android.content.Context
import android.os.SystemClock
import com.hp.jipp.encoding.Attribute
import com.hp.jipp.encoding.AttributeGroup
import com.hp.jipp.encoding.IntType
import com.hp.jipp.encoding.IppInputStream
import com.hp.jipp.encoding.IppOutputStream
import com.hp.jipp.encoding.IppPacket
import com.hp.jipp.encoding.KeywordType
import com.hp.jipp.encoding.NameType
import com.hp.jipp.encoding.Resolution
import com.hp.jipp.encoding.ResolutionUnit
import com.hp.jipp.encoding.StringType
import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.JobState
import com.hp.jipp.model.MediaCol
import com.hp.jipp.model.Operation
import com.hp.jipp.model.PrinterState
import com.hp.jipp.model.Sides
import com.hp.jipp.model.Status
import com.hp.jipp.model.Types
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

class AirPrintServer(
    val desiredPort: Int = 8631,
    private val context: Context,
    private val printerManager: PrinterManager,
    private val scope: CoroutineScope
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var isRunning = false
    var activePort: Int = desiredPort
        private set

    private val jobIdCounter = AtomicInteger(100)
    private class PrintJob(val paperCode: Int) {
        var state = JobState.pending
        var document: ByteArray? = null
        var closed = false
    }
    private val jobs = LinkedHashMap<Int, PrintJob>()
    private val conversionLock = Any()
    private val clientSlots = Semaphore(16)
    private var queuedBytes = 0L
    private val maxQueuedBytes = 64L * 1024 * 1024
    private val maxJobs = 100

    private fun jobGroup(id: Int, job: PrintJob, deviceIp: String): AttributeGroup {
        val reason = when (job.state) {
            JobState.processing -> "job-printing"
            JobState.completed -> "job-completed-successfully"
            JobState.canceled -> "job-canceled-by-user"
            JobState.aborted -> "aborted-by-system"
            else -> "none"
        }
        return AttributeGroup.groupOf(
            Tag.jobAttributes,
            Types.jobId.of(id),
            Types.jobUri.of(URI("ipp://${deviceIp}:${activePort}/jobs/$id")),
            Types.jobState.of(job.state),
            Types.jobStateReasons.of(reason)
        )
    }

    private fun response(ver: Int, reqId: Int, status: Status, vararg groups: AttributeGroup): IppPacket {
        val operation = AttributeGroup.groupOf(
            Tag.operationAttributes,
            Types.attributesCharset.of("utf-8"),
            Types.attributesNaturalLanguage.of("en")
        )
        return IppPacket(ver, status.code, reqId, listOf(operation) + groups)
    }

    private fun jobId(request: IppPacket): Int? {
        val operation = request.attributeGroups.firstOrNull { it.tag == Tag.operationAttributes } ?: return null
        return operation.get(Types.jobId)?.firstOrNull()
            ?: operation.get(Types.jobUri)?.firstOrNull()?.path?.substringAfterLast('/')?.toIntOrNull()
    }


    fun start(onReady: (Int) -> Unit = {}) {
        if (isRunning) return
        isRunning = true

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                // Try port 631 first (standard IPP); if privileged, fall back to desiredPort (8631)
                var ss = ServerSocket()
                var boundPort = 631
                try {
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(631))
                    AppState.log("AirPrint IPP server successfully bound to standard port 631!")
                } catch (e: Exception) {
                    ss.close()
                    ss = ServerSocket()
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(desiredPort))
                    boundPort = desiredPort
                    AppState.log("AirPrint IPP server listening on port $boundPort")
                }

                activePort = boundPort
                serverSocket = ss
                onReady(boundPort)

                while (isActive && !ss.isClosed) {
                    try {
                        val client = ss.accept()
                        if (!clientSlots.tryAcquire()) {
                            client.close()
                        } else {
                            launch(Dispatchers.IO) {
                                try {
                                    handleClient(client)
                                } finally {
                                    clientSlots.release()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (isActive && !ss.isClosed) {
                            AppState.log("AirPrint accept error: ${e.message}", isError = true)
                        }
                    }
                }
            } catch (e: Exception) {
                AppState.log("Could not bind AirPrint server on port $desiredPort: ${e.message}", isError = true)
            } finally {
                isRunning = false
            }
        }
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        val clientIp = socket.inetAddress?.hostAddress ?: "unknown"
        socket.soTimeout = 15000

        try {
            socket.use { s ->
                val input = BufferedInputStream(s.getInputStream(), 65536)
                val out = s.getOutputStream()

                // Keep connection alive for potential multi-request IPP transactions
                while (s.isConnected && !s.isClosed) {
                    val httpRequest = readHttpRequest(input, out) ?: break
                    val ippResponse = processIppRequest(httpRequest.bodyBytes, clientIp)
                    val responseBytes = serializeIppPacket(ippResponse)

                    sendHttpResponse(out, responseBytes, httpRequest.keepAlive)

                    if (!httpRequest.keepAlive) {
                        break
                    }
                }
            }
        } catch (_: EOFException) {
            // Normal client disconnect
        } catch (_: SocketTimeoutException) {
            // Normal keep-alive idle timeout
        } catch (e: SocketException) {
            if (isRunning) AppState.log("AirPrint socket closed ($clientIp): ${e.message}")
        } catch (e: Exception) {
            AppState.log("AirPrint HTTP error from $clientIp: ${e.message}", isError = true)
        }
    }

    private class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val bodyBytes: ByteArray,
        val keepAlive: Boolean
    )

    private fun readHttpRequest(input: InputStream, out: OutputStream): HttpRequest? {
        val headerLines = mutableListOf<String>()
        var line = readLine(input) ?: return null
        if (line.isEmpty()) return null

        val requestLine = line
        while (true) {
            val next = readLine(input) ?: throw java.io.EOFException("Incomplete headers")
            if (next.isEmpty()) break
            if (headerLines.size >= 100) throw java.io.IOException("Too many headers")
            headerLines.add(next)
        }

        val headers = mutableMapOf<String, String>()
        for (h in headerLines) {
            val colon = h.indexOf(':')
            if (colon > 0) {
                val key = h.substring(0, colon).trim().lowercase()
                val value = h.substring(colon + 1).trim()
                val existing = headers[key]
                if (existing != null && key in setOf("content-length", "transfer-encoding")) {
                    throw java.io.IOException("Duplicate header: $key")
                }
                headers[key] = if (existing == null) value else "$existing, $value"
            }
        }

        // Handle Expect: 100-continue (standard for IPP file uploads)
        if (headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
            val continueResp = "HTTP/1.1 100 Continue\r\n\r\n"
            out.write(continueResp.toByteArray(Charsets.US_ASCII))
            out.flush()
        }

        val transferEncoding = headers["transfer-encoding"]
        if (transferEncoding != null && (!transferEncoding.equals("chunked", ignoreCase = true) || headers.containsKey("content-length"))) {
            throw java.io.IOException("Invalid transfer encoding")
        }
        val maxBodySize = 32 * 1024 * 1024
        val body = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        fun readExactly(length: Int) {
            if (length < 0 || length > maxBodySize - body.size()) {
                throw java.io.IOException("Request body too large")
            }
            var remaining = length
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count <= 0) throw java.io.EOFException("Incomplete request body")
                body.write(buffer, 0, count)
                remaining -= count
            }
        }
        if (headers["transfer-encoding"]?.equals("chunked", ignoreCase = true) == true) {
            while (true) {
                val chunkLine = readLine(input) ?: throw java.io.EOFException("Missing chunk size")
                val sizeText = chunkLine.substringBefore(';').trim()
                if (sizeText.isEmpty() || sizeText.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }) {
                    throw java.io.IOException("Invalid chunk size")
                }
                val size = sizeText.toLongOrNull(16) ?: throw java.io.IOException("Invalid chunk size")
                if (size > maxBodySize - body.size()) throw java.io.IOException("Request body too large")
                if (size == 0L) {
                    while (true) {
                        val trailer = readLine(input) ?: throw java.io.EOFException("Incomplete trailers")
                        if (trailer.isEmpty()) break
                    }
                    break
                }
                readExactly(size.toInt())
                if (input.read() != 13 || input.read() != 10) throw java.io.IOException("Invalid chunk delimiter")
            }
        } else {
            val lengthText = headers["content-length"] ?: "0"
            val length = lengthText.toIntOrNull() ?: throw java.io.IOException("Invalid Content-Length")
            readExactly(length)
        }
        val bodyBytes = body.toByteArray()

        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0) ?: "POST"
        val path = parts.getOrNull(1) ?: "/ipp/print"
        val conn = headers["connection"]?.lowercase() ?: "close"
        val keepAlive = conn != "close"

        return HttpRequest(method, path, headers, bodyBytes, keepAlive)
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte == -1) {
                if (line.isEmpty()) return null
                throw java.io.EOFException("Incomplete HTTP line")
            }
            if (byte == 13) {
                if (input.read() != 10) throw java.io.IOException("Invalid HTTP line ending")
                return line.toString()
            }
            if (byte == 10 || line.length >= 8192) throw java.io.IOException("Invalid HTTP line")
            line.append(byte.toChar())
        }
    }

    @Synchronized
    private fun processIppRequest(bodyBytes: ByteArray, clientIp: String): IppPacket {
        if (bodyBytes.size < 8) {
            return IppPacket(Status.clientErrorBadRequest, 1)
        }

        val bais = ByteArrayInputStream(bodyBytes)
        val ippIn = IppInputStream(bais)
        val request = try {
            ippIn.readPacket()
        } catch (e: Exception) {
            AppState.log("IPP parse error from $clientIp: ${e.message}", isError = true)
            return IppPacket(Status.clientErrorBadRequest, 1)
        }

        val op = request.operation
        val reqId = request.requestId
        val ver = request.versionNumber
        val deviceIp = AppState.networkAddress.value.substringBefore(":")
        AppState.log("IPP $op (v${ver shr 8}.${ver and 0xFF}, id $reqId) from $clientIp (${bodyBytes.size} bytes)")

        while (jobs.size >= maxJobs) {
            val finished = jobs.entries.firstOrNull {
                it.value.state in setOf(JobState.completed, JobState.aborted, JobState.canceled)
            } ?: break
            jobs.remove(finished.key)
        }
        return when (op) {
            Operation.getPrinterAttributes -> {
                buildGetPrinterAttributesResponse(ver, reqId, deviceIp)
            }
            Operation.validateJob -> {
                IppPacket(
                    ver,
                    Status.successfulOk.code,
                    reqId,
                    AttributeGroup.groupOf(
                        Tag.operationAttributes,
                        Types.attributesCharset.of("utf-8"),
                        Types.attributesNaturalLanguage.of("en")
                    )
                )
            }
            Operation.createJob -> {
                if (jobs.size >= maxJobs) return response(ver, reqId, Status.serverErrorBusy)
                val id = jobIdCounter.incrementAndGet()
                val job = PrintJob(paperCode(request))
                jobs[id] = job
                scope.launch {
                    delay(300_000)
                    synchronized(this@AirPrintServer) {
                        if (job.state == JobState.pending && !job.closed) {
                            job.document = null
                            job.state = JobState.aborted
                        }
                    }
                }
                response(ver, reqId, Status.successfulOk, jobGroup(id, job, deviceIp))
            }
            Operation.sendDocument -> {
                val id = jobId(request) ?: return response(ver, reqId, Status.clientErrorBadRequest)
                val job = jobs[id] ?: return response(ver, reqId, Status.clientErrorNotFound)
                if (job.state != JobState.pending || job.closed) {
                    return response(ver, reqId, Status.clientErrorNotPossible)
                }
                val last = request.getValue(Tag.operationAttributes, Types.lastDocument)
                    ?: return response(ver, reqId, Status.clientErrorBadRequest)
                val document = ippIn.readBytes()
                if (document.isNotEmpty()) {
                    if (job.document != null) {
                        return response(ver, reqId, Status.serverErrorMultipleDocumentJobsNotSupported)
                    }
                    val stagedBytes = jobs.values.sumOf { it.document?.size?.toLong() ?: 0L }
                    if (stagedBytes + queuedBytes + document.size > maxQueuedBytes) {
                        return response(ver, reqId, Status.serverErrorBusy)
                    }
                    job.document = document
                } else if (!last) {
                    return response(ver, reqId, Status.clientErrorBadRequest)
                }
                if (last) closeJob(id, job)
                response(ver, reqId, Status.successfulOk, jobGroup(id, job, deviceIp))
            }
            Operation.printJob -> {
                val document = ippIn.readBytes()
                if (document.isEmpty()) return response(ver, reqId, Status.clientErrorBadRequest)
                if (jobs.size >= maxJobs || queuedBytes + jobs.values.sumOf { it.document?.size?.toLong() ?: 0L } + document.size > maxQueuedBytes) {
                    return response(ver, reqId, Status.serverErrorBusy)
                }
                val id = jobIdCounter.incrementAndGet()
                val job = PrintJob(paperCode(request))
                job.document = document
                jobs[id] = job
                closeJob(id, job)
                response(ver, reqId, Status.successfulOk, jobGroup(id, job, deviceIp))
            }
            Operation.getJobAttributes -> {
                val id = jobId(request) ?: return response(ver, reqId, Status.clientErrorBadRequest)
                val job = jobs[id] ?: return response(ver, reqId, Status.clientErrorNotFound)
                response(ver, reqId, Status.successfulOk, jobGroup(id, job, deviceIp))
            }
            Operation.cancelJob -> {
                val id = jobId(request) ?: return response(ver, reqId, Status.clientErrorBadRequest)
                val job = jobs[id] ?: return response(ver, reqId, Status.clientErrorNotFound)
                if (job.state != JobState.pending) {
                    return response(ver, reqId, Status.clientErrorNotPossible)
                }
                job.document = null
                job.state = JobState.canceled
                response(ver, reqId, Status.successfulOk)
            }
            Operation.getJobs -> {
                val which = request.getValue(Tag.operationAttributes, Types.whichJobs) ?: "not-completed"
                val terminal = setOf(JobState.completed, JobState.canceled, JobState.aborted)
                val selected = jobs.filterValues { job ->
                    when (which) {
                        "all" -> true
                        "not-completed" -> job.state !in terminal
                        "completed" -> job.state == JobState.completed
                        "canceled" -> job.state == JobState.canceled
                        "aborted" -> job.state == JobState.aborted
                        "pending" -> job.state == JobState.pending
                        "processing" -> job.state == JobState.processing
                        else -> false
                    }
                }
                val limit = request.getValue(Tag.operationAttributes, Types.limit)
                if (limit != null && limit < 1) return response(ver, reqId, Status.clientErrorBadRequest)
                val groups = selected.entries.take(limit ?: selected.size).map { (id, job) -> jobGroup(id, job, deviceIp) }
                response(ver, reqId, Status.successfulOk, *groups.toTypedArray())
            }
            else -> response(ver, reqId, Status.serverErrorOperationNotSupported)
        }
    }

    private fun paperCode(request: IppPacket): Int =
        if (request.getValue(Tag.jobAttributes, Types.media)?.keyword == "na_letter_8.5x11in") 1 else 9

    private fun closeJob(id: Int, job: PrintJob) {
        job.closed = true
        val document = job.document
        job.document = null
        if (document == null) {
            job.state = JobState.completed
        } else {
            queuedBytes += document.size
            scope.launch(Dispatchers.IO) { runPrintJob(id, job, document) }
        }
    }

    private fun buildGetPrinterAttributesResponse(ver: Int, reqId: Int, deviceIp: String): IppPacket {
        val upTimeSeconds = (SystemClock.elapsedRealtime() / 1000).toInt()
        val state = AppState.printerState.value
        val ready = state == com.usbprint.server.PrinterState.READY
        val printing = state == com.usbprint.server.PrinterState.PRINTING

        val printerAttrs = mutableListOf<Attribute<*>>(
            Types.printerName.of("HP LaserJet Pro P1102"),
            Types.printerMakeAndModel.of("HP LaserJet Professional P1102"),
            Types.printerInfo.of("HP LaserJet Professional P1102 (MiBox)"),
            Types.printerLocation.of("Local"),
            Types.printerMoreInfo.of(URI("http://${deviceIp}:9101/")),
            Types.printerState.of(if (printing) PrinterState.processing else if (ready) PrinterState.idle else PrinterState.stopped),
            Types.printerStateReasons.of(if (ready || printing) "none" else "offline"),
            Types.printerIsAcceptingJobs.of(ready || printing),
            Types.printerUpTime.of(upTimeSeconds),
            Types.printerUriSupported.of(URI("ipp://${deviceIp}:${activePort}/ipp/print")),
            Types.uriSecuritySupported.of("none"),
            Types.uriAuthenticationSupported.of("none"),
            Types.documentFormatSupported.of(
                "application/pdf",
                RasterToPbm.URF_MIME,
                RasterToPbm.PWG_MIME,
                "image/jpeg",
                "image/png",
                "image/webp",
                "application/octet-stream"
            ),
            Types.ippFeaturesSupported.of("ipp-everywhere"),
            Types.pwgRasterDocumentTypeSupported.of("black_1", "sgray_8"),
            Types.pwgRasterDocumentSheetBack.of("normal"),
            Types.pwgRasterDocumentResolutionSupported.of(Resolution(600, 600, ResolutionUnit.dotsPerInch)),
            Types.documentFormatDefault.of("application/pdf"),
            Types.colorSupported.of(false),
            Types.printColorModeSupported.of("monochrome"),
            Types.printColorModeDefault.of("monochrome"),
            Types.charsetConfigured.of("utf-8"),
            Types.charsetSupported.of("utf-8"),
            Types.naturalLanguageConfigured.of("en"),
            Types.generatedNaturalLanguageSupported.of("en"),
            Types.ippVersionsSupported.of("1.1", "2.0"),
            Types.compressionSupported.of("none"),
            Types.multipleDocumentJobsSupported.of(false),
            Types.multipleOperationTimeOut.of(300),
            Types.mediaDefault.of("iso_a4_210x297mm"),
            Types.mediaSupported.of("iso_a4_210x297mm", "na_letter_8.5x11in"),
            Types.mediaReady.of("iso_a4_210x297mm"),
            Types.mediaColDefault.of(MediaCol(
                mediaBottomMargin = 400,
                mediaTopMargin = 400,
                mediaLeftMargin = 400,
                mediaRightMargin = 400
            )),
            Types.mediaBottomMarginSupported.of(400),
            Types.mediaTopMarginSupported.of(400),
            Types.mediaLeftMarginSupported.of(400),
            Types.mediaRightMarginSupported.of(400),
            Types.copiesSupported.of(IntRange(1, 1)),
            Types.copiesDefault.of(1),
            Types.sidesSupported.of(Sides.oneSided),
            Types.sidesDefault.of(Sides.oneSided),
            Types.pdlOverrideSupported.of("not-attempted"),
            Types.operationsSupported.of(
                Operation.printJob.code,
                Operation.validateJob.code,
                Operation.createJob.code,
                Operation.sendDocument.code,
                Operation.cancelJob.code,
                Operation.getPrinterAttributes.code,
                Operation.getJobAttributes.code,
                Operation.getJobs.code
            ),
            Types.printerResolutionSupported.of(
                Resolution(600, 600, ResolutionUnit.dotsPerInch)
            ),
            Types.printerResolutionDefault.of(
                Resolution(600, 600, ResolutionUnit.dotsPerInch)
            ),
            StringType.Set(Tag.keyword, "urf-supported").of(
                "W8", "SRGB24", "CP1", "RS300", "RS600"
            ),
            Types.printerUuid.of(URI("urn:uuid:e48d3c50-256b-4f9e-8c9a-b44e05b5f631"))
        )

        val supply = AppState.supplyStatus.value
        if (supply.available) {
            val effectivePct = AppState.effectiveSupplyPercent(supply)
            printerAttrs.add(NameType.Set("marker-names").of(supply.name))
            printerAttrs.add(NameType.Set("marker-colors").of("#000000"))
            printerAttrs.add(KeywordType.Set("marker-types").of("toner"))
            printerAttrs.add(IntType.Set("marker-levels").of(effectivePct.coerceIn(-2, 100)))
            printerAttrs.add(IntType.Set("marker-low-levels").of(10))
            printerAttrs.add(IntType.Set("marker-high-levels").of(100))
        }

        return IppPacket(
            ver,
            Status.successfulOk.code,
            reqId,
            AttributeGroup.groupOf(
                Tag.operationAttributes,
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en")
            ),
            AttributeGroup.groupOf(Tag.printerAttributes, printerAttrs)
        )
    }

    private suspend fun runPrintJob(jobId: Int, job: PrintJob, document: ByteArray) {
        synchronized(this) {
            if (job.state == JobState.canceled) {
                queuedBytes -= document.size
                return
            }
            job.state = JobState.processing
        }
        var conversion: ConversionResult? = null
        var success = false
        try {
            conversion = synchronized(conversionLock) {
                AirPrintConverter.convertDocumentToZjs(context, document, job.paperCode)
            }
            val file = conversion?.zjsFile
            if (conversion != null && file != null && file.exists()) {
                val stats = conversion.stats
                val metrics = JobTonerMetrics(
                    pages = stats.pageCount,
                    coveragePercent = stats.coveragePercent,
                    standardPages = stats.standardPageEquivalents(),
                    sourceLabel = conversion.documentType
                )
                FileInputStream(file).use { input ->
                    success = printerManager.printStream(input, metrics) { }
                }
            }
        } catch (e: Exception) {
            AppState.log("AirPrint job #$jobId failed: ${e.message}", isError = true)
        } finally {
            conversion?.zjsFile?.delete()
            synchronized(this) {
                queuedBytes -= document.size
                job.state = if (success) JobState.completed else JobState.aborted
            }
        }
        AppState.log("AirPrint job #$jobId ${if (success) "completed" else "failed"}", isError = !success)
    }

    private fun serializeIppPacket(packet: IppPacket): ByteArray {
        val baos = ByteArrayOutputStream()
        IppOutputStream(baos).write(packet)
        return baos.toByteArray()
    }

    private fun sendHttpResponse(out: OutputStream, responseBytes: ByteArray, keepAlive: Boolean) {
        val connHeader = if (keepAlive) "keep-alive" else "close"
        val header = "HTTP/1.1 200 OK\r\n" +
                "Server: MiBox-AirPrint/1.0\r\n" +
                "Content-Type: application/ipp\r\n" +
                "Content-Length: ${responseBytes.size}\r\n" +
                "Connection: $connHeader\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(responseBytes)
        out.flush()
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {}
        serverJob?.cancel()
        serverSocket = null
        serverJob = null
        AppState.log("AirPrint IPP server stopped.")
    }
}
