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
import com.google.common.collect.ImmutableList
import com.shahdullah.nomatune.db.MusicDatabase
import com.shahdullah.nomatune.db.entities.Playlist
import com.shahdullah.nomatune.constants.PlaylistSortType
import com.shahdullah.nomatune.db.entities.PlaylistSongMap
import com.shahdullah.nomatune.db.entities.Song
import com.shahdullah.nomatune.models.MediaMetadata
import com.shahdullah.nomatune.models.toMediaMetadata
import com.shahdullah.nomatune.playback.DownloadUtil
import com.shahdullah.nomatune.ui.utils.sendPauseDownloads
import com.shahdullah.nomatune.ui.utils.sendRemoveDownloads
import com.shahdullah.nomatune.ui.utils.sendResumeDownloads
import com.shahdullah.nomatune.utils.isLocalMediaId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the Downloads page model from what NomaTune already has: media3's download
 * index (via [downloadUtil]) joined against the local song, album and playlist tables.
 *
 * Songs are the only thing that can actually be downloaded, so albums and playlists are
 * derived — a group appears here once every one of its songs is downloaded, which is
 * also how its size and progress are computed.
 */
@Singleton
class ManageDownloadsUseCase
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
        private val downloadUtil: DownloadUtil,
    ) {
        fun observeLibrary(): Flow<DownloadLibraryUiModel> =
            combine(
                downloadUtil.downloads,
                database.allSongs(),
                database.allPlaylistSongMapsForDownloads(),
                database.playlists(PlaylistSortType.CREATE_DATE, true),
            ) { downloads, allSongs, playlistSongMaps, playlists ->
                buildModel(
                    downloads = downloads,
                    allSongs = allSongs,
                    playlistSongMaps = playlistSongMaps,
                    playlists = playlists,
                )
            }.distinctUntilChanged()

        private fun buildModel(
            downloads: Map<String, Download>,
            allSongs: List<Song>,
            playlistSongMaps: List<PlaylistSongMap>,
            playlists: List<Playlist>,
        ): DownloadLibraryUiModel {
            val songById = allSongs.associateBy { it.id }

            // ---- Songs -------------------------------------------------------------
            // A song that was streamed and then downloaded may have no row in the songs
            // table at all — NomaTune only builds a transient Song for playback. Falling
            // back to the download's own request data keeps such a song on this page
            // instead of silently vanishing.
            val songEntries =
                downloads.values.map { download ->
                    songById[download.request.id]?.toEntry(download) ?: download.toEntryFromRequest()
                }

            // ---- Albums ------------------------------------------------------------
            val albumEntries =
                allSongs
                    .filter { it.song.albumId != null }
                    .groupBy { it.song.albumId!! }
                    .mapNotNull { (albumId, songs) ->
                        val downloadsForAlbum = songs.mapNotNull { downloads[it.id] }
                        if (downloadsForAlbum.isEmpty()) return@mapNotNull null
                        val complete = downloadsForAlbum.size == songs.size &&
                            downloadsForAlbum.all { it.state == Download.STATE_COMPLETED }
                        if (!complete) return@mapNotNull null
                        val first = songs.first()
                        DownloadEntryUiModel(
                            id = albumId,
                            title = first.album?.title ?: first.song.title,
                            subtitle = first.artists.joinToString(", ") { it.name }.ifBlank { null },
                            thumbnailUrl = first.album?.thumbnailUrl ?: first.song.thumbnailUrl,
                            mediaType = DownloadMediaType.ALBUM,
                            songIds = songs.map { it.id },
                            playbackMetadata = first.toMediaMetadata(),
                            durationSeconds = songs.sumOf { it.song.duration },
                            totalCount = songs.size,
                            modifiedAtMs = downloadsForAlbum.maxOf { it.updatedAtMillis() },
                            status = DownloadStatus.COMPLETED,
                            progress = 1f,
                            bytesDownloaded = downloadsForAlbum.sumOf { it.bytesDownloaded },
                        )
                    }

            // ---- Playlists ---------------------------------------------------------
            val playlistById = playlists.associateBy { it.id }
            val songByIdForPlaylists = allSongs.associateBy { it.id }
            val playlistEntries =
                playlistSongMaps
                    .groupBy { it.playlistId }
                    .mapNotNull { (playlistId, maps) ->
                        val playlist = playlistById[playlistId] ?: return@mapNotNull null
                        val songs = maps.mapNotNull { songByIdForPlaylists[it.songId] }
                        val songIds = songs.map { it.id }.filterNot { it.isLocalMediaId() }.distinct()
                        if (songIds.isEmpty()) return@mapNotNull null
                        val downloadsForPlaylist = songIds.mapNotNull { downloads[it] }
                        if (downloadsForPlaylist.isEmpty()) return@mapNotNull null
                        val complete = downloadsForPlaylist.size == songIds.size &&
                            downloadsForPlaylist.all { it.state == Download.STATE_COMPLETED }
                        if (!complete) return@mapNotNull null
                        val firstSong = songs.firstOrNull()
                        DownloadEntryUiModel(
                            id = playlist.id,
                            title = playlist.playlist.name,
                            subtitle = null,
                            thumbnailUrl = playlist.playlist.thumbnailUrl ?: firstSong?.song?.thumbnailUrl,
                            mediaType = DownloadMediaType.PLAYLIST,
                            songIds = songIds,
                            playbackMetadata = firstSong?.toMediaMetadata(),
                            durationSeconds = songs.sumOf { it.song.duration },
                            totalCount = songIds.size,
                            modifiedAtMs = downloadsForPlaylist.maxOf { it.updatedAtMillis() },
                            status = DownloadStatus.COMPLETED,
                            progress = 1f,
                            bytesDownloaded = downloadsForPlaylist.sumOf { it.bytesDownloaded },
                        )
                    }

            val completed =
                listOf(
                    DownloadSectionUiModel(DownloadMediaType.PLAYLIST, playlistEntries.sortedByDescending { it.modifiedAtMs }),
                    DownloadSectionUiModel(DownloadMediaType.ALBUM, albumEntries.sortedByDescending { it.modifiedAtMs }),
                    DownloadSectionUiModel(DownloadMediaType.SONG, songEntries.sortedByDescending { it.modifiedAtMs }),
                ).filterNot { it.isEmpty }

            // ---- In-progress -------------------------------------------------------
            val activeDownloads = downloads.values.filter { it.state != Download.STATE_COMPLETED }
            val activeEntries = activeDownloads.map { songById[it.request.id]?.toEntry(it) ?: it.toEntryFromRequest() }
            val progressSections =
                if (activeEntries.isEmpty()) {
                    emptyList()
                } else {
                    listOf(DownloadSectionUiModel(DownloadMediaType.SONG, activeEntries.sortedByDescending { it.modifiedAtMs }))
                }

            val queue =
                DownloadQueueSummary(
                    songIds = activeEntries.filter { it.isActive }.map { it.id },
                    failedSongIds = activeEntries.filter { it.status == DownloadStatus.FAILED }.map { it.id },
                    progress =
                        if (activeEntries.isEmpty()) {
                            0f
                        } else {
                            activeEntries.map { it.progress }.average().toFloat()
                        },
                    paused = activeEntries.isNotEmpty() && activeEntries.all { it.paused },
                )

            val storage =
                DownloadStorageSummary(
                    songCount = songEntries.size,
                    albumCount = albumEntries.size,
                    playlistCount = playlistEntries.size,
                    bytes = downloads.values.sumOf { it.bytesDownloaded },
                )

            return DownloadLibraryUiModel(
                completedSections = ImmutableList.copyOf(completed),
                progressSections = ImmutableList.copyOf(progressSections),
                storage = storage,
                queue = queue,
            )
        }

        private fun Song.toEntry(download: Download): DownloadEntryUiModel {
            val status = download.toDownloadStatus()
            val duration = song.duration
            val progress =
                if (status == DownloadStatus.COMPLETED) {
                    1f
                } else if (duration > 0) {
                    (download.bytesDownloaded.toFloat() / (duration * 32_000f)).coerceIn(0f, 1f)
                } else {
                    0f
                }
            return DownloadEntryUiModel(
                id = id,
                title = song.title,
                subtitle =
                    buildList {
                        artists.joinToString(", ") { it.name }.takeIf { it.isNotBlank() }?.let(::add)
                        album?.title?.let(::add)
                    }.joinToString(" · ").ifBlank { null },
                thumbnailUrl = song.thumbnailUrl ?: album?.thumbnailUrl,
                mediaType = DownloadMediaType.SONG,
                songIds = listOf(id),
                playbackMetadata = toMediaMetadata(),
                durationSeconds = duration.takeIf { it > 0 },
                totalCount = 1,
                modifiedAtMs = download.updatedAtMillis(),
                status = status,
                progress = progress,
                bytesDownloaded = download.bytesDownloaded,
            )
        }

        /**
         * Builds a song entry from the media3 request alone, for downloads whose song is
         * not in the local database. The title is what the enqueue stored in the request
         * payload, and artwork is derived from the video id when it looks like one.
         */
        private fun Download.toEntryFromRequest(): DownloadEntryUiModel {
            val title =
                runCatching { String(request.data ?: ByteArray(0)) }
                    .getOrDefault("")
                    .trim()
                    .ifBlank { request.id }
            val looksLikeYouTubeId =
                request.id.length == 11 && request.id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
            val status = toDownloadStatus()
            return DownloadEntryUiModel(
                id = request.id,
                title = title,
                subtitle = null,
                thumbnailUrl = if (looksLikeYouTubeId) "https://i.ytimg.com/vi/${request.id}/mqdefault.jpg" else null,
                mediaType = DownloadMediaType.SONG,
                songIds = listOf(request.id),
                playbackMetadata = null,
                durationSeconds = null,
                totalCount = 1,
                modifiedAtMs = updatedAtMillis(),
                status = status,
                progress = if (status == DownloadStatus.COMPLETED) 1f else 0f,
                bytesDownloaded = bytesDownloaded,
            )
        }

        // ---- Actions ---------------------------------------------------------------

        fun pause(songIds: Collection<String>) {
            sendPauseDownloads(context, songIds.toList())
        }

        fun resume(songIds: Collection<String>) {
            sendResumeDownloads(context, songIds.toList())
        }

        fun remove(songIds: Collection<String>) {
            sendRemoveDownloads(context, songIds.toList())
        }
    }
