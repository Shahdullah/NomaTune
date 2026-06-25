/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.utils

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.shahdullah.nomatune.BuildConfig
import com.shahdullah.nomatune.constants.EnableUpdateNotificationKey
import com.shahdullah.nomatune.constants.UpdateChannel
import com.shahdullah.nomatune.constants.UpdateChannelKey

class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!BuildConfig.UPDATER_AVAILABLE) {
            return Result.success()
        }

        return try {
            val dataStore = applicationContext.dataStore

            val isEnabled = dataStore.data.map { it[EnableUpdateNotificationKey] ?: false }.first()
            if (!isEnabled) return Result.success()

            val updateChannel = dataStore.data.map {
                it[UpdateChannelKey]?.let { value ->
                    try { UpdateChannel.valueOf(value) } catch (e: Exception) { UpdateChannel.STABLE }
                } ?: UpdateChannel.STABLE
            }.first()

            when (updateChannel) {
                UpdateChannel.NIGHTLY -> return Result.success()
                UpdateChannel.DAILY_NIGHTLY -> {
                    Updater.getLatestDailyNightlyVersionName().onSuccess { latestVersion ->
                        if (!Updater.isSameVersion(latestVersion, BuildConfig.VERSION_NAME)) {
                            UpdateNotificationManager.notifyIfNewVersion(
                                applicationContext,
                                latestVersion,
                                updateChannel,
                            )
                        }
                    }
                }
                else -> {
                    Updater.getLatestVersionName().onSuccess { latestVersion ->
                        if (!Updater.isSameVersion(latestVersion, BuildConfig.VERSION_NAME)) {
                            UpdateNotificationManager.notifyIfNewVersion(applicationContext, latestVersion)
                        }
                    }
                }
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
