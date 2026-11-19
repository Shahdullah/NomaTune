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
import android.os.SystemClock
import android.util.Base64
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors

internal class YoutubeiQuickJsWorker(
    context: Context,
    private val name: String,
    private val httpClient: YoutubeiHttpClient,
    private val diskCache: YoutubeiDiskCache,
    private val diagnostics: (String) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val dispatcher =
        Executors
            .newSingleThreadExecutor { runnable ->
                Thread(
                    null,
                    runnable,
                    "NomaTune-Youtubei-$name",
                    WORKER_STACK_SIZE_BYTES,
                ).apply { isDaemon = true }
            }.asCoroutineDispatcher()
    private val mutex = Mutex()
    private val secureRandom = SecureRandom()

    @Volatile
    private var initialized = false

    private var quickJs: QuickJs? = null
    private var activeVideoPoTokenProvider: (suspend (String) -> String?)? = null
    private var activeRequestAuthentication: YoutubeiRequestAuthentication? = null

    suspend fun preWarm() {
        mutex.withLock {
            withContext(dispatcher) {
                ensureInitialized()
            }
        }
    }

    suspend fun resolve(
        requestJson: String,
        videoPoTokenProvider: suspend (String) -> String?,
    ): String =
        mutex.withLock {
            withContext(dispatcher) {
                val runtime = ensureInitialized()
                activeVideoPoTokenProvider = videoPoTokenProvider
                try {
                    activeRequestAuthentication = YoutubeiRequestAuthentication.fromRequest(JSONObject(requestJson))
                    val preparation = traceStage("prepare-session") {
                        evaluateRequest(
                            runtime = runtime,
                            code =
                                "await globalThis.NomaTuneYoutubei.prepare(" +
                                    JSONObject.quote(requestJson) +
                                    ");",
                            filename = "archivetune-prepare.js",
                        )
                    }
                    runtime.gc()
                    if (!JSONObject(preparation).optBoolean("ok")) {
                        return@withContext preparation
                    }
                    val response = traceStage("resolve-stream") {
                        evaluateRequest(
                            runtime = runtime,
                            code =
                                "await globalThis.NomaTuneYoutubei.resolvePrepared(" +
                                    JSONObject.quote(requestJson) +
                                    ");",
                            filename = "archivetune-resolve.js",
                        )
                    }
                    runtime.gc()
                    response
                } catch (throwable: Throwable) {
                    if (
                        throwable is CancellationException ||
                        throwable is QuickJsInterruptedException ||
                        throwable is YoutubeiException && throwable.kind == YoutubeiFailureKind.TIMEOUT ||
                        throwable.isQuickJsOutOfMemory()
                    ) {
                        discardRuntime(runtime, throwable)
                    } else {
                        try {
                            runtime.gc()
                        } catch (gcFailure: Throwable) {
                            throwable.addSuppressed(gcFailure)
                        }
                    }
                    throw throwable
                } finally {
                    activeVideoPoTokenProvider = null
                    activeRequestAuthentication = null
                }
            }
        }

    private suspend fun evaluateRequest(
        runtime: QuickJs,
        code: String,
        filename: String,
    ): String {
        val response = runtime.evaluate<String>(code, filename)
        currentCoroutineContext().ensureActive()
        val error = JSONObject(response).optJSONObject("error")
        if (error?.optString("message")?.trim().equals("interrupted", ignoreCase = true)) {
            throw YoutubeiException(
                kind = YoutubeiFailureKind.TIMEOUT,
                message = "youtubei.js evaluation was interrupted",
            )
        }
        return response
    }

    suspend fun closeRuntime() {
        mutex.withLock {
            withContext(dispatcher) {
                val runtime = quickJs
                quickJs = null
                initialized = false
                runtime?.close()
            }
        }
    }

    private suspend fun ensureInitialized(): QuickJs {
        quickJs?.takeIf { initialized }?.let { return it }
        quickJs?.close()
        val runtime = QuickJs.create(dispatcher)
        try {
            runtime.memoryLimit = JAVASCRIPT_MEMORY_LIMIT_BYTES
            runtime.maxStackSize = JAVASCRIPT_STACK_LIMIT_BYTES
            runtime.asyncFunction<String, String>("__archiveTuneHttp") { request ->
                httpClient.execute(request, activeRequestAuthentication)
            }
            runtime.asyncFunction<String, String>("__archiveTunePlayerSource") { request ->
                httpClient.executePlayerScript(request)
            }
            runtime.asyncFunction<String, String?>("__archiveTuneVideoPoToken") { mediaId ->
                activeVideoPoTokenProvider?.invoke(mediaId)
            }
            runtime.asyncFunction<String, String?>("__archiveTuneCacheRead") { key ->
                diskCache.read(key)
            }
            runtime.asyncFunction<String, Unit>("__archiveTuneCacheWrite") { request ->
                diskCache.write(request)
            }
            runtime.asyncFunction<String, Unit>("__archiveTuneCacheRemove") { key ->
                diskCache.remove(key)
            }
            runtime.asyncFunction<String, String>("__archiveTuneSha1") { value ->
                MessageDigest
                    .getInstance("SHA-1")
                    .digest(value.toByteArray(Charsets.UTF_8))
                    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
            }
            runtime.function("__archiveTuneUuid") { UUID.randomUUID().toString() }
            runtime.function("__archiveTuneRandom") { arguments ->
                val size = (arguments.firstOrNull() as? Number)?.toInt()?.coerceIn(0, MAX_RANDOM_BYTES) ?: 0
                ByteArray(size)
                    .also(secureRandom::nextBytes)
                    .let { Base64.encodeToString(it, Base64.NO_WRAP) }
            }
            evaluateAsset(runtime, RUNTIME_POLYFILLS_ASSET)
            evaluateAsset(runtime, BUNDLE_ASSET)
            val version = runtime.evaluate<String>("globalThis.NomaTuneYoutubei.version;")
            check(version == YOUTUBEI_VERSION)
            quickJs = runtime
            initialized = true
            return runtime
        } catch (throwable: Throwable) {
            discardRuntime(runtime, throwable)
            throw throwable
        }
    }

    private suspend fun evaluateAsset(
        runtime: QuickJs,
        assetName: String,
    ) {
        val source =
            applicationContext.assets
                .open(assetName)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        traceStage("load-$assetName") {
            runtime.evaluate<Unit>(source, assetName)
        }
    }

    private suspend fun <T> traceStage(
        stage: String,
        block: suspend () -> T,
    ): T {
        val startedAt = SystemClock.elapsedRealtime()
        diagnostics("Worker $name $stage started")
        return try {
            block().also {
                diagnostics("Worker $name $stage complete elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
            }
        } catch (exception: Exception) {
            diagnostics("Worker $name $stage failed type=${exception.javaClass.simpleName} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
            throw exception
        }
    }

    private fun discardRuntime(
        runtime: QuickJs,
        failure: Throwable,
    ) {
        if (quickJs === runtime) {
            quickJs = null
            initialized = false
        }
        diagnostics("Worker $name discarded type=${failure.javaClass.simpleName}")
        try {
            runtime.close()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
    }

    private fun Throwable.isQuickJsOutOfMemory(): Boolean =
        this is QuickJsException && message.orEmpty().contains("out of memory", ignoreCase = true)

    private companion object {
        const val YOUTUBEI_VERSION = "18.0.0"
        const val BUNDLE_ASSET = "youtubei/youtubei.bundle.js"
        const val RUNTIME_POLYFILLS_ASSET = "youtubei/runtime-polyfills.js"
        const val JAVASCRIPT_MEMORY_LIMIT_BYTES = 256L * 1024L * 1024L
        const val JAVASCRIPT_STACK_LIMIT_BYTES = 2L * 1024L * 1024L
        const val WORKER_STACK_SIZE_BYTES = 4L * JAVASCRIPT_STACK_LIMIT_BYTES
        const val MAX_RANDOM_BYTES = 4096
    }
}
