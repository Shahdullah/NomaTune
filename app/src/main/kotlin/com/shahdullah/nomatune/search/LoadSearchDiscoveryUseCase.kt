/*
 * NomaTune (2026)
 * Â© Rukamori â€” github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.search

import androidx.compose.runtime.Immutable
import com.google.common.collect.ImmutableList
import com.shahdullah.nomatune.innertube.models.AlbumItem
import com.shahdullah.nomatune.innertube.models.ArtistItem
import com.shahdullah.nomatune.innertube.models.SongItem
import com.shahdullah.nomatune.innertube.pages.MoodAndGenres
import com.shahdullah.nomatune.repository.SearchDiscoveryRepository
import javax.inject.Inject

class LoadSearchDiscoveryUseCase
    @Inject
    constructor(
        private val repository: SearchDiscoveryRepository,
    ) {
        suspend operator fun invoke(): Result<SearchDiscoveryUiModel> =
            repository.loadDiscovery().map { data ->
                val chartItems = data.chartSections.flatMap { section -> section.items }

                SearchDiscoveryUiModel(
                    moodAndGenres = ImmutableList.copyOf(data.moodAndGenres),
                    suggestedSongs =
                        ImmutableList.copyOf(
                            data
                                .suggestedSongs
                                .distinctBy { item -> item.id }
                                .take(MaxDiscoveryItems),
                        ),
                    trendingAlbums =
                        ImmutableList.copyOf(
                            (
                                chartItems.filterIsInstance<AlbumItem>() +
                                    data.newReleaseAlbums +
                                    data.searchedAlbums
                            ).distinctBy { item -> item.id }.take(MaxDiscoveryItems),
                        ),
                    suggestedArtists =
                        ImmutableList.copyOf(
                            data
                                .suggestedArtists
                                .distinctBy { item -> item.id }
                                .take(MaxDiscoveryItems),
                        ),
                )
            }

        private companion object {
            const val MaxDiscoveryItems = 12
        }
    }

@Immutable
data class SearchDiscoveryUiModel(
    val moodAndGenres: ImmutableList<MoodAndGenres.Item>,
    val suggestedSongs: ImmutableList<SongItem>,
    val trendingAlbums: ImmutableList<AlbumItem>,
    val suggestedArtists: ImmutableList<ArtistItem>,
) {
    val isEmpty: Boolean
        get() = moodAndGenres.isEmpty() && suggestedSongs.isEmpty() && trendingAlbums.isEmpty() && suggestedArtists.isEmpty()
}
