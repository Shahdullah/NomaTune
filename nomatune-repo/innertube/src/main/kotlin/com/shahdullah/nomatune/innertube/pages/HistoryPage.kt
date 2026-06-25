/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.innertube.pages

import com.shahdullah.nomatune.innertube.models.MusicResponsiveListItemRenderer
import com.shahdullah.nomatune.innertube.models.MusicShelfRenderer
import com.shahdullah.nomatune.innertube.models.SectionListRenderer
import com.shahdullah.nomatune.innertube.models.SongItem
import com.shahdullah.nomatune.innertube.models.getItems

data class HistoryPage(
    val sections: List<HistorySection>?,
) {
    data class HistorySection(
        val title: String,
        val songs: List<SongItem>
    )

    companion object {
        fun fromSectionListContent(content: SectionListRenderer.Content): List<HistorySection> {
            val directSongs = mutableListOf<SongItem>()
            val sections = buildList {
                content.musicShelfRenderer?.toHistorySection()?.let(::add)
                content.itemSectionRenderer?.contents.orEmpty().forEach { itemSectionContent ->
                    itemSectionContent.musicShelfRenderer?.toHistorySection()?.let(::add)
                    itemSectionContent.musicResponsiveListItemRenderer
                        ?.let { fromMusicResponsiveListItemRenderer(it) }
                        ?.let(directSongs::add)
                }
            }

            return if (directSongs.isEmpty()) {
                sections
            } else {
                sections + HistorySection(
                    title = content.musicShelfRenderer?.title?.runs?.firstOrNull()?.text.orEmpty(),
                    songs = directSongs
                )
            }
        }

        fun fromMusicShelfRenderer(renderer: MusicShelfRenderer): HistorySection {
            return renderer.toHistorySection()
                ?: HistorySection(
                    title = renderer.title?.runs?.firstOrNull()?.text.orEmpty(),
                    songs = emptyList()
                )
        }

        private fun fromMusicResponsiveListItemRenderer(renderer: MusicResponsiveListItemRenderer): SongItem? {
            return renderer.toSongItem(albumColumnIndex = 3)
        }
    }
}

private fun MusicShelfRenderer.toHistorySection(): HistoryPage.HistorySection? {
    val songs = contents.orEmpty().getItems().mapNotNull {
        it.toSongItem(albumColumnIndex = 3)
    }
    if (songs.isEmpty()) return null
    return HistoryPage.HistorySection(
        title = title?.runs?.firstOrNull()?.text.orEmpty(),
        songs = songs
    )
}
