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

package com.shahdullah.nomatune.playback.stream

import android.content.Context
import com.shahdullah.nomatune.constants.AudioQuality
import com.shahdullah.nomatune.innertube.PlaybackAuthState
import com.shahdullah.nomatune.innertube.YouTube
import com.shahdullah.nomatune.innertube.utils.hasCompleteYouTubeLoginCookies
import com.shahdullah.nomatune.morideobfuscator.youtubei.YoutubeiAudioQuality
import com.shahdullah.nomatune.morideobfuscator.youtubei.YoutubeiException
import com.shahdullah.nomatune.morideobfuscator.youtubei.YoutubeiFailureKind
import com.shahdullah.nomatune.morideobfuscator.youtubei.YoutubeiResolutionPriority
import com.shahdullah.nomatune.morideobfuscator.youtubei.YoutubeiResolver
import com.shahdullah.nomatune.morideobfuscator.youtubei.YoutubeiStreamRequest
import com.shahdullah.nomatune.utils.YTPlayerUtils
import com.shahdullah.nomatune.utils.potoken.BotGuardTokenGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class YoutubeiStreamRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : AudioStreamRepository {
        private val resolver =
            YoutubeiResolver(
                context = context,
                diagnostics = { message -> Timber.tag(LOG_TAG).d(message) },
            ) {
                playbackNetworkConfiguration(context)
            }

        private val streamProbeClient =
            PlaybackHttpClient(
                context,
                OkHttpClient
                    .Builder()
                    .connectTimeout(STREAM_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(STREAM_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .callTimeout(STREAM_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build(),
            )

        private val gvsPoTokenVerifier =
            GvsPoTokenVerifier(
                maxMinterAttempts = MAX_TOKEN_GENERATOR_ATTEMPTS,
                replaceMinter = BotGuardTokenGenerator::discardMinter,
                probeStatus = ::probeStreamStatus,
            )

        override suspend fun resolve(request: AudioStreamRequest): ResolvedAudioStream =
            resolve(
                request = request,
                priority =
                    when (request.purpose) {
                        StreamPurpose.PLAYBACK -> StreamResolutionPriority.FOREGROUND
                        StreamPurpose.DOWNLOAD -> StreamResolutionPriority.BACKGROUND
                    },
            )

        internal suspend fun resolve(
            request: AudioStreamRequest,
            priority: StreamResolutionPriority,
        ): ResolvedAudioStream =
            try {
                resolveOnce(request, priority, request.authState)
            } catch (failure: YoutubeiException) {
                // A signed-out device can still be carrying playback state from an earlier
                // session (a stale visitor identity, a PoToken minted for a previous login).
                // YouTube answers "Please sign in" for every such request even though the
                // user is not signed in. Retry exactly once with a pristine anonymous state
                // instead of sending the user a dead end.
                if (request.authState.hasLoginCookie || !failure.isAnonymousRefusal()) throw failure
                val cleanAuthState =
                    YouTube.updateAuthState { current ->
                        if (!current.hasLoginCookie) current.withoutResidualPlaybackState() else current
                    }
                if (cleanAuthState.hasLoginCookie || cleanAuthState == request.authState) throw failure
                resolver.invalidateSessions()
                YTPlayerUtils.clearPlaybackAuthCaches()
                Timber.tag(LOG_TAG).i(
                    "Anonymous resolve refused (%s); retrying without residual playback state",
                    failure.kind,
                )
                try {
                    resolveOnce(request, priority, cleanAuthState)
                } catch (retryFailure: YoutubeiException) {
                    retryFailure.addSuppressed(failure)
                    throw retryFailure
                }
            }

        private suspend fun resolveOnce(
            request: AudioStreamRequest,
            priority: StreamResolutionPriority,
            authState0: PlaybackAuthState,
        ): ResolvedAudioStream {
            val authState = authState0
            if (authState.hasLoginCookie && !hasCompleteYouTubeLoginCookies(authState.cookie)) {
                throw YTPlayerUtils.InvalidPlaybackLoginContextException(
                    videoId = request.mediaId,
                    targetUrl = request.mediaUrl,
                    cause = IllegalStateException("YouTube login cookies are incomplete"),
                )
            }

            val locale = YouTube.locale
            var streamMediaId = request.mediaId
            val resolved =
                try {
                    resolver.resolve(
                        request =
                            YoutubeiStreamRequest(
                                mediaId = request.mediaId,
                                quality = request.quality.toYoutubeiQuality(),
                                networkMetered = request.networkMetered,
                                authFingerprint = authState.streamCacheFingerprint,
                                pinnedItag = request.pinnedFormatId,
                                requiresSongMetadata = request.requiresSongMetadata,
                                cookie = authState.cookie.takeIf { authState.hasLoginCookie },
                                visitorData = authState.visitorData,
                                dataSyncId = authState.dataSyncId.takeIf { authState.hasLoginCookie },
                                sessionPoToken = authState.poTokenGvsSession,
                                videoPoToken =
                                    authState.poTokenGvs?.takeIf {
                                        authState.poTokenGvsVideoId == request.mediaId
                                    },
                                language = locale.hl,
                                location = locale.gl,
                                timezone = TimeZone.getDefault().id,
                            ),
                        priority = priority.toYoutubeiPriority(),
                        videoPoTokenProvider = { replacementMediaId ->
                            streamMediaId = replacementMediaId
                            val replacementAuthState =
                                YTPlayerUtils.ensureYoutubeiPoTokensForPlayback(
                                    videoId = replacementMediaId,
                                    authState = authState,
                                )
                            replacementAuthState.poTokenGvs?.takeIf {
                                replacementAuthState.poTokenGvsVideoId == replacementMediaId
                            }
                        },
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: YoutubeiException) {
                    if (failure.kind == YoutubeiFailureKind.PO_TOKEN ||
                        failure.kind == YoutubeiFailureKind.LOGIN_REQUIRED &&
                        YTPlayerUtils.isBotDetectionError(failure.message.orEmpty())
                    ) {
                        throw YTPlayerUtils.BotDetectionPlaybackException(
                            videoId = request.mediaId,
                            clients = setOf(if (authState.hasLoginCookie) "WEB_CREATOR" else "MWEB"),
                            cause = failure,
                        )
                    }
                    if (failure.kind == YoutubeiFailureKind.LOGIN_REQUIRED ||
                        failure.kind == YoutubeiFailureKind.HTTP && failure.httpStatus == 401
                    ) {
                        if (!failure.requiresContentConfirmation()) {
                            if (!authState.hasLoginCookie) {
                                throw YTPlayerUtils.BadStreamPlayerResponseException(
                                    videoId = request.mediaId,
                                    failedClients = setOf("MWEB"),
                                    cause = YoutubeiException(
                                        kind = YoutubeiFailureKind.LOGIN_REQUIRED,
                                        message = failure.message.orEmpty() + " " + authState.diagnosticsTag(),
                                        httpStatus = failure.httpStatus,
                                        cause = failure,
                                    ),
                                )
                            }
                            throw YTPlayerUtils.InvalidPlaybackLoginContextException(
                                videoId = request.mediaId,
                                targetUrl = request.mediaUrl,
                                cause = failure,
                            )
                        }
                        throw YTPlayerUtils.LoginRequiredForPlaybackException(
                            videoId = request.mediaId,
                            targetUrl = request.mediaUrl,
                            reason = failure.message,
                            cause = failure,
                        )
                    }
                    throw failure
                }

            val resolvedUrl = resolved.url.toHttpUrlOrNull()
            val verifiedStream =
                resolvedUrl?.let { url ->
                    verifyGvsPoToken(
                        mediaId = request.mediaId,
                        streamMediaId = streamMediaId,
                        authState = authState,
                        streamUrl = url,
                        contentLength = resolved.contentLength,
                        requestHeaders = resolved.requestHeaders,
                    )
                }
            val streamUrl = verifiedStream?.url ?: resolvedUrl
            return ResolvedAudioStream(
                url = streamUrl?.toString() ?: resolved.url,
                requestHeaders = resolved.requestHeaders,
                formatId = resolved.formatId,
                mimeType = resolved.mimeType,
                codecs = resolved.codecs,
                bitrate = resolved.bitrate,
                sampleRate = resolved.sampleRate,
                contentLength = resolved.contentLength,
                expiresAtMs = resolved.expiresAtMs,
                authFingerprint = authState.streamCacheFingerprint,
                source = StreamSource.YOUTUBEI,
                runtimeVersion = resolved.runtimeVersion,
                title = resolved.title,
                durationSeconds = resolved.durationSeconds,
                thumbnailUrl = resolved.thumbnailUrl,
                loudnessDb = resolved.loudnessDb,
                perceptualLoudnessDb = resolved.perceptualLoudnessDb,
                playbackTrackingUrl = resolved.playbackTrackingUrl,
                gvsPoTokenBinding = verifiedStream?.binding,
                gvsPoTokenMinterGeneration = verifiedStream?.minterGeneration,
            )
        }

        private suspend fun verifyGvsPoToken(
            mediaId: String,
            streamMediaId: String,
            authState: PlaybackAuthState,
            streamUrl: HttpUrl,
            contentLength: Long,
            requestHeaders: Map<String, String>,
        ): VerifiedGvsStream? {
            if (streamUrl.queryParameter("pot").isNullOrBlank() || streamUrl.queryParameter("sabr") == "1") return null
            return try {
                gvsPoTokenVerifier.verify(
                    streamUrl = streamUrl,
                    contentLength = contentLength,
                    mint = {
                        val tokens = YTPlayerUtils.mintYoutubeiPoTokens(streamMediaId, authState)
                        MintedGvsPoTokens(
                            videoToken = tokens.playerToken,
                            sessionToken = tokens.sessionToken,
                            minterGeneration = tokens.minterGeneration,
                        )
                    },
                    requestHeaders = requestHeaders,
                )
            } catch (failure: YoutubeiException) {
                if (failure.kind != YoutubeiFailureKind.PO_TOKEN) throw failure
                throw YTPlayerUtils.BotDetectionPlaybackException(
                    videoId = mediaId,
                    clients = setOf(if (authState.hasLoginCookie) "WEB_CREATOR" else "MWEB"),
                    cause = failure,
                )
            }
        }

        private suspend fun probeStreamStatus(
            url: HttpUrl,
            byteRange: LongRange,
            requestHeaders: Map<String, String>,
        ): Int? {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .apply { requestHeaders.forEach { (name, value) -> header(name, value) } }
                    .header("Range", "bytes=${byteRange.first}-${byteRange.last}")
                    .build()
            return try {
                val statusCode = streamProbeClient.newCall(request).awaitStatusCode()
                Timber.tag(LOG_TAG).d("Stream token check status=%d", statusCode)
                statusCode
            } catch (failure: IOException) {
                Timber.tag(LOG_TAG).w("Stream token check failed type=%s", failure.javaClass.simpleName)
                null
            }
        }

        private suspend fun Call.awaitStatusCode(): Int =
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { cancel() }
                enqueue(
                    object : Callback {
                        override fun onFailure(
                            call: Call,
                            e: IOException,
                        ) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }

                        override fun onResponse(
                            call: Call,
                            response: Response,
                        ) {
                            val statusCode = response.use { it.code }
                            if (continuation.isActive) continuation.resume(statusCode)
                        }
                    },
                )
            }

        internal fun reportRejectedGvsPoToken(
            minterGeneration: Long,
            binding: GvsPoTokenBinding,
        ) {
            gvsPoTokenVerifier.reportRejected(minterGeneration, binding)
        }

        suspend fun preWarm() = resolver.preWarm()

        suspend fun invalidateSessions() = resolver.invalidateSessions()

        fun trimMemory(level: Int) = resolver.trimMemory(level)

        private fun AudioQuality.toYoutubeiQuality(): YoutubeiAudioQuality =
            when (this) {
                AudioQuality.LOW -> YoutubeiAudioQuality.LOW
                AudioQuality.HIGH -> YoutubeiAudioQuality.HIGH
                AudioQuality.HIGHEST -> YoutubeiAudioQuality.HIGHEST
                AudioQuality.AUTO -> YoutubeiAudioQuality.AUTO
            }

        private fun StreamResolutionPriority.toYoutubeiPriority(): YoutubeiResolutionPriority =
            when (this) {
                StreamResolutionPriority.FOREGROUND -> YoutubeiResolutionPriority.FOREGROUND
                StreamResolutionPriority.BACKGROUND -> YoutubeiResolutionPriority.BACKGROUND
            }

        private val AudioStreamRequest.mediaUrl: String
            get() = "https://music.youtube.com/watch?v=$mediaId"

        private fun YoutubeiException.requiresContentConfirmation(): Boolean {
            if (httpStatus == 401) return false
            val reason = message.orEmpty()
            return CONTENT_CONFIRMATION_REASONS.any { reason.contains(it, ignoreCase = true) }
        }

        private fun PlaybackAuthState.withoutResidualPlaybackState(): PlaybackAuthState =
            copy(
                cookie = null,
                dataSyncId = null,
                poToken = null,
                poTokenGvs = null,
                poTokenGvsSession = null,
                poTokenGvsVideoId = null,
                poTokenPlayer = null,
                poTokenPlayerVideoId = null,
                poTokenSubs = null,
                poTokenSubsVideoId = null,
                webClientPoTokenEnabled = false,
            ).normalized()

        /**
         * Compact, non-sensitive description of the playback auth state. Printed in the playback
         * error so a screenshot is enough to tell which request shape reached YouTube.
         */
        private fun PlaybackAuthState.diagnosticsTag(): String =
            "[" +
                "signedIn=" + hasLoginCookie +
                ",visitor=" + !visitorData.isNullOrBlank() +
                ",dataSync=" + !dataSyncId.isNullOrBlank() +
                ",poToken=" + !poTokenGvs.isNullOrBlank() +
                ",sessionPoToken=" + !poTokenGvsSession.isNullOrBlank() +
                ",webPoTokens=" + webClientPoTokenEnabled +
                "]"

        private fun YoutubeiException.isAnonymousRefusal(): Boolean =
            kind == YoutubeiFailureKind.LOGIN_REQUIRED ||
                kind == YoutubeiFailureKind.PO_TOKEN ||
                (kind == YoutubeiFailureKind.HTTP && httpStatus == 401)

        private companion object {
            const val LOG_TAG = "YoutubeiResolver"
            const val STREAM_PROBE_TIMEOUT_SECONDS = 10L
            const val MAX_TOKEN_GENERATOR_ATTEMPTS = 3
            val CONTENT_CONFIRMATION_REASONS =
                listOf(
                    "confirm your age",
                    "verify your age",
                    "age-restricted",
                    "age restricted",
                    "age verification",
                    "AGE_CHECK_REQUIRED",
                    "AGE_VERIFICATION_REQUIRED",
                    "CONTENT_CHECK_REQUIRED",
                    "inappropriate for some users",
                    "mature audiences",
                    "private video",
                    "members-only",
                    "members only",
                )
        }
    }

internal enum class StreamResolutionPriority {
    FOREGROUND,
    BACKGROUND,
}
