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

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.shahdullah.nomatune.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * Downloads a release APK and hands it to the system package installer.
 *
 * Used by the notification action (which must not run coroutines on its own) and
 * by [AppUpdateInstaller] for the in-app "Update" button.
 */
object UpdateApkFetcher {
    /**
     * Downloads [url] into the app cache and opens the package installer.
     *
     * Returns true when the installer intent was launched.
     */
    fun downloadAndInstallBlocking(
        context: Context,
        url: String,
        timeoutMs: Int = READ_TIMEOUT_MS,
    ): Boolean {
        val safeUrl = url.takeIf { it.isNotBlank() } ?: return false
        // Same directory layout and same lock as [AppUpdateInstaller]: two entry points,
        // one shared cache dir, so neither may clear the other's work. The directory is
        // created and pruned *inside* the lock, otherwise a waiter could wipe the
        // directory of the download that currently holds it.
        // This entry point is called from a BroadcastReceiver's goAsync() block on a
        // background thread, not from a coroutine, so it cannot suspend on the mutex.
        // Use tryLock so a download already in progress is not clobbered; report failure
        // rather than silently deleting its directory.
        if (!AppUpdateInstaller.downloadMutex.tryLock()) {
            return false
        }
        return try {
            val updateDir = AppUpdateInstaller.newDownloadDirectory(context)
            AppUpdateInstaller.pruneStaleDownloads(context, keep = updateDir)
            downloadAndLaunch(context, safeUrl, updateDir, File(updateDir, APK_FILE_NAME), timeoutMs)
        } finally {
            AppUpdateInstaller.downloadMutex.unlock()
        }
    }

    private fun downloadAndLaunch(
        context: Context,
        safeUrl: String,
        updateDir: File,
        target: File,
        timeoutMs: Int,
    ): Boolean {
        runCatching {
            val connection =
                (URL(safeUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = timeoutMs
                    setRequestProperty("Accept", "application/octet-stream")
                    setRequestProperty("User-Agent", "NomaTune/${BuildConfig.VERSION_NAME}")
                }
            try {
                if (connection.responseCode !in 200..299) {
                    throw IOException("Update download failed: HTTP ${connection.responseCode}")
                }
                connection.inputStream.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            } finally {
                connection.disconnect()
            }
            if (!target.isApkArchive()) {
                val extracted = extractApkFromArchive(target, File(updateDir, EXTRACTED_APK_FILE_NAME))
                if (extracted == null) {
                    target.delete()
                    throw IOException("Downloaded file is not an installable APK")
                }
                val canonical = File(updateDir, APK_FILE_NAME)
                if (extracted != canonical) {
                    if (canonical.exists()) canonical.delete()
                    if (!extracted.renameTo(canonical)) {
                        extracted.copyTo(canonical, overwrite = true)
                        extracted.delete()
                    }
                }
            }
            val apk = File(updateDir, APK_FILE_NAME).takeIf { it.isFile && it.length() > 0L }
                ?: throw IOException("Update APK is missing after download")
            launchInstaller(context, apk)
        }.getOrDefault(false)
    }

    private fun launchInstaller(
        context: Context,
        apkFile: File,
    ): Boolean =
        runCatching {
            val uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.FileProvider",
                    apkFile,
                )
            val intent =
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME_TYPE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrElse { failure ->
            // Surface the real reason instead of silently doing nothing; the caller
            // turns a `false` result into a user-visible notification.
            if (failure is ActivityNotFoundException || failure is SecurityException) {
                false
            } else {
                throw failure
            }
        }

    private fun File.isApkArchive(): Boolean =
        runCatching { ZipFile(this).use { zip -> zip.getEntry("AndroidManifest.xml") != null } }
            .getOrDefault(false)

    /** Release pages sometimes hand back a zip bundle; pick the first APK inside it. */
    private fun extractApkFromArchive(
        archive: File,
        target: File,
    ): File? =
        runCatching {
            ZipFile(archive).use { zip ->
                val entry =
                    zip.entries().asSequence().firstOrNull { candidate ->
                        !candidate.isDirectory &&
                            candidate.name.endsWith(".apk", ignoreCase = true) &&
                            !candidate.name.substringAfterLast('/').contains("foss-", ignoreCase = true)
                    } ?: return@runCatching null
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target.takeIf { it.isFile && it.length() > 0L }
            }
        }.getOrNull()

    fun directApkUrl(
        tag: String,
        device: String,
        architecture: String,
        distributionPrefix: String = "",
    ): String =
        "https://github.com/Shahdullah/NomaTune/releases/download/$tag/" +
            "app-$distributionPrefix$device-$architecture-release.apk"

    private const val APK_FILE_NAME = "nomatune-update.apk"
    private const val EXTRACTED_APK_FILE_NAME = "nomatune-update-extracted.apk"
    private const val DOWNLOAD_FILE_NAME = "nomatune-update.apk"
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 180_000
}
