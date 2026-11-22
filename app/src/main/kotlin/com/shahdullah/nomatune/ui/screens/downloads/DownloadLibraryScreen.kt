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

package com.shahdullah.nomatune.ui.screens.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateBottomPadding
import androidx.compose.foundation.layout.calculateTopPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.shahdullah.nomatune.LocalPlayerAwareWindowInsets
import com.shahdullah.nomatune.LocalPlayerConnection
import com.shahdullah.nomatune.R
import com.shahdullah.nomatune.downloads.DownloadEntryUiModel
import com.shahdullah.nomatune.downloads.DownloadMediaType
import com.shahdullah.nomatune.downloads.DownloadSectionUiModel
import com.shahdullah.nomatune.downloads.DownloadStatus
import com.shahdullah.nomatune.downloads.formatDownloadDate
import com.shahdullah.nomatune.extensions.toMediaItem
import com.shahdullah.nomatune.playback.queues.ListQueue
import com.shahdullah.nomatune.ui.component.ItemThumbnail
import com.shahdullah.nomatune.ui.utils.backToMain
import com.shahdullah.nomatune.ui.utils.formatFileSize
import com.shahdullah.nomatune.utils.makeTimeString
import com.shahdullah.nomatune.viewmodels.DownloadLibraryScreenState
import com.shahdullah.nomatune.viewmodels.DownloadLibrarySortField
import com.shahdullah.nomatune.viewmodels.DownloadLibrarySortOrder
import com.shahdullah.nomatune.viewmodels.DownloadLibraryTab
import com.shahdullah.nomatune.viewmodels.DownloadLibraryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadLibraryScreen(
    navController: NavController,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    viewModel: DownloadLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.screenState.collectAsState()
    val tab by viewModel.tab.collectAsState()
    val sortField by viewModel.sortField.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val confirmation by viewModel.removalConfirmation.collectAsState()
    val message by viewModel.messages.collectAsState()

    val playerConnection = LocalPlayerConnection.current ?: return
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.downloads)) },
                navigationIcon = {
                    IconButton(onClick = { navController.backToMain() }) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = stringResource(R.string.back_button_desc),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when (val current = state) {
            DownloadLibraryScreenState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            DownloadLibraryScreenState.Empty -> {
                DownloadLibraryEmptyState(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                )
            }

            is DownloadLibraryScreenState.Error -> {
                DownloadLibraryEmptyState(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    message = current.message,
                )
            }

            is DownloadLibraryScreenState.Success -> {
                val library = current.library
                val sections =
                    if (tab == DownloadLibraryTab.COMPLETED) {
                        current.sortedSections
                    } else {
                        current.sortedProgressSections
                    }
                val totalBytes = library.storage.bytes
                val songCount = library.storage.songCount
                val allOfflineMetadata =
                    library.completedSections
                        .flatMap { it.entries }
                        .mapNotNull { it.playbackMetadata }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding =
                        PaddingValues(
                            top = innerPadding.calculateTopPadding(),
                            bottom =
                                LocalPlayerAwareWindowInsets.current
                                    .asPaddingValues()
                                    .calculateBottomPadding() + 24.dp,
                        ),
                ) {
                    item(key = "header") {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.downloads),
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text =
                                    if (totalBytes > 0) {
                                        "${formatFileSize(totalBytes)} on this device"
                                    } else {
                                        "Nothing downloaded yet"
                                    },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = tab == DownloadLibraryTab.COMPLETED,
                                    onClick = { viewModel.selectTab(DownloadLibraryTab.COMPLETED) },
                                    label = { Text("Downloaded") },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.done),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    },
                                )
                                FilterChip(
                                    selected = tab == DownloadLibraryTab.IN_PROGRESS,
                                    onClick = { viewModel.selectTab(DownloadLibraryTab.IN_PROGRESS) },
                                    label = { Text("In progress") },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.download),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    },
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item {
                                    FilterChip(
                                        selected = true,
                                        onClick = { viewModel.toggleSortOrder() },
                                        label = {
                                            Text(
                                                when (sortField) {
                                                    DownloadLibrarySortField.DATE_UPDATED -> "Date updated"
                                                    DownloadLibrarySortField.NAME -> "Name"
                                                    DownloadLibrarySortField.SIZE -> "Size"
                                                },
                                            )
                                        },
                                        trailingIcon = {
                                            Icon(
                                                painter = painterResource(R.drawable.sort),
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        },
                                    )
                                }
                                items(DownloadLibrarySortField.entries.toList()) { field ->
                                    FilterChip(
                                        selected = sortField == field,
                                        onClick = { viewModel.setSortField(field) },
                                        label = {
                                            Text(
                                                when (field) {
                                                    DownloadLibrarySortField.DATE_UPDATED -> "Date updated"
                                                    DownloadLibrarySortField.NAME -> "Name"
                                                    DownloadLibrarySortField.SIZE -> "Size"
                                                },
                                            )
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                    }

                    item(key = "summary") {
                        DownloadSummaryCard(
                            songCount = songCount,
                            totalBytes = totalBytes,
                            onPlayAll = {
                                if (allOfflineMetadata.isNotEmpty()) {
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = "Downloads",
                                            items = allOfflineMetadata.shuffled().map { it.toMediaItem() },
                                        ),
                                    )
                                }
                            },
                            onShuffle = {
                                if (allOfflineMetadata.isNotEmpty()) {
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = "Downloads",
                                            items = allOfflineMetadata.map { it.toMediaItem() },
                                        ),
                                    )
                                }
                            },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }

                    if (sections.isEmpty()) {
                        item(key = "section-empty") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text =
                                        if (tab == DownloadLibraryTab.COMPLETED) {
                                            "No downloads yet"
                                        } else {
                                            "Nothing downloading right now"
                                        },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    sections.forEach { section ->
                        item(key = "section-${section.mediaType.name}") {
                            SectionHeader(section = section, sortOrder = sortOrder)
                        }
                        items(section.entries, key = { "${section.mediaType.name}-${it.id}" }) { entry ->
                            DownloadRow(
                                entry = entry,
                                onClick = {
                                    val metadata = entry.playbackMetadata
                                    if (metadata != null) {
                                        playerConnection.playQueue(
                                            ListQueue(title = entry.title, items = listOf(metadata.toMediaItem())),
                                        )
                                    }
                                },
                                onRemove = {
                                    viewModel.requestRemoval(entry.mediaType, entry.title, entry.songIds)
                                },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    confirmation?.let {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissRemoval() },
            title = { Text("Remove download?") },
            text = {
                Text(
                    if (it.songCount > 1) {
                        "This removes ${it.songCount} songs from \"${it.title}\" on this device."
                    } else {
                        "This removes \"${it.title}\" from this device."
                    },
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.confirmRemoval() },
                ) { Text("Remove") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.dismissRemoval() },
                ) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionHeader(
    section: DownloadSectionUiModel,
    sortOrder: DownloadLibrarySortOrder,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text =
                    when (section.mediaType) {
                        DownloadMediaType.PLAYLIST -> "Playlists"
                        DownloadMediaType.ALBUM -> "Albums"
                        DownloadMediaType.SONG -> "Songs"
                    },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                    "${section.entries.size} · ${formatFileSize(section.bytes)}" +
                        if (sortOrder == DownloadLibrarySortOrder.DESCENDING) " · newest first" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadSummaryCard(
    songCount: Int,
    totalBytes: Long,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.done),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = "$songCount ${if (songCount == 1) "song" else "songs"} offline",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = "${formatFileSize(totalBytes)} on this device",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    modifier = Modifier.weight(1f).clickable(onClick = onPlayAll),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.play),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Play all",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Surface(
                    modifier = Modifier.weight(1f).clickable(onClick = onShuffle),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.shuffle),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(text = "Shuffle", fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    entry: DownloadEntryUiModel,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ItemThumbnail(
                thumbnailUrl = entry.thumbnailUrl,
                isActive = false,
                isPlaying = false,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(56.dp),
                placeholderIconRes =
                    when (entry.mediaType) {
                        DownloadMediaType.PLAYLIST -> R.drawable.queue_music
                        DownloadMediaType.ALBUM -> R.drawable.album
                        DownloadMediaType.SONG -> R.drawable.music_note
                    },
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildList {
                        entry.subtitle?.let(::add)
                        entry.durationSeconds?.let { add(makeTimeString(it.toLong())) }
                        if (entry.mediaType != DownloadMediaType.SONG) add("${entry.totalCount} songs")
                        formatDownloadDate(entry.modifiedAtMs).takeIf { it.isNotBlank() }?.let(::add)
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!entry.isFinished) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { entry.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    painter = painterResource(R.drawable.delete),
                    contentDescription = "Remove download",
                )
            }
        }
    }
}

@Composable
private fun DownloadLibraryEmptyState(
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(R.drawable.download),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "No downloads yet",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message ?: "Songs you download appear here and play without a connection.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
