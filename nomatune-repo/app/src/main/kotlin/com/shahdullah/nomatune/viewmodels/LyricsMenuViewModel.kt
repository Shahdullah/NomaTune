/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.viewmodels

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import com.shahdullah.nomatune.R
import com.shahdullah.nomatune.ai.AiLyricsTranslator
import com.shahdullah.nomatune.ai.AiServiceConfig
import com.shahdullah.nomatune.constants.AiApiKeyKey
import com.shahdullah.nomatune.constants.AiApiValidationStatus
import com.shahdullah.nomatune.constants.AiApiValidationStatusKey
import com.shahdullah.nomatune.constants.AiCustomEndpointKey
import com.shahdullah.nomatune.constants.AiCustomModelKey
import com.shahdullah.nomatune.constants.AiProvider
import com.shahdullah.nomatune.constants.AiProviderKey
import com.shahdullah.nomatune.constants.AiSelectedModelKey
import com.shahdullah.nomatune.constants.TranslatorTargetLangKey
import com.shahdullah.nomatune.db.MusicDatabase
import com.shahdullah.nomatune.db.entities.LyricsEntity
import com.shahdullah.nomatune.extensions.toEnum
import com.shahdullah.nomatune.lyrics.LyricsHelper
import com.shahdullah.nomatune.lyrics.LyricsResult
import com.shahdullah.nomatune.models.MediaMetadata
import com.shahdullah.nomatune.utils.NetworkConnectivityObserver
import com.shahdullah.nomatune.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
 
import javax.inject.Inject

@HiltViewModel
class LyricsMenuViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val lyricsHelper: LyricsHelper,
    val database: MusicDatabase,
    private val networkConnectivity: NetworkConnectivityObserver,
) : ViewModel() {
    private var job: Job? = null
    val results = MutableStateFlow(emptyList<LyricsResult>())
    val isLoading = MutableStateFlow(false)
    val isRefetching = MutableStateFlow(false)
    val isAiTranslating = MutableStateFlow(false)

    private val _aiTranslationEvents = MutableSharedFlow<String>()
    val aiTranslationEvents: SharedFlow<String> = _aiTranslationEvents.asSharedFlow()

    private val _isNetworkAvailable = MutableStateFlow(false)
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

    init {
        viewModelScope.launch {
            networkConnectivity.networkStatus.collect { isConnected ->
                _isNetworkAvailable.value = isConnected
            }
        }
        
        // Set initial state using synchronous check
        _isNetworkAvailable.value = try {
            networkConnectivity.isCurrentlyConnected()
        } catch (e: Exception) {
            true // Assume connected as fallback
        }
    }

    fun search(
        mediaId: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ) {
        isLoading.value = true
        results.value = emptyList()
        job?.cancel()
        job =
            viewModelScope.launch(Dispatchers.IO) {
                lyricsHelper.getAllLyrics(mediaId, title, artist, album, duration) { result ->
                    results.update {
                        it + result
                    }
                }
                isLoading.value = false
            }
    }

    fun cancelSearch() {
        job?.cancel()
        job = null
    }

    fun refetchLyrics(
        mediaMetadata: MediaMetadata,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            isRefetching.value = true
            try {
                val lyrics = lyricsHelper.getLyrics(mediaMetadata)
                database.query {
                    replaceLyrics(
                        id = mediaMetadata.id,
                        lyrics = lyrics,
                        source = LyricsEntity.Source.REMOTE.value,
                    )
                }
            } catch (_: Exception) {
            } finally {
                isRefetching.value = false
            }
        }
    }

    fun updateLyrics(
        mediaMetadata: MediaMetadata,
        lyrics: String,
        source: LyricsEntity.Source = LyricsEntity.Source.USER_EDIT,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            database.query {
                replaceLyrics(
                    id = mediaMetadata.id,
                    lyrics = lyrics,
                    source = source.value,
                )
            }
        }
    }

    fun translateLyricsWithAi(
        mediaMetadata: MediaMetadata,
        lyrics: String,
    ) {
        if (isAiTranslating.value || lyrics.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            isAiTranslating.value = true
            try {
                val prefs = context.dataStore.data.first()
                val translatedLyrics = AiLyricsTranslator().translate(
                    config = AiServiceConfig(
                        provider = prefs[AiProviderKey].toEnum(AiProvider.NONE),
                        apiKey = prefs[AiApiKeyKey].orEmpty(),
                        customEndpoint = prefs[AiCustomEndpointKey].orEmpty(),
                        model = if (prefs[AiProviderKey].toEnum(AiProvider.NONE) == AiProvider.CUSTOM) {
                            prefs[AiCustomModelKey].orEmpty()
                        } else {
                            prefs[AiSelectedModelKey].orEmpty()
                        },
                    ),
                    lyrics = lyrics,
                    targetLanguage = prefs[TranslatorTargetLangKey].orEmpty().ifBlank { "ENGLISH" },
                )
                database.query {
                    replaceLyrics(
                        id = mediaMetadata.id,
                        lyrics = translatedLyrics,
                        source = LyricsEntity.Source.AI_TRANSLATION.value,
                    )
                }
                context.dataStore.edit { settings ->
                    settings[AiApiValidationStatusKey] = AiApiValidationStatus.SUCCESS.name
                }
                _aiTranslationEvents.emit(context.getString(R.string.translation_success))
            } catch (e: Exception) {
                context.dataStore.edit { settings ->
                    settings[AiApiValidationStatusKey] = AiApiValidationStatus.FAILED.name
                }
                _aiTranslationEvents.emit(
                    context.getString(R.string.translation_failed) + ": " + (e.localizedMessage ?: e.toString()),
                )
            } finally {
                isAiTranslating.value = false
            }
        }
    }
}
