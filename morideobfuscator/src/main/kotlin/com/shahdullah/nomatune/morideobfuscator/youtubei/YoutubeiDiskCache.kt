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
package com.shahdullah.nomatune.morideobfuscator.youtubei

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal class YoutubeiDiskCache(context: Context) {
    private val directory = File(context.noBackupFilesDir, CACHE_DIRECTORY).apply { mkdirs() }
    private val mutex = Mutex()

    suspend fun read(key: String): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val file = fileForKey(key)
                if (!file.isFile || file.length() !in 0L..MAX_ENTRY_BYTES) return@withLock null
                val bytes = runCatching { file.readBytes() }.getOrElse {
                    file.delete()
                    return@withLock null
                }
                file.setLastModified(System.currentTimeMillis())
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        }

    suspend fun write(requestJson: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val request = JSONObject(requestJson)
                val key = request.getString("key")
                val bytes = Base64.decode(request.getString("value"), Base64.DEFAULT)
                require(bytes.size <= MAX_ENTRY_BYTES)
                val destination = fileForKey(key)
                val temporary = File.createTempFile(destination.name, ".tmp", directory)
                try {
                    FileOutputStream(temporary).use { output ->
                        output.write(bytes)
                        output.fd.sync()
                    }
                    runCatching {
                        Files.move(
                            temporary.toPath(),
                            destination.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }.getOrElse {
                        if (destination.exists() && !destination.delete()) {
                            throw it
                        }
                        if (!temporary.renameTo(destination)) throw it
                    }
                    destination.setLastModified(System.currentTimeMillis())
                    prune()
                } finally {
                    temporary.delete()
                }
            }
        }
    }

    suspend fun remove(key: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                fileForKey(key).delete()
            }
        }
    }

    private fun fileForKey(key: String): File = File(directory, key.sha256())

    private fun prune() {
        val files = directory.listFiles()?.filter(File::isFile).orEmpty()
        var totalBytes = files.sumOf(File::length)
        if (totalBytes <= MAX_TOTAL_BYTES) return
        files.sortedBy(File::lastModified).forEach { file ->
            if (totalBytes <= MAX_TOTAL_BYTES) return
            val length = file.length()
            if (file.delete()) totalBytes -= length
        }
    }

    private fun String.sha256(): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val CACHE_DIRECTORY = "youtubei/18.0.0"
        const val MAX_ENTRY_BYTES = 8L * 1024L * 1024L
        const val MAX_TOTAL_BYTES = 24L * 1024L * 1024L
    }
}
