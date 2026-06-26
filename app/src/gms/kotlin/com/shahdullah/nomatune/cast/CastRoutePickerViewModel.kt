/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.cast

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface CastRoutePickerScreenState {
    data object Loading : CastRoutePickerScreenState
    data object Empty : CastRoutePickerScreenState
    data class Error(@StringRes val messageResId: Int) : CastRoutePickerScreenState
    data class Success(val routes: List<CastRouteUiModel>) : CastRoutePickerScreenState
}

data class CastRouteUiModel(
    val id: String,
    val name: String,
    val description: String?,
    val selected: Boolean,
    val enabled: Boolean,
    val connecting: Boolean,
)

class CastRoutePickerViewModel(application: Application) : AndroidViewModel(application) {
    private val _screenState = MutableStateFlow<CastRoutePickerScreenState>(CastRoutePickerScreenState.Loading)
    val screenState: StateFlow<CastRoutePickerScreenState> = _screenState.asStateFlow()

    private val mediaRouter = MediaRouter.getInstance(application)
    private val selector = MediaRouteSelector.Builder()
        .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
        .build()
    private var discovering = false

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = updateRoutes(router)
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = updateRoutes(router)
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = updateRoutes(router)
        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = updateRoutes(router)
        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = updateRoutes(router)
    }

    fun startDiscovery() {
        if (discovering) return
        discovering = true
        mediaRouter.addCallback(selector, routerCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        updateRoutes(mediaRouter)
    }

    fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        mediaRouter.removeCallback(routerCallback)
    }

    fun selectRoute(routeId: String): Boolean {
        val route = mediaRouter.routes.firstOrNull { it.id == routeId } ?: return false
        mediaRouter.selectRoute(route)
        return true
    }

    private fun updateRoutes(router: MediaRouter) {
        val selected = router.selectedRoute
        val routes = router.routes
            .filter { it.matchesSelector(selector) && it.isEnabled && !it.isDefault }
            .map { route ->
                CastRouteUiModel(
                    id = route.id,
                    name = route.name,
                    description = route.description,
                    selected = route.id == selected.id,
                    enabled = route.isEnabled,
                    connecting = route.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING,
                )
            }
        _screenState.value = if (routes.isEmpty()) CastRoutePickerScreenState.Empty else CastRoutePickerScreenState.Success(routes)
    }

    override fun onCleared() {
        stopDiscovery()
        super.onCleared()
    }
}
