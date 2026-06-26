/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.utils

import com.shahdullah.nomatune.db.entities.Song

internal fun Song.discordAlbumMusicUrl(): String? =
    if (song.isLocal || song.id.isLocalMediaId()) {
        null
    } else {
        album
            ?.takeUnless { it.isLocal }
            ?.let { album ->
                album.playlistId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "https://music.youtube.com/playlist?list=$it" }
                    ?: album.id.toYouTubeMusicAlbumUrl()
            }
            ?: song.albumId.toYouTubeMusicAlbumUrl()
    }

private fun String?.toYouTubeMusicAlbumUrl(): String? {
    val id = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (id.isLocalMediaId()) return null

    return if (id.startsWith("OLAK5uy_", ignoreCase = true)) {
        "https://music.youtube.com/playlist?list=$id"
    } else {
        "https://music.youtube.com/browse/$id"
    }
}
