package com.usbprint.server

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var logJob: JobHolder = JobHolder()
    private var showLogs = false

    private lateinit var tvHeaderEndpoints: TextView
    private lateinit var tvServiceBadge: TextView
    private lateinit var tvPrinterBadge: TextView
    private lateinit var tvUsbEndpointMeta: TextView
    private lateinit var tvPrinterStatus: TextView
    private lateinit var tvPrinterDetails: TextView
    private lateinit var tvRecoveryHint: TextView
    private lateinit var tvSupplyModeBadge: TextView
    private lateinit var tvSupplyPercent: TextView
    private lateinit var pbTonerLevel: ProgressBar
    private lateinit var tvSupplyDetails: TextView
    private lateinit var tvJobStats: TextView
    private lateinit var tvDiagUrl: TextView
    private lateinit var tvSummaryNetwork: TextView
    private lateinit var tvLastJob: TextView
    private lateinit var tvLifetimePages: TextView
    private lateinit var layoutRecentActivity: LinearLayout
    private lateinit var scrollLogs: NestedScrollView
    private lateinit var tvLogs: TextView

    private lateinit var btnRescan: Button
    private lateinit var btnTestPage: Button
    private lateinit var btnReloadFirmware: Button
    private lateinit var btnMarkRefilled: Button
    private lateinit var btnToggleLogs: Button
    private lateinit var btnRestartService: Button

    private class JobHolder {
        var job: kotlinx.coroutines.Job? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })

        AppState.initPersistence(this)
        initViews()
        setupListeners()
        observeState()
        requestIgnoreBatteryOptimizations()

        PrintServerService.start(this)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        AppState.log("MainActivity received new intent: ${intent?.action}")
        PrintServerService.start(this)
    }

    private fun initViews() {
        tvHeaderEndpoints = findViewById(R.id.tvHeaderEndpoints)
        tvServiceBadge = findViewById(R.id.tvServiceBadge)
        tvPrinterBadge = findViewById(R.id.tvPrinterBadge)
        tvUsbEndpointMeta = findViewById(R.id.tvUsbEndpointMeta)
        tvPrinterStatus = findViewById(R.id.tvPrinterStatus)
        tvPrinterDetails = findViewById(R.id.tvPrinterDetails)
        tvRecoveryHint = findViewById(R.id.tvRecoveryHint)
        tvSupplyModeBadge = findViewById(R.id.tvSupplyModeBadge)
        tvSupplyPercent = findViewById(R.id.tvSupplyPercent)
        pbTonerLevel = findViewById(R.id.pbTonerLevel)
        tvSupplyDetails = findViewById(R.id.tvSupplyDetails)
        tvJobStats = findViewById(R.id.tvJobStats)
        tvDiagUrl = findViewById(R.id.tvDiagUrl)
        tvSummaryNetwork = findViewById(R.id.tvSummaryNetwork)
        tvLastJob = findViewById(R.id.tvLastJob)
        tvLifetimePages = findViewById(R.id.tvLifetimePages)
        layoutRecentActivity = findViewById(R.id.layoutRecentActivity)
        scrollLogs = findViewById(R.id.scrollLogs)
        tvLogs = findViewById(R.id.tvLogs)

        btnRescan = findViewById(R.id.btnRescan)
        btnTestPage = findViewById(R.id.btnTestPage)
        btnReloadFirmware = findViewById(R.id.btnReloadFirmware)
        btnMarkRefilled = findViewById(R.id.btnMarkRefilled)
        btnToggleLogs = findViewById(R.id.btnToggleLogs)
        btnRestartService = findViewById(R.id.btnRestartService)
        btnRescan.requestFocus()
    }

    private fun setupListeners() {
        btnRescan.setOnClickListener {
            AppState.log("User clicked 'Rescan USB'")
            PrintServerService.rescan(this)
        }

        btnTestPage.setOnClickListener {
            AppState.log("User clicked 'Print Test Page'")
            PrintServerService.printTestPage(this)
        }

        btnReloadFirmware.setOnClickListener {
            AppState.log("User clicked 'Reload Firmware'")
            PrintServerService.reloadFirmware(this)
        }

        btnMarkRefilled.setOnClickListener {
            val refill = AppState.refillState.value
            val supply = AppState.supplyStatus.value
            if (refill.enabled) {
                RefillTracker.clearRefill(this)
            } else {
                RefillTracker.markRefilled(this, maxOf(0, supply.pageCount))
            }
        }

        btnToggleLogs.setOnClickListener {
            showLogs = !showLogs
            layoutRecentActivity.visibility = if (showLogs) View.GONE else View.VISIBLE
            scrollLogs.visibility = if (showLogs) View.VISIBLE else View.GONE
            btnToggleLogs.text = getString(
                if (showLogs) R.string.btn_show_summary else R.string.btn_tech_logs
            )
            if (showLogs) {
                scrollLogs.post {
                    scrollLogs.getChildAt(0)?.let { scrollLogs.scrollTo(0, it.height) }
                }
            }
        }

        btnRestartService.setOnClickListener {
            AppState.log("User clicked 'Restart Server'")
            PrintServerService.stop(this)
            lifecycleScope.launch {
                kotlinx.coroutines.delay(1000)
                PrintServerService.start(this@MainActivity)
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            AppState.serverStatus.collectLatest { status ->
                val (label, bgColor, borderColor, summaryText, summaryColor) = when (status) {
                    "LISTENING" -> PillStyle("SERVER ONLINE", R.color.pill_ready_bg, R.color.pill_ready_border, "Active (:8631 & :9100)", R.color.accent_green)
                    "STARTING" -> PillStyle("SERVER STARTING", R.color.pill_busy_bg, R.color.pill_busy_border, "Starting listeners…", R.color.accent_blue)
                    "ERROR" -> PillStyle("SERVER ERROR", R.color.pill_err_bg, R.color.pill_err_border, "Socket error", R.color.accent_red)
                    else -> PillStyle("SERVER STOPPED", R.color.pill_warn_bg, R.color.pill_warn_border, "Stopped", R.color.accent_orange)
                }
                tvServiceBadge.text = label
                stylePill(tvServiceBadge, bgColor, borderColor)
                tvSummaryNetwork.text = summaryText
                tvSummaryNetwork.setTextColor(ContextCompat.getColor(this@MainActivity, summaryColor))
            }
        }

        lifecycleScope.launch {
            AppState.networkAddress.collectLatest { address ->
                val ip = address.substringBefore(":")
                tvHeaderEndpoints.text = "Raw TCP $address • AirPrint IPP :8631 • Diagnostics :9101"
                tvUsbEndpointMeta.text = "AirPrint $ip:8631"
                tvDiagUrl.text = "Web Diagnostics: http://$ip:9101/"
                if (AppState.printerState.value == PrinterState.READY) {
                    tvRecoveryHint.text = "Ready to print from Mac, iPhone, or Windows on $ip"
                }
            }
        }

        lifecycleScope.launch {
            combine(AppState.printerState, AppState.printerInfo, AppState.networkAddress) { state, info, addr ->
                Triple(state, info, addr.substringBefore(":"))
            }.collectLatest { (state, info, ip) ->
                val (badgeText, headline, hintText, bgRes, borderRes, headlineColor) = when (state) {
                    PrinterState.DISCONNECTED -> HeroStyle(
                        "USB DISCONNECTED",
                        "Printer Not Detected",
                        "Check that the printer power switch is ON and USB cable is plugged into the Mi Box, then press Rescan USB.",
                        R.color.pill_warn_bg, R.color.pill_warn_border, R.color.accent_orange
                    )
                    PrinterState.CONNECTING -> HeroStyle(
                        "CONNECTING",
                        "Opening USB Printer…",
                        "Claiming USB printer interface and reading IEEE-1284 device ID.",
                        R.color.pill_busy_bg, R.color.pill_busy_border, R.color.accent_blue
                    )
                    PrinterState.PERMISSION_REQUIRED -> HeroStyle(
                        "PERMISSION NEEDED",
                        "Approve USB Access",
                        "Accept the USB permission popup on your TV screen to connect the printer.",
                        R.color.pill_warn_bg, R.color.pill_warn_border, R.color.accent_orange
                    )
                    PrinterState.SWITCHING_SMART_INSTALL -> HeroStyle(
                        "SMART INSTALL",
                        "Switching HP USB Mode…",
                        "Disabling virtual CD mode on HP LaserJet P1102. Wait a few seconds or power-cycle the printer.",
                        R.color.pill_busy_bg, R.color.pill_busy_border, R.color.status_switching
                    )
                    PrinterState.UPLOADING_FIRMWARE -> HeroStyle(
                        "LOADING FIRMWARE",
                        "Uploading sihpP1102.dl…",
                        "Sending HP P1102 printer firmware over USB. Printing will unlock in ~3 seconds.",
                        R.color.pill_warn_bg, R.color.pill_warn_border, R.color.accent_orange
                    )
                    PrinterState.READY -> HeroStyle(
                        "READY TO PRINT",
                        "HP LaserJet Pro P1102",
                        "Ready to print from Mac, iPhone, or Windows on $ip",
                        R.color.pill_ready_bg, R.color.pill_ready_border, R.color.white
                    )
                    PrinterState.PRINTING -> HeroStyle(
                        "PRINTING NOW",
                        "Sending Print Job…",
                        "Streaming raster data over USB Bulk OUT. Please wait until page feeding completes.",
                        R.color.pill_busy_bg, R.color.pill_busy_border, R.color.status_printing
                    )
                    PrinterState.ERROR -> HeroStyle(
                        "PRINTER ERROR",
                        "USB Transfer Interrupted",
                        "Check USB cable connection and printer paper tray, then press Rescan USB or Reload Firmware.",
                        R.color.pill_err_bg, R.color.pill_err_border, R.color.accent_red
                    )
                }
                tvPrinterBadge.text = badgeText
                stylePill(tvPrinterBadge, bgRes, borderRes)
                tvPrinterStatus.text = headline
                tvPrinterStatus.setTextColor(ContextCompat.getColor(this@MainActivity, headlineColor))
                tvPrinterDetails.text = info
                tvRecoveryHint.text = hintText
            }
        }

        lifecycleScope.launch {
            AppState.stats.collectLatest { stats ->
                tvJobStats.text = "Total: ${stats.jobsCompleted} job(s) • ${AppMemoryStore.formatBytes(stats.totalBytesTransferred)}"
                tvLastJob.text = when {
                    stats.lastJobTime == "None" -> "None yet"
                    stats.lastJobBytes > 0L -> "${stats.lastJobTime} (${AppMemoryStore.formatBytes(stats.lastJobBytes)})"
                    else -> stats.lastJobTime
                }
            }
        }

        lifecycleScope.launch {
            combine(AppState.supplyStatus, AppState.refillState) { supply, refill ->
                supply to refill
            }.collectLatest { (supply, refill) ->
                btnMarkRefilled.text = getString(
                    if (refill.enabled) R.string.btn_use_chip else R.string.btn_mark_refilled
                )
                val refillExactPct = if (refill.enabled) {
                    RefillTracker.estimateRemainingPercentExact(supply.pageCount, refill)
                } else -2.0

                if (refill.enabled && refillExactPct >= 0.0) {
                    val estPctInt = refillExactPct.toInt().coerceIn(0, 100)
                    val pagesPrinted = RefillTracker.effectivePagesPrinted(supply.pageCount, refill)
                    val stdUsed = RefillTracker.effectiveStandardPagesUsed(supply.pageCount, refill)
                    val levelColor = ContextCompat.getColor(
                        this@MainActivity,
                        when {
                            refillExactPct <= 10.0 -> R.color.accent_red
                            refillExactPct <= 25.0 -> R.color.accent_orange
                            else -> R.color.accent_green
                        }
                    )
                    tvSupplyModeBadge.text = "Refill Est."
                    tvSupplyPercent.setTextColor(levelColor)
                    tvSupplyPercent.text = "~${RefillTracker.formatPercent(refillExactPct)}% (${supply.cartridgeModel})"
                    pbTonerLevel.progress = estPctInt
                    pbTonerLevel.progressTintList = ColorStateList.valueOf(levelColor)
                    val stdText = String.format(Locale.US, "%.1f", stdUsed)
                    val lastText = if (refill.lastJobCoveragePercent >= 0.0) {
                        " • Last: ${RefillTracker.formatPercent(refill.lastJobCoveragePercent)}% ink"
                    } else ""
                    tvSupplyDetails.text = "$pagesPrinted pages printed ($stdText std)$lastText"
                    tvLifetimePages.text = if (supply.pageCount >= 0) "${supply.pageCount} lifetime pages" else "Waiting for printer…"
                } else if (!supply.available) {
                    tvSupplyModeBadge.text = if (refill.enabled) "Refill Est." else "Chip"
                    tvSupplyPercent.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    tvSupplyPercent.text = getString(R.string.initial_supply_value)
                    pbTonerLevel.progress = 0
                    pbTonerLevel.progressTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(this@MainActivity, R.color.text_secondary)
                    )
                    tvSupplyDetails.text = getString(R.string.initial_supply_details)
                    tvLifetimePages.text = "Waiting for printer…"
                } else {
                    val pct = supply.percentRemaining
                    val levelColor = ContextCompat.getColor(
                        this@MainActivity,
                        when {
                            supply.lowReached || pct in 0..10 -> R.color.accent_orange
                            pct in 11..25 -> R.color.accent_orange
                            else -> R.color.accent_green
                        }
                    )
                    tvSupplyModeBadge.text = "Chip"
                    tvSupplyPercent.setTextColor(levelColor)
                    tvSupplyPercent.text = if (pct >= 0) "$pct% (${supply.cartridgeModel})" else "Unknown"
                    pbTonerLevel.progress = pct.coerceIn(0, 100)
                    pbTonerLevel.progressTintList = ColorStateList.valueOf(levelColor)
                    val chipText = if (supply.lowReached) "${supply.chipState} / Refill OK" else supply.chipState
                    tvSupplyDetails.text = "Chip: $chipText"
                    tvLifetimePages.text = if (supply.pageCount >= 0) "${supply.pageCount} lifetime pages" else "Unknown"
                }
            }
        }
    }

    private fun stylePill(view: TextView, fillColorRes: Int, strokeColorRes: Int) {
        val drawable = ContextCompat.getDrawable(this, R.drawable.tv_pill_bg)?.mutate() as? GradientDrawable ?: return
        drawable.setColor(ContextCompat.getColor(this, fillColorRes))
        drawable.setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1), ContextCompat.getColor(this, strokeColorRes))
        view.background = drawable
    }

    override fun onStart() {
        super.onStart()
        tvLogs.text = ""
        logJob.job = lifecycleScope.launch {
            val pending = StringBuilder()
            val collector = launch { AppState.logs.collect { pending.append(it).append('\n') } }
            try {
                while (true) {
                    kotlinx.coroutines.delay(250)
                    if (pending.isEmpty()) continue
                    val follow = !scrollLogs.canScrollVertically(1)
                    tvLogs.append(pending.toString())
                    pending.clear()
                    val text = tvLogs.text
                    if (text.length > 20000) tvLogs.text = text.subSequence(text.length - 12000, text.length)
                    if (follow && showLogs) scrollLogs.post {
                        scrollLogs.getChildAt(0)?.let { scrollLogs.scrollTo(0, it.height) }
                    }
                }
            } finally {
                collector.cancel()
            }
        }
    }

    override fun onStop() {
        logJob.job?.cancel()
        logJob.job = null
        super.onStop()
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                AppState.log("Could not request battery ignore: ${e.message}")
            }
        }
    }

    private data class PillStyle(
        val label: String,
        val bgColor: Int,
        val borderColor: Int,
        val summaryText: String,
        val summaryColor: Int
    )

    private data class HeroStyle(
        val badgeText: String,
        val headline: String,
        val hintText: String,
        val bgRes: Int,
        val borderRes: Int,
        val headlineColor: Int
    )
}
