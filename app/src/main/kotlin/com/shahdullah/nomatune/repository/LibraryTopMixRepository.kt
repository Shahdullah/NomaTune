/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import com.shahdullah.nomatune.constants.HideExplicitKey
import com.shahdullah.nomatune.constants.SongSortType
import com.shahdullah.nomatune.db.MusicDatabase
import com.shahdullah.nomatune.extensions.filterExplicit
import com.shahdullah.nomatune.models.MediaMetadata
import com.shahdullah.nomatune.models.toMediaMetadata
import com.shahdullah.nomatune.utils.dataStore

private const val LibraryTopMixCandidateLimit = 300

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LibraryTopMixRepository
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
) {
    fun observeRecentTracks(): Flow<List<MediaMetadata>> =
        hideExplicitEnabled()
            .flatMapLatest { hideExplicit ->
                database
                    .recentSongs(LibraryTopMixCandidateLimit)
                    .map { songs -> songs.filterExplicit(hideExplicit).map { it.toMediaMetadata() } }
            }
            .flowOn(Dispatchers.IO)

    fun observeLikedTracks(): Flow<List<MediaMetadata>> =
        hideExplicitEnabled()
            .flatMapLatest { hideExplicit ->
                database
                    .likedSongsByCreateDateAsc()
                    .map { songs ->
                        songs
                            .filterExplicit(hideExplicit)
                            .asReversed()
                            .take(LibraryTopMixCandidateLimit)
                            .map { it.toMediaMetadata() }
                    }
            }
            .flowOn(Dispatchers.IO)

    fun observeListenedTracks(): Flow<List<MediaMetadata>> =
        hideExplicitEnabled()
            .flatMapLatest { hideExplicit ->
                database
                    .songs(SongSortType.PLAY_TIME, descending = true, filterVideo = true)
                    .map { songs ->
                        songs
                            .filterExplicit(hideExplicit)
                            .filter { it.song.totalPlayTime > 0L }
                            .map { it.toMediaMetadata() }
                    }
            }
            .flowOn(Dispatchers.IO)

    fun observeLibraryTracks(): Flow<List<MediaMetadata>> =
        hideExplicitEnabled()
            .flatMapLatest { hideExplicit ->
                database
                    .songs(SongSortType.CREATE_DATE, descending = true, filterVideo = true)
                    .map { songs ->
                        songs
                            .filterExplicit(hideExplicit)
                            .take(LibraryTopMixCandidateLimit)
                            .map { it.toMediaMetadata() }
                    }
            }
            .flowOn(Dispatchers.IO)

    private fun hideExplicitEnabled(): Flow<Boolean> =
        context.dataStore.data
            .map { preferences -> preferences[HideExplicitKey] ?: false }
            .distinctUntilChanged()

    private val _generatedTopMixes = kotlinx.coroutines.flow.MutableStateFlow<List<GeneratedLibraryTopMix>>(emptyList())
    val generatedTopMixesFlow: kotlinx.coroutines.flow.StateFlow<List<GeneratedLibraryTopMix>> = _generatedTopMixes.asStateFlow()

    suspend fun recentSongsForTopMixes(limit: Int): List<Song> =
        withContext(Dispatchers.IO) {
            database
                .recentSongs(limit)
                .first()
        }

    fun replaceTopMixes(mixes: List<GeneratedLibraryTopMix>) {
        _generatedTopMixes.value = mixes
    }
}
