/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.innertube.pages

import com.shahdullah.nomatune.innertube.models.Album
import com.shahdullah.nomatune.innertube.models.Artist
import com.shahdullah.nomatune.innertube.models.MusicResponsiveListItemRenderer
import com.shahdullah.nomatune.innertube.models.PlaylistItem
import com.shahdullah.nomatune.innertube.models.Run
import com.shahdullah.nomatune.innertube.models.SongItem
import com.shahdullah.nomatune.innertube.models.WatchEndpoint
import com.shahdullah.nomatune.innertube.models.clean
import com.shahdullah.nomatune.innertube.models.oddElements
import com.shahdullah.nomatune.innertube.models.splitBySeparator
import com.shahdullah.nomatune.innertube.utils.parseTime

data class PlaylistPage(
    val playlist: PlaylistItem,
    val songs: List<SongItem>,
    val songsContinuation: String?,
    val continuation: String?,
) {
    companion object {
        fun fromMusicResponsiveListItemRenderer(
            renderer: MusicResponsiveListItemRenderer,
            playlistId: String? = null,
        ): SongItem? {
            if (playlistId != null && !renderer.belongsToPlaylist(playlistId)) return null
            return renderer.toSongItem(albumColumnIndex = 2)
        }
    }
}

private fun MusicResponsiveListItemRenderer.belongsToPlaylist(playlistId: String): Boolean {
    val expectedPlaylistId = playlistId.removePrefix("VL")
    val endpoint = watchEndpoint()
    val endpointPlaylistId = endpoint?.playlistId?.removePrefix("VL")
    if (playlistItemData?.playlistSetVideoId?.isNotBlank() == true) {
        return endpointPlaylistId == null || endpointPlaylistId == expectedPlaylistId
    }
    if (endpointPlaylistId != expectedPlaylistId) return false
    return endpoint?.playlistSetVideoId?.isNotBlank() == true || endpoint?.index != null
}

internal fun MusicResponsiveListItemRenderer.toSongItem(
    albumColumnIndex: Int? = 2,
): SongItem? {
    val endpoint = watchEndpoint()
    val videoId = playlistItemData?.videoId ?: endpoint?.videoId ?: return null
    val metadataGroups = metadataGroups()
    return SongItem(
        id = videoId,
        title = titleText ?: return null,
        artists = artistsFromColumn(1).ifEmpty { metadataGroups.firstOrNull().toArtists() },
        album = albumColumnIndex?.let(::albumFromColumn)
            ?: metadataGroups.drop(1).firstNotNullOfOrNull { it.toAlbum() },
        duration = fixedDuration ?: metadataGroups.duration(),
        thumbnail = thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
        explicit = isExplicit,
        endpoint = endpoint,
        setVideoId = playlistItemData?.playlistSetVideoId ?: endpoint?.playlistSetVideoId
    )
}

private val MusicResponsiveListItemRenderer.titleText: String?
    get() =
        flexColumns
            .firstOrNull()
            ?.musicResponsiveListItemFlexColumnRenderer
            ?.text
            ?.runs
            ?.joinToString(separator = "") { it.text }
            ?.takeIf { it.isNotBlank() }

private val MusicResponsiveListItemRenderer.isExplicit: Boolean
    get() =
        badges?.any {
            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
        } == true

private val MusicResponsiveListItemRenderer.fixedDuration: Int?
    get() =
        fixedColumns
            ?.firstOrNull()
            ?.musicResponsiveListItemFlexColumnRenderer
            ?.text
            ?.runs
            ?.firstOrNull()
            ?.text
            ?.parseTime()

private fun MusicResponsiveListItemRenderer.watchEndpoint(): WatchEndpoint? =
    navigationEndpoint?.anyWatchEndpoint
        ?: overlay
            ?.musicItemThumbnailOverlayRenderer
            ?.content
            ?.musicPlayButtonRenderer
            ?.playNavigationEndpoint
            ?.anyWatchEndpoint

private fun MusicResponsiveListItemRenderer.metadataGroups(): List<List<Run>> =
    flexColumns
        .drop(1)
        .flatMap {
            it.musicResponsiveListItemFlexColumnRenderer.text?.runs?.splitBySeparator().orEmpty()
        }
        .clean()

private fun MusicResponsiveListItemRenderer.artistsFromColumn(index: Int): List<Artist> =
    flexColumns
        .getOrNull(index)
        ?.musicResponsiveListItemFlexColumnRenderer
        ?.text
        ?.runs
        ?.splitBySeparator()
        ?.clean()
        ?.firstOrNull()
        .toArtists()

private fun MusicResponsiveListItemRenderer.albumFromColumn(index: Int): Album? =
    flexColumns
        .getOrNull(index)
        ?.musicResponsiveListItemFlexColumnRenderer
        ?.text
        ?.runs
        ?.toAlbum()

private fun List<Run>?.toArtists(): List<Artist> =
    this
        ?.oddElements()
        ?.mapNotNull { run ->
            run.text
                .takeIf { it.isNotBlank() && it.parseTime() == null }
                ?.let { name ->
                    Artist(
                        name = name,
                        id = run.navigationEndpoint?.browseEndpoint?.browseId
                    )
                }
        }
        .orEmpty()

private fun List<Run>.toAlbum(): Album? =
    firstNotNullOfOrNull { run ->
        val browseId = run.navigationEndpoint?.browseEndpoint?.browseId ?: return@firstNotNullOfOrNull null
        run.text
            .takeIf { it.isNotBlank() && it.parseTime() == null }
            ?.let { name ->
                Album(
                    name = name,
                    id = browseId
                )
            }
    }

private fun List<List<Run>>.duration(): Int? {
    for (group in asReversed()) {
        for (run in group.asReversed()) {
            run.text.parseTime()?.let { return it }
        }
    }
    return null
}
