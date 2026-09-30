package com.ghostcleaner

import android.content.Context

/** Small wrapper around SharedPreferences for the app's settings. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ghostcleaner", Context.MODE_PRIVATE)

    /** Absolute folder paths the scanner must never touch. */
    var ignored: Set<String>
        get() = sp.getStringSet("ignored", emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet("ignored", value).apply()

    /** When on, cleaned files go to the safety bin instead of being deleted right away. */
    var useBin: Boolean
        get() = sp.getBoolean("use_bin", true)
        set(value) = sp.edit().putBoolean("use_bin", value).apply()

    /** Running total of space freed, for the header. */
    var totalFreed: Long
        get() = sp.getLong("total_freed", 0L)
        set(value) = sp.edit().putLong("total_freed", value).apply()

    // ---------- Tunable settings ----------

    var largeFileMb: Int
        get() = sp.getInt("large_file_mb", 100)
        set(value) = sp.edit().putInt("large_file_mb", value.coerceIn(1, 100_000)).apply()

    var binDays: Int
        get() = sp.getInt("bin_days", 3)
        set(value) = sp.edit().putInt("bin_days", value.coerceIn(1, 365)).apply()

    var dupMinKb: Int
        get() = sp.getInt("dup_min_kb", 512)
        set(value) = sp.edit().putInt("dup_min_kb", value.coerceIn(1, 10_000_000)).apply()

    var oldDownloadDays: Int
        get() = sp.getInt("old_download_days", 90)
        set(value) = sp.edit().putInt("old_download_days", value.coerceIn(1, 3650)).apply()

    var oldMediaDays: Int
        get() = sp.getInt("old_media_days", 60)
        set(value) = sp.edit().putInt("old_media_days", value.coerceIn(1, 3650)).apply()

    var unusedAppDays: Int
        get() = sp.getInt("unused_app_days", 30)
        set(value) = sp.edit().putInt("unused_app_days", value.coerceIn(1, 3650)).apply()

    var weeklyScan: Boolean
        get() = sp.getBoolean("weekly_scan", false)
        set(value) = sp.edit().putBoolean("weekly_scan", value).apply()

    fun scanConfig() = ScanConfig(
        largeFileBytes = largeFileMb * 1024L * 1024L,
        dupMinBytes = dupMinKb * 1024L,
        oldDownloadDays = oldDownloadDays,
        oldMediaDays = oldMediaDays,
    )
}

/** The knobs the scanner uses; built from [Prefs]. */
data class ScanConfig(
    val largeFileBytes: Long = 100L * 1024 * 1024,
    val dupMinBytes: Long = 512L * 1024,
    val oldDownloadDays: Int = 90,
    val oldMediaDays: Int = 60,
)
