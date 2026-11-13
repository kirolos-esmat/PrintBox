package com.usbprint.server

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PrinterState {
    DISCONNECTED,
    CONNECTING,
    PERMISSION_REQUIRED,
    SWITCHING_SMART_INSTALL,
    UPLOADING_FIRMWARE,
    READY,
    PRINTING,
    ERROR
}

data class ServerStats(
    val jobsCompleted: Int = 0,
    val totalBytesTransferred: Long = 0,
    val lastJobTime: String = "None",
    val lastJobBytes: Long = 0
)

data class SupplyStatus(
    val available: Boolean = false,
    val cartridgeModel: String = "CE285A",
    val name: String = "Black Toner (CE285A)",
    val percentRemaining: Int = -2,
    val chipState: String = "Unknown",
    val lowReached: Boolean = false,
    val pageCount: Int = -1
)

object AppState {
    private const val TAG = "UsbPrintServer"
    private fun timestamp(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    private fun jobTimestamp(): String = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var persistenceInitialized = false

    private val _logs = MutableSharedFlow<String>(replay = 80, extraBufferCapacity = 80)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    private val _serverStatus = MutableStateFlow("STOPPED")
    val serverStatus: StateFlow<String> = _serverStatus.asStateFlow()

    fun updateServerStatus(status: String) { _serverStatus.value = status }

    private val _printerState = MutableStateFlow(PrinterState.DISCONNECTED)
    val printerState: StateFlow<PrinterState> = _printerState.asStateFlow()

    private val _printerInfo = MutableStateFlow("No USB printer connected")
    val printerInfo: StateFlow<String> = _printerInfo.asStateFlow()

    private val _stats = MutableStateFlow(ServerStats())
    val stats: StateFlow<ServerStats> = _stats.asStateFlow()

    private val _networkAddress = MutableStateFlow("0.0.0.0:9100")
    val networkAddress: StateFlow<String> = _networkAddress.asStateFlow()

    private val _supplyStatus = MutableStateFlow(SupplyStatus())
    val supplyStatus: StateFlow<SupplyStatus> = _supplyStatus.asStateFlow()

    private val _refillState = MutableStateFlow(RefillState())
    val refillState: StateFlow<RefillState> = _refillState.asStateFlow()

    fun initPersistence(context: Context) {
        val appCtx = context.applicationContext ?: context
        appContext = appCtx
        if (persistenceInitialized) return
        synchronized(this) {
            if (persistenceInitialized) return
            persistenceInitialized = true
            val savedLogs = AppMemoryStore.loadLogs(appCtx)
            if (savedLogs.isNotEmpty()) {
                for (line in savedLogs) {
                    _logs.tryEmit(line)
                }
                _logs.tryEmit("--- Restored previous session (${savedLogs.size} lines) ---")
            }
            _stats.value = AppMemoryStore.loadStats(appCtx)
            val cachedSupply = AppMemoryStore.loadCachedSupply(appCtx)
            if (cachedSupply.pageCount >= 0 && !_supplyStatus.value.available) {
                _supplyStatus.value = cachedSupply
            }
            RefillTracker.loadState(appCtx)
        }
    }

    fun updateSupplyStatus(status: SupplyStatus) {
        val resolved = if (!status.available && status.pageCount < 0 && _supplyStatus.value.pageCount >= 0) {
            status.copy(
                pageCount = _supplyStatus.value.pageCount,
                cartridgeModel = _supplyStatus.value.cartridgeModel
            )
        } else {
            status
        }
        _supplyStatus.value = resolved
        if (resolved.available) {
            appContext?.let { AppMemoryStore.saveSupplySnapshot(it, resolved) }
        }
    }

    fun updateRefillState(state: RefillState) {
        _refillState.value = state
    }

    fun effectiveSupplyPercentExact(
        supply: SupplyStatus = _supplyStatus.value,
        refill: RefillState = _refillState.value
    ): Double {
        if (refill.enabled) {
            val est = RefillTracker.estimateRemainingPercentExact(supply.pageCount, refill)
            if (est >= 0.0) return est
        }
        if (!supply.available) return -2.0
        return supply.percentRemaining.toDouble()
    }

    fun effectiveSupplyPercent(
        supply: SupplyStatus = _supplyStatus.value,
        refill: RefillState = _refillState.value
    ): Int {
        if (refill.enabled) {
            val est = RefillTracker.estimateRemainingPercentInt(supply.pageCount, refill)
            if (est >= 0) return est
        }
        if (!supply.available) return -2
        return supply.percentRemaining
    }

    fun log(message: String, isError: Boolean = false) {
        val timestamp = timestamp()
        val formatted = "[$timestamp] $message"
        if (isError) {
            Log.e(TAG, message)
        } else {
            Log.i(TAG, message)
        }
        _logs.tryEmit(formatted)
        appContext?.let { AppMemoryStore.appendLog(it, formatted) }
    }

    fun updatePrinterState(state: PrinterState, details: String? = null) {
        _printerState.value = state
        if (details != null) {
            _printerInfo.value = details
        }
        log("Printer state -> $state${if (details != null) " ($details)" else ""}")
    }

    fun updateNetworkAddress(address: String) {
        _networkAddress.value = address
    }

    fun recordJobCompleted(bytes: Long) {
        val timestamp = jobTimestamp()
        val current = _stats.value
        val updated = current.copy(
            jobsCompleted = current.jobsCompleted + 1,
            totalBytesTransferred = current.totalBytesTransferred + bytes,
            lastJobTime = timestamp,
            lastJobBytes = bytes
        )
        _stats.value = updated
        appContext?.let { AppMemoryStore.saveStats(it, updated) }
        log("Print job completed: ${bytes / 1024} KB transferred (Total jobs: ${updated.jobsCompleted})")
    }
}
