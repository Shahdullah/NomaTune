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

package com.shahdullah.nomatune.recap

import android.graphics.Typeface
import com.shahdullah.nomatune.db.entities.Album
import com.shahdullah.nomatune.db.entities.Artist
import com.shahdullah.nomatune.db.entities.Song
import com.shahdullah.nomatune.playback.PlayerConnection
import javax.inject.Inject

sealed interface RecapMenu {
    data class SongMenu(val song: Song) : RecapMenu
    data class ArtistMenu(val artist: Artist) : RecapMenu
    data class AlbumMenu(val album: Album) : RecapMenu
}

class RecapActionsUseCase @Inject constructor(
    private val repository: RecapRepository,
    private val shares: RecapShareRepository,
) {
    internal fun attach(connection: PlayerConnection) = repository.attach(connection)
    internal fun detach(connection: PlayerConnection) = repository.detach(connection)
    internal fun releaseMute() = repository.releaseMute()
    internal fun toggleMute() = repository.toggleMute()
    internal fun playback() = repository.playback()

    internal suspend fun share(data: RecapData, chapter: RecapChapter, font: Typeface) =
        shares.export(data, if (data.month == null && chapter in data.chapters) chapter else RecapChapter.SUMMARY, font)

    internal suspend fun play(data: RecapData, songId: String?, title: String) {
        val songs = eligibleSongs(data)
        val index = if (songId == null) 0 else songs.indexOfFirst { it.id == songId }.also { check(it >= 0) }
        repository.play(songs, index, title, toggleCurrent = songId != null)
    }

    internal suspend fun startSoundtrack(data: RecapData, title: String) {
        repository.startSoundtrack(eligibleSongs(data), title)
    }

    private suspend fun eligibleSongs(data: RecapData): List<Song> {
        // ArchiveTune also excludes podcasts and blocked artists here; NomaTune has
        // neither isPodcast nor blockedAt, so every resolved song is eligible.
        val songs = repository.songs(data.songs.map { it.id })
        check(songs.isNotEmpty())
        return songs
    }

    internal suspend fun menu(entry: RecapEntry): RecapMenu = when (entry.kind) {
        RecapEntryKind.SONG -> RecapMenu.SongMenu(checkNotNull(repository.song(entry.id)))
        RecapEntryKind.ARTIST -> RecapMenu.ArtistMenu(checkNotNull(repository.artist(entry.id)))
        RecapEntryKind.ALBUM -> RecapMenu.AlbumMenu(checkNotNull(repository.album(entry.id)))
    }
}
