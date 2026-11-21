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
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.shahdullah.nomatune.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipFile

object AppUpdateInstaller {
    /**
     * Serialises every update download in the process.
     *
     * The in-app "Update" button ([AppUpdateInstaller]) and the notification action
     * ([UpdateApkFetcher]) are independent entry points but they share one cache
     * directory, and both clear that directory before writing. Pressing Update twice,
     * or tapping the notification while the sheet is downloading, deleted the first
     * download's APK out from under the installer, which surfaced as
     * "The source file doesn't exist". Downloads are now serialised, and each gets
     * its own directory as a second layer of protection.
     */
    internal val downloadMutex = Mutex()

    /** Unique per-download directory so a concurrent download can never clobber a finished one. */
    internal fun newDownloadDirectory(context: Context): File {
        val root = File(context.cacheDir, UpdateDirectoryName).apply { mkdirs() }
        val dir = File(root, "run-${System.currentTimeMillis()}-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    /** Deletes stale download directories left behind by earlier runs. */
    internal fun pruneStaleDownloads(context: Context, keep: File?) {
        val root = File(context.cacheDir, UpdateDirectoryName)
        root.listFiles()?.forEach { entry ->
            if (keep == null || entry.absolutePath != keep.absolutePath) {
                entry.deleteRecursively()
            }
        }
    }

    data class Progress(
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) {
        val fraction: Float?
            get() =
                totalBytes
                    .takeIf { it > 0L }
                    ?.let { downloadedBytes.toFloat() / it.toFloat() }
                    ?.coerceIn(0f, 1f)
    }

    suspend fun downloadAndInstall(
        context: Context,
        url: String,
        onProgress: (Progress) -> Unit,
    ): Result<Unit> {
        if (BuildConfig.DISTRIBUTION != "gms") {
            return Result.failure(IllegalStateException("In-app updates are only available for GMS builds"))
        }

        return try {
            val apkFile =
                downloadMutex.withLock {
                    withContext(Dispatchers.IO) {
                        downloadApk(context.applicationContext, url, onProgress)
                    }
                }
            // The APK must still be on disk right before we hand its Uri to the installer;
            // this is where the old shared-directory race blew up.
            if (!apkFile.isFile || apkFile.length() == 0L) {
                throw IOException("Downloaded update APK disappeared before install")
            }
            withContext(Dispatchers.Main) {
                installApk(context, apkFile)
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private suspend fun downloadApk(
        context: Context,
        url: String,
        onProgress: (Progress) -> Unit,
    ): File {
        if (url.isBlank()) {
            throw IOException("Update download URL is empty")
        }

        val updateDir = newDownloadDirectory(context)
        pruneStaleDownloads(context, keep = updateDir)

        val downloadedFile = File(updateDir, DownloadFileName)
        val connection = openConnection(url)
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("Update download failed: HTTP $responseCode")
            }

            val totalBytes = connection.contentLengthLong
            connection.inputStream.use { input ->
                downloadedFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloadedBytes = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read.toLong()
                        emitProgress(downloadedBytes, totalBytes, onProgress)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        return if (url.lowercase(Locale.US).substringBefore('?').endsWith(".apk")) {
            downloadedFile.renameAsApk()
        } else {
            extractGmsApk(downloadedFile, File(updateDir, ApkFileName))
                ?: if (downloadedFile.containsApkManifest()) {
                    downloadedFile.renameAsApk()
                } else {
                    throw IOException("No GMS APK found in update artifact")
                }
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = NetworkTimeoutMs
            readTimeout = NetworkTimeoutMs
            setRequestProperty("User-Agent", "NomaTune/${BuildConfig.VERSION_NAME}")
        }

    private suspend fun emitProgress(
        downloadedBytes: Long,
        totalBytes: Long,
        onProgress: (Progress) -> Unit,
    ) {
        withContext(Dispatchers.Main.immediate) {
            onProgress(Progress(downloadedBytes, totalBytes))
        }
    }

    private fun extractGmsApk(
        sourceFile: File,
        targetFile: File,
    ): File? =
        runCatching {
            ZipFile(sourceFile).use { zip ->
                val entries = zip.entries().asSequence().filter { entry ->
                    val fileName = entry.name.substringAfterLast('/')
                    !entry.isDirectory &&
                        entry.name.endsWith(".apk", ignoreCase = true) &&
                        !fileName.contains("foss-", ignoreCase = true) &&
                        !fileName.contains("izzy-", ignoreCase = true)
                }
                val preferredArtifactNames =
                    listOf(
                        "app-gms-${BuildConfig.DEVICE}-${BuildConfig.ARCHITECTURE}-",
                        "app-${BuildConfig.DEVICE}-${BuildConfig.ARCHITECTURE}-",
                    )
                val selectedEntry =
                    entries
                        .sortedBy { entry ->
                            val fileName = entry.name.substringAfterLast('/')
                            val preferredIndex =
                                preferredArtifactNames.indexOfFirst { preferredArtifactName ->
                                    fileName.contains(preferredArtifactName, ignoreCase = true)
                                }
                            if (preferredIndex >= 0) preferredIndex else preferredArtifactNames.size
                        }.firstOrNull()
                        ?: return@runCatching null

                zip.getInputStream(selectedEntry).use { input ->
                    targetFile.outputStream().use { output -> input.copyTo(output) }
                }
                targetFile
            }
        }.getOrNull()

    private fun File.containsApkManifest(): Boolean =
        runCatching {
            ZipFile(this).use { zip -> zip.getEntry("AndroidManifest.xml") != null }
        }.getOrDefault(false)

    private fun File.renameAsApk(): File {
        val apkFile = File(parentFile, ApkFileName)
        if (this == apkFile) return this
        if (!renameTo(apkFile)) {
            copyTo(apkFile, overwrite = true)
            delete()
        }
        return apkFile
    }

    private fun installApk(
        context: Context,
        apkFile: File,
    ) {
        val uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.FileProvider",
                apkFile,
            )
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, ApkMimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    internal const val UpdateDirectoryName = "app_update"
    private const val DownloadFileName = "nomatune-update.download"
    private const val ApkFileName = "nomatune-update.apk"
    private const val ApkMimeType = "application/vnd.android.package-archive"
    private const val NetworkTimeoutMs = 30_000
}
