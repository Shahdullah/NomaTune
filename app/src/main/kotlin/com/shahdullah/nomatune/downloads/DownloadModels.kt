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

package com.shahdullah.nomatune.downloads

import androidx.compose.runtime.Immutable
import androidx.media3.exoplayer.offline.Download
import com.google.common.collect.ImmutableList
import com.shahdullah.nomatune.models.MediaMetadata
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

/** Groups a download can belong to, in the order the Downloads page lists them. */
enum class DownloadMediaType {
    PLAYLIST,
    ALBUM,
    SONG,
}

/** Where a download stands, collapsed from media3's nine states into the four we show. */
enum class DownloadStatus {
    COMPLETED,
    DOWNLOADING,
    QUEUED,
    FAILED,
    PAUSED,
}

@Immutable
data class DownloadEntryUiModel(
    val id: String,
    val title: String,
    val subtitle: String?,
    val thumbnailUrl: String?,
    val mediaType: DownloadMediaType,
    val songIds: List<String>,
    val playbackMetadata: MediaMetadata?,
    val durationSeconds: Int?,
    val totalCount: Int,
    val modifiedAtMs: Long,
    val status: DownloadStatus,
    val progress: Float,
    val bytesDownloaded: Long,
) {
    val percent: Int = (progress * 100f).roundToInt().coerceIn(0, 100)

    val isFinished: Boolean get() = status == DownloadStatus.COMPLETED

    val isActive: Boolean
        get() = status == DownloadStatus.DOWNLOADING || status == DownloadStatus.QUEUED

    val paused: Boolean get() = status == DownloadStatus.PAUSED

    val destinationRoute: String?
        get() =
            when (mediaType) {
                DownloadMediaType.PLAYLIST -> "local_playlist/$id"
                DownloadMediaType.ALBUM -> "album/$id"
                // A downloaded song plays straight away rather than opening a detail page.
                DownloadMediaType.SONG -> null
            }
}

@Immutable
data class DownloadSectionUiModel(
    val mediaType: DownloadMediaType,
    val entries: List<DownloadEntryUiModel>,
) {
    val songIds: List<String> = entries.flatMap(DownloadEntryUiModel::songIds).distinct()

    val paused: Boolean = entries.isNotEmpty() && entries.all(DownloadEntryUiModel::paused)

    val bytes: Long = entries.sumOf(DownloadEntryUiModel::bytesDownloaded)

    val percent: Int =
        entries
            .map(DownloadEntryUiModel::progress)
            .average()
            .times(100)
            .roundToInt()
            .coerceIn(0, 100)

    val isEmpty: Boolean = entries.isEmpty()
}

@Immutable
data class DownloadStorageSummary(
    val songCount: Int = 0,
    val albumCount: Int = 0,
    val playlistCount: Int = 0,
    val bytes: Long = 0L,
)

@Immutable
data class DownloadQueueSummary(
    val songIds: List<String> = emptyList(),
    val failedSongIds: List<String> = emptyList(),
    val progress: Float = 0f,
    val paused: Boolean = false,
) {
    val activeCount: Int = songIds.size

    val hasFailures: Boolean = failedSongIds.isNotEmpty()

    val percent: Int = (progress * 100f).roundToInt().coerceIn(0, 100)
}

@Immutable
data class DownloadLibraryUiModel(
    val completedSections: ImmutableList<DownloadSectionUiModel> = ImmutableList.of(),
    val progressSections: ImmutableList<DownloadSectionUiModel> = ImmutableList.of(),
    val storage: DownloadStorageSummary = DownloadStorageSummary(),
    val queue: DownloadQueueSummary = DownloadQueueSummary(),
) {
    val isEmpty: Boolean
        get() = completedSections.isEmpty() && progressSections.isEmpty()

    val totalEntries: Int
        get() = completedSections.sumOf { it.entries.size } + progressSections.sumOf { it.entries.size }
}

/** Collapses media3's download state into the status the page renders. */
fun Download.toDownloadStatus(): DownloadStatus =
    when (state) {
        Download.STATE_COMPLETED -> DownloadStatus.COMPLETED
        Download.STATE_DOWNLOADING -> DownloadStatus.DOWNLOADING
        Download.STATE_QUEUED -> DownloadStatus.QUEUED
        Download.STATE_FAILED -> DownloadStatus.FAILED
        Download.STATE_REMOVING -> DownloadStatus.PAUSED
        else -> DownloadStatus.PAUSED
    }

/**
 * media3 keeps the enqueue time in `startTimeMs` for finished downloads, but a failed
 * or queued one can still report 0, so fall back to now instead of showing 1970.
 */
fun Download.updatedAtMillis(): Long =
    listOf(lastProgressTimeMs, startTimeMs)
        .firstOrNull { it > 0L }
        ?: System.currentTimeMillis()

/** Human label for "Date updated", e.g. "Today, 14:02" or "12 Nov 2026". */
fun formatDownloadDate(timestampMs: Long): String {
    if (timestampMs <= 0L) return ""
    return runCatching {
        val dateTime = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(timestampMs), ZoneId.systemDefault())
        val today = LocalDateTime.now().toLocalDate()
        when (dateTime.toLocalDate()) {
            today -> "Today, %02d:%02d".format(dateTime.hour, dateTime.minute)
            today.minusDays(1) -> "Yesterday, %02d:%02d".format(dateTime.hour, dateTime.minute)
            else ->
                dateTime.month.name
                    .lowercase()
                    .replaceFirstChar { it.uppercase() }
                    .take(3) + " ${dateTime.dayOfMonth} ${dateTime.year}"
        }
    }.getOrDefault("")
}
