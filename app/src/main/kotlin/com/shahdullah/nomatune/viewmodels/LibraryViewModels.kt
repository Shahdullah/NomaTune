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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.shahdullah.nomatune.viewmodels

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.offline.Download
import com.google.common.collect.ImmutableList
import com.shahdullah.nomatune.R
import com.shahdullah.nomatune.ai.AiServiceConfig
import com.shahdullah.nomatune.ai.AiTextService
import com.shahdullah.nomatune.innertube.YouTube
import com.shahdullah.nomatune.innertube.models.SongItem
import com.shahdullah.nomatune.constants.AiApiKeyKey
import com.shahdullah.nomatune.constants.AiApiValidationStatus
import com.shahdullah.nomatune.constants.AiApiValidationStatusKey
import com.shahdullah.nomatune.constants.AiCustomEndpointKey
import com.shahdullah.nomatune.constants.AiCustomModelKey
import com.shahdullah.nomatune.constants.AiProvider
import com.shahdullah.nomatune.constants.AiProviderKey
import com.shahdullah.nomatune.constants.AiSelectedModelKey
import com.shahdullah.nomatune.constants.AlbumFilter
import com.shahdullah.nomatune.constants.AlbumFilterKey
import com.shahdullah.nomatune.constants.AlbumSortDescendingKey
import com.shahdullah.nomatune.constants.AlbumSortType
import com.shahdullah.nomatune.constants.AlbumSortTypeKey
import com.shahdullah.nomatune.constants.ArtistFilter
import com.shahdullah.nomatune.constants.ArtistFilterKey
import com.shahdullah.nomatune.constants.ArtistSongSortDescendingKey
import com.shahdullah.nomatune.constants.ArtistSongSortType
import com.shahdullah.nomatune.constants.ArtistSongSortTypeKey
import com.shahdullah.nomatune.constants.ArtistSortDescendingKey
import com.shahdullah.nomatune.constants.ArtistSortType
import com.shahdullah.nomatune.constants.ArtistSortTypeKey
import com.shahdullah.nomatune.constants.HideExplicitKey
import com.shahdullah.nomatune.constants.HideVideoKey
import com.shahdullah.nomatune.constants.LibraryFilter
import com.shahdullah.nomatune.constants.PlaylistSortDescendingKey
import com.shahdullah.nomatune.constants.PlaylistSortType
import com.shahdullah.nomatune.constants.PlaylistSortDescendingKey
import com.shahdullah.nomatune.constants.PlaylistSortTypeKey
import com.shahdullah.nomatune.constants.SongFilter
import com.shahdullah.nomatune.constants.SongFilterKey
import com.shahdullah.nomatune.constants.SongSortDescendingKey
import com.shahdullah.nomatune.constants.SongSortType
import com.shahdullah.nomatune.constants.SongSortTypeKey
import com.shahdullah.nomatune.constants.TopSize
import com.shahdullah.nomatune.db.MusicDatabase
import com.shahdullah.nomatune.db.entities.Playlist
import com.shahdullah.nomatune.db.entities.PlaylistEntity
import com.shahdullah.nomatune.db.entities.PlaylistSongMap
import com.shahdullah.nomatune.db.entities.Song
import com.shahdullah.nomatune.extensions.filterExplicit
import com.shahdullah.nomatune.extensions.filterExplicitAlbums
import com.shahdullah.nomatune.extensions.reversed
import com.shahdullah.nomatune.extensions.toEnum
import com.shahdullah.nomatune.library.LibraryTopMix
import com.shahdullah.nomatune.library.LibraryTopMixId
import com.shahdullah.nomatune.library.ObserveLibraryTopMixesUseCase
import com.shahdullah.nomatune.library.RefreshLibraryTopMixesResult
import com.shahdullah.nomatune.library.RefreshLibraryTopMixesUseCase
import com.shahdullah.nomatune.library.TopMixGenerationFailure
import com.shahdullah.nomatune.models.MediaMetadata
import com.shahdullah.nomatune.models.toMediaMetadata
import com.shahdullah.nomatune.playback.DownloadUtil
import com.shahdullah.nomatune.library.LibrarySyncFailure
import com.shahdullah.nomatune.library.LibrarySyncTarget
import com.shahdullah.nomatune.library.RefreshLibraryResult
import com.shahdullah.nomatune.library.RefreshLibraryUseCase
import com.shahdullah.nomatune.utils.SyncUtils
import com.shahdullah.nomatune.utils.dataStore
import com.shahdullah.nomatune.utils.get
import com.shahdullah.nomatune.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator
import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Outcome of the last library refresh, so the UI can explain *why* a pull-to-refresh
 * did nothing (not signed in, sync switched off, or the request failed) instead of
 * silently stopping its spinner.
 */
sealed interface LibraryRefreshState {
    data object Loading : LibraryRefreshState

    data object Success : LibraryRefreshState

    data object Empty : LibraryRefreshState

    data class Error(val failure: LibrarySyncFailure) : LibraryRefreshState
}

/**
 * Shared refresh plumbing for the library screens. Runs [RefreshLibraryUseCase],
 * publishes [refreshState], and keeps only the newest request's result so a fast
 * second pull cannot be overwritten by a slower first one.
 */
internal class LibraryRefresher(
    private val scope: CoroutineScope,
    private val useCase: RefreshLibraryUseCase,
) {
    private val _state = MutableStateFlow<LibraryRefreshState>(LibraryRefreshState.Empty)
    val state = _state.asStateFlow()

    @Volatile
    private var job: Job? = null

    @Volatile
    private var generation = 0L

    fun refresh(target: LibrarySyncTarget) {
        job?.cancel()
        val current = ++generation
        _state.value = LibraryRefreshState.Loading
        job =
            scope.launch(Dispatchers.IO) {
                val result =
                    try {
                        useCase(target)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        RefreshLibraryResult.Failure(LibrarySyncFailure.RequestFailed)
                    }
                if (current == generation) {
                    _state.value =
                        when (result) {
                            RefreshLibraryResult.Success -> LibraryRefreshState.Success
                            is RefreshLibraryResult.Failure -> LibraryRefreshState.Error(result.reason)
                        }
                }
            }
    }

    /** Clears a consumed error so the snackbar is not shown twice. */
    fun clearError() {
        if (_state.value is LibraryRefreshState.Error) _state.value = LibraryRefreshState.Empty
    }
}

@HiltViewModel
class LibrarySongsViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    downloadUtil: DownloadUtil,
    private val syncUtils: SyncUtils,
    refreshLibraryUseCase: RefreshLibraryUseCase,
) : ViewModel() {
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private val refresher = LibraryRefresher(viewModelScope, refreshLibraryUseCase)
    val refreshState = refresher.state

    val allSongs =
        context.dataStore.data
            .map {
                Triple(
                    Triple(
                        it[SongFilterKey].toEnum(SongFilter.LIKED),
                        it[SongSortTypeKey].toEnum(SongSortType.CREATE_DATE),
                        (it[SongSortDescendingKey] ?: true),
                    ),
                    it[HideExplicitKey] ?: false,
                    it[HideVideoKey] ?: false,
                )
            }.distinctUntilChanged()
            .flatMapLatest { (filterSort, hideExplicit, hideVideo) ->
                val (filter, sortType, descending) = filterSort
                when (filter) {
                    SongFilter.LIBRARY -> database.songs(sortType, descending, hideVideo).map { it.filterExplicit(hideExplicit) }
                    SongFilter.LIKED -> database.likedSongs(sortType, descending, hideVideo).map { it.filterExplicit(hideExplicit) }
                    SongFilter.DOWNLOADED ->
                        downloadUtil.downloads.flatMapLatest { downloads ->
                            database
                                .allSongs()
                                .flowOn(Dispatchers.IO)
                                .map { songs ->
                                    songs.filter { song: Song ->
                                        downloads[song.id]?.state == Download.STATE_COMPLETED
                                    }
                                }.map { songs ->
                                    when (sortType) {
                                        SongSortType.CREATE_DATE -> songs.sortedBy { song: Song ->
                                            downloads[song.id]?.updateTimeMs ?: 0L
                                        }

                                        SongSortType.NAME -> songs.sortedBy { song: Song -> song.song.title }
                                        SongSortType.ARTIST -> {
                                            val collator =
                                                Collator.getInstance(Locale.getDefault())
                                            collator.strength = Collator.PRIMARY
                                            songs.sortedWith(compareBy(collator) { song: Song ->
                                                song.artists.joinToString("") { artist -> artist.name }
                                            })
                                        }

                                        SongSortType.PLAY_TIME -> songs.sortedBy { song: Song -> song.song.totalPlayTime }
                                    }.reversed(descending).filterExplicit(hideExplicit)
                                }
                        }
                }
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun refresh(filter: SongFilter) {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        val target =
            when (filter) {
                SongFilter.LIKED -> LibrarySyncTarget.LikedSongs
                SongFilter.LIBRARY -> LibrarySyncTarget.Songs
                SongFilter.DOWNLOADED -> null
            }
        if (target == null) {
            _isRefreshing.value = false
            return
        }
        refresher.refresh(target)
        viewModelScope.launch {
            refresher.state.filter { it !is LibraryRefreshState.Loading }.first()
            _isRefreshing.value = false
        }
    }

    fun onRefreshErrorShown() = refresher.clearError()

    fun syncLikedSongs() {
        refresh(SongFilter.LIKED)
    }

    fun syncLibrarySongs() {
        refresh(SongFilter.LIBRARY)
    }
}

@HiltViewModel
class LibraryArtistsViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    private val syncUtils: SyncUtils,
    refreshLibraryUseCase: RefreshLibraryUseCase,
) : ViewModel() {
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private val refresher = LibraryRefresher(viewModelScope, refreshLibraryUseCase)
    val refreshState = refresher.state

    val allArtists =
        context.dataStore.data
            .map {
                Triple(
                    it[ArtistFilterKey].toEnum(ArtistFilter.LIKED),
                    it[ArtistSortTypeKey].toEnum(ArtistSortType.CREATE_DATE),
                    it[ArtistSortDescendingKey] ?: true,
                )
            }.distinctUntilChanged()
            .flatMapLatest { (filter, sortType, descending) ->
                when (filter) {
                    ArtistFilter.LIBRARY -> database.artists(sortType, descending)
                    ArtistFilter.LIKED -> database.artistsBookmarked(sortType, descending)
                }
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun refresh(filter: ArtistFilter) {
        if (filter != ArtistFilter.LIKED) return
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        refresher.refresh(LibrarySyncTarget.Artists)
        viewModelScope.launch {
            refresher.state.filter { it !is LibraryRefreshState.Loading }.first()
            _isRefreshing.value = false
        }
    }

    fun onRefreshErrorShown() = refresher.clearError()

    fun sync() {
        refresh(ArtistFilter.LIKED)
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            allArtists.collect { artists ->
                artists
                    .map { it.artist }
                    .filter {
                        it.thumbnailUrl == null || Duration.between(
                            it.lastUpdateTime,
                            LocalDateTime.now()
                        ) > Duration.ofDays(10)
                    }.forEach { artist ->
                        YouTube.artist(artist.id).onSuccess { artistPage ->
                            database.query {
                                update(artist, artistPage)
                            }
                        }
                    }
            }
        }
    }
}

@HiltViewModel
class LibraryAlbumsViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    downloadUtil: DownloadUtil,
    private val syncUtils: SyncUtils,
    refreshLibraryUseCase: RefreshLibraryUseCase,
) : ViewModel() {
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private val refresher = LibraryRefresher(viewModelScope, refreshLibraryUseCase)
    val refreshState = refresher.state

    val allAlbums =
        context.dataStore.data
            .map {
                Pair(
                    Triple(
                        it[AlbumFilterKey].toEnum(AlbumFilter.LIKED),
                        it[AlbumSortTypeKey].toEnum(AlbumSortType.CREATE_DATE),
                        it[AlbumSortDescendingKey] ?: true,
                    ),
                    it[HideExplicitKey] ?: false
                )
            }.distinctUntilChanged()
            .flatMapLatest { (filterSort, hideExplicit) ->
                val (filter, sortType, descending) = filterSort
                when (filter) {
                    AlbumFilter.DOWNLOADED ->
                        downloadUtil.downloads.flatMapLatest { downloads ->
                            database.allSongs()
                                .flowOn(Dispatchers.IO)
                                .map { songs ->
                                    songs
                                        .filter { song -> downloads[song.id]?.state == Download.STATE_COMPLETED }
                                        .mapNotNull { it.song.albumId }
                                        .toSet()
                                }.flatMapLatest { downloadedAlbumIds ->
                                    database.albumsByIds(downloadedAlbumIds, sortType, descending)
                                        .map { albums -> albums.filterExplicitAlbums(hideExplicit) }
                                }
                        }
                    
                        AlbumFilter.DOWNLOADED_FULL ->
                            downloadUtil.downloads.flatMapLatest { downloads ->
                                database.allSongs()
                                    .flowOn(Dispatchers.IO)
                                    .map { songs ->
                                        songs
                                            .filter { song -> downloads[song.id]?.state == Download.STATE_COMPLETED }
                                            .mapNotNull { song -> song.song.albumId?.let { albumId -> albumId to song } }
                                            .groupBy({ it.first }, { it.second })
                                            .mapValues { (_, songList) -> songList.size }
                                    }.flatMapLatest { downloadedCountByAlbum ->
                                        database.albumsByIds(downloadedCountByAlbum.keys, sortType, descending)
                                            .map { albums ->
                                                albums.filter { album ->
                                                    val totalSongsInAlbum = album.album.songCount
                                                    val downloadedSongsCount = downloadedCountByAlbum[album.album.id] ?: 0
                                                    totalSongsInAlbum > 0 && downloadedSongsCount >= totalSongsInAlbum
                                                }.filterExplicitAlbums(hideExplicit)
                                            }
                                    }
                            }
                    AlbumFilter.LIBRARY -> database.albums(sortType, descending).map { it.filterExplicitAlbums(hideExplicit) }
                    AlbumFilter.LIKED -> database.albumsLiked(sortType, descending).map { it.filterExplicitAlbums(hideExplicit) }
                }
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun refresh(filter: AlbumFilter) {
        if (filter != AlbumFilter.LIKED) return
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        refresher.refresh(LibrarySyncTarget.Albums)
        viewModelScope.launch {
            refresher.state.filter { it !is LibraryRefreshState.Loading }.first()
            _isRefreshing.value = false
        }
    }

    fun onRefreshErrorShown() = refresher.clearError()

    fun sync() {
        refresh(AlbumFilter.LIKED)
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            allAlbums.collect { albums ->
                albums
                    .filter {
                        it.album.songCount == 0
                    }.forEach { album ->
                        YouTube
                            .album(album.id)
                            .onSuccess { albumPage ->
                                database.query {
                                    update(album.album, albumPage, album.artists)
                                }
                            }.onFailure {
                                reportException(it)
                                if (it.message?.contains("NOT_FOUND") == true) {
                                    database.query {
                                        delete(album.album)
                                    }
                                }
                            }
                    }
            }
        }
    }
}

@HiltViewModel
class LibraryPlaylistsViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    private val syncUtils: SyncUtils,
    refreshLibraryUseCase: RefreshLibraryUseCase,
) : ViewModel() {
    private val refresher = LibraryRefresher(viewModelScope, refreshLibraryUseCase)
    val refreshState = refresher.state

    val allPlaylists =
        context.dataStore.data
            .map {
                it[PlaylistSortTypeKey].toEnum(PlaylistSortType.CUSTOM) to (it[PlaylistSortDescendingKey]
                    ?: true)
            }.distinctUntilChanged()
            .flatMapLatest { (sortType, descending) ->
                database.playlists(sortType, descending)
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    fun sync() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        refresher.refresh(LibrarySyncTarget.Playlists)
        viewModelScope.launch {
            refresher.state.filter { it !is LibraryRefreshState.Loading }.first()
            _isRefreshing.value = false
        }
    }

    fun onRefreshErrorShown() = refresher.clearError()

    /**
     * Persists a new manual playlist order. Called once when a drag settles rather
     * than on every move, so a long drag does not write the whole list per frame.
     */
    fun updateCustomPlaylistOrder(playlists: List<Playlist>) {
        if (playlists.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                database.withTransaction {
                    playlists.forEachIndexed { index, playlist ->
                        setPlaylistCustomOrder(playlist.id, index)
                    }
                }
            }.onFailure { reportException(it) }
        }
    }

    val topValue =
        context.dataStore.data
            .map { it[TopSize] ?: "50" }
            .distinctUntilChanged()
}

@HiltViewModel
class ArtistSongsViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val artistId = savedStateHandle.get<String>("artistId")!!
    val artist =
        database
            .artist(artistId)
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val songs =
        context.dataStore.data
            .map {
                Pair(
                    it[ArtistSongSortTypeKey].toEnum(ArtistSongSortType.CREATE_DATE) to (it[ArtistSongSortDescendingKey]
                        ?: true),
                    it[HideExplicitKey] ?: false
                )
            }.distinctUntilChanged()
            .flatMapLatest { (sortDesc, hideExplicit) ->
                val (sortType, descending) = sortDesc
                database.artistSongs(artistId, sortType, descending).map { it.filterExplicit(hideExplicit) }
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}

@HiltViewModel
class LibraryMixViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    private val syncUtils: SyncUtils,
    observeLibraryTopMixes: ObserveLibraryTopMixesUseCase,
    private val refreshLibraryTopMixes: RefreshLibraryTopMixesUseCase,
    refreshLibraryUseCase: RefreshLibraryUseCase,
) : ViewModel() {
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private val refresher = LibraryRefresher(viewModelScope, refreshLibraryUseCase)
    val refreshState = refresher.state

    private val _topMixEvents = MutableSharedFlow<String>()
    val topMixEvents: SharedFlow<String> = _topMixEvents.asSharedFlow()

    private val _isTopMixRefreshing = MutableStateFlow(false)
    val isTopMixRefreshing: StateFlow<Boolean> = _isTopMixRefreshing.asStateFlow()

    private val _buildYourMixState = MutableStateFlow<BuildYourMixUiState>(BuildYourMixUiState.Idle)
    val buildYourMixState = _buildYourMixState.asStateFlow()

    val topMixesUiState =
        observeLibraryTopMixes()
            .map<List<LibraryTopMix>, LibraryTopMixesUiState> { mixes ->
                if (mixes.isEmpty()) {
                    LibraryTopMixesUiState.Empty(
                        reason =
                            if (isBuildYourMixAvailable.value) {
                                LibraryTopMixEmptyReason.NO_RECENT_HISTORY
                            } else {
                                LibraryTopMixEmptyReason.AI_NOT_CONFIGURED
                            },
                    )
                } else {
                    LibraryTopMixesUiState.Success(
                        mixes = ImmutableList.copyOf(mixes.map { it.toUiModel() }),
                    )
                }
            }
            .catch { throwable ->
                if (throwable is CancellationException) throw throwable
                reportException(throwable)
                emit(LibraryTopMixesUiState.Error(context.getString(R.string.build_your_mix_empty_library)))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryTopMixesUiState.Loading)

    /**
     * Most-played album over the last two weeks. Reads through the explicit-content
     * preference so the spotlight respects the same filter as the lists.
     */
    val mostPlayedAlbumUiState =
        context.dataStore.data
            .map { it[HideExplicitKey] ?: false }
            .distinctUntilChanged()
            .flatMapLatest { hideExplicit ->
                database
                    .mostPlayedAlbums(
                        fromTimeStamp = System.currentTimeMillis() - MOST_PLAYED_ALBUM_WINDOW_MILLIS,
                        limit = 10,
                    ).flatMapLatest { albums ->
                        val album = albums.filterExplicitAlbums(hideExplicit).firstOrNull()
                        if (album == null) {
                            flowOf(MostPlayedAlbumUiState.Empty)
                        } else {
                            database.albumWithSongs(album.id).map { albumWithSongs ->
                                val songs = albumWithSongs?.songs.orEmpty()
                                if (songs.isEmpty()) {
                                    MostPlayedAlbumUiState.Empty
                                } else {
                                    MostPlayedAlbumUiState.Success(
                                        album =
                                            MostPlayedAlbumUiModel(
                                                id = album.id,
                                                title = album.title,
                                                thumbnailUrl = album.thumbnailUrl,
                                                trackCount = songs.size,
                                                tracks = ImmutableList.copyOf(songs.map { it.toMediaMetadata() }),
                                            ),
                                    )
                                }
                            }
                        }
                    }
            }.flowOn(Dispatchers.IO)
            .catch { throwable ->
                if (throwable is CancellationException) throw throwable
                reportException(throwable)
                emit(MostPlayedAlbumUiState.Error(context.getString(R.string.error_unknown)))
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MostPlayedAlbumUiState.Loading)

    val isBuildYourMixAvailable =
        context.dataStore.data
            .map { prefs ->
                val provider = prefs[AiProviderKey].toEnum(AiProvider.NONE)
                provider != AiProvider.NONE &&
                    prefs[AiApiKeyKey].orEmpty().isNotBlank() &&
                    (provider != AiProvider.CUSTOM || prefs[AiCustomEndpointKey].orEmpty().isNotBlank()) &&
                    prefs[AiApiValidationStatusKey].toEnum(AiApiValidationStatus.UNKNOWN) != AiApiValidationStatus.FAILED
            }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Lazily, false)

    /**
     * Regenerates the AI top mixes. Failures during a refresh are surfaced as a
     * one-shot snackbar message so the existing mixes stay on screen instead of
     * being replaced by an error state.
     */
    fun refreshTopMixes() {
        if (_isTopMixRefreshing.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isTopMixRefreshing.value = true
            try {
                when (val result = refreshLibraryTopMixes()) {
                    RefreshLibraryTopMixesResult.Success -> Unit
                    is RefreshLibraryTopMixesResult.Failure -> {
                        reportException(result.cause ?: IllegalStateException(result.reason.name))
                        _topMixEvents.emit(result.reason.toUserMessage())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
            } finally {
                _isTopMixRefreshing.value = false
            }
        }
    }

    fun syncAllLibrary() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        refresher.refresh(LibrarySyncTarget.All)
        viewModelScope.launch {
            refresher.state.filter { it !is LibraryRefreshState.Loading }.first()
            _isRefreshing.value = false
        }
    }

    fun onRefreshErrorShown() = refresher.clearError()

    fun buildYourMix(
        basis: BuildYourMixBasis,
        songCount: Int,
        manualBasis: String,
    ) {
        if (_buildYourMixState.value == BuildYourMixUiState.Loading) return
        viewModelScope.launch(Dispatchers.IO) {
            _buildYourMixState.value = BuildYourMixUiState.Loading
            try {
                val count = songCount.coerceIn(1, 100)
                val normalizedManualBasis = manualBasis.trim()
                if (basis == BuildYourMixBasis.INPUT_MANUALLY && normalizedManualBasis.isBlank()) {
                    _buildYourMixState.value = BuildYourMixUiState.Error(context.getString(R.string.build_your_mix_manual_required))
                    return@launch
                }

                val candidates = candidatesForMix(
                    basis = basis,
                    songCount = count,
                    manualBasis = normalizedManualBasis,
                )
                if (candidates.isEmpty()) {
                    _buildYourMixState.value = BuildYourMixUiState.Error(
                        context.getString(
                            if (basis == BuildYourMixBasis.INPUT_MANUALLY) {
                                R.string.build_your_mix_no_manual_match
                            } else {
                                R.string.build_your_mix_empty_library
                            },
                        ),
                    )
                    return@launch
                }

                val aiMix = requestAiMix(
                    config = readAiConfig(),
                    basis = basis,
                    songCount = count,
                    manualBasis = if (basis == BuildYourMixBasis.INPUT_MANUALLY) normalizedManualBasis else "",
                    candidates = candidates,
                )
                val finalSongs = validateFinalMixSongs(
                    aiSongs = aiMix.songs,
                    fallbackCandidates = candidates,
                    basis = basis,
                    manualBasis = normalizedManualBasis,
                    songCount = count,
                )
                if (finalSongs.isEmpty()) {
                    _buildYourMixState.value = BuildYourMixUiState.Error(
                        context.getString(
                            if (basis == BuildYourMixBasis.INPUT_MANUALLY) {
                                R.string.build_your_mix_no_manual_match
                            } else {
                                R.string.build_your_mix_empty_library
                            },
                        ),
                    )
                    return@launch
                }
                val playlistId = createGeneratedPlaylist(
                    title = aiMix.title.ifBlank { basis.defaultTitle() },
                    songs = finalSongs,
                )
                _buildYourMixState.value = BuildYourMixUiState.Success(
                    playlistId = playlistId,
                    playlistTitle = aiMix.title.ifBlank { basis.defaultTitle() },
                )
            } catch (e: Exception) {
                reportException(e)
                _buildYourMixState.value = BuildYourMixUiState.Error(
                    context.getString(R.string.build_your_mix_failed) + ": " + (e.localizedMessage ?: e.toString()),
                )
            }
        }
    }

    fun resetBuildYourMixState() {
        _buildYourMixState.value = BuildYourMixUiState.Idle
    }

    private suspend fun readAiConfig(): AiServiceConfig {
        val prefs = context.dataStore.data.first()
        val provider = prefs[AiProviderKey].toEnum(AiProvider.NONE)
        return AiServiceConfig(
            provider = provider,
            apiKey = prefs[AiApiKeyKey].orEmpty(),
            customEndpoint = prefs[AiCustomEndpointKey].orEmpty(),
            model = if (provider == AiProvider.CUSTOM) {
                prefs[AiCustomModelKey].orEmpty()
            } else {
                prefs[AiSelectedModelKey].orEmpty()
            },
        )
    }

    private suspend fun candidatesForMix(
        basis: BuildYourMixBasis,
        songCount: Int,
        manualBasis: String,
    ): List<ValidatedMixSong> =
        if (basis == BuildYourMixBasis.INPUT_MANUALLY) {
            searchManualCandidatesWithYtm(manualBasis = manualBasis, songCount = songCount)
        } else {
            validateSongsWithYtm(
                songs = localCandidatesForMix(
                    basis = basis,
                    songCount = songCount,
                ),
                basis = basis,
                manualBasis = manualBasis,
            )
        }

    private suspend fun localCandidatesForMix(
        basis: BuildYourMixBasis,
        songCount: Int,
    ): List<Song> {
        val requestedPoolSize = (songCount * 2).coerceIn(50, 100)
        val primary = when (basis) {
            BuildYourMixBasis.LISTENING_HISTORY -> database.recentSongs(requestedPoolSize).first()
            BuildYourMixBasis.AVERAGE_LISTENED -> database
                .songs(SongSortType.PLAY_TIME, descending = true)
                .first()
                .filter { it.song.totalPlayTime > 0L }
            BuildYourMixBasis.INPUT_MANUALLY -> emptyList()
        }
        val fallback = if (primary.size < songCount && basis != BuildYourMixBasis.INPUT_MANUALLY) {
            database.songs(SongSortType.CREATE_DATE, descending = true).first()
        } else {
            emptyList()
        }
        return (primary + fallback)
            .distinctBy { it.id }
            .take(requestedPoolSize)
    }

    private suspend fun searchManualCandidatesWithYtm(
        manualBasis: String,
        songCount: Int,
    ): List<ValidatedMixSong> {
        val targetSize = (songCount * 2).coerceIn(25, 100)
        val items = YouTube.search(manualBasis, YouTube.SearchFilter.FILTER_SONG)
            .getOrNull()
            ?.items
            .orEmpty()
            .filterIsInstance<SongItem>()
        return items
            .distinctBy { it.id }
            .take(targetSize)
            .map { it.toValidatedMixSong() }
    }

    private suspend fun validateSongsWithYtm(
        songs: List<Song>,
        basis: BuildYourMixBasis,
        manualBasis: String,
    ): List<ValidatedMixSong> =
        buildList {
            songs.distinctBy { it.id }.forEach { song ->
                song.validateWithYtm()
                    ?.takeIf { it.matchesBasis(basis, manualBasis) }
                    ?.let(::add)
            }
        }

    private suspend fun validateValidatedSongsWithYtm(
        songs: List<ValidatedMixSong>,
        basis: BuildYourMixBasis,
        manualBasis: String,
    ): List<ValidatedMixSong> =
        buildList {
            songs.distinctBy { it.id }.forEach { song ->
                song.validateWithYtm(basis = basis, manualBasis = manualBasis)
                    ?.takeIf { it.matchesBasis(basis, manualBasis) }
                    ?.let(::add)
            }
        }

    private suspend fun validateFinalMixSongs(
        aiSongs: List<ValidatedMixSong>,
        fallbackCandidates: List<ValidatedMixSong>,
        basis: BuildYourMixBasis,
        manualBasis: String,
        songCount: Int,
    ): List<ValidatedMixSong> {
        val aiValidated = validateValidatedSongsWithYtm(
            songs = aiSongs,
            basis = basis,
            manualBasis = manualBasis,
        )
        val selectedIds = aiValidated.mapTo(mutableSetOf()) { it.id }
        val proposedSongs = buildList {
            addAll(aiValidated)
            fallbackCandidates.forEach { candidate ->
                if (size >= songCount) return@forEach
                if (selectedIds.add(candidate.id)) add(candidate)
            }
        }
        return validateValidatedSongsWithYtm(
            songs = proposedSongs,
            basis = basis,
            manualBasis = manualBasis,
        ).take(songCount)
    }

    private suspend fun requestAiMix(
        config: AiServiceConfig,
        basis: BuildYourMixBasis,
        songCount: Int,
        manualBasis: String,
        candidates: List<ValidatedMixSong>,
    ): GeneratedMix {
        val candidateById = candidates.associateBy { it.id }
        val candidatePayload = JSONArray().apply {
            candidates.forEach { candidate ->
                put(
                    JSONObject()
                        .put("id", candidate.id)
                        .put("title", candidate.ytmTitle)
                        .put("artists", candidate.ytmArtists.joinToString(", "))
                        .put("localTitle", candidate.localSong?.song?.title.orEmpty())
                        .put("localArtists", candidate.localSong?.artists?.joinToString(", ") { it.name }.orEmpty())
                        .put("totalPlayTimeMs", candidate.localSong?.song?.totalPlayTime ?: 0L),
                )
            }
        }
        val response = AiTextService.complete(
            config = config,
            systemPrompt = """
                You are a music curator for NomaTune.
                Build one personal playlist using only the provided candidate song IDs.
                Return JSON only with this schema: {"title":"short accurate playlist title","songIds":["id"]}.
                The title must reflect the selected basis and the selected songs.
                For manual user input, choose from the provided YouTube Music search results for the user's prompt.
                Select up to $songCount songs, avoid duplicates, and prioritize coherence over variety.
            """.trimIndent(),
            userPrompt = JSONObject()
                .put("basis", basis.promptLabel)
                .put("manualBasis", manualBasis.trim())
                .put("requestedSongCount", songCount)
                .put("candidates", candidatePayload)
                .toString(),
            temperature = 0.35,
            maxTokens = 4096,
        )
        val json = JSONObject(response.substringAfter('{').substringBeforeLast('}').let { "{$it}" })
        val selected = json
            .optJSONArray("songIds")
            ?.toStringList()
            .orEmpty()
            .mapNotNull(candidateById::get)
            .filter { it.matchesBasis(basis, manualBasis) }
            .distinctBy { it.id }
            .toMutableList()
        if (selected.size < songCount) {
            val selectedIds = selected.mapTo(mutableSetOf()) { it.id }
            candidates.forEach { candidate ->
                if (selected.size >= songCount) return@forEach
                if (candidate.matchesBasis(basis, manualBasis) && selectedIds.add(candidate.id)) selected.add(candidate)
            }
        }
        return GeneratedMix(
            title = json.optString("title").sanitizePlaylistTitle().ifBlank { basis.defaultTitle() },
            songs = selected.take(songCount),
        )
    }

    private suspend fun createGeneratedPlaylist(
        title: String,
        songs: List<ValidatedMixSong>,
    ): String {
        val playlistId = PlaylistEntity.generatePlaylistId()
        database.withTransaction {
            val playlistEntity = PlaylistEntity(
                id = playlistId,
                name = title,
                bookmarkedAt = LocalDateTime.now(),
                customOrder = (maxPlaylistCustomOrder() ?: -1) + 1,
                isLocal = true,
            )
            insert(playlistEntity)
            songs.forEachIndexed { index, song ->
                insert(song.ytmSong.toMediaMetadata())
                insert(
                    PlaylistSongMap(
                        playlistId = playlistId,
                        songId = song.id,
                        position = index,
                    ),
                )
            }
        }
        return playlistId
    }
    val topValue =
        context.dataStore.data
            .map { it[TopSize] ?: "50" }
            .distinctUntilChanged()
    var artists =
        database
            .artistsBookmarked(
                ArtistSortType.CREATE_DATE,
                true,
            ).stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    var albums = context.dataStore.data
        .map { it[HideExplicitKey] ?: false }
        .distinctUntilChanged()
        .flatMapLatest { hideExplicit ->
            database.albumsLiked(AlbumSortType.CREATE_DATE, true).map { it.filterExplicitAlbums(hideExplicit) }
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    var playlists =
        context.dataStore.data
            .map {
                it[PlaylistSortTypeKey].toEnum(PlaylistSortType.CUSTOM) to (it[PlaylistSortDescendingKey] ?: true)
            }.distinctUntilChanged()
            .flatMapLatest { (sortType, descending) -> database.playlists(sortType, descending) }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            albums.collect { albums ->
                albums
                    .filter {
                        it.album.songCount == 0
                    }.forEach { album ->
                        YouTube
                            .album(album.id)
                            .onSuccess { albumPage ->
                                database.query {
                                    update(album.album, albumPage, album.artists)
                                }
                            }.onFailure {
                                reportException(it)
                                if (it.message?.contains("NOT_FOUND") == true) {
                                    database.query {
                                        delete(album.album)
                                    }
                                }
                            }
                    }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            artists.collect { artists ->
                artists
                    .map { it.artist }
                    .filter {
                        it.thumbnailUrl == null ||
                                Duration.between(
                                    it.lastUpdateTime,
                                    LocalDateTime.now(),
                                ) > Duration.ofDays(10)
                    }.forEach { artist ->
                        YouTube.artist(artist.id).onSuccess { artistPage ->
                            database.query {
                                update(artist, artistPage)
                            }
                        }
                    }
            }
        }
    }
}

/** Maps a top-mix generation failure to a message the user can act on. */
private fun TopMixGenerationFailure.toUserMessage(): String =
    when (this) {
        TopMixGenerationFailure.AI_NOT_CONFIGURED ->
            "Add an AI provider and key in Settings to generate mixes"
        TopMixGenerationFailure.NO_RECENT_HISTORY ->
            "Play a few songs first — mixes are built from recent listening"
        TopMixGenerationFailure.NO_VALID_MIXES ->
            "None of the suggested songs could be matched to YouTube Music"
        TopMixGenerationFailure.AI_REQUEST_FAILED ->
            "Couldn't reach the AI provider — try again in a moment"
    }

@Immutable
sealed interface LibraryTopMixesUiState {
    data object Loading : LibraryTopMixesUiState

    @Immutable
    data class Success(
        val mixes: ImmutableList<LibraryTopMixUiModel>,
        val isRefreshing: Boolean = false,
    ) : LibraryTopMixesUiState

    @Immutable
    data class Empty(
        val reason: LibraryTopMixEmptyReason,
        val isRefreshing: Boolean = false,
    ) : LibraryTopMixesUiState

    @Immutable
    data class Error(
        val message: String,
    ) : LibraryTopMixesUiState
}

/** Why the mix shelf has nothing to show, so the empty state can say what to do. */
enum class LibraryTopMixEmptyReason {
    AI_NOT_CONFIGURED,
    NO_RECENT_HISTORY,
}

/** The album the user has actually been playing most, surfaced as a spotlight. */
@Immutable
sealed interface MostPlayedAlbumUiState {
    data object Loading : MostPlayedAlbumUiState

    @Immutable
    data class Success(
        val album: MostPlayedAlbumUiModel,
    ) : MostPlayedAlbumUiState

    data object Empty : MostPlayedAlbumUiState

    @Immutable
    data class Error(
        val message: String,
    ) : MostPlayedAlbumUiState
}

@Immutable
data class MostPlayedAlbumUiModel(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val trackCount: Int,
    val tracks: ImmutableList<MediaMetadata>,
)

private const val MOST_PLAYED_ALBUM_WINDOW_MILLIS = 14L * 24L * 60L * 60L * 1000L

@Immutable
data class LibraryTopMixUiModel(
    val id: String,
    val title: String,
    val description: String,
    val tracks: ImmutableList<MediaMetadata>,
) {
    val previewArtworkUrls: ImmutableList<String>
        get() = ImmutableList.copyOf(tracks.mapNotNull { it.thumbnailUrl }.distinct().take(4))
}

private fun LibraryTopMix.toUiModel(): LibraryTopMixUiModel {
    val (title, description) =
        when (id) {
            LibraryTopMixId.DAILY ->
                "Daily Mix" to "Songs from your library you have not played yet"
            LibraryTopMixId.CHILL ->
                "Chill Mix" to "Lower-energy picks from artists you return to"
            LibraryTopMixId.FOCUS ->
                "Focus Mix" to "Deep cuts from the artists you play most"
        }
    return LibraryTopMixUiModel(
        id = id.name,
        title = title,
        description = description,
        tracks = ImmutableList.copyOf(tracks),
    )
}

enum class BuildYourMixBasis(
    val promptLabel: String,
) {
    LISTENING_HISTORY("recent listening history"),
    AVERAGE_LISTENED("average listened songs"),
    INPUT_MANUALLY("manual user input"),
}

fun BuildYourMixBasis.defaultTitle(): String =
    when (this) {
        BuildYourMixBasis.LISTENING_HISTORY -> "Listening History Mix"
        BuildYourMixBasis.AVERAGE_LISTENED -> "Average Listened Mix"
        BuildYourMixBasis.INPUT_MANUALLY -> "Custom Mix"
    }

@Immutable
sealed interface BuildYourMixUiState {
    data object Idle : BuildYourMixUiState
    data object Loading : BuildYourMixUiState

    @Immutable
    data class Success(
        val playlistId: String,
        val playlistTitle: String,
    ) : BuildYourMixUiState

    @Immutable
    data class Error(
        val message: String,
    ) : BuildYourMixUiState
}

@Immutable
private data class GeneratedMix(
    val title: String,
    val songs: List<ValidatedMixSong>,
)

@Immutable
private data class ValidatedMixSong(
    val localSong: Song?,
    val ytmSong: SongItem,
    val ytmTitle: String,
    val ytmArtists: List<String>,
) {
    val id: String
        get() = ytmSong.id
}

private suspend fun Song.validateWithYtm(): ValidatedMixSong? {
    val query = ytmValidationQuery()
    if (query.isBlank()) return null
    val songs = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
        .getOrNull()
        ?.items
        .orEmpty()
        .filterIsInstance<SongItem>()
    val match = songs.firstOrNull { it.id == id }
        ?: songs.firstOrNull { it.matchesLocalIdentity(this) }
    return match?.let { songItem ->
        songItem.toValidatedMixSong(localSong = this)
    }
}

private suspend fun ValidatedMixSong.validateWithYtm(
    basis: BuildYourMixBasis,
    manualBasis: String,
): ValidatedMixSong? {
    val query = if (basis == BuildYourMixBasis.INPUT_MANUALLY) {
        manualBasis
    } else {
        ytmValidationQuery()
    }
    if (query.isBlank()) return null
    val songs = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
        .getOrNull()
        ?.items
        .orEmpty()
        .filterIsInstance<SongItem>()
    val match = songs.firstOrNull { it.id == id }
        ?: localSong?.let { song -> songs.firstOrNull { it.matchesLocalIdentity(song) } }
    return match?.toValidatedMixSong(localSong = localSong)
}

private fun SongItem.toValidatedMixSong(localSong: Song? = null): ValidatedMixSong =
    ValidatedMixSong(
        localSong = localSong,
        ytmSong = this,
        ytmTitle = title,
        ytmArtists = artists.map { it.name },
    )

private fun ValidatedMixSong.ytmValidationQuery(): String =
    buildString {
        append(ytmTitle)
        val artistNames = ytmArtists.joinToString(" ")
        if (artistNames.isNotBlank()) {
            append(' ')
            append(artistNames)
        }
    }.trim()

private fun Song.ytmValidationQuery(): String =
    buildString {
        append(song.title)
        val artistNames = artists.joinToString(" ") { it.name }
        if (artistNames.isNotBlank()) {
            append(' ')
            append(artistNames)
        }
    }.trim()

private fun SongItem.matchesLocalIdentity(song: Song): Boolean =
    title.matchesComparableTitle(song.song.title) && artists.matchesAnyLocalArtist(song)

private fun String.matchesComparableTitle(other: String): Boolean {
    val self = normalizedForMixMatch()
    val target = other.normalizedForMixMatch()
    return self.isNotBlank() &&
        target.isNotBlank() &&
        (self == target || (self.length > 3 && target.contains(self)) || (target.length > 3 && self.contains(target)))
}

private fun List<com.shahdullah.nomatune.innertube.models.Artist>.matchesAnyLocalArtist(song: Song): Boolean {
    val remoteArtists = map { it.name.normalizedForMixMatch() }.filter { it.isNotBlank() }
    val localArtists = song.artists.map { it.name.normalizedForMixMatch() }.filter { it.isNotBlank() }
    if (remoteArtists.isEmpty() || localArtists.isEmpty()) return false
    return localArtists.any { localArtist ->
        remoteArtists.any { remoteArtist ->
            localArtist == remoteArtist ||
                (localArtist.length > 3 && remoteArtist.contains(localArtist)) ||
                (remoteArtist.length > 3 && localArtist.contains(remoteArtist))
        }
    }
}

private fun ValidatedMixSong.matchesBasis(
    basis: BuildYourMixBasis,
    manualBasis: String,
): Boolean =
    basis != BuildYourMixBasis.INPUT_MANUALLY || manualBasis.isNotBlank()

private fun JSONArray.toStringList(): List<String> =
    List(length()) { index -> optString(index) }.filter { it.isNotBlank() }

private fun String.sanitizePlaylistTitle(): String =
    lineSequence()
        .firstOrNull()
        .orEmpty()
        .trim()
        .take(80)

private fun String.normalizedForMixMatch(): String =
    lowercase(Locale.getDefault())
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

@HiltViewModel
class LibraryViewModel
@Inject
constructor() : ViewModel() {
    private val curScreen = mutableStateOf(LibraryFilter.LIBRARY)
    val filter: MutableState<LibraryFilter> = curScreen
}
