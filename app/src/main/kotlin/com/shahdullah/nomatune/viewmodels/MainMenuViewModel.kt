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

package com.shahdullah.nomatune.viewmodels

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

/**
 * State of the profile header shown at the top of the top-app-bar overflow menu.
 *
 * Ported from ArchiveTune. NomaTune already exposes `accountName` / `accountImageUrl`
 * on [HomeViewModel], so this type is populated from there rather than from a
 * dedicated use case.
 */
@Immutable
sealed interface MenuProfileState {
    data object Loading : MenuProfileState

    data class Success(val profile: MenuProfile) : MenuProfileState

    data object Empty : MenuProfileState

    data class Error(@param:StringRes val messageResId: Int) : MenuProfileState
}

/** Minimal profile shape required by the overflow menu header. */
@Immutable
data class MenuProfile(
    val name: String,
    val email: String = "",
)

/** Destinations reachable from the top-app-bar overflow menu. */
enum class MainMenuDestination(val route: String) {
    Profile("settings/account"),
    NewRelease("new_release"),
    News("news"),
    History("history"),
    Settings("settings"),
}
