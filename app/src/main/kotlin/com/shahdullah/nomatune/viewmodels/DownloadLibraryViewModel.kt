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

package com.shahdullah.nomatune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shahdullah.nomatune.downloads.DownloadLibraryUiModel
import com.shahdullah.nomatune.downloads.DownloadMediaType
import com.shahdullah.nomatune.downloads.ManageDownloadsUseCase
import com.shahdullah.nomatune.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/** Which half of the page is showing. */
enum class DownloadLibraryTab {
    COMPLETED,
    IN_PROGRESS,
}

/** What the list is sorted by. Each field is applied inside a single section. */
enum class DownloadLibrarySortField {
    DATE_UPDATED,
    NAME,
    SIZE,
}

enum class DownloadLibrarySortOrder {
    ASCENDING,
    DESCENDING,
}

sealed interface DownloadLibraryScreenState {
    data object Loading : DownloadLibraryScreenState

    data class Success(
        val library: DownloadLibraryUiModel,
        val sortField: DownloadLibrarySortField = DownloadLibrarySortField.DATE_UPDATED,
        val sortOrder: DownloadLibrarySortOrder = DownloadLibrarySortOrder.DESCENDING,
    ) : DownloadLibraryScreenState {
        val sortedSections
            get() =
                library.completedSections
                    .map { section -> section.copy(entries = section.entries.sortedWith(comparator())) }

        val sortedProgressSections
            get() =
                library.progressSections
                    .map { section -> section.copy(entries = section.entries.sortedWith(comparator())) }

        private fun comparator(): Comparator<com.shahdullah.nomatune.downloads.DownloadEntryUiModel> {
            val base =
                when (sortField) {
                    DownloadLibrarySortField.DATE_UPDATED ->
                        compareBy<com.shahdullah.nomatune.downloads.DownloadEntryUiModel> { it.modifiedAtMs }
                    DownloadLibrarySortField.NAME ->
                        compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                    DownloadLibrarySortField.SIZE ->
                        compareBy<com.shahdullah.nomatune.downloads.DownloadEntryUiModel> { it.bytesDownloaded }
                }
            return if (sortOrder == DownloadLibrarySortOrder.DESCENDING) base.reversed() else base
        }
    }

    data object Empty : DownloadLibraryScreenState

    data class Error(val message: String) : DownloadLibraryScreenState
}

/** Asks the user to confirm before a destructive bulk removal. */
data class DownloadRemovalConfirmation(
    val mediaType: DownloadMediaType,
    val title: String,
    val songIds: List<String>,
) {
    val songCount: Int get() = songIds.size
}

@HiltViewModel
class DownloadLibraryViewModel
    @Inject
    constructor(
        private val manageDownloads: ManageDownloadsUseCase,
    ) : ViewModel() {
        private val _tab = MutableStateFlow(DownloadLibraryTab.COMPLETED)
        val tab: StateFlow<DownloadLibraryTab> = _tab.asStateFlow()

        private val _sortField = MutableStateFlow(DownloadLibrarySortField.DATE_UPDATED)
        val sortField: StateFlow<DownloadLibrarySortField> = _sortField.asStateFlow()

        private val _sortOrder = MutableStateFlow(DownloadLibrarySortOrder.DESCENDING)
        val sortOrder: StateFlow<DownloadLibrarySortOrder> = _sortOrder.asStateFlow()

        private val _removalConfirmation = MutableStateFlow<DownloadRemovalConfirmation?>(null)
        val removalConfirmation: StateFlow<DownloadRemovalConfirmation?> = _removalConfirmation.asStateFlow()

        private val _messages = MutableStateFlow<String?>(null)
        val messages: StateFlow<String?> = _messages.asStateFlow()

        init {
            // The download index is only mirrored into memory on listener callbacks, so
            // resync it whenever the page opens instead of trusting a possibly stale map.
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { manageDownloads.refreshDownloads() }
                    .onFailure {
                        Timber.tag("Downloads").w(it, "Could not refresh the download index")
                        reportException(it)
                    }
            }
        }

        val screenState: StateFlow<DownloadLibraryScreenState> =
            combine(
                manageDownloads.observeLibrary().catch { throwable ->
                    if (throwable is CancellationException) throw throwable
                    reportException(throwable)
                    // Report the failure instead of rendering "nothing downloaded", which
                    // hid a broken data path behind an innocent-looking empty state.
                    emitError(throwable)
                },
                _sortField,
                _sortOrder,
            ) { library, field, order ->
                if (lastFailure != null) {
                    DownloadLibraryScreenState.Error(
                        lastFailure?.message ?: "Couldn't read your downloads",
                    )
                } else if (library.isReallyEmpty) {
                    DownloadLibraryScreenState.Empty
                } else {
                    DownloadLibraryScreenState.Success(
                        library = library,
                        sortField = field,
                        sortOrder = order,
                    )
                }
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                DownloadLibraryScreenState.Loading,
            )

        private fun emitError(throwable: Throwable): DownloadLibraryUiModel {
            lastFailure = throwable
            return DownloadLibraryUiModel()
        }

        /** Set when the library flow failed, so the mapper can show an error, not "empty". */
        @Volatile
        private var lastFailure: Throwable? = null

        fun selectTab(tab: DownloadLibraryTab) {
            _tab.value = tab
        }

        fun setSortField(field: DownloadLibrarySortField) {
            _sortField.value = field
        }

        fun toggleSortOrder() {
            _sortOrder.value =
                if (_sortOrder.value == DownloadLibrarySortOrder.DESCENDING) {
                    DownloadLibrarySortOrder.ASCENDING
                } else {
                    DownloadLibrarySortOrder.DESCENDING
                }
        }

        fun pause(songIds: Collection<String>) {
            manageDownloads.pause(songIds)
        }

        fun resume(songIds: Collection<String>) {
            manageDownloads.resume(songIds)
        }

        fun requestRemoval(
            mediaType: DownloadMediaType,
            title: String,
            songIds: List<String>,
        ) {
            if (songIds.isEmpty()) return
            _removalConfirmation.value = DownloadRemovalConfirmation(mediaType, title, songIds)
        }

        fun dismissRemoval() {
            _removalConfirmation.value = null
        }

        fun confirmRemoval() {
            val pending = _removalConfirmation.value ?: return
            _removalConfirmation.value = null
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    manageDownloads.remove(pending.songIds)
                    _messages.value = "Removing ${pending.songCount} songs"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag("Downloads").w(e, "Failed to remove downloads")
                    reportException(e)
                    _messages.value = "Couldn't remove those downloads"
                }
            }
        }

        fun clearMessage() {
            _messages.value = null
        }
    }
