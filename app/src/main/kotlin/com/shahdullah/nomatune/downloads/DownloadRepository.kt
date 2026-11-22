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

package com.shahdullah.nomatune.downloads

import android.content.Context
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import com.shahdullah.nomatune.db.MusicDatabase
import com.shahdullah.nomatune.db.entities.Album
import com.shahdullah.nomatune.db.entities.Playlist
import com.shahdullah.nomatune.db.entities.PlaylistSongMap
import com.shahdullah.nomatune.db.entities.Song
import com.shahdullah.nomatune.playback.DownloadUtil
import com.shahdullah.nomatune.playback.ExoDownloadService
import javax.inject.Inject
import javax.inject.Singleton

data class DownloadRepositorySnapshot(
    val songs: List<Song>,
    val albums: List<Album>,
    val playlists: List<Playlist>,
    val playlistSongMaps: List<PlaylistSongMap>,
    val downloads: Map<String, Download>,
)

interface DownloadRepository {
    fun observeDownloads(): Flow<DownloadRepositorySnapshot>

    fun pause(songIds: Collection<String>)

    fun resume(songIds: Collection<String>)

    fun remove(songIds: Collection<String>)
}

@Singleton
class Media3DownloadRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
        private val downloadUtil: DownloadUtil,
    ) : DownloadRepository {
        override fun observeDownloads(): Flow<DownloadRepositorySnapshot> =
            combine(
                database.allSongs().onStart { emit(emptyList()) },
                database.allAlbumsForDownloads().onStart { emit(emptyList()) },
                database.allPlaylistsForDownloads().onStart { emit(emptyList()) },
                database.allPlaylistSongMapsForDownloads().onStart { emit(emptyList()) },
            ) { songs, albums, playlists, playlistSongMaps ->
                DownloadRepositorySnapshot(
                    songs = songs,
                    albums = albums,
                    playlists = playlists,
                    playlistSongMaps = playlistSongMaps,
                    downloads = emptyMap(),
                )
            }.combine(downloadUtil.downloads) { snapshot, downloads ->
                snapshot.copy(downloads = downloads)
            }.flowOn(Dispatchers.IO)

        override fun pause(songIds: Collection<String>) {
            songIds.distinct().forEach { songId ->
                DownloadService.sendSetStopReason(
                    context,
                    ExoDownloadService::class.java,
                    songId,
                    PAUSED_STOP_REASON,
                    false,
                )
            }
        }

        override fun resume(songIds: Collection<String>) {
            songIds.distinct().forEach { songId ->
                val download = downloadUtil.downloads.value[songId] ?: return@forEach
                when (download.state) {
                    Download.STATE_FAILED ->
                        DownloadService.sendAddDownload(
                            context,
                            ExoDownloadService::class.java,
                            download.request,
                            NO_STOP_REASON,
                            true,
                        )

                    Download.STATE_STOPPED ->
                        DownloadService.sendSetStopReason(
                            context,
                            ExoDownloadService::class.java,
                            songId,
                            NO_STOP_REASON,
                            true,
                        )
                }
            }
        }

        override fun remove(songIds: Collection<String>) {
            songIds.distinct().forEach { songId ->
                DownloadService.sendRemoveDownload(
                    context,
                    ExoDownloadService::class.java,
                    songId,
                    false,
                )
            }
        }

        private companion object {
            const val NO_STOP_REASON = 0
            const val PAUSED_STOP_REASON = 1
        }
    }
