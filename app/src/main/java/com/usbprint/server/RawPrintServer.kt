package com.usbprint.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import java.io.BufferedInputStream
import java.io.SequenceInputStream
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

class RawPrintServer(
    private val port: Int = 9100,
    private val printerManager: PrinterManager,
    private val scope: CoroutineScope
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var isRunning = false
    private val clientSlots = Semaphore(8)

    fun start() {
        if (isRunning) return
        isRunning = true
        AppState.updateServerStatus("STARTING")

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                AppState.updateServerStatus("LISTENING")
                AppState.log("Raw TCP Print Server listening on port $port")

                while (isActive && !ss.isClosed) {
                    try {
                        val clientSocket = ss.accept()
                        if (!clientSlots.tryAcquire()) {
                            clientSocket.close()
                        } else {
                            launch(Dispatchers.IO) {
                                try {
                                    handleClient(clientSocket)
                                } finally {
                                    clientSlots.release()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (isActive && !ss.isClosed) {
                            AppState.log("Server accept error: ${e.message}", isError = true)
                        }
                    }
                }
            } catch (e: Exception) {
                AppState.updateServerStatus("ERROR")
                AppState.log("Could not bind ServerSocket on port $port: ${e.message}", isError = true)
            } finally {
                isRunning = false
            }
        }
    }

    private suspend fun handleClient(clientSocket: Socket) = withContext(Dispatchers.IO) {
        val clientIp = clientSocket.inetAddress?.hostAddress ?: "unknown"
        val clientPort = clientSocket.port
        AppState.log("Incoming print connection from $clientIp:$clientPort")

        clientSocket.use { socket ->
            socket.soTimeout = 5000
            socket.tcpNoDelay = true

            val input = BufferedInputStream(socket.getInputStream(), 16384)
            val firstByte = try {
                input.read()
            } catch (e: SocketTimeoutException) {
                AppState.log("Idle print connection closed for $clientIp")
                return@use
            }
            if (firstByte < 0) return@use
            socket.soTimeout = 30000
            val document = SequenceInputStream(ByteArrayInputStream(byteArrayOf(firstByte.toByte())), input)
            var lastReport = 0L

            val success = printerManager.printStream(document) { bytesWritten ->
                if (bytesWritten - lastReport >= 100 * 1024) { // every 100 KB
                    AppState.log("Job progress: ${bytesWritten / 1024} KB sent to printer...")
                    lastReport = bytesWritten
                }
            }

            if (success) {
                AppState.log("Job completed successfully for client $clientIp")
            } else {
                AppState.log("Client connection closed for $clientIp")
            }
        }
    }

    fun stop() {
        isRunning = false
        AppState.updateServerStatus("STOPPED")
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {}
        serverJob?.cancel()
        serverSocket = null
        serverJob = null
        AppState.log("Raw TCP Print Server stopped.")
    }
}
