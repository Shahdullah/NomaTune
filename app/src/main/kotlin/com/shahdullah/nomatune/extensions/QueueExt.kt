/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.extensions

import com.shahdullah.nomatune.innertube.models.WatchEndpoint
import com.shahdullah.nomatune.models.PersistQueue
import com.shahdullah.nomatune.models.QueueData
import com.shahdullah.nomatune.models.QueueType
import com.shahdullah.nomatune.models.MediaMetadata
import com.shahdullah.nomatune.playback.queues.ListQueue
import com.shahdullah.nomatune.playback.queues.LocalAlbumRadio
import com.shahdullah.nomatune.playback.queues.Queue
import com.shahdullah.nomatune.playback.queues.YouTubeAlbumRadio
import com.shahdullah.nomatune.playback.queues.YouTubeQueue

fun Queue.toPersistQueue(
    title: String?,
    items: List<MediaMetadata>,
    mediaItemIndex: Int,
    position: Long,
): PersistQueue = when (this) {
    is ListQueue -> PersistQueue(
        title = title,
        items = items,
        mediaItemIndex = mediaItemIndex,
        position = position,
        queueType = QueueType.LIST,
    )
    is YouTubeQueue -> PersistQueue(
        title = title,
        items = items,
        mediaItemIndex = mediaItemIndex,
        position = position,
        queueType = QueueType.YOUTUBE,
        queueData = QueueData.YouTubeData(
            videoId = endpoint.videoId,
            playlistId = endpoint.playlistId,
            endpointParams = endpoint.params,
            followAutomixPreview = followAutomixPreview,
        ),
    )
    is YouTubeAlbumRadio -> PersistQueue(
        title = title,
        items = items,
        mediaItemIndex = mediaItemIndex,
        position = position,
        queueType = QueueType.YOUTUBE_ALBUM_RADIO,
        queueData = QueueData.YouTubeAlbumRadioData(
            playlistId = playlistId,
            albumSongCount = albumSongCount,
            continuation = continuation,
            firstTimeLoaded = firstTimeLoaded,
        ),
    )
    is LocalAlbumRadio -> PersistQueue(
        title = title,
        items = items,
        mediaItemIndex = mediaItemIndex,
        position = position,
        queueType = QueueType.LOCAL_ALBUM_RADIO,
        queueData = QueueData.LocalAlbumRadioData(
            albumId = albumWithSongs.album.id,
            startIndex = startIndex,
        ),
    )
    else -> PersistQueue(
        title = title,
        items = items,
        mediaItemIndex = mediaItemIndex,
        position = position,
        queueType = QueueType.LIST,
    )
}

fun PersistQueue.toQueue(): Queue = ListQueue(
    title = title,
    items = items.map { it.toMediaItem() },
    startIndex = mediaItemIndex,
    position = position,
)

fun PersistQueue.toContinuationQueue(): Queue = when (queueType) {
    is QueueType.LIST -> ListQueue(
        title = title,
        items = items.map { it.toMediaItem() },
        startIndex = mediaItemIndex,
        position = position,
    )
    is QueueType.YOUTUBE -> {
        val data = queueData as? QueueData.YouTubeData
            ?: return ListQueue(title, items.map { it.toMediaItem() }, mediaItemIndex, position)
        YouTubeQueue(
            endpoint = WatchEndpoint(
                videoId = data.videoId,
                playlistId = data.playlistId,
                params = data.endpointParams,
            ),
            followAutomixPreview = data.followAutomixPreview,
        )
    }
    is QueueType.YOUTUBE_ALBUM_RADIO -> {
        val data = queueData as? QueueData.YouTubeAlbumRadioData
            ?: return ListQueue(title, items.map { it.toMediaItem() }, mediaItemIndex, position)
        YouTubeAlbumRadio(
            playlistId = data.playlistId,
            albumSongCount = data.albumSongCount,
            continuation = data.continuation,
            firstTimeLoaded = data.firstTimeLoaded,
        )
    }
    is QueueType.LOCAL_ALBUM_RADIO -> ListQueue(
        title = title,
        items = items.map { it.toMediaItem() },
        startIndex = mediaItemIndex,
        position = position,
    )
}
