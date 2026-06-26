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
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.shahdullah.nomatune.R
import com.shahdullah.nomatune.ai.AiModelOption
import com.shahdullah.nomatune.ai.AiServiceConfig
import com.shahdullah.nomatune.ai.AiTextService
import com.shahdullah.nomatune.constants.AiApiKeyKey
import com.shahdullah.nomatune.constants.AiApiValidationStatus
import com.shahdullah.nomatune.constants.AiApiValidationStatusKey
import com.shahdullah.nomatune.constants.AiCustomEndpointKey
import com.shahdullah.nomatune.constants.AiCustomModelKey
import com.shahdullah.nomatune.constants.AiProvider
import com.shahdullah.nomatune.constants.AiProviderKey
import com.shahdullah.nomatune.constants.AiSelectedModelKey
import com.shahdullah.nomatune.extensions.toEnum
import com.shahdullah.nomatune.utils.dataStore

data class AiIntegrationActionState(
    val isTesting: Boolean = false,
    val isFetchingModels: Boolean = false,
    val errorMessage: String? = null,
)

private const val MaxInlineErrorLength = 140

@HiltViewModel
class AiIntegrationSettingsViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _actionState = MutableStateFlow(AiIntegrationActionState())
    val actionState: StateFlow<AiIntegrationActionState> = _actionState.asStateFlow()

    private val _events = MutableSharedFlow<String>()
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val _availableModels = MutableStateFlow<List<AiModelOption>>(emptyList())
    val availableModels: StateFlow<List<AiModelOption>> = _availableModels.asStateFlow()
    private var fetchModelsJob: Job? = null
    private val fetchModelsRequestId = AtomicInteger()

    fun clearAvailableModels() {
        fetchModelsRequestId.incrementAndGet()
        fetchModelsJob?.cancel()
        fetchModelsJob = null
        _availableModels.value = emptyList()
        _actionState.value = _actionState.value.copy(
            isFetchingModels = false,
            errorMessage = null,
        )
    }

    fun clearError() {
        _actionState.value = _actionState.value.copy(errorMessage = null)
    }

    fun fetchModels(provider: AiProvider, apiKey: String, customEndpoint: String) {
        if (fetchModelsJob?.isActive == true) return
        val requestId = fetchModelsRequestId.incrementAndGet()
        fetchModelsJob = viewModelScope.launch(Dispatchers.IO) {
            _actionState.value = _actionState.value.copy(
                isFetchingModels = true,
                errorMessage = null,
            )
            _availableModels.value = emptyList()
            try {
                val config = AiServiceConfig(
                    provider = provider,
                    apiKey = apiKey,
                    customEndpoint = customEndpoint,
                    model = "",
                )
                val models = AiTextService.fetchModels(config)
                if (requestId == fetchModelsRequestId.get()) {
                    _availableModels.value = models
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestId == fetchModelsRequestId.get()) {
                    _actionState.value = _actionState.value.copy(
                        errorMessage = e.shortMessage(context.getString(R.string.ai_model_fetch_failed)),
                    )
                }
            } finally {
                if (requestId == fetchModelsRequestId.get()) {
                    _actionState.value = _actionState.value.copy(isFetchingModels = false)
                    fetchModelsJob = null
                }
            }
        }
    }

    fun testApi() {
        if (_actionState.value.isTesting) return
        viewModelScope.launch(Dispatchers.IO) {
            _actionState.value = _actionState.value.copy(
                isTesting = true,
                errorMessage = null,
            )
            try {
                AiTextService.test(readConfig())
                context.dataStore.edit { prefs ->
                    prefs[AiApiValidationStatusKey] = AiApiValidationStatus.SUCCESS.name
                }
                _actionState.value = _actionState.value.copy(errorMessage = null)
                _events.emit(context.getString(R.string.ai_api_connected))
            } catch (e: Exception) {
                context.dataStore.edit { prefs ->
                    prefs[AiApiValidationStatusKey] = AiApiValidationStatus.FAILED.name
                }
                _actionState.value = _actionState.value.copy(
                    errorMessage = e.shortMessage(context.getString(R.string.ai_api_test_failed)),
                )
            } finally {
                _actionState.value = _actionState.value.copy(isTesting = false)
            }
        }
    }

    private suspend fun readConfig(): AiServiceConfig {
        val prefs = context.dataStore.data.first()
        val provider = prefs[AiProviderKey].toEnum(AiProvider.NONE)
        val model = if (provider == AiProvider.CUSTOM) {
            prefs[AiCustomModelKey].orEmpty()
        } else {
            prefs[AiSelectedModelKey].orEmpty()
        }
        return AiServiceConfig(
            provider = provider,
            apiKey = prefs[AiApiKeyKey].orEmpty(),
            customEndpoint = prefs[AiCustomEndpointKey].orEmpty(),
            model = model,
        )
    }

    private fun Throwable.shortMessage(fallback: String): String {
        val raw = localizedMessage?.takeIf { it.isNotBlank() } ?: fallback
        val message = raw
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
            .ifBlank { fallback }
            .removePrefix("AI API failed ")
            .replace(Regex("^\\((\\d{3})\\):\\s*"), "HTTP $1: ")
        return if (message.length <= MaxInlineErrorLength) {
            message
        } else {
            message.take(MaxInlineErrorLength).trimEnd() + "..."
        }
    }
}
