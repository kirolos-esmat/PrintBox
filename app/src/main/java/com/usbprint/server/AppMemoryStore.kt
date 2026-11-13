package com.usbprint.server

import android.content.Context
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Persists job counters, last-job metadata, last supply snapshot, and recent log history
 * so app upgrades, restarts, and TV reboots never reset runtime state back to zero.
 */
internal object AppMemoryStore {
    private const val PREFS_NAME = "usb_print_prefs"
    private const val KEY_JOBS_COMPLETED = "mem_jobs_completed"
    private const val KEY_TOTAL_BYTES = "mem_total_bytes"
    private const val KEY_LAST_JOB_TIME = "mem_last_job_time"
    private const val KEY_LAST_JOB_BYTES = "mem_last_job_bytes"
    private const val KEY_STATS_SEED_VERSION = "mem_stats_seed_version"

    private const val KEY_LAST_CHIP_PAGES = "mem_last_chip_pages"
    private const val KEY_LAST_CHIP_MODEL = "mem_last_chip_model"
    private const val KEY_LAST_CHIP_STATE = "mem_last_chip_state"
    private const val KEY_LAST_CHIP_PCT = "mem_last_chip_pct"
    private const val KEY_LAST_CHIP_LOW = "mem_last_chip_low"

    private const val CURRENT_STATS_SEED_VERSION = 1
    private const val LOGS_FILENAME = "logs_history.txt"
    private const val MAX_PERSISTED_LOGS = 80

    private val logLock = Any()
    private val recentLines = ArrayDeque<String>(MAX_PERSISTED_LOGS)
    private val ioExecutor = Executors.newSingleThreadExecutor()

    fun loadStats(context: Context): ServerStats {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_STATS_SEED_VERSION, 0) < CURRENT_STATS_SEED_VERSION) {
            val seeded = ServerStats(
                jobsCompleted = 3,
                totalBytesTransferred = 0L,
                lastJobTime = "Initial (3 PDFs)",
                lastJobBytes = 0L
            )
            saveStats(context, seeded)
            return seeded
        }
        return ServerStats(
            jobsCompleted = prefs.getInt(KEY_JOBS_COMPLETED, 0).coerceAtLeast(0),
            totalBytesTransferred = prefs.getLong(KEY_TOTAL_BYTES, 0L).coerceAtLeast(0L),
            lastJobTime = prefs.getString(KEY_LAST_JOB_TIME, "None") ?: "None",
            lastJobBytes = prefs.getLong(KEY_LAST_JOB_BYTES, 0L).coerceAtLeast(0L)
        )
    }

    fun saveStats(context: Context, stats: ServerStats) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_JOBS_COMPLETED, stats.jobsCompleted)
            .putLong(KEY_TOTAL_BYTES, stats.totalBytesTransferred)
            .putString(KEY_LAST_JOB_TIME, stats.lastJobTime)
            .putLong(KEY_LAST_JOB_BYTES, stats.lastJobBytes)
            .putInt(KEY_STATS_SEED_VERSION, CURRENT_STATS_SEED_VERSION)
            .apply()
    }

    fun loadCachedSupply(context: Context): SupplyStatus {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pageCount = prefs.getInt(KEY_LAST_CHIP_PAGES, -1)
        if (pageCount < 0) return SupplyStatus()
        return SupplyStatus(
            available = false,
            cartridgeModel = prefs.getString(KEY_LAST_CHIP_MODEL, "CE285A") ?: "CE285A",
            percentRemaining = prefs.getInt(KEY_LAST_CHIP_PCT, -2),
            chipState = prefs.getString(KEY_LAST_CHIP_STATE, "Cached") ?: "Cached",
            lowReached = prefs.getBoolean(KEY_LAST_CHIP_LOW, false),
            pageCount = pageCount
        )
    }

    fun saveSupplySnapshot(context: Context, supply: SupplyStatus) {
        if (!supply.available) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_LAST_CHIP_PAGES, supply.pageCount)
            .putString(KEY_LAST_CHIP_MODEL, supply.cartridgeModel)
            .putString(KEY_LAST_CHIP_STATE, supply.chipState)
            .putInt(KEY_LAST_CHIP_PCT, supply.percentRemaining)
            .putBoolean(KEY_LAST_CHIP_LOW, supply.lowReached)
            .apply()
    }

    fun loadLogs(context: Context): List<String> {
        val file = File(context.filesDir, LOGS_FILENAME)
        if (!file.exists()) return emptyList()
        return try {
            val loaded = file.readLines()
                .filter { it.isNotBlank() }
                .takeLast(MAX_PERSISTED_LOGS)
            synchronized(logLock) {
                recentLines.clear()
                recentLines.addAll(loaded)
            }
            loaded
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun appendLog(context: Context, formattedLine: String) {
        val snapshot: List<String> = synchronized(logLock) {
            if (recentLines.size >= MAX_PERSISTED_LOGS) {
                recentLines.removeFirst()
            }
            recentLines.addLast(formattedLine)
            recentLines.toList()
        }
        val appContext = context.applicationContext
        ioExecutor.execute {
            try {
                File(appContext.filesDir, LOGS_FILENAME).writeText(snapshot.joinToString("\n") + "\n")
            } catch (_: Exception) {
            }
        }
    }

    fun formatBytes(bytes: Long): String {
        return if (bytes >= 1024L * 1024L) {
            String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        } else {
            "${bytes / 1024L} KB"
        }
    }
}
