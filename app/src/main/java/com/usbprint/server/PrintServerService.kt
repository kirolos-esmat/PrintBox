package com.usbprint.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

class PrintServerService : Service() {

    companion object {
        const val CHANNEL_ID = "usb_print_server_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.usbprint.server.ACTION_START"
        const val ACTION_STOP = "com.usbprint.server.ACTION_STOP"
        const val ACTION_RELOAD_FW = "com.usbprint.server.ACTION_RELOAD_FW"
        const val ACTION_RESCAN = "com.usbprint.server.ACTION_RESCAN"
        const val ACTION_PRINT_TEST_PAGE = "com.usbprint.server.ACTION_PRINT_TEST_PAGE"

        fun rescan(context: Context) {
            val intent = Intent(context, PrintServerService::class.java).apply {
                action = ACTION_RESCAN
            }
            context.startService(intent)
        }

        fun printTestPage(context: Context) {
            val intent = Intent(context, PrintServerService::class.java).apply {
                action = ACTION_PRINT_TEST_PAGE
            }
            context.startService(intent)
        }

        fun start(context: Context) {
            val intent = Intent(context, PrintServerService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PrintServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun reloadFirmware(context: Context) {
            val intent = Intent(context, PrintServerService::class.java).apply {
                action = ACTION_RELOAD_FW
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var serverStarted = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallbackRegistered = false
    @Volatile private var lastRegisteredIp: String? = null
    @Volatile private var airPrintReady = false

    private lateinit var printerManager: PrinterManager
    private lateinit var rawPrintServer: RawPrintServer
    private lateinit var airPrintServer: AirPrintServer
    private lateinit var nsdHelper: NsdHelper
    private lateinit var diagnosticServer: DiagnosticHttpServer

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshNetworkState()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refreshNetworkState()
        override fun onLost(network: Network) = refreshNetworkState()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        AppState.initPersistence(this)

        // Acquire WakeLock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UsbPrintServer::WakeLock").apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L) // 24h fallback
        }

        // Acquire MulticastLock for mDNS
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("UsbPrintServer::Multicast").apply {
            setReferenceCounted(true)
            acquire()
        }

        printerManager = PrinterManager(this, serviceScope)
        rawPrintServer = RawPrintServer(9100, printerManager, serviceScope)
        airPrintServer = AirPrintServer(8631, this, printerManager, serviceScope)
        nsdHelper = NsdHelper(this)
        diagnosticServer = DiagnosticHttpServer(9101, this, printerManager, serviceScope)

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
            networkCallbackRegistered = true
        } catch (e: Exception) {
            AppState.log("Could not register network callback: ${e.message}", isError = true)
        }

        updateDeviceIp()
        startForeground(NOTIFICATION_ID, buildNotification("Starting print server..."))

        // Observe printer state to update foreground notification
        serviceScope.launch {
            AppState.printerState.collectLatest { state ->
                val text = when (state) {
                    PrinterState.DISCONNECTED -> "Printer disconnected (Waiting for USB)"
                    PrinterState.CONNECTING -> "Connecting to USB printer..."
                    PrinterState.PERMISSION_REQUIRED -> "Waiting for USB permission"
                    PrinterState.SWITCHING_SMART_INSTALL -> "Switching HP Smart Install mode..."
                    PrinterState.UPLOADING_FIRMWARE -> "Uploading firmware to printer..."
                    PrinterState.READY -> "Ready - ${AppState.networkAddress.value}"
                    PrinterState.PRINTING -> "Printing in progress..."
                    PrinterState.ERROR -> "Printer error"
                }
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(
                        this@PrintServerService, android.Manifest.permission.POST_NOTIFICATIONS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AppState.log("Service stop requested")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD_FW -> {
                AppState.log("Firmware reload requested from service")
                printerManager.reloadFirmware()
                return START_STICKY
            }
            ACTION_RESCAN -> {
                printerManager.rescan()
                return START_STICKY
            }
            ACTION_PRINT_TEST_PAGE -> {
                serviceScope.launch(Dispatchers.IO) { runTestPagePrint() }
                return START_STICKY
            }
            else -> {
                startServerComponents()
            }
        }
        return START_STICKY
    }

    private fun startServerComponents() {
        updateDeviceIp()
        // Idempotent: repeated start intents (USB attach, activity relaunch) only trigger a rescan.
        if (serverStarted) {
            printerManager.rescan()
            return
        }
        serverStarted = true
        printerManager.start()
        rawPrintServer.start()
        airPrintServer.start { port ->
            airPrintReady = true
            lastRegisteredIp = getLocalIpAddress()
            nsdHelper.registerServices(pdlPort = 9100, ippPort = port)
        }
        diagnosticServer.start()
    }

    private suspend fun runTestPagePrint() {
        if (AppState.printerState.value != PrinterState.READY) {
            AppState.log("Self-test print skipped: printer is not READY", isError = true)
            return
        }
        AppState.log("Generating self-test A4 PDF page...")
        var conversion: ConversionResult? = null
        try {
            val pdfBytes = TestPageGenerator.buildA4TestPdf()
            conversion = AirPrintConverter.convertDocumentToZjs(this, pdfBytes, paperCode = 9)
            val zjsFile = conversion?.zjsFile
            if (conversion != null && zjsFile != null && zjsFile.exists()) {
                val stats = conversion.stats
                val metrics = JobTonerMetrics(
                    pages = stats.pageCount,
                    coveragePercent = stats.coveragePercent,
                    standardPages = stats.standardPageEquivalents(),
                    sourceLabel = "Test Page"
                )
                java.io.FileInputStream(zjsFile).use { input ->
                    val ok = printerManager.printStream(input, metrics) { }
                    if (!ok) AppState.log("Self-test page transfer failed", isError = true)
                }
            }
        } catch (e: Exception) {
            AppState.log("Self-test print error: ${e.message}", isError = true)
        } finally {
            conversion?.zjsFile?.delete()
        }
    }

    private fun updateDeviceIp() {
        val ip = getLocalIpAddress() ?: "127.0.0.1"
        val newAddress = "$ip:9100"
        if (AppState.networkAddress.value != newAddress) {
            AppState.updateNetworkAddress(newAddress)
            AppState.log("Local IP Address: $ip (Port: 9100)")
        }
    }

    @Synchronized
    private fun refreshNetworkState() {
        val ip = getLocalIpAddress()
        val displayIp = ip ?: "127.0.0.1"
        val newAddress = "$displayIp:9100"
        if (AppState.networkAddress.value != newAddress) {
            AppState.updateNetworkAddress(newAddress)
            AppState.log("Local IP Address: $displayIp (Port: 9100)")
        }
        if (serverStarted && airPrintReady && ip != null && ip != lastRegisteredIp) {
            lastRegisteredIp = ip
            AppState.log("Network IP changed to $ip; refreshing mDNS/AirPrint registration...")
            nsdHelper.registerServices(pdlPort = 9100, ippPort = airPrintServer.activePort)
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            AppState.log("Error obtaining IP: ${e.message}", isError = true)
        }
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PrintBox Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps PrintBox AirPrint and USB print bridge active"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PrintBox")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_stat_printbox)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        AppState.log("PrintServerService destroying...")
        if (networkCallbackRegistered) {
            try {
                connectivityManager?.unregisterNetworkCallback(networkCallback)
            } catch (ignored: Exception) {}
            networkCallbackRegistered = false
        }
        nsdHelper.unregisterServices()
        diagnosticServer.stop()
        airPrintServer.stop()
        rawPrintServer.stop()
        printerManager.stop()

        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (ignored: Exception) {}

        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (ignored: Exception) {}

        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
