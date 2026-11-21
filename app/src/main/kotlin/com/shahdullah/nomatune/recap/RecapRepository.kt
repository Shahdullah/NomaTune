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

import androidx.media3.common.Player
import dagger.hilt.android.scopes.ViewModelScoped
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import com.shahdullah.nomatune.db.MusicDatabase
import com.shahdullah.nomatune.db.entities.Album
import com.shahdullah.nomatune.db.entities.Artist
import com.shahdullah.nomatune.db.entities.Song
import com.shahdullah.nomatune.extensions.toMediaItem
import com.shahdullah.nomatune.extensions.togglePlayPause
import com.shahdullah.nomatune.playback.PlayerConnection
import com.shahdullah.nomatune.playback.queues.ListQueue

@OptIn(ExperimentalCoroutinesApi::class)
@ViewModelScoped
class RecapRepository @Inject constructor(private val database: MusicDatabase) {
    private val connection = MutableStateFlow<PlayerConnection?>(null)

    internal fun attach(player: PlayerConnection) { connection.value = player }

    internal fun detach(player: PlayerConnection) { connection.compareAndSet(player, null) }

    internal fun releaseMute() { connection.value?.service?.setRecapMuted(false) }

    internal fun toggleMute() {
        connection.value?.service?.let { it.setRecapMuted(!it.recapAudioMuted.value) }
    }

    internal fun playback() = connection.flatMapLatest { player ->
        if (player == null) flowOf(RecapPlayback()) else {
            val transport = combine(player.mediaMetadata, player.isPlaying, player.playbackState) { metadata, playing, state ->
                RecapPlayback(available = true, currentId = metadata?.id, playing = playing,
                    buffering = state == Player.STATE_BUFFERING)
            }
            combine(transport, player.service.recapAudioMuted, player.error, player.service.recapQueueActive) {
                playback, muted, error, recapQueue ->
                playback.copy(muted = muted, failed = error != null, recapQueue = recapQueue)
            }
        }
    }

    internal suspend fun play(songs: List<Song>, startIndex: Int, title: String, toggleCurrent: Boolean) {
        val items = songs.map { it.toMediaItem() }
        withContext(Dispatchers.Main.immediate) {
            val player = checkNotNull(connection.value)
            if (toggleCurrent && player.mediaMetadata.value?.id == songs[startIndex].id) {
                player.player.togglePlayPause()
            } else {
                player.playQueue(ListQueue(title, items, startIndex))
            }
        }
    }

    internal suspend fun startSoundtrack(songs: List<Song>, title: String) = withContext(Dispatchers.Main.immediate) {
        check(checkNotNull(connection.value).service.playRecapQueue(ListQueue(title, songs.map { it.toMediaItem() })))
    }

    internal fun changes() = database.recapChanges()

    internal suspend fun load(start: LocalDateTime, end: LocalDateTime): RecapSnapshot =
        withContext(Dispatchers.IO) {
            val from = start.toInstant(ZoneOffset.UTC).toEpochMilli()
            val until = end.toInstant(ZoneOffset.UTC).toEpochMilli() + if (end.nano % 1_000_000 == 0) 0L else 1L
            database.withTransaction {
                val dao = recapDao
                RecapSnapshot(
                    firstTimestamp = dao.firstTimestamp(),
                    totals = dao.totals(from, until),
                    songs = dao.songs(from, until),
                    artists = dao.artists(from, until),
                    albums = dao.albums(from, until),
                    dayHours = dao.dayHours(from, until),
                    monthlyArtists = dao.monthlyArtists(from, until),
                )
            }
        }

    internal suspend fun songs(ids: List<String>): List<Song> = withContext(Dispatchers.IO) {
        ids.mapNotNull { database.song(it).first() }
    }

    internal suspend fun song(id: String): Song? = withContext(Dispatchers.IO) { database.song(id).first() }
    internal suspend fun artist(id: String): Artist? = withContext(Dispatchers.IO) { database.artist(id).first() }
    internal suspend fun album(id: String): Album? = withContext(Dispatchers.IO) { database.album(id).first() }
}
