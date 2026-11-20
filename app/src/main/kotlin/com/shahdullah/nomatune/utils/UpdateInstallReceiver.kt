/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Based on ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shahdullah.nomatune.MainActivity
import com.shahdullah.nomatune.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles the "Download" action of the update notification.
 *
 * Downloads the release APK in-process and launches the package installer, so the
 * user stays inside NomaTune instead of being thrown to github.com in a browser.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_INSTALL_UPDATE) return
        val appContext = context.applicationContext
        val url = intent.getStringExtra(EXTRA_DOWNLOAD_URL).orEmpty()
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                notifyProgress(appContext)
                val installed = UpdateApkFetcher.downloadAndInstallBlocking(appContext, url)
                if (!installed) {
                    notifyFailure(appContext)
                } else {
                    NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun notifyProgress(context: Context) {
        val notification =
            NotificationCompat.Builder(context, UpdateNotificationManager.CHANNEL_ID)
                .setSmallIcon(R.drawable.small_icon)
                .setContentTitle(context.getString(R.string.update_notification_title))
                .setContentText(context.getString(R.string.update_downloading))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setProgress(0, 0, true)
                .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    private fun notifyFailure(context: Context) {
        // Tapping a failed download lands on the in-app Updates screen so the user
        // can retry there - never on github.com.
        val retryIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(MainActivity.EXTRA_NAVIGATE_TO, MainActivity.ROUTE_UPDATE_SETTINGS)
            }
        val retryPendingIntent =
            PendingIntent.getActivity(
                context,
                2,
                retryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(context, UpdateNotificationManager.CHANNEL_ID)
                .setSmallIcon(R.drawable.small_icon)
                .setContentTitle(context.getString(R.string.update_notification_title))
                .setContentText(context.getString(R.string.update_download_failed))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(retryPendingIntent)
                .setAutoCancel(true)
                .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    companion object {
        const val ACTION_INSTALL_UPDATE = "com.shahdullah.nomatune.action.INSTALL_UPDATE"
        const val EXTRA_DOWNLOAD_URL = "download_url"
        private const val NOTIFICATION_ID = 1002
    }
}
