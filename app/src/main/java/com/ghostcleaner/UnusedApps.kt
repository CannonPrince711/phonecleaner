package com.ghostcleaner

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import android.graphics.drawable.Drawable

/** Finds installed apps that haven't been opened in a while. Needs "Usage access". */
object UnusedApps {

    data class App(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        val sizeBytes: Long,
        /** Days since last use, or null if not used at all in the past year. */
        val daysUnused: Long?,
    )

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun find(context: Context, minDaysUnused: Int): List<App> {
        val now = System.currentTimeMillis()
        val yearAgo = now - 365 * Scanner.DAY_MS
        val threshold = minDaysUnused * Scanner.DAY_MS

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val lastUsed: Map<String, Long> = try {
            usm.queryAndAggregateUsageStats(yearAgo, now).mapValues { it.value.lastTimeUsed }
        } catch (e: Exception) { emptyMap() }

        val pm = context.packageManager
        val stats = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager

        @Suppress("DEPRECATION")
        val installed = if (Build.VERSION.SDK_INT >= 33)
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        else pm.getInstalledApplications(0)

        return installed.asSequence()
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .filter { it.packageName != context.packageName }
            .mapNotNull { info ->
                val pkg = info.packageName
                val installTime = try {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0).firstInstallTime
                } catch (e: Exception) { 0L }
                if (now - installTime < threshold) return@mapNotNull null // too new to judge

                val used = lastUsed[pkg]?.takeIf { it > 0 }
                if (used != null && now - used < threshold) return@mapNotNull null

                val size = try {
                    val s = stats.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle())
                    s.appBytes + s.dataBytes + s.cacheBytes
                } catch (e: Exception) { 0L }

                App(
                    packageName = pkg,
                    label = pm.getApplicationLabel(info).toString(),
                    icon = try { pm.getApplicationIcon(info) } catch (e: Exception) { null },
                    sizeBytes = size,
                    daysUnused = used?.let { (now - it) / Scanner.DAY_MS },
                )
            }
            .sortedByDescending { it.sizeBytes }
            .toList()
    }
}
