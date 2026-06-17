/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.shahdullah.nomatune.LocalPlayerAwareWindowInsets
import com.shahdullah.nomatune.R
import com.shahdullah.nomatune.constants.ArtistSeparatorsKey
import com.shahdullah.nomatune.constants.ExternalDownloaderEnabledKey
import com.shahdullah.nomatune.constants.ExternalDownloaderPackageKey
import com.shahdullah.nomatune.constants.AudioNormalizationKey
import com.shahdullah.nomatune.constants.AudioOffload
import com.shahdullah.nomatune.constants.AudioQuality
import com.shahdullah.nomatune.constants.AudioQualityKey
import com.shahdullah.nomatune.constants.LowDataModeKey
import com.shahdullah.nomatune.constants.AutoDownloadOnLikeKey
import com.shahdullah.nomatune.constants.AutoStartOnBluetoothKey
import com.shahdullah.nomatune.constants.AutoSkipNextOnErrorKey
import com.shahdullah.nomatune.constants.PauseOnDeviceMuteKey
import com.shahdullah.nomatune.constants.PermanentShuffleKey
import com.shahdullah.nomatune.constants.PersistentQueueKey

import com.shahdullah.nomatune.constants.SkipSilenceKey
import com.shahdullah.nomatune.constants.StopMusicOnTaskClearKey
import com.shahdullah.nomatune.constants.WakelockKey
import com.shahdullah.nomatune.constants.HistoryDuration
import com.shahdullah.nomatune.constants.HISTORY_DURATION_DEFAULT
import com.shahdullah.nomatune.constants.CrossfadeDurationKey
import com.shahdullah.nomatune.constants.CrossfadeEnabledKey
import com.shahdullah.nomatune.constants.CrossfadeGaplessKey
import com.shahdullah.nomatune.constants.DeviceMutePlaybackRecoveryVolumeKey
import com.shahdullah.nomatune.constants.PlayerStreamClient
import com.shahdullah.nomatune.constants.PlayerStreamClientKey
import com.shahdullah.nomatune.constants.SeekExtraSeconds
import com.shahdullah.nomatune.ui.component.ArtistSeparatorsDialog
import com.shahdullah.nomatune.ui.component.TagsManagementDialog
import com.shahdullah.nomatune.ui.component.TextFieldDialog
import com.shahdullah.nomatune.ui.component.EnumListPreference
import com.shahdullah.nomatune.ui.component.ListPreference
import com.shahdullah.nomatune.ui.component.IconButton
import com.shahdullah.nomatune.ui.component.NumberPickerPreference
import com.shahdullah.nomatune.ui.component.PreferenceEntry
import com.shahdullah.nomatune.ui.component.PreferenceGroup
import com.shahdullah.nomatune.ui.component.SliderPreference
import com.shahdullah.nomatune.ui.component.CrossfadeSliderPreference
import com.shahdullah.nomatune.ui.component.SwitchPreference
import com.shahdullah.nomatune.ui.utils.backToMain
import com.shahdullah.nomatune.utils.rememberEnumPreference
import com.shahdullah.nomatune.utils.rememberPreference
import com.shahdullah.nomatune.LocalDatabase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (audioQuality, onAudioQualityChange) = rememberEnumPreference(
        AudioQualityKey,
        defaultValue = AudioQuality.AUTO
    )
    val (playerStreamClient, onPlayerStreamClientChange) = rememberEnumPreference(
        PlayerStreamClientKey,
        defaultValue = PlayerStreamClient.ANDROID_VR
    )
    val (lowDataMode, onLowDataModeChange) = rememberPreference(
        LowDataModeKey,
        defaultValue = true
    )
    val (persistentQueue, onPersistentQueueChange) = rememberPreference(
        PersistentQueueKey,
        defaultValue = true
    )
    val (permanentShuffle, onPermanentShuffleChange) = rememberPreference(
        PermanentShuffleKey,
        defaultValue = false
    )
    val (skipSilence, onSkipSilenceChange) = rememberPreference(
        SkipSilenceKey,
        defaultValue = false
    )
    val (audioNormalization, onAudioNormalizationChange) = rememberPreference(
        AudioNormalizationKey,
        defaultValue = true
    )
    val (audioOffload, onAudioOffloadChange) = rememberPreference(
        AudioOffload,
        defaultValue = false
    )

    val (seekExtraSeconds, onSeekExtraSeconds) = rememberPreference(
        SeekExtraSeconds,
        defaultValue = false
    )

    val (autoDownloadOnLike, onAutoDownloadOnLikeChange) = rememberPreference(
        AutoDownloadOnLikeKey,
        defaultValue = false
    )
    val (autoSkipNextOnError, onAutoSkipNextOnErrorChange) = rememberPreference(
        AutoSkipNextOnErrorKey,
        defaultValue = false
    )
    val (pauseOnDeviceMute, onPauseOnDeviceMuteChange) = rememberPreference(
        PauseOnDeviceMuteKey,
        defaultValue = false
    )
    val (
        deviceMutePlaybackRecoveryVolume,
        onDeviceMutePlaybackRecoveryVolumeChange
    ) = rememberPreference(
        DeviceMutePlaybackRecoveryVolumeKey,
        defaultValue = 0
    )
    val (autoStartOnBluetooth, onAutoStartOnBluetoothChange) = rememberPreference(
        AutoStartOnBluetoothKey,
        defaultValue = false
    )
    val (stopMusicOnTaskClear, onStopMusicOnTaskClearChange) = rememberPreference(
        StopMusicOnTaskClearKey,
        defaultValue = false
    )
    val (historyDuration, onHistoryDurationChange) = rememberPreference(
        HistoryDuration,
        defaultValue = HISTORY_DURATION_DEFAULT
    )

    val (crossfadeEnabled, onCrossfadeEnabledChange) = rememberPreference(
        CrossfadeEnabledKey,
        defaultValue = false
    )
    val (crossfadeDurationSeconds, onCrossfadeDurationSecondsChange) = rememberPreference(
        CrossfadeDurationKey,
        defaultValue = 5f
    )
    val (crossfadeGapless, onCrossfadeGaplessChange) = rememberPreference(
        CrossfadeGaplessKey,
        defaultValue = true
    )

    val (artistSeparators, onArtistSeparatorsChange) = rememberPreference(
        ArtistSeparatorsKey,
        defaultValue = ",;/&"
    )
    val (externalDownloaderEnabled, onExternalDownloaderEnabledChange) = rememberPreference(
        ExternalDownloaderEnabledKey,
        defaultValue = false
    )
    val (externalDownloaderPackage, onExternalDownloaderPackageChange) = rememberPreference(
        ExternalDownloaderPackageKey,
        defaultValue = ""
    )

    val (wakelockEnabled, onWakelockChange) = rememberPreference(
        WakelockKey,
        defaultValue = false
    )

    var showArtistSeparatorsDialog by remember { mutableStateOf(false) }
    var showTagsManagementDialog by remember { mutableStateOf(false) }
    var showExternalDownloaderPackageDialog by remember { mutableStateOf(false) }
    val database = LocalDatabase.current

    if (showArtistSeparatorsDialog) {
        ArtistSeparatorsDialog(
            currentSeparators = artistSeparators,
            onDismiss = { showArtistSeparatorsDialog = false },
            onSave = { newSeparators ->
                onArtistSeparatorsChange(newSeparators)
                showArtistSeparatorsDialog = false
            }
        )
    }

    if (showTagsManagementDialog) {
        TagsManagementDialog(
            database = database,
            onDismiss = { showTagsManagementDialog = false }
        )
    }

    if (showExternalDownloaderPackageDialog) {
        TextFieldDialog(
            initialTextFieldValue = androidx.compose.ui.text.input.TextFieldValue(externalDownloaderPackage),
            onDone = { pkg ->
                onExternalDownloaderPackageChange(pkg)
                showExternalDownloaderPackageDialog = false
            },
            onDismiss = { showExternalDownloaderPackageDialog = false },
            singleLine = true,
            maxLines = 1,
        )
    }


    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top
                )
            )
        )

        PreferenceGroup(title = stringResource(R.string.player)) {
            item {
                EnumListPreference(
                    title = { Text(stringResource(R.string.audio_quality)) },
                    icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                    selectedValue = audioQuality,
                    onValueSelected = onAudioQualityChange,
                    valueText = {
                        when (it) {
                            AudioQuality.HIGHEST -> stringResource(R.string.audio_quality_max)
                            AudioQuality.HIGH -> stringResource(R.string.audio_quality_high)
                            AudioQuality.AUTO -> stringResource(R.string.audio_quality_auto)
                            AudioQuality.LOW -> stringResource(R.string.audio_quality_low)
                        }
                    }
                )
            }

            item {
                ListPreference(
                    title = { Text(stringResource(R.string.player_stream_client)) },
                    description = stringResource(R.string.player_stream_client_desc),
                    icon = { Icon(painterResource(R.drawable.integration), null) },
                    selectedValue = playerStreamClient,
                    values = remember {
                        listOf(
                            PlayerStreamClient.ANDROID_VR,
                            PlayerStreamClient.WEB_REMIX,
                            PlayerStreamClient.HI_RES_LOSSLESS,
                        )
                    },
                    onValueSelected = onPlayerStreamClientChange,
                    valueText = {
                        when (it) {
                            PlayerStreamClient.ANDROID_VR -> stringResource(R.string.player_stream_client_android_vr)
                            PlayerStreamClient.HI_RES_LOSSLESS -> stringResource(R.string.player_stream_client_hi_res_lossless)
                            else -> stringResource(R.string.player_stream_client_web_remix)
                        }
                    },
                    valueDescription = {
                        when (it) {
                            PlayerStreamClient.ANDROID_VR -> stringResource(R.string.player_stream_client_android_vr_desc)
                            PlayerStreamClient.HI_RES_LOSSLESS -> stringResource(R.string.player_stream_client_hi_res_lossless_desc)
                            else -> stringResource(R.string.player_stream_client_web_remix_desc)
                        }
                    },
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.low_data_mode_title)) },
                    description = stringResource(R.string.low_data_mode_description),
                    icon = { Icon(painterResource(R.drawable.android_cell), null) },
                    checked = lowDataMode,
                    onCheckedChange = onLowDataModeChange
                )
            }

            item {
                SliderPreference(
                    title = { Text(stringResource(R.string.history_duration)) },
                    icon = { Icon(painterResource(R.drawable.history), null) },
                    value = historyDuration,
                    onValueChange = onHistoryDurationChange,
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.audio_crossfade_title)) },
                    description = stringResource(R.string.audio_crossfade_description),
                    icon = { Icon(painterResource(R.drawable.animation), null) },
                    checked = crossfadeEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            onAudioOffloadChange(false)
                        }
                        onCrossfadeEnabledChange(enabled)
                    },
                )
            }

            item {
                CrossfadeSliderPreference(
                    valueSeconds = crossfadeDurationSeconds,
                    onValueChange = onCrossfadeDurationSecondsChange,
                    isEnabled = crossfadeEnabled,
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.crossfade_gapless_title)) },
                    description = stringResource(R.string.crossfade_gapless_description),
                    icon = { Icon(painterResource(R.drawable.fast_forward), null) },
                    checked = crossfadeGapless,
                    onCheckedChange = onCrossfadeGaplessChange,
                    isEnabled = crossfadeEnabled,
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.skip_silence)) },
                    icon = { Icon(painterResource(R.drawable.fast_forward), null) },
                    checked = skipSilence,
                    onCheckedChange = onSkipSilenceChange,
                    isEnabled = !audioOffload,
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.audio_normalization)) },
                    icon = { Icon(painterResource(R.drawable.volume_up), null) },
                    checked = audioNormalization,
                    onCheckedChange = onAudioNormalizationChange
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.audio_offload)) },
                    description = stringResource(R.string.audio_offload_desc),
                    icon = { Icon(painterResource(R.drawable.speed), null) },
                    checked = audioOffload,
                    onCheckedChange = { enabled ->
                        onAudioOffloadChange(enabled)
                        if (enabled) {
                            onSkipSilenceChange(false)
                            onCrossfadeEnabledChange(false)
                        }
                    }
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.seek_seconds_addup)) },
                    description = stringResource(R.string.seek_seconds_addup_description),
                    icon = { Icon(painterResource(R.drawable.arrow_forward), null) },
                    checked = seekExtraSeconds,
                    onCheckedChange = onSeekExtraSeconds
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.pause_on_device_mute)) },
                    description = stringResource(R.string.pause_on_device_mute_desc),
                    icon = { Icon(painterResource(R.drawable.volume_off), null) },
                    checked = pauseOnDeviceMute,
                    onCheckedChange = onPauseOnDeviceMuteChange
                )
            }

            item(visible = pauseOnDeviceMute) {
                val context = LocalContext.current
                val disabledLabel = stringResource(R.string.device_mute_recovery_volume_disabled)
                val recoveryVolumeText = remember(context, disabledLabel) {
                    { value: Int ->
                        if (value == 0) {
                            disabledLabel
                        } else {
                            context.getString(R.string.percentage_format, value)
                        }
                    }
                }
                NumberPickerPreference(
                    title = { Text(stringResource(R.string.device_mute_recovery_volume)) },
                    icon = { Icon(painterResource(R.drawable.volume_up), null) },
                    value = deviceMutePlaybackRecoveryVolume,
                    onValueChange = onDeviceMutePlaybackRecoveryVolumeChange,
                    minValue = 0,
                    maxValue = 100,
                    valueText = recoveryVolumeText,
                    isEnabled = pauseOnDeviceMute,
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.auto_start_on_bluetooth)) },
                    description = stringResource(R.string.auto_start_on_bluetooth_desc),
                    icon = { Icon(painterResource(R.drawable.bluetooth), null) },
                    checked = autoStartOnBluetooth,
                    onCheckedChange = onAutoStartOnBluetoothChange
                )
            }
        }

        PreferenceGroup(title = stringResource(R.string.queue)) {
            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.persistent_queue)) },
                    description = stringResource(R.string.persistent_queue_desc),
                    icon = { Icon(painterResource(R.drawable.queue_music), null) },
                    checked = persistentQueue,
                    onCheckedChange = onPersistentQueueChange
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.permanent_shuffle)) },
                    description = stringResource(R.string.permanent_shuffle_desc),
                    icon = { Icon(painterResource(R.drawable.shuffle), null) },
                    checked = permanentShuffle,
                    onCheckedChange = onPermanentShuffleChange
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.auto_download_on_like)) },
                    description = stringResource(R.string.auto_download_on_like_desc),
                    icon = { Icon(painterResource(R.drawable.download), null) },
                    checked = autoDownloadOnLike,
                    onCheckedChange = onAutoDownloadOnLikeChange
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.auto_skip_next_on_error)) },
                    description = stringResource(R.string.auto_skip_next_on_error_desc),
                    icon = { Icon(painterResource(R.drawable.skip_next), null) },
                    checked = autoSkipNextOnError,
                    onCheckedChange = onAutoSkipNextOnErrorChange
                )
            }
        }

        PreferenceGroup(title = stringResource(R.string.misc)) {
            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.stop_music_on_task_clear)) },
                    icon = { Icon(painterResource(R.drawable.clear_all), null) },
                    checked = stopMusicOnTaskClear,
                    onCheckedChange = onStopMusicOnTaskClearChange
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.wakelock)) },
                    description = stringResource(R.string.wakelock_desc),
                    icon = { Icon(painterResource(R.drawable.bolt), null) },
                    checked = wakelockEnabled,
                    onCheckedChange = onWakelockChange
                )
            }

            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.artist_separators)) },
                    description = artistSeparators.map { "\"$it\"" }.joinToString("  "),
                    icon = { Icon(painterResource(R.drawable.artist), null) },
                    onClick = { showArtistSeparatorsDialog = true }
                )
            }

            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.manage_playlist_tags)) },
                    description = stringResource(R.string.manage_playlist_tags_desc),
                    icon = { Icon(painterResource(R.drawable.style), null) },
                    onClick = { showTagsManagementDialog = true }
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.external_downloader)) },
                    description = stringResource(R.string.external_downloader_desc),
                    icon = { Icon(painterResource(R.drawable.download), null) },
                    checked = externalDownloaderEnabled,
                    onCheckedChange = onExternalDownloaderEnabledChange
                )
            }

            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.external_downloader_package)) },
                    description = externalDownloaderPackage.ifEmpty { stringResource(R.string.external_downloader_package_desc) },
                    icon = { Icon(painterResource(R.drawable.integration), null) },
                    onClick = { showExternalDownloaderPackageDialog = true },
                    isEnabled = externalDownloaderEnabled
                )
            }
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.player_and_audio)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null
                )
            }
        }
    )
}
