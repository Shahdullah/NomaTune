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

package com.shahdullah.nomatune.playback

import android.content.Context
import android.net.ConnectivityManager
import androidx.annotation.OptIn
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.shahdullah.nomatune.constants.AudioQuality
import com.shahdullah.nomatune.constants.AudioQualityKey
import com.shahdullah.nomatune.db.MusicDatabase
import com.shahdullah.nomatune.db.entities.FormatEntity
import com.shahdullah.nomatune.db.entities.SongEntity
import com.shahdullah.nomatune.di.DownloadCache
import com.shahdullah.nomatune.di.PlayerCache
import com.shahdullah.nomatune.innertube.YouTube
import com.shahdullah.nomatune.playback.stream.AudioStreamRequest
import com.shahdullah.nomatune.playback.stream.PlaybackHttpClient
import com.shahdullah.nomatune.playback.stream.ResolveAudioStreamUseCase
import com.shahdullah.nomatune.playback.stream.ResolvedAudioStream
import com.shahdullah.nomatune.playback.stream.StreamPurpose
import com.shahdullah.nomatune.playback.stream.StreamSource
import com.shahdullah.nomatune.utils.StreamClientUtils
import com.shahdullah.nomatune.utils.YTPlayerUtils
import com.shahdullah.nomatune.utils.enumPreference
import com.shahdullah.nomatune.utils.isLowDataModeActive
import com.shahdullah.nomatune.utils.retryWithoutPlaybackLoginContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(UnstableApi::class)
@Singleton
class DownloadUtil
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        val database: MusicDatabase,
        val databaseProvider: DatabaseProvider,
        @DownloadCache val downloadCache: Cache,
        @PlayerCache val playerCache: Cache,
        private val resolveAudioStream: ResolveAudioStreamUseCase,
    ) {
        private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
        private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
        private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val downloadExecutor = Executors.newFixedThreadPool(DEFAULT_MAX_PARALLEL_DOWNLOADS)

        private val mediaOkHttpClient: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .dispatcher(
                    okhttp3.Dispatcher().apply {
                        maxRequests = MAX_DOWNLOAD_HTTP_REQUESTS
                        maxRequestsPerHost = MAX_DOWNLOAD_HTTP_REQUESTS
                    },
                ).addInterceptor { chain ->
                    val request = chain.request()
                    val host = request.url.host
                    val isYouTubeMediaHost =
                        host.endsWith("googlevideo.com") ||
                            host.endsWith("googleusercontent.com") ||
                            host.endsWith("youtube.com") ||
                            host.endsWith("youtube-nocookie.com") ||
                            host.endsWith("ytimg.com")

                    if (!isYouTubeMediaHost) return@addInterceptor chain.proceed(request)

                    val requestProfile = StreamClientUtils.resolveRequestProfile(request.url)
                    val requestBuilder = request.newBuilder()
                    if (request.header("User-Agent") == null) {
                        requestBuilder.header("User-Agent", requestProfile.userAgent)
                    }
                    if (request.header("Origin") == null && requestProfile.origin != null) {
                        requestBuilder.header("Origin", requestProfile.origin)
                    }
                    if (request.header("Referer") == null && requestProfile.referer != null) {
                        requestBuilder.header("Referer", requestProfile.referer)
                    }
                    chain.proceed(requestBuilder.build())
                }.addNetworkInterceptor { chain ->
                    val request = chain.request()
                    val response = chain.proceed(request)
                    if (response.code == 403 &&
                        (request.url.host == "googlevideo.com" || request.url.host.endsWith(".googlevideo.com"))
                    ) {
                        resolveAudioStream.reportRejectedStream(chain.call().request().url.toString())
                    }
                    response
                }.build()
        }

        val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

        private val httpDataSourceFactory =
            OkHttpDataSource.Factory(
                PlaybackHttpClient(context, mediaOkHttpClient) {
                    ConnectionPool(
                        MAX_IDLE_DOWNLOAD_CONNECTIONS,
                        DOWNLOAD_CONNECTION_KEEP_ALIVE_MINUTES,
                        TimeUnit.MINUTES,
                    )
                },
            )

        private val chunkedNetworkDataSourceFactory =
            DataSource.Factory {
                ChunkedDataSource(
                    upstream = httpDataSourceFactory.createDataSource(),
                    chunkLength = DOWNLOAD_CHUNK_LENGTH,
                )
            }

        private val resolvedNetworkDataSourceFactory =
            ResolvingDataSource.Factory(chunkedNetworkDataSourceFactory) { dataSpec ->
                val mediaId = dataSpec.key ?: throw IOException("Download has no media id")
                val lowDataModeActive = context.isLowDataModeActive()
                val requestedAudioQuality = resolveDownloadAudioQuality(lowDataModeActive)
                val existingSong = database.getSongByIdBlocking(mediaId)
                val hasCachedDownload = downloadCache.getCachedSpans(mediaId).isNotEmpty()
                val pinnedFormatId =
                    if (hasCachedDownload) {
                        runBlocking(Dispatchers.IO) { database.format(mediaId).first() }
                            ?.itag
                            ?.takeIf { it > 0 }
                    } else {
                        null
                    }

                val resolved =
                    runCatching {
                        resolveAudioStream.resolveBlocking(
                            AudioStreamRequest(
                                mediaId = mediaId,
                                quality = requestedAudioQuality,
                                networkMetered = lowDataModeActive,
                                purpose = StreamPurpose.DOWNLOAD,
                                authState = YouTube.currentPlaybackAuthState(),
                                pinnedFormatId = pinnedFormatId,
                                requiresSongMetadata = existingSong == null,
                            ),
                        )
                    }.recoverCatching { primaryFailure ->
                        if (primaryFailure is CancellationException) throw primaryFailure
                        val playbackData =
                            runBlocking(Dispatchers.IO) {
                                context.retryWithoutPlaybackLoginContext {
                                    YTPlayerUtils.playerResponseForDownload(
                                        mediaId,
                                        audioQuality = requestedAudioQuality,
                                        connectivityManager = connectivityManager,
                                        networkMetered = lowDataModeActive,
                                    )
                                }
                            }.getOrThrow()
                        val format = playbackData.format
                        ResolvedAudioStream(
                            url = playbackData.streamUrl,
                            requestHeaders = emptyMap(),
                            formatId = format.itag,
                            mimeType = format.mimeType.substringBefore(";"),
                            codecs =
                                format.mimeType
                                    .substringAfter("codecs=", "")
                                    .removeSurrounding("\"")
                                    .substringBefore("\""),
                            bitrate = format.bitrate,
                            sampleRate = format.audioSampleRate,
                            contentLength = format.contentLength ?: 0L,
                            expiresAtMs = System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L),
                            authFingerprint = playbackData.authFingerprint,
                            source = StreamSource.YOUTUBEI,
                            title = playbackData.videoDetails?.title,
                            durationSeconds = playbackData.videoDetails?.lengthSeconds?.toIntOrNull(),
                            thumbnailUrl = playbackData.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url,
                            loudnessDb = playbackData.audioConfig?.loudnessDb,
                            perceptualLoudnessDb = playbackData.audioConfig?.perceptualLoudnessDb,
                            playbackTrackingUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl,
                        )
                    }.getOrElse { failure ->
                        throw (failure as? IOException ?: IOException("Failed to resolve download stream", failure))
                    }

                persistPlaybackMetadata(mediaId, resolved)

                val resolvedDataSpec =
                    dataSpec
                        .buildUpon()
                        .setUri(resolved.url.toUri())
                        .setHttpRequestHeaders(dataSpec.httpRequestHeaders + resolved.requestHeaders)
                        .build()
                val resolvedContentLength = resolved.contentLength.takeIf { it > 0L }
                if (resolvedContentLength != null && dataSpec.position == resolvedContentLength) {
                    return@Factory resolvedDataSpec.buildUpon().setLength(C.LENGTH_UNSET.toLong()).build()
                }
                val remainingLength =
                    resolvedContentLength?.let { len -> (len - dataSpec.position).takeIf { it > 0L } }
                val length =
                    remainingLength?.let { remaining ->
                        if (dataSpec.length == C.LENGTH_UNSET.toLong()) remaining else minOf(dataSpec.length, remaining)
                    }
                length?.let { resolvedDataSpec.subrange(0L, it) } ?: resolvedDataSpec
            }

        private val dataSourceFactory =
            CacheDataSource
                .Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(resolvedNetworkDataSourceFactory)
                .setCacheWriteDataSinkFactory(null)
                .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)

        val downloadNotificationHelper =
            DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

        val downloadManager: DownloadManager =
            DownloadManager(
                context,
                databaseProvider,
                downloadCache,
                dataSourceFactory,
                downloadExecutor,
            ).apply {
                maxParallelDownloads = DEFAULT_MAX_PARALLEL_DOWNLOADS
                addListener(
                    object : DownloadManager.Listener {
                        override fun onInitialized(downloadManager: DownloadManager) {
                            // Downloads that already exist on disk - and, crucially, ones that
                            // are still in progress from an earlier session - are only ever
                            // delivered here. Without this the in-memory map starts empty and
                            // fills up only when a download happens to change state, so the
                            // Downloads page could show nothing at all while the system
                            // notification happily reported "Downloading N songs".
                            downloadScope.launch { refreshSnapshot() }
                        }

                        override fun onDownloadChanged(
                            downloadManager: DownloadManager,
                            download: Download,
                            finalException: Exception?,
                        ) {
                            downloads.update { map ->
                                map.toMutableMap().apply {
                                    set(download.request.id, download)
                                }
                            }
                            if (download.state == Download.STATE_FAILED) {
                                resolveAudioStream.invalidate(download.request.id, StreamPurpose.DOWNLOAD)
                            }
                        }

                        override fun onDownloadRemoved(
                            downloadManager: DownloadManager,
                            download: Download,
                        ) {
                            downloads.update { map -> map - download.request.id }
                        }
                    },
                )
            }

        init {
            downloadScope.launch {
                val result = mutableMapOf<String, Download>()
                downloadManager.downloadIndex.getDownloads().use { cursor ->
                    while (cursor.moveToNext()) {
                        result[cursor.download.request.id] = cursor.download
                    }
                }
                downloads.value = result
            }
        }

        fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

        /**
         * Re-reads the media3 download index into [downloads].
         *
         * The index is only pushed into [downloads] when a listener fires, so a download
         * that was queued while another process (or an earlier session) owned the
         * DownloadManager can be missing from the map even though it exists on disk.
         * Callers that are about to render download state use this to resync first.
         */
        suspend fun refreshDownloads() {
            withContext(Dispatchers.IO) { refreshSnapshot() }
        }

        /** Reads the whole media3 download index into [downloads]. */
        private fun refreshSnapshot() {
            val result = mutableMapOf<String, Download>()
            runCatching {
                downloadManager.downloadIndex.getDownloads().use { cursor ->
                    while (cursor.moveToNext()) {
                        val download = cursor.download
                        result[download.request.id] = download
                    }
                }
            }.onFailure { Timber.tag("DownloadUtil").w(it, "Could not read the download index") }

            // Keep whatever the listeners already know about, so a partial read can never
            // erase a download that is mid-transfer.
            downloads.update { known ->
                val merged = result.toMutableMap()
                known.forEach { (id, download) -> merged.set(id, download) }
                merged
            }
        }

        private fun resolveDownloadAudioQuality(lowDataModeActive: Boolean): AudioQuality =
            if (lowDataModeActive) AudioQuality.LOW else audioQuality

        private fun persistPlaybackMetadata(
            mediaId: String,
            resolved: ResolvedAudioStream,
        ) {
            downloadScope.launch {
                runCatching {
                    database.query {
                        upsert(
                            FormatEntity(
                                id = mediaId,
                                itag = resolved.formatId,
                                mimeType = resolved.mimeType.substringBefore(";"),
                                codecs = resolved.codecs,
                                bitrate = resolved.bitrate,
                                sampleRate = resolved.sampleRate,
                                contentLength = resolved.contentLength.coerceAtLeast(0L),
                                loudnessDb = resolved.loudnessDb,
                                perceptualLoudnessDb = resolved.perceptualLoudnessDb,
                                playbackUrl = resolved.playbackTrackingUrl,
                            ),
                        )

                        val now = LocalDateTime.now()
                        val existing = getSongByIdBlocking(mediaId)?.song

                        val updatedSong =
                            if (existing != null) {
                                if (existing.dateDownload == null) existing.copy(dateDownload = now) else existing
                            } else {
                                SongEntity(
                                    id = mediaId,
                                    title = resolved.title ?: "Unknown",
                                    duration = resolved.durationSeconds ?: 0,
                                    thumbnailUrl = resolved.thumbnailUrl,
                                    dateDownload = now,
                                )
                            }

                        upsert(updatedSong)
                    }
                }
            }
        }

        companion object {
            private const val DEFAULT_MAX_PARALLEL_DOWNLOADS = 6
            private const val MAX_IDLE_DOWNLOAD_CONNECTIONS = 12
            private const val MAX_DOWNLOAD_HTTP_REQUESTS = 24
            private const val DOWNLOAD_CONNECTION_KEEP_ALIVE_MINUTES = 5L
            private const val DOWNLOAD_CHUNK_LENGTH = 8 * 1024 * 1024L
        }
    }
