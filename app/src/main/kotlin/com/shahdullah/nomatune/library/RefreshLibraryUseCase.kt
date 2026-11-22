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

package com.shahdullah.nomatune.library

import com.shahdullah.nomatune.constants.InnerTubeCookieKey
import com.shahdullah.nomatune.constants.YtmSyncKey
import com.shahdullah.nomatune.innertube.utils.hasYouTubeLoginCookie
import android.content.Context
import com.shahdullah.nomatune.utils.SyncUtils
import com.shahdullah.nomatune.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

enum class LibrarySyncTarget {
    All,
    LikedSongs,
    Songs,
    Artists,
    Albums,
    Playlists,
    Podcasts,
}

enum class LibrarySyncFailure {
    LoginRequired,
    SyncDisabled,
    RequestFailed,
}

sealed interface RefreshLibraryResult {
    data object Success : RefreshLibraryResult

    data class Failure(val reason: LibrarySyncFailure) : RefreshLibraryResult
}

/**
 * Runs a library refresh for one section and reports *why* it failed instead of
 * swallowing it, so the screen can offer the right next step (sign in, turn sync
 * on, or retry).
 *
 * NomaTune's [SyncUtils] takes `authoritative` rather than a failure-propagation
 * flag, and reports login/sync problems by returning quietly. This use case checks
 * both preconditions up front so the UI can distinguish them, then translates any
 * thrown error into [LibrarySyncFailure.RequestFailed].
 */
class RefreshLibraryUseCase
    @Inject
    constructor(
        private val syncUtils: SyncUtils,
        @ApplicationContext private val context: Context,
    ) {
        private val dataStore get() = context.dataStore

        suspend operator fun invoke(target: LibrarySyncTarget): RefreshLibraryResult {
            val cookie = dataStore.data.first()[InnerTubeCookieKey]
            if (!hasYouTubeLoginCookie(cookie)) {
                return RefreshLibraryResult.Failure(LibrarySyncFailure.LoginRequired)
            }

            val syncEnabled = dataStore.data.first()[YtmSyncKey] ?: true
            if (!syncEnabled) {
                return RefreshLibraryResult.Failure(LibrarySyncFailure.SyncDisabled)
            }

            return try {
                when (target) {
                    LibrarySyncTarget.All -> syncUtils.performFullSync(authoritative = true)
                    LibrarySyncTarget.LikedSongs -> syncUtils.syncLikedSongs(authoritative = true)
                    LibrarySyncTarget.Songs -> syncUtils.syncLibrarySongs(authoritative = true)
                    LibrarySyncTarget.Artists -> syncUtils.syncArtistsSubscriptions(authoritative = true)
                    LibrarySyncTarget.Albums -> syncUtils.syncLikedAlbums(authoritative = true)
                    LibrarySyncTarget.Playlists -> {
                        syncUtils.syncSavedPlaylists(authoritative = true)
                        syncUtils.syncAutoSyncPlaylists()
                    }
                    // NomaTune has no podcast library; treat as a no-op refresh.
                    LibrarySyncTarget.Podcasts -> Unit
                }
                RefreshLibraryResult.Success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("RefreshLibrary").w(e, "Library refresh failed for %s", target)
                RefreshLibraryResult.Failure(LibrarySyncFailure.RequestFailed)
            }
        }
    }
