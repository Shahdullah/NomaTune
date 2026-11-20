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
import android.os.Looper
import androidx.annotation.WorkerThread
import androidx.datastore.preferences.core.edit
import com.shahdullah.nomatune.constants.DataSyncIdKey
import com.shahdullah.nomatune.innertube.PlaybackAuthState
import com.shahdullah.nomatune.innertube.YouTube
import com.shahdullah.nomatune.morideobfuscator.youtubei.YoutubeiException
import com.shahdullah.nomatune.morideobfuscator.youtubei.YoutubeiFailureKind
import com.shahdullah.nomatune.morideobfuscator.youtubei.YoutubeiNetworkConfiguration
import com.shahdullah.nomatune.utils.YTPlayerUtils
import com.shahdullah.nomatune.utils.dataStore
import com.shahdullah.nomatune.utils.potoken.BotGuardTokenGenerator
import com.shahdullah.nomatune.utils.toPlaybackAuthState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResolveAudioStreamUseCase
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val youtubeiRepository: YoutubeiStreamRepository,
    ) {
        private data class CacheKey(
            val mediaId: String,
            val quality: String,
            val networkMetered: Boolean,
            val purpose: StreamPurpose,
            val authFingerprint: String,
            val pinnedFormatId: Int?,
            val requiresSongMetadata: Boolean,
            val network: YoutubeiNetworkConfiguration,
        )

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val cache = ConcurrentHashMap<CacheKey, ResolvedAudioStream>()
        private val inFlight = ConcurrentHashMap<CacheKey, Deferred<ResolvedAudioStream>>()
        private val sessionResetMutex = Mutex()
        private val sessionResetRequired = AtomicBoolean(false)
        private val loginContextMutex = Mutex()

        suspend operator fun invoke(request: AudioStreamRequest): ResolvedAudioStream {
            val key = request.cacheKey()
            cache[key]?.let { cached ->
                if (isFresh(cached)) return cached
                cache.remove(key, cached)
            }
            val priority =
                if (request.purpose == StreamPurpose.PLAYBACK) {
                    StreamResolutionPriority.FOREGROUND
                } else {
                    StreamResolutionPriority.BACKGROUND
                }

            val deferred =
                inFlight.computeIfAbsent(key) {
                    scope.async {
                        try {
                            resolveUncached(request, priority).also { resolved ->
                                cache[key] = resolved
                                cache[key.copy(authFingerprint = resolved.authFingerprint)] = resolved
                                trimCacheIfNeeded()
                            }
                        } finally {
                            inFlight.remove(key)
                        }
                    }
                }
            return deferred.await()
        }

        @WorkerThread
        fun resolveBlocking(request: AudioStreamRequest): ResolvedAudioStream {
            check(Looper.myLooper() != Looper.getMainLooper())
            val future = scope.future { invoke(request) }
            return try {
                future.get()
            } catch (e: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Audio stream resolution interrupted").apply { initCause(e) }
            } catch (e: ExecutionException) {
                future.cancel(true)
                throw (e.cause ?: e)
            }
        }

        fun invalidate(
            mediaId: String,
            purpose: StreamPurpose? = null,
        ) {
            cache.keys.removeIf { it.mediaId == mediaId && (purpose == null || it.purpose == purpose) }
            inFlight.entries.removeIf { (k, d) ->
                val match = k.mediaId == mediaId && (purpose == null || k.purpose == purpose)
                if (match) d.cancel()
                match
            }
        }

        fun reportRejectedStream(url: String) {
            val canonical = url.toHttpUrlOrNull()?.toString() ?: url
            val entry =
                cache.entries.firstOrNull {
                    (it.value.url.toHttpUrlOrNull()?.toString() ?: it.value.url) == canonical
                } ?: return
            cache.entries.removeIf {
                (it.value.url.toHttpUrlOrNull()?.toString() ?: it.value.url) == canonical
            }
            if (YTPlayerUtils.isExpiredOrNearExpiredStreamUrl(url)) return
            val binding = entry.value.gvsPoTokenBinding ?: return
            val generation = entry.value.gvsPoTokenMinterGeneration ?: return
            youtubeiRepository.reportRejectedGvsPoToken(generation, binding)
            cache.entries.removeIf { it.value.gvsPoTokenMinterGeneration == generation }
        }

        fun invalidateSession() {
            sessionResetRequired.set(true)
            clear()
        }

        fun clear() {
            cache.clear()
            inFlight.values.forEach { it.cancel() }
            inFlight.clear()
        }

        private suspend fun resolveUncached(
            request: AudioStreamRequest,
            priority: StreamResolutionPriority,
        ): ResolvedAudioStream {
            sessionResetMutex.withLock {
                if (sessionResetRequired.getAndSet(false)) {
                    YTPlayerUtils.resetAnonymousFallbackSession()
                    BotGuardTokenGenerator.invalidateAll()
                    youtubeiRepository.invalidateSessions()
                }
            }
            return try {
                resolveWithAuth(request, priority)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                val rejectedLogin =
                    failure is YTPlayerUtils.InvalidPlaybackLoginContextException ||
                        (failure is YoutubeiException &&
                            failure.kind == YoutubeiFailureKind.HTTP &&
                            failure.httpStatus in setOf(401, 403))
                if (!request.authState.hasLoginCookie || !rejectedLogin) throw failure
                resolveWithAuth(request.copy(authState = PlaybackAuthState.EMPTY), priority)
            }
        }

        private suspend fun resolveWithAuth(
            request: AudioStreamRequest,
            priority: StreamResolutionPriority,
        ): ResolvedAudioStream {
            val resolvedAuthState =
                YTPlayerUtils.ensureYoutubeiPoTokensForPlayback(
                    videoId = request.mediaId,
                    authState = restoreLoginContext(request),
                )
            return youtubeiRepository.resolve(
                request = request.copy(authState = resolvedAuthState),
                priority = priority,
            )
        }

        private suspend fun restoreLoginContext(request: AudioStreamRequest): PlaybackAuthState {
            val authState = request.authState
            if (!authState.hasLoginCookie || authState.hasPlaybackLoginContext) return authState
            val dataSyncId =
                loginContextMutex.withLock {
                    context.dataStore.data
                        .first()
                        .toPlaybackAuthState()
                        .takeIf { it.cookie == authState.cookie }
                        ?.dataSyncId
                        ?: YouTube
                            .accountChannels()
                            .getOrNull()
                            ?.let { list -> list.firstOrNull { it.isSelected } ?: list.firstOrNull() }
                            ?.dataSyncId
                            ?.takeIf { it.isNotBlank() }
                } ?: return authState
            context.dataStore.edit { prefs ->
                val persisted = prefs.toPlaybackAuthState()
                if (persisted.cookie == authState.cookie && persisted.dataSyncId == null) {
                    prefs[DataSyncIdKey] = dataSyncId
                }
            }
            return authState.copy(dataSyncId = dataSyncId).normalized()
        }

        private fun AudioStreamRequest.cacheKey(): CacheKey =
            CacheKey(
                mediaId = mediaId,
                quality = quality.name,
                networkMetered = networkMetered,
                purpose = purpose,
                authFingerprint = authState.streamCacheFingerprint,
                pinnedFormatId = pinnedFormatId,
                requiresSongMetadata = requiresSongMetadata,
                network = playbackNetworkConfiguration(context),
            )

        private fun isFresh(stream: ResolvedAudioStream): Boolean =
            stream.expiresAtMs > System.currentTimeMillis() + 60_000L

        private fun trimCacheIfNeeded() {
            if (cache.size <= 256) return
            cache.entries.removeIf { !isFresh(it.value) }
        }
    }
