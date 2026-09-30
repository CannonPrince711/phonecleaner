package com.ghostcleaner

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment

/** Runs a quiet scan in the background and posts a notification. Never deletes anything. */
class ScanJobService : JobService() {

    @Volatile private var scanner: Scanner? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val hasAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
            else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (!hasAccess) return false

        Thread {
            val prefs = Prefs(this)
            val s = Scanner(this, prefs.ignored, prefs.scanConfig())
            scanner = s
            val found = s.scan { }
            if (!s.cancelled) {
                val chosen = found.filter { it.selected }
                val bytes = chosen.sumOf { it.size }
                if (bytes > MIN_NOTIFY_BYTES) notify(this, chosen.size, bytes)
            }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scanner?.cancelled = true
        return true // try again later
    }

    companion object {
        private const val JOB_ID = 4211
        private const val CHANNEL_ID = "weekly_scan"
        private const val MIN_NOTIFY_BYTES = 10L * 1024 * 1024
        private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

        /** Schedules the weekly job if it's turned on and not already pending (so the timer isn't reset). */
        fun ensureScheduled(context: Context) {
            if (!Prefs(context).weeklyScan) return
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) == null) schedule(context, true)
        }

        fun schedule(context: Context, enabled: Boolean) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (!enabled) { js.cancel(JOB_ID); return }
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, ScanJobService::class.java))
                .setPeriodic(WEEK_MS)
                .setRequiresBatteryNotLow(true)
                .setRequiresDeviceIdle(false)
                .setPersisted(true)
                .build()
            js.schedule(job)
        }

        private fun notify(context: Context, count: Int, bytes: Long) {
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Weekly scan", NotificationManager.IMPORTANCE_DEFAULT))

            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_AUTO_SCAN, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("${Scanner.formatSize(bytes)} of junk found")
                .setContentText("$count items ready to review. Tap to open GhostCleaner.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(1, n)
        }
    }
}
