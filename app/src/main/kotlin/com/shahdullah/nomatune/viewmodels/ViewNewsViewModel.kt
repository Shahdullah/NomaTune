/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.shahdullah.nomatune.models.NewsItem
import com.shahdullah.nomatune.repository.NewsRepository
import javax.inject.Inject

sealed interface ViewNewsUiState {
    data object Loading : ViewNewsUiState
    data class Success(val content: String) : ViewNewsUiState
    data class Error(val message: String) : ViewNewsUiState
}

@HiltViewModel
class ViewNewsViewModel @Inject constructor(
    private val repository: NewsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val newsId: String = savedStateHandle.get<String>("newsId") ?: ""
    val newsItem: NewsItem? = repository.getCachedItem(newsId)

    private val _contentState = MutableStateFlow<ViewNewsUiState>(ViewNewsUiState.Loading)
    val contentState: StateFlow<ViewNewsUiState> = _contentState.asStateFlow()

    init {
        loadContent()
    }

    fun loadContent() {
        viewModelScope.launch {
            _contentState.value = ViewNewsUiState.Loading
            runCatching {
                repository.fetchNewsContent(newsId)
            }.onSuccess { content ->
                _contentState.value = ViewNewsUiState.Success(content)
            }.onFailure { error ->
                _contentState.value = ViewNewsUiState.Error(error.message ?: "Unknown error")
            }
        }
    }
}
