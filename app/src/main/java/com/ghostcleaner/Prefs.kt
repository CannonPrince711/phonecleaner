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
}
