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

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import timber.log.Timber
import kotlinx.coroutines.CancellationException
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
    private companion object {
        const val MAX_ATTEMPTS = 3
    }


    override suspend fun doWork(): Result {
        if (!BuildConfig.UPDATER_AVAILABLE) {
            return Result.success()
        }

        return try {
            val dataStore = applicationContext.dataStore

            val isEnabled = dataStore.data.map { it[EnableUpdateNotificationKey] ?: true }.first()
            if (!isEnabled) return Result.success()

            val updateChannel = dataStore.data.map {
                it[UpdateChannelKey]?.let { value ->
                    try { UpdateChannel.valueOf(value) } catch (e: Exception) { UpdateChannel.STABLE }
                } ?: UpdateChannel.STABLE
            }.first()

            UpdateNotificationManager.recordCheckPerformed(applicationContext, "worker")

            when (updateChannel) {
                UpdateChannel.NIGHTLY -> {
                    UpdateNotificationManager.recordCheckNote(applicationContext, "channel=NIGHTLY, nothing to check")
                    return Result.success()
                }
                UpdateChannel.DAILY_NIGHTLY -> {
                    Updater.getLatestDailyNightlyVersionName()
                        .onSuccess { latestVersion ->
                            if (Updater.isUpdateAvailable(latestVersion, BuildConfig.VERSION_NAME)) {
                                UpdateNotificationManager.notifyIfNewVersion(
                                    applicationContext,
                                    latestVersion,
                                    updateChannel,
                                )
                            } else {
                                UpdateNotificationManager.recordCheckNote(
                                    applicationContext,
                                    "latest=$latestVersion, installed=${BuildConfig.VERSION_NAME} - up to date",
                                )
                            }
                        }
                        .onFailure { throwable -> throw throwable }
                }
                else -> {
                    Updater.getLatestVersionName(forceRefresh = true)
                        .onSuccess { latestVersion ->
                            if (Updater.isUpdateAvailable(latestVersion, BuildConfig.VERSION_NAME)) {
                                UpdateNotificationManager.notifyIfNewVersion(applicationContext, latestVersion)
                            } else {
                                UpdateNotificationManager.recordCheckNote(
                                    applicationContext,
                                    "latest=$latestVersion, installed=${BuildConfig.VERSION_NAME} - up to date",
                                )
                            }
                        }
                        // Without this the worker "succeeded" while the fetch had failed
                        // outright, which is why a broken check looked identical to "no new
                        // release exists".
                        .onFailure { throwable -> throw throwable }
                }
            }

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("UpdateCheck").w(e, "Update check failed")
            UpdateNotificationManager.recordCheckFailure(applicationContext, e)
            if (runAttemptCount >= MAX_ATTEMPTS) {
                // Give up rather than retrying forever; the failure is recorded for the UI.
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }
}
