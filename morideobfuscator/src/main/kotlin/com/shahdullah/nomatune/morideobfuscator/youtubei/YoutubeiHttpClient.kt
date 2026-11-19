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

import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal class YoutubeiHttpClient(
    private val configurationProvider: () -> YoutubeiNetworkConfiguration,
    private val diagnostics: (String) -> Unit,
) {
    private val clientLock = Any()

    @Volatile
    private var activeClient: ActiveClient? = null

    suspend fun execute(
        requestJson: String,
        authentication: YoutubeiRequestAuthentication?,
    ): String =
        try {
            executeRequest(requestJson, authentication)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SocketTimeoutException) {
            failure(FailureKind.TIMEOUT, "youtubei.js network request timed out")
        } catch (exception: IOException) {
            failure(FailureKind.NETWORK, "youtubei.js network request failed")
        } catch (exception: JSONException) {
            failure(FailureKind.INVALID_RESPONSE, "youtubei.js request was invalid")
        } catch (exception: IllegalArgumentException) {
            failure(FailureKind.INVALID_RESPONSE, "youtubei.js request was rejected")
        } catch (exception: Exception) {
            failure(FailureKind.INTERNAL, "youtubei.js HTTP bridge failed")
        }

    suspend fun executePlayerScript(requestJson: String): String =
        try {
            executePlayerScriptRequest(requestJson)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SocketTimeoutException) {
            playerSourceFailure(FailureKind.TIMEOUT, "YouTube player script request timed out")
        } catch (exception: IOException) {
            playerSourceFailure(FailureKind.NETWORK, "YouTube player script request failed")
        } catch (exception: JSONException) {
            playerSourceFailure(FailureKind.INVALID_RESPONSE, "YouTube player script request was invalid")
        } catch (exception: IllegalArgumentException) {
            playerSourceFailure(FailureKind.INVALID_RESPONSE, "YouTube player script request was rejected")
        } catch (exception: Exception) {
            playerSourceFailure(FailureKind.INTERNAL, "YouTube player script bridge failed")
        }

    private suspend fun executeRequest(
        requestJson: String,
        authentication: YoutubeiRequestAuthentication?,
    ): String {
        val parsed = JSONObject(requestJson)
        var url = parsed.getString("url").toHttpUrlOrNull()
            ?: return failure(FailureKind.INVALID_RESPONSE, "Invalid request URL")
        var method = parsed.optString("method", "GET").uppercase()
        require(method in ALLOWED_METHODS)
        validateUrl(url)
        var headers = parsed.optJSONObject("headers")
        var body =
            parsed.optString("bodyBase64")
                .takeIf(String::isNotEmpty)
                ?.let { Base64.decode(it, Base64.DEFAULT) }
        var redirected = false

        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val unsignedRequest = buildRequest(url, method, headers, body)
            val request = authentication?.applyTo(unsignedRequest) ?: unsignedRequest
            val result = currentClient().newCall(request).await { value ->
                validateUrl(value.request.url)
                if (value.isRedirect) {
                    if (redirectCount == MAX_REDIRECTS) {
                        throw IOException("Too many extraction redirects")
                    }
                    val location = value.header("Location")
                        ?: throw IOException("Extraction redirect did not include a location")
                    val redirectedUrl = value.request.url.resolve(location)
                        ?: throw IOException("Invalid extraction redirect location")
                    validateUrl(redirectedUrl)
                    if (!value.request.url.isSameOrigin(redirectedUrl)) {
                        headers = headers?.withoutHeaders(SENSITIVE_REDIRECT_HEADERS)
                    }
                    if (
                        (value.code == 301 || value.code == 302) && method == "POST" ||
                        value.code == 303 && method != "GET" && method != "HEAD"
                    ) {
                        method = "GET"
                        body = null
                        headers = headers?.withoutHeaders(BODY_HEADERS)
                    }
                    url = redirectedUrl
                    redirected = true
                    return@await null
                }
                val responseBytes = value.readLimitedBody(MAX_RESPONSE_BYTES)
                JSONObject()
                    .put("ok", true)
                    .put("status", value.code)
                    .put("statusText", value.message)
                    .put("url", value.request.url.toString())
                    .put("redirected", redirected)
                    .put("headers", value.headers.toJson())
                    .put("bodyBase64", Base64.encodeToString(responseBytes, Base64.NO_WRAP))
                    .toString()
            }
            if (result != null) return result
        }
        return failure(FailureKind.NETWORK, "Extraction redirect failed")
    }

    private suspend fun executePlayerScriptRequest(requestJson: String): String {
        val parsed = JSONObject(requestJson)
        var url = parsed.getString("url").toHttpUrlOrNull()
            ?: return playerSourceFailure(FailureKind.INVALID_RESPONSE, "Invalid player script URL")
        require(parsed.optString("method", "GET").uppercase() == "GET")
        validatePlayerScriptUrl(url)
        var headers = parsed.optJSONObject("headers")

        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val request = buildRequest(url, "GET", headers, null)
            val result = currentClient().newCall(request).await { value ->
                validatePlayerScriptUrl(value.request.url)
                if (value.isRedirect) {
                    if (redirectCount == MAX_REDIRECTS) {
                        throw IOException("Too many player script redirects")
                    }
                    val location = value.header("Location")
                        ?: throw IOException("Player script redirect did not include a location")
                    val redirectedUrl = value.request.url.resolve(location)
                        ?: throw IOException("Invalid player script redirect location")
                    validatePlayerScriptUrl(redirectedUrl)
                    if (!value.request.url.isSameOrigin(redirectedUrl)) {
                        headers = headers?.withoutHeaders(SENSITIVE_REDIRECT_HEADERS)
                    }
                    url = redirectedUrl
                    return@await null
                }
                if (!value.isSuccessful) {
                    return@await playerSourceFailure(
                        FailureKind.HTTP,
                        "YouTube player script request failed with HTTP ${value.code}",
                    )
                }
                val source = value.readLimitedBody(MAX_PLAYER_SCRIPT_BYTES).toString(Charsets.UTF_8)
                if (source.isBlank() || source.startsWith(PLAYER_SOURCE_ERROR_PREFIX)) {
                    return@await playerSourceFailure(
                        FailureKind.INVALID_RESPONSE,
                        "YouTube returned an invalid player script",
                    )
                }
                source
            }
            if (result != null) return result
        }
        return playerSourceFailure(FailureKind.NETWORK, "Player script redirect failed")
    }

    private fun buildRequest(
        url: HttpUrl,
        method: String,
        headers: JSONObject?,
        body: ByteArray?,
    ): Request {
        val builder = Request.Builder().url(url)
        headers?.keys()?.forEach { name ->
            val value = headers.optString(name)
            if (isAllowedHeader(name, value)) builder.header(name, value)
        }
        val requestBody =
            if (method == "POST") {
                (body ?: ByteArray(0)).toRequestBody(
                    headers?.optString("content-type")?.toMediaTypeOrNull(),
                )
            } else {
                null
            }
        return builder.method(method, requestBody).build()
    }

    private fun currentClient(): OkHttpClient {
        val configuration = configurationProvider()
        activeClient?.takeIf { it.configuration == configuration }?.let { return it.client }
        return synchronized(clientLock) {
            activeClient?.takeIf { it.configuration == configuration }?.client
                ?: buildClient(configuration).also { client ->
                    activeClient?.client?.dispatcher?.cancelAll()
                    activeClient?.client?.connectionPool?.evictAll()
                    activeClient = ActiveClient(configuration, client)
                }
        }
    }

    private fun buildClient(configuration: YoutubeiNetworkConfiguration): OkHttpClient =
        OkHttpClient
            .Builder()
            .proxy(configuration.proxy ?: Proxy.NO_PROXY)
            .dns(configuration.dns)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .apply {
                configuration.interceptors.forEach(::addInterceptor)
                if (
                    configuration.proxy != null &&
                    !configuration.proxyUsername.isNullOrBlank() &&
                    !configuration.proxyPassword.isNullOrBlank()
                ) {
                    proxyAuthenticator { _, response ->
                        if (response.request.header("Proxy-Authorization") != null) {
                            null
                        } else {
                            response.request
                                .newBuilder()
                                .header(
                                    "Proxy-Authorization",
                                    Credentials.basic(
                                        configuration.proxyUsername,
                                        configuration.proxyPassword,
                                    ),
                                ).build()
                        }
                    }
                }
            }.build()

    private fun validateUrl(url: HttpUrl) {
        require(url.isHttps)
        val host = url.host.lowercase()
        require(ALLOWED_HOST_SUFFIXES.any { suffix -> host == suffix || host.endsWith(".$suffix") })
    }

    private fun validatePlayerScriptUrl(url: HttpUrl) {
        require(url.isHttps)
        require(
            url.host.equals("www.youtube.com", ignoreCase = true) ||
                url.host.equals("youtube.com", ignoreCase = true),
        )
        require(PLAYER_SCRIPT_PATH.matches(url.encodedPath))
    }

    private fun isAllowedHeader(
        name: String,
        value: String,
    ): Boolean {
        if (name.isBlank() || value.isBlank() || name.any { it == '\r' || it == '\n' } || value.any { it == '\r' || it == '\n' }) {
            return false
        }
        val normalized = name.lowercase()
        return normalized in ALLOWED_HEADERS || normalized.startsWith("x-goog-") || normalized.startsWith("x-youtube-")
    }

    private fun HttpUrl.isSameOrigin(other: HttpUrl): Boolean =
        scheme == other.scheme && host == other.host && port == other.port

    private fun JSONObject.withoutHeaders(excludedHeaders: Set<String>): JSONObject =
        JSONObject().also { filtered ->
            keys().forEach { name ->
                if (name.lowercase() !in excludedHeaders) {
                    filtered.put(name, opt(name))
                }
            }
        }

    private fun Response.readLimitedBody(maxBytes: Int): ByteArray {
        val responseBody = body
        val declaredLength = responseBody.contentLength()
        require(declaredLength < 0L || declaredLength <= maxBytes)
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        responseBody.byteStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maxBytes)
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun okhttp3.Headers.toJson(): JSONObject =
        JSONObject().also { json ->
            names().forEach { name ->
                if (name.equals("set-cookie", ignoreCase = true)) return@forEach
                json.put(name, values(name).joinToString(", "))
            }
        }

    private suspend fun <T> Call.await(consume: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val startedAt = SystemClock.elapsedRealtime()
            val stage =
                when {
                    request().url.encodedPath == "/iframe_api" -> "player-id"
                    PLAYER_SCRIPT_PATH.matches(request().url.encodedPath) -> "player-script"
                    request().url.encodedPath == "/youtubei/v1/player" -> "player-response"
                    else -> "youtube-api"
                }
            diagnostics("HTTP $stage started")
            continuation.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        diagnostics("HTTP $stage failed type=${e.javaClass.simpleName} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        if (!continuation.isActive) {
                            response.close()
                            return
                        }
                        val result: Result<T> =
                            try {
                                Result.success(response.use(consume))
                            } catch (exception: Throwable) {
                                Result.failure(exception)
                            }
                        val outcome = result.exceptionOrNull()?.javaClass?.simpleName ?: "complete"
                        diagnostics("HTTP $stage $outcome status=${response.code} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
                        continuation.resumeWith(result)
                    }
                },
            )
        }

    private fun failure(
        kind: FailureKind,
        message: String,
    ): String =
        JSONObject()
            .put("ok", false)
            .put("kind", kind.name)
            .put("message", message)
            .toString()

    private fun playerSourceFailure(
        kind: FailureKind,
        message: String,
    ): String = "$PLAYER_SOURCE_ERROR_PREFIX${kind.name}|$message"

    private enum class FailureKind {
        HTTP,
        TIMEOUT,
        NETWORK,
        INVALID_RESPONSE,
        INTERNAL,
    }

    private data class ActiveClient(
        val configuration: YoutubeiNetworkConfiguration,
        val client: OkHttpClient,
    )

    private companion object {
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        const val MAX_PLAYER_SCRIPT_BYTES = 4 * 1024 * 1024
        const val MAX_REDIRECTS = 5
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 20L
        const val WRITE_TIMEOUT_SECONDS = 20L
        const val CALL_TIMEOUT_SECONDS = 30L
        const val PLAYER_SOURCE_ERROR_PREFIX = "ARCHIVETUNE_PLAYER_SOURCE_ERROR|"
        val PLAYER_SCRIPT_PATH =
            Regex("^/s/player/[A-Za-z0-9._-]+/player_es6\\.vflset/[A-Za-z0-9._-]+/base\\.js$")
        val ALLOWED_METHODS = setOf("GET", "POST", "HEAD")
        val BODY_HEADERS = setOf("content-type")
        val SENSITIVE_REDIRECT_HEADERS = setOf("authorization", "cookie")
        val ALLOWED_HOST_SUFFIXES =
            setOf(
                "youtube.com",
                "google.com",
                "googleapis.com",
                "googlevideo.com",
                "ytimg.com",
                "ggpht.com",
            )
        val ALLOWED_HEADERS =
            setOf(
                "accept",
                "accept-language",
                "authorization",
                "content-type",
                "cookie",
                "origin",
                "referer",
                "user-agent",
            )
    }
}
