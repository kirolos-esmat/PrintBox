package com.usbprint.server

import android.content.Context
import java.util.Locale
import kotlin.math.floor

data class RefillState(
    val enabled: Boolean = false,
    val baselinePages: Int = -1,
    val pagesPrinted: Int = 0,
    val standardPagesUsed: Double = 0.0,
    val lastJobPages: Int = 0,
    val lastJobCoveragePercent: Double = -1.0,
    val lastJobSource: String = ""
)

data class JobTonerMetrics(
    val pages: Int,
    val coveragePercent: Double,
    val standardPages: Double,
    val sourceLabel: String
)

/**
 * Tracks post-refill toner consumption using exact PBM pixel coverage (for PDF, image, and URF/PWG
 * jobs) plus PJL page-count / average fallbacks when raw TCP streams bypass conversion.
 */
object RefillTracker {
    private const val PREFS_NAME = "usb_print_prefs"
    private const val KEY_ENABLED = "refill_mode_enabled"
    private const val KEY_BASELINE_PAGES = "refill_baseline_pages"
    private const val KEY_PAGES_PRINTED = "refill_pages_printed"
    private const val KEY_STD_PAGES_USED_MILLIS = "refill_std_pages_millis"
    private const val KEY_LAST_JOB_PAGES = "refill_last_job_pages"
    private const val KEY_LAST_JOB_COV_TENTHS = "refill_last_job_cov_tenths"
    private const val KEY_LAST_JOB_SOURCE = "refill_last_job_source"
    private const val KEY_SEED_VERSION = "refill_seed_version"

    private const val CURRENT_SEED_VERSION = 1
    private const val SEED_INITIAL_PDF_PAGES = 3
    private const val SEED_INITIAL_STD_PAGES = 4.8 // ~8% average coverage across 3 initial PDFs -> 99.7% left
    private const val SEED_INITIAL_COV_PERCENT = 8.0

    const val DEFAULT_RATED_YIELD = 1600

    fun pagesSinceRefill(lifetimePages: Int, baselinePages: Int): Int {
        if (lifetimePages < 0 || baselinePages < 0) return 0
        return maxOf(0, lifetimePages - baselinePages)
    }

    fun effectivePagesPrinted(lifetimePages: Int, state: RefillState): Int {
        val pjlDelta = pagesSinceRefill(lifetimePages, state.baselinePages)
        return state.pagesPrinted + maxOf(0, pjlDelta - state.pagesPrinted)
    }

    fun effectiveStandardPagesUsed(lifetimePages: Int, state: RefillState): Double {
        val pjlDelta = pagesSinceRefill(lifetimePages, state.baselinePages)
        val unrecordedPjl = maxOf(0, pjlDelta - state.pagesPrinted)
        return state.standardPagesUsed + unrecordedPjl.toDouble()
    }

    fun estimateRemainingPercent(
        lifetimePages: Int,
        baselinePages: Int,
        ratedYieldPages: Int = DEFAULT_RATED_YIELD
    ): Int {
        if (ratedYieldPages <= 0 || lifetimePages < 0 || baselinePages < 0) return -2
        val used = pagesSinceRefill(lifetimePages, baselinePages)
        val remaining = ratedYieldPages - used
        return ((remaining * 100) / ratedYieldPages).coerceIn(0, 100)
    }

    fun estimateRemainingPercentExact(
        lifetimePages: Int,
        state: RefillState,
        ratedYieldPages: Int = DEFAULT_RATED_YIELD
    ): Double {
        if (!state.enabled || ratedYieldPages <= 0) return -2.0
        if (state.baselinePages < 0 && state.pagesPrinted <= 0 && state.standardPagesUsed <= 0.0) return -2.0
        val used = effectiveStandardPagesUsed(lifetimePages, state)
        val remaining = ratedYieldPages.toDouble() - used
        return ((remaining * 100.0) / ratedYieldPages.toDouble()).coerceIn(0.0, 100.0)
    }

    fun estimateRemainingPercentInt(
        lifetimePages: Int,
        state: RefillState,
        ratedYieldPages: Int = DEFAULT_RATED_YIELD
    ): Int {
        val exact = estimateRemainingPercentExact(lifetimePages, state, ratedYieldPages)
        if (exact < 0.0) return -2
        return floor(exact).toInt().coerceIn(0, 100)
    }

    fun formatPercent(percent: Double): String =
        String.format(Locale.US, "%.1f", percent.coerceIn(0.0, 100.0))

    fun loadState(context: Context): RefillState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val state = if (prefs.getInt(KEY_SEED_VERSION, 0) < CURRENT_SEED_VERSION) {
            val seeded = RefillState(
                enabled = true,
                baselinePages = -1,
                pagesPrinted = SEED_INITIAL_PDF_PAGES,
                standardPagesUsed = SEED_INITIAL_STD_PAGES,
                lastJobPages = SEED_INITIAL_PDF_PAGES,
                lastJobCoveragePercent = SEED_INITIAL_COV_PERCENT,
                lastJobSource = "Initial 3 PDFs"
            )
            saveState(prefs, seeded, seedVersion = CURRENT_SEED_VERSION)
            AppState.log(
                "Initialized refill tracker: $SEED_INITIAL_PDF_PAGES PDFs counted " +
                    "(~${formatPercent(estimateRemainingPercentExact(-1, seeded))}% remaining)"
            )
            seeded
        } else {
            val enabled = prefs.getBoolean(KEY_ENABLED, false)
            val baseline = prefs.getInt(KEY_BASELINE_PAGES, -1)
            val pagesPrinted = prefs.getInt(KEY_PAGES_PRINTED, 0).coerceAtLeast(0)
            val stdPagesUsed = prefs.getLong(KEY_STD_PAGES_USED_MILLIS, 0L).coerceAtLeast(0L) / 1000.0
            val lastJobPages = prefs.getInt(KEY_LAST_JOB_PAGES, 0).coerceAtLeast(0)
            val lastCovTenths = prefs.getInt(KEY_LAST_JOB_COV_TENTHS, -10)
            val lastCov = if (lastCovTenths >= 0) lastCovTenths / 10.0 else -1.0
            val lastSource = prefs.getString(KEY_LAST_JOB_SOURCE, "") ?: ""
            RefillState(
                enabled = enabled && (baseline >= 0 || pagesPrinted > 0 || stdPagesUsed > 0.0),
                baselinePages = baseline,
                pagesPrinted = pagesPrinted,
                standardPagesUsed = stdPagesUsed,
                lastJobPages = lastJobPages,
                lastJobCoveragePercent = lastCov,
                lastJobSource = lastSource
            )
        }
        AppState.updateRefillState(state)
        return state
    }

    fun bindBaselineIfNeeded(context: Context, currentLifetimePages: Int) {
        if (currentLifetimePages < 0) return
        val current = AppState.refillState.value
        if (!current.enabled || current.baselinePages >= 0) return
        val updated = current.copy(baselinePages = currentLifetimePages)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        saveState(prefs, updated, seedVersion = CURRENT_SEED_VERSION)
        AppState.updateRefillState(updated)
    }

    fun recordPrintJob(
        context: Context,
        metrics: JobTonerMetrics,
        currentLifetimePages: Int? = null
    ): RefillState {
        val current = AppState.refillState.value
        if (!current.enabled) return current

        val lifetime = currentLifetimePages ?: AppState.supplyStatus.value.pageCount
        val resolvedBaseline = when {
            current.baselinePages >= 0 -> current.baselinePages
            lifetime >= 0 -> lifetime
            else -> -1
        }
        val safePages = metrics.pages.coerceAtLeast(1)
        val safeStd = metrics.standardPages.coerceAtLeast(0.0)
        val updated = current.copy(
            baselinePages = resolvedBaseline,
            pagesPrinted = current.pagesPrinted + safePages,
            standardPagesUsed = current.standardPagesUsed + safeStd,
            lastJobPages = safePages,
            lastJobCoveragePercent = metrics.coveragePercent.coerceIn(0.0, 100.0),
            lastJobSource = metrics.sourceLabel
        )
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        saveState(prefs, updated, seedVersion = CURRENT_SEED_VERSION)
        AppState.updateRefillState(updated)

        val remainingPct = estimateRemainingPercentExact(lifetime, updated)
        AppState.log(
            "Toner usage (${metrics.sourceLabel}): ${safePages}p @ " +
                "${formatPercent(metrics.coveragePercent)}% avg ink " +
                "(${String.format(Locale.US, "%.2f", safeStd)} std eq.) -> ~${formatPercent(remainingPct)}% left"
        )
        return updated
    }

    fun markRefilled(context: Context, currentLifetimePages: Int): RefillState {
        val state = RefillState(
            enabled = true,
            baselinePages = maxOf(0, currentLifetimePages),
            pagesPrinted = 0,
            standardPagesUsed = 0.0,
            lastJobPages = 0,
            lastJobCoveragePercent = -1.0,
            lastJobSource = ""
        )
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        saveState(prefs, state, seedVersion = CURRENT_SEED_VERSION)
        AppState.updateRefillState(state)
        AppState.log("Local refill counter reset at ${state.baselinePages} lifetime pages (100.0%)")
        return state
    }

    fun clearRefill(context: Context): RefillState {
        val current = AppState.refillState.value
        val state = current.copy(enabled = false)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_ENABLED, false)
            .putInt(KEY_SEED_VERSION, CURRENT_SEED_VERSION)
            .apply()
        AppState.updateRefillState(state)
        AppState.log("Switched supply tracking back to printer chip reading")
        return state
    }

    private fun saveState(
        prefs: android.content.SharedPreferences,
        state: RefillState,
        seedVersion: Int
    ) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, state.enabled)
            .putInt(KEY_BASELINE_PAGES, state.baselinePages)
            .putInt(KEY_PAGES_PRINTED, state.pagesPrinted)
            .putLong(KEY_STD_PAGES_USED_MILLIS, (state.standardPagesUsed * 1000.0).toLong())
            .putInt(KEY_LAST_JOB_PAGES, state.lastJobPages)
            .putInt(
                KEY_LAST_JOB_COV_TENTHS,
                if (state.lastJobCoveragePercent >= 0.0) (state.lastJobCoveragePercent * 10.0).toInt() else -1
            )
            .putString(KEY_LAST_JOB_SOURCE, state.lastJobSource)
            .putInt(KEY_SEED_VERSION, seedVersion)
            .apply()
    }
}
